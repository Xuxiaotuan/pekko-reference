package cn.xuyinyin.magic.workflow.snapshot

import cn.xuyinyin.magic.workflow.checkpoint.{AlreadyCommitted, Committed}
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException, ExecutionFailure}
import cn.xuyinyin.magic.workflow.execution.SnapshotSplitRunContext
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.Node
import cn.xuyinyin.magic.workflow.spi.{BatchSourceSession, CheckpointedNodeSink, CheckpointedNodeSource, SnapshotSplitSource}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.{Attributes, Materializer}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.Done
import spray.json._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Single-owner, snapshot-only execution. Recovery ownership stays here; CDC never enters this path. */
final class SnapshotSplitExecutor(source: CheckpointedNodeSource with SnapshotSplitSource, sink: CheckpointedNodeSink,
  sourceNode: Node, sinkNode: Node, workflowId: String, executionId: String, revision: Long,
  context: SnapshotSplitRunContext, ensureActive: () => Future[Done],
  install: BatchSourceSession => Unit, remove: BatchSourceSession => Unit,
  onLog: String => Unit, blockingEc: ExecutionContext, closeEc: ExecutionContext,
  cancelled: () => Boolean = () => false, releaseUnconfirmed: Throwable => Unit = _ => ())
  (implicit system: ActorSystem[_], ec: ExecutionContext, materializer: Materializer) {

  private val unsafeRelease = new java.util.concurrent.atomic.AtomicBoolean(false)
  private def active(): Future[Done] =
    if (unsafeRelease.get()) Future.failed(new CdcExecutionException(
      ExecutionFailure(ExecutionFailure.ReleaseUnknown, "snapshot-admission", releaseConfirmed = false),
      new IllegalStateException("new snapshot work blocked by unconfirmed release")))
    else ensureActive()

  private def positive(name: String, default: Int, max: Int): Int = sourceNode.config.fields.get(name) match {
    case None => default
    case Some(JsNumber(n)) if n.isValidInt && n.toInt > 0 && n.toInt <= max => n.toInt
    case _ => throw new IllegalArgumentException(s"invalid split snapshot $name")
  }
  private def canonical(value: JsValue): String = value match {
    case JsObject(fields) => fields.toVector.filterNot { case (k, _) => Set("password", "passwordEnv").contains(k) }
      .sortBy(_._1).map { case (k, v) => JsString(k).compactPrint + ":" + canonical(v) }.mkString("{", ",", "}")
    case JsArray(values) => values.map(canonical).mkString("[", ",", "]")
    case other => other.compactPrint
  }
  private val fingerprint = SnapshotSplitState.sha256(s"$revision|${sourceNode.id}|${canonical(sourceNode.config)}|${sinkNode.id}|${canonical(sinkNode.config)}")
  private def write(update: SnapshotSplitUpdate): Future[SnapshotSplitState] = context.update(update).recoverWith {
    case e: CdcExecutionException => Future.failed(e)
    case NonFatal(e) => Future.failed(new CdcExecutionException(ExecutionFailure(ExecutionFailure.ProgressUnknown, "snapshot-journal"), e))
  }
  private def state(): Future[SnapshotSplitState] = context.read().map(_.getOrElse(throw new IllegalStateException("snapshot state disappeared")))
  private def fail(code: String, release: Boolean = true): Nothing = throw new CdcExecutionException(
    ExecutionFailure(code, "snapshot", releaseConfirmed = release), new IllegalStateException(code))

  def run(): Future[Long] = for {
    _ <- active()
    existing <- context.read()
    initialized <- existing match {
      case Some(s) =>
        if (s.plan.fingerprint != fingerprint) Future.failed(new IllegalStateException("frozen snapshot definition changed"))
        else Future.successful(s)
      case None =>
        val pageRows = positive("chunkSize", 1000, 10000)
        val splitRows = positive("splitRows", 100000, 1000000)
        require(splitRows >= pageRows, "splitRows must be >= chunkSize")
        val parallelism = positive("splitParallelism", 2, 8)
        val bytes = positive("maxPageBytes", 1024 * 1024, 16 * 1024 * 1024)
        source.discoverBoundary(sourceNode, None, onLog)(blockingEc).flatMap { boundary =>
          val id = SnapshotSplitState.sha256(s"$executionId|$fingerprint|${boundary.upperBound}")
          write(InitializeSnapshotSplits(SnapshotSplitPlanState(id, sourceNode.id, fingerprint, boundary.upperBound,
            pageRows, splitRows, parallelism, bytes)))
        }
    }
    result <- windows(initialized)
  } yield result

  private def windows(s: SnapshotSplitState): Future[Long] = active().flatMap { _ =>
    if (s.enumerated && s.splits.isEmpty) Future.successful(s.rowsCommitted)
    else if (s.splits.nonEmpty && s.splits.forall(_.completed)) write(RetireSnapshotWindow).flatMap(windows)
    else if (s.splits.nonEmpty) {
      // Recover an already durable window; do not enumerate different boundaries after a restart.
      Source(s.splits.filterNot(_.completed)).mapAsyncUnordered(s.plan.parallelism) { p =>
        runSplit(p.split).map(_ => Option.empty[Throwable]).recover { case NonFatal(e) => Some(e) }
      }.runWith(Sink.seq).flatMap { failures =>
        failures.flatten.headOption match {
          case Some(e) => Future.failed(e) // healthy siblings have been allowed to finish.
          case None => state().flatMap(windows)
        }
      }
    } else fillWindow(s).flatMap { filled =>
      if (filled.splits.isEmpty) write(FinishSnapshotEnumeration).flatMap(windows)
      else windows(filled)
    }
  }

  private def fillWindow(s: SnapshotSplitState): Future[SnapshotSplitState] = {
    val lower = s.splits.lastOption.map(_.split.upper).orElse(s.nextLower)
    if (s.splits.size >= SnapshotSplitState.WindowSize || s.plan.upper.isEmpty || lower == s.plan.upper) Future.successful(s)
    else active().flatMap(_ => source.discoverSplit(sourceNode, s.plan, lower, s.nextIndex + s.splits.size)(blockingEc))
      .flatMap {
        case None => Future.successful(s)
        case Some(split) => write(RegisterSnapshotSplit(split)).flatMap(fillWindow)
      }
  }

  private def runSplit(split: SnapshotSplit): Future[Done] = for {
    _ <- active()
    s <- state()
    p = s.splits.find(_.split.id == split.id).get
    _ = if (p.blocked.nonEmpty) fail(p.blocked.get)
    done <- if (p.completed) Future.successful(Done) else {
      val begin = if (!p.started) write(StartSnapshotSplit(split.id))
        else if (p.attempts < 3) write(RetrySnapshotSplit(split.id, p.attempts + 1))
        else write(BlockSnapshotSplit(split.id, ExecutionFailure.BudgetExhausted)).map(_ => fail(ExecutionFailure.BudgetExhausted))
      begin.flatMap { begun =>
        val current = begun.splits.find(_.split.id == split.id).get
        val running = try attempt(begun.plan, current) catch { case NonFatal(e) => Future.failed(e) }
        running.recoverWith { case NonFatal(error) =>
          val failure = ExecutionFailure.from(error, "snapshot")
          if (!failure.releaseConfirmed) { unsafeRelease.set(true); releaseUnconfirmed(error) }
          if (cancelled()) Future.failed(error)
          else if (failure.releaseConfirmed && ExecutionFailure.Retryable(failure.code))
            org.apache.pekko.pattern.after(200.millis, system.classicSystem.scheduler)(runSplit(split))(ec)
          else write(BlockSnapshotSplit(split.id, failure.code)).flatMap(_ => Future.failed(error))
        }
      }
    }
  } yield done

  private def attempt(plan: SnapshotSplitPlanState, progress: SnapshotSplitProgress): Future[Done] = {
    val session = source.openSplit(sourceNode, executionId, plan, progress.split, progress.checkpoint, onLog, closeEc)(blockingEc)
    install(session)
    val pageLock = new Object
    var admittedPage: Future[Done] = Future.successful(Done)
    val running = session.source.withAttributes(Attributes.inputBuffer(1, 1)).mapAsync(1) { batch =>
      val drained = Promise[Done]()
      pageLock.synchronized { admittedPage = drained.future }
      val committing = active().flatMap(_ => sink.commitBatch(sinkNode, workflowId, executionId, batch, batch.rows, onLog)(blockingEc))
        .flatMap { result =>
          val checkpoint = result match { case Committed(c) => c; case AlreadyCommitted(c) => c }
          write(CommitSnapshotPage(checkpoint, progress.attempts)).map(_ => Done)
        }
      committing.onComplete(drained.tryComplete)
      drained.future
    }.runWith(Sink.ignore)
    running.transformWith { streamOutcome =>
      // Upstream failure can terminate mapAsync while its target/J Future is still alive.
      // Parallelism is one: retaining the last admitted page drains every admitted effect.
      pageLock.synchronized(admittedPage).transformWith { pageOutcome =>
        val outcome = pageOutcome match {
          case scala.util.Failure(_) => pageOutcome // retain a target/J release or protocol failure
          case _ => streamOutcome
        }
        val closed = session.close().map { _ => remove(session); Done }
        val timeout = Promise[Done]()
        val timer = system.scheduler.scheduleOnce(10.seconds, new Runnable {
          def run(): Unit = timeout.tryFailure(new CdcExecutionException(
            ExecutionFailure(ExecutionFailure.ReleaseUnknown, "snapshot-release", releaseConfirmed = false),
            new IllegalStateException("snapshot resource release unconfirmed")))
        })(ec)
        closed.onComplete(timeout.tryComplete)
        timeout.future.transformWith { released =>
          timer.cancel()
          released match {
            case scala.util.Failure(e) => Future.failed(new CdcExecutionException(
              ExecutionFailure(ExecutionFailure.ReleaseUnknown, "snapshot-release", releaseConfirmed = false), e))
            case scala.util.Success(_) => outcome match {
              case scala.util.Failure(e) => Future.failed(e)
              case scala.util.Success(_) => active().flatMap(_ => write(CompleteSnapshotSplit(progress.split.id, progress.attempts))).map(_ => Done)
            }
          }
        }
      }
    }
  }
}
