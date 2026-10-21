package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.workflow.engine.CdcExecutionException
import cn.xuyinyin.magic.workflow.engine.ExecutionFailure
import cn.xuyinyin.magic.workflow.engine.ExecutionResult
import cn.xuyinyin.magic.workflow.engine.NodeExecutionResult

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchCommitResult, SourceBatch}
import cn.xuyinyin.magic.workflow.execution.executors.TransformExecutor
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow
import cn.xuyinyin.magic.connectors.jdbc.JdbcPasswordResolver
import cn.xuyinyin.magic.workflow.spi.{CheckpointedNodeSink, CheckpointedNodeSource, NodeSink, NodeSource}
import com.typesafe.scalalogging.Logger
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector}
import org.apache.pekko.stream.{KillSwitches, Materializer, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import spray.json.{JsObject, JsString}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class WorkflowExecutionEngine(
  getenv: String => Option[String] = sys.env.get,
  val nodeRegistry: NodeRegistry = NodeRegistry.builtins()
)(implicit system: ActorSystem[_], ec: ExecutionContext) {
  private val logger = Logger(getClass)
  private val JdbcPasswordNodeTypes = Set(
    "mysql.snapshot", "mysql.write", "mysql.cdc", "mysql.cdc.apply"
  )
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer

  private val planCompiler = new WorkflowPlanCompiler(nodeRegistry)
  private val transformExecutor = new TransformExecutor()
  private val reliableExecutions = new ReliableExecutionSessions(logger)
  private lazy val jdbcBlockingEc: ExecutionContext =
    system.dispatchers.lookup(DispatcherSelector.fromConfig("pekko.workflow.jdbc-dispatcher"))

  private lazy val closeEc: ExecutionContext =
    system.dispatchers.lookup(DispatcherSelector.fromConfig("pekko.workflow.session-close-dispatcher"))

  /** Preserves the existing checkpoint-capability rule; this is not a general recovery guarantee. */
  def isResumable(workflow: Workflow): Boolean = planCompiler.compile(workflow).exists(_.resumable)

  def supportsCompleteGroupRecovery(workflow: Workflow): Boolean = planCompiler.compile(workflow).exists {
    case plan: MySqlCdcPlan => plan.pipeline.source.config.fields.contains("groupProtocol")
    case _ => false
  }

  def execute(workflow: Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
    executeInternal(workflow, executionId, None, None, onLog)

  def execute(workflow: Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] =
    reliableExecutions.register(runContext.executionId).flatMap { control =>
      control.continueIfActive().flatMap { _ =>
        val execution = try executeInternal(workflow, runContext.executionId, Some(runContext), Some(control), onLog)
        catch { case NonFatal(exception) => Future.failed(exception) }
        execution
      }.transformWith { outcome =>
        val observed = Promise[ExecutionResult]()
        val primary = outcome.toOption.flatMap(_.failure)
          .orElse(outcome.failed.toOption.map(ExecutionFailure.from(_, "execution")))
          .getOrElse(ExecutionFailure(ExecutionFailure.ReleaseUnknown, "release"))
        val deadline = system.settings.config.getDuration("pekko.workflow.session-control-wait-timeout").toMillis.millis
        val timer = system.scheduler.scheduleOnce(deadline, new Runnable {
          override def run(): Unit = observed.tryFailure(new CdcExecutionException(
            primary.copy(releaseConfirmed = false), new IllegalStateException("local release observation timed out")))
        })(ec)
        control.drainAndClose().onComplete {
          case Success(_) =>
            // Actual release opens the fence, even if the observer has already timed out.
            reliableExecutions.release(runContext.executionId, control)
            observed.tryComplete(outcome)
            timer.cancel()
          case Failure(error) =>
            val safe = control.sanitizeCloseFailure(error)
            control.closeFailure.set(safe)
            observed.tryFailure(new CdcExecutionException(primary.copy(releaseConfirmed = false), safe))
            timer.cancel()
        }
        observed.future
      }
    }

  def cancel(executionId: String): Future[Done] = reliableExecutions.cancel(executionId)

  /** A timeout is an observation, never permission to replace an unreleased session. */
  def cancelWithin(executionId: String, timeout: FiniteDuration =
    system.settings.config.getDuration("pekko.workflow.session-control-wait-timeout").toMillis.millis
  ): Future[WorkflowExecutionEngine.ReleaseStatus] = reliableExecutions.cancelWithin(executionId, timeout)(cancel(executionId))

  private[execution] def activeReliableExecutionCount: Int = reliableExecutions.activeCount

  private def executeInternal(
    workflow: Workflow,
    executionId: String,
    runContext: Option[ReliableRunContext],
    reliableControl: Option[ReliableExecutionControl],
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    val startTime = System.currentTimeMillis()
    logger.info(s"Starting workflow execution: ${workflow.id}")
    onLog(s"开始执行工作流: ${workflow.name}")
    val notStartedNodes = validationFailureResults(workflow)

    planCompiler.compile(workflow) match {
      case Left(InvalidWorkflowPlan(errors)) =>
        val message = errors.map(_.message).mkString("; ")
        onLog(s"执行失败: $message")
        Future.successful(failedResult(message, startTime, notStartedNodes))
      case Left(CheckpointSinkRequired(pipeline)) =>
        val message = runContext.fold(
          s"Reliable source ${pipeline.source.nodeType} requires checkpoint-aware sink ${pipeline.sink.nodeType}"
        )(_ =>
          s"ReliableRunContext capability loss: sink ${pipeline.sink.nodeType} is not a checkpoint-aware sink for source ${pipeline.source.nodeType}"
        )
        capabilityFailure(ExecutionTask(workflow.id, workflow.name), notStartedNodes, message, startTime, onLog)
      case Right(plan: RowStreamPlan) => runContext match {
        case Some(_) => capabilityFailure(
          plan.task, notStartedNodes,
          s"ReliableRunContext capability loss: source ${plan.pipeline.source.nodeType} is not a checkpoint-aware source for sink ${plan.pipeline.sink.nodeType}",
          startTime, onLog
        )
        case None => executeRowStream(plan, startTime, onLog)
      }
      case Right(plan: CheckpointedExecutionPlan) => runContext match {
        case Some(context) => executeReliable(
          plan, context,
          reliableControl.getOrElse(throw new IllegalStateException("reliable execution control is unavailable")),
          startTime, onLog
        )
        case None => capabilityFailure(
          plan.task, notStartedNodes,
          s"Reliable source ${plan.pipeline.source.nodeType} requires ReliableRunContext for sink ${plan.pipeline.sink.nodeType}",
          startTime, onLog
        )
      }
    }
  }

  private def executeRowStream(
    plan: RowStreamPlan,
    startTime: Long,
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    val task = plan.task
    val pipeline = plan.pipeline
    try {
      val graph = buildExecutionGraph(plan, onLog)
      onLog("开始执行Pekko Stream图")
      graph.run().map(_ => completedResult(task, pipeline, None, startTime, onLog)).recover {
        case failure: NodeRuntimeFailure => runtimeFailureResult(task, pipeline, failure.node, failure.cause, startTime, onLog)
        case NonFatal(ex) => unknownFailureResult(task, pipeline, ex, startTime, onLog)
      }
    } catch {
      case failure: NodeSetupFailure => setupFailureResult(task, pipeline, failure, startTime, onLog)
      case NonFatal(ex) =>
        val message = s"执行异常: ${ex.getMessage}"
        onLog(message)
        logger.error(s"Workflow execution error: ${task.id}", ex)
        Future.successful(failedResult(message, startTime, unknownResults(pipeline, ex)))
    }
  }

  private def executeReliable(
    plan: CheckpointedExecutionPlan,
    runContext: ReliableRunContext,
    control: ReliableExecutionControl,
    startTime: Long,
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    val task = plan.task
    val pipeline = plan.pipeline
    val source = plan.source
    val sink = plan.sink
    val preparedSource = try prepareRuntimeNode(pipeline.source)
    catch {
      case NonFatal(exception) =>
        return Future.successful(runtimeFailureResult(task, pipeline, pipeline.source, exception, startTime, onLog))
    }
    val preparedSink = try prepareRuntimeNode(pipeline.sink)
    catch {
      case NonFatal(exception) =>
        return Future.successful(runtimeFailureResult(task, pipeline, pipeline.sink, exception, startTime, onLog))
    }
    val runtimeSecrets = Vector(preparedSource, preparedSink).flatMap(runtimePassword)
    control.sanitizeCloseFailure = error => sanitizeRuntimeFailure(error, runtimeSecrets)
    val runtimeOnLog: String => Unit = message => onLog(redactRuntimeSecrets(message, runtimeSecrets))
    val latestSourceCheckpoint = runContext.checkpoints
      .filter(_.sourceNodeId == pipeline.source.id)
      .sortBy(_.batchSequence)
      .lastOption

    val execution = for {
      _ <- control.continueIfActive()
      _ <- nodeFuture(pipeline.sink)(sink.validateReady(preparedSink.connectorNode, runtimeOnLog)(jdbcBlockingEc))
      _ <- control.continueIfActive()
      boundary <- runContext.boundary match {
        case Some(existing) => Future.successful(existing)
        case None =>
          nodeFuture(pipeline.source)(source.discoverBoundary(preparedSource.connectorNode, latestSourceCheckpoint, runtimeOnLog)(jdbcBlockingEc))
            .flatMap(boundary => control.continueIfActive()
              .flatMap(_ => workflowFuture(runContext.initializeBoundary(boundary))).map(_ => boundary))
      }
      _ <- control.continueIfActive()
      _ <- nodeFuture(pipeline.sink)(sink.validateSourceBoundary(preparedSink.connectorNode, boundary, runtimeOnLog)(jdbcBlockingEc))
      _ <- control.continueIfActive()
      rowsProcessed <- {
        val resumeFrom = runContext.checkpoints
          .filter(checkpoint => checkpoint.sourceNodeId == pipeline.source.id && checkpoint.partitionId == boundary.partitionId)
          .sortBy(_.batchSequence)
          .lastOption
        val transforms = plan match {
          case _: MySqlCdcPlan => Flow[String] // CDC has no generic Transform execution path.
          case _: CheckpointedLinearPlan => createTransforms(pipeline.transforms, runtimeOnLog)
        }
        val batches = try {
          val session = source.openBatches(preparedSource.connectorNode, runContext.executionId, boundary, resumeFrom, runtimeOnLog, closeEc)(jdbcBlockingEc)
          control.installSession(session)
          session.source
                      .mapError(wrapRuntimeFailure(pipeline.source))
        } catch {
          case NonFatal(ex) => Source.failed(NodeRuntimeFailure(pipeline.source, ex))
        }
        val (killSwitch, rows) = batches
          .viaMat(KillSwitches.single)(Keep.right)
          .mapAsync(1)(batch => control.processBatch(processBatch(
            task,
            pipeline,
            preparedSource,
            source,
            preparedSink,
            sink,
            transforms,
            runContext,
            batch,
            runtimeOnLog
          )))
          .toMat(Sink.fold(0L)(_ + _))(Keep.both)
          .run()
        control.install(killSwitch)
        rows
      }
    } yield rowsProcessed

    execution
      .map(rows => completedResult(task, pipeline, reportedRowsProcessed(runContext, rows), startTime, runtimeOnLog))
      .recover {
        case failure: NodeRuntimeFailure =>
          runtimeFailureResult(task, pipeline, failure.node, sanitizeRuntimeFailure(failure.cause, runtimeSecrets), startTime, runtimeOnLog)
        case failure: NodeSetupFailure =>
          runtimeFailureResult(task, pipeline, failure.node, sanitizeRuntimeFailure(failure.cause, runtimeSecrets), startTime, runtimeOnLog)
        case NonFatal(ex) =>
          unknownFailureResult(task, pipeline, sanitizeRuntimeFailure(ex, runtimeSecrets), startTime, runtimeOnLog)
      }
  }

  private def reportedRowsProcessed(runContext: ReliableRunContext, rows: Long): Option[Int] =
    Option.when(runContext.checkpoints.isEmpty && rows.isValidInt)(rows.toInt)

  private def processBatch(
    task: ExecutionTask,
    pipeline: ExecutionPipeline,
    preparedSource: ExecutionNode,
    source: CheckpointedNodeSource,
    preparedSink: ExecutionNode,
    sink: CheckpointedNodeSink,
    transforms: Flow[String, String, NotUsed],
    runContext: ReliableRunContext,
    batch: SourceBatch,
    onLog: String => Unit
  ): Future[Long] =
    Source(batch.rows)
      .via(transforms)
      .runWith(Sink.seq)
      .map(_.toVector)
      .flatMap(rows => nodeFuture(pipeline.sink)(
        sink.commitBatch(preparedSink.connectorNode, task.id, runContext.executionId, batch, rows, onLog)(jdbcBlockingEc)
      ))
      .flatMap { result =>
        val committed = checkpoint(result)
        workflowFuture(runContext.checkpointCommitted(committed)).recoverWith {
          case explicit: CdcExecutionException => Future.failed(explicit)
          case NonFatal(e) => Future.failed(new CdcExecutionException(ExecutionFailure(ExecutionFailure.ProgressUnknown,"journal",Some(batch.batchId)),e))
        }
          .flatMap(_ => nodeFuture(pipeline.source)(
            source.acknowledgeCommittedBatch(preparedSource.connectorNode, batch, onLog)(jdbcBlockingEc).recoverWith {
              case explicit: CdcExecutionException => Future.failed(explicit)
              case NonFatal(e) => Future.failed(new CdcExecutionException(ExecutionFailure(ExecutionFailure.ProgressUnknown,"ack",Some(batch.batchId)),e))
            }
          ))
          .map(_ => committed.targetRowsWritten)
      }

  private def checkpoint(result: BatchCommitResult): BatchCheckpoint = result match {
    case cn.xuyinyin.magic.workflow.checkpoint.Committed(value) => value
    case cn.xuyinyin.magic.workflow.checkpoint.AlreadyCommitted(value) => value
  }

  private def createTransforms(nodes: Vector[ExecutionNode], onLog: String => Unit): Flow[String, String, NotUsed] =
    nodes.foldLeft(Flow[String]) { (flow, node) =>
      onLog(s"添加转换: ${node.label} (${node.nodeType})")
      flow.via(createTransform(node, onLog))
    }

  private def nodeFuture[A](node: ExecutionNode)(operation: => Future[A]): Future[A] =
    try operation.recoverWith {
      case failure: NodeRuntimeFailure => Future.failed(failure)
      case NonFatal(ex) => Future.failed(NodeRuntimeFailure(node, ex))
    } catch {
      case NonFatal(ex) => Future.failed(NodeRuntimeFailure(node, ex))
    }

  private def workflowFuture[A](operation: => Future[A]): Future[A] =
    try operation catch { case NonFatal(ex) => Future.failed(ex) }

  private def prepareRuntimeNode(node: ExecutionNode): ExecutionNode =
    if (
      JdbcPasswordNodeTypes.contains(node.nodeType) &&
      node.config.fields.contains("passwordEnv")
    ) {
      val password = JdbcPasswordResolver.resolve(node.config.fields, getenv)
      node.copy(config = JsObject((node.config.fields - "passwordEnv") + ("password" -> JsString(password))))
    } else node

  private def runtimePassword(node: ExecutionNode): Option[String] =
    node.config.fields.get("password").collect {
      case JsString(value) if value.nonEmpty => value
    }

  private def redactRuntimeSecrets(value: String, secrets: Vector[String]): String =
    secrets.distinct.foldLeft(Option(value).getOrElse(""))(_.replace(_, "<redacted>"))

  private def sanitizeRuntimeFailure(cause: Throwable, secrets: Vector[String]): Throwable =
    new CdcExecutionException(ExecutionFailure.from(cause,"execution"),
      new IllegalStateException(redactRuntimeSecrets(Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName), secrets)))

  private def capabilityFailure(
    task: ExecutionTask,
    nodeResults: Vector[NodeExecutionResult],
    message: String,
    startTime: Long,
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    onLog(s"执行失败: $message")
    logger.error(s"Workflow capability validation failed: ${task.id}: $message")
    Future.successful(failedResult(message, startTime, nodeResults))
  }

  private def completedResult(
    task: ExecutionTask,
    pipeline: ExecutionPipeline,
    rowsProcessed: Option[Int],
    startTime: Long,
    onLog: String => Unit
  ): ExecutionResult = {
    val duration = System.currentTimeMillis() - startTime
    onLog(s"工作流执行成功完成 (耗时: ${duration}ms)")
    logger.info(s"Workflow executed successfully: ${task.id} in ${duration}ms")
    ExecutionResult(
      status = "completed",
      success = true,
      message = s"Workflow executed successfully in ${duration}ms",
      rowsProcessed = rowsProcessed,
      duration = Some(duration),
      nodeResults = pipeline.nodes.map(node => completedNode(node, duration))
    )
  }

  private def setupFailureResult(
    task: ExecutionTask,
    pipeline: ExecutionPipeline,
    failure: NodeSetupFailure,
    startTime: Long,
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    val duration = System.currentTimeMillis() - startTime
    val message = s"执行失败: ${failure.cause.getMessage}"
    onLog(message)
    logger.error(s"Workflow setup failed: ${task.id} after ${duration}ms", failure.cause)
    Future.successful(failedResult(message, startTime, setupFailureResults(pipeline, failure.node, failure.cause, duration)))
  }

  private def buildExecutionGraph(plan: RowStreamPlan, onLog: String => Unit): RunnableGraph[Future[Done]] = {
    val pipeline = plan.pipeline
    onLog("开始构建Pekko Stream执行图")
    onLog(s"节点执行顺序: ${pipeline.nodes.map(node => s"${node.id}(${node.nodeType})").mkString(" -> ")}")

    val source = createSource(pipeline.source, plan.source, onLog)
    val transforms = pipeline.transforms.foldLeft(Flow[String]) { (flow, node) =>
      onLog(s"添加转换: ${node.label} (${node.nodeType})")
      flow.via(createTransform(node, onLog))
    }
    val sink = createSink(pipeline.sink, plan.sink, onLog)

    source.via(transforms).toMat(sink)(Keep.right)
  }

  private def createSource(node: ExecutionNode, adapter: NodeSource, onLog: String => Unit): Source[String, NotUsed] =
    try adapter.createSource(node.connectorNode, onLog).mapError(wrapRuntimeFailure(node))
    catch { case NonFatal(ex) => throw NodeSetupFailure(node, ex) }

  private def createTransform(node: ExecutionNode, onLog: String => Unit): Flow[String, String, NotUsed] =
    try transformExecutor.createTransform(node.connectorNode, onLog).mapError(wrapRuntimeFailure(node))
    catch { case NonFatal(ex) => throw NodeSetupFailure(node, ex) }

  private def createSink(node: ExecutionNode, adapter: NodeSink, onLog: String => Unit): Sink[String, Future[Done]] =
    try adapter.createSink(node.connectorNode, onLog).mapMaterializedValue(_.recoverWith {
      case failure: NodeRuntimeFailure => Future.failed(failure)
      case NonFatal(ex) => Future.failed(NodeRuntimeFailure(node, ex))
    })
    catch { case NonFatal(ex) => throw NodeSetupFailure(node, ex) }

  private def wrapRuntimeFailure(node: ExecutionNode): PartialFunction[Throwable, Throwable] = {
    case failure: NodeRuntimeFailure => failure
    case NonFatal(ex) => NodeRuntimeFailure(node, ex)
  }

  private def failedResult(
    message: String,
    startTime: Long,
    nodeResults: Vector[NodeExecutionResult]
  ): ExecutionResult = {
    val duration = System.currentTimeMillis() - startTime
    ExecutionResult(
      status = "failed",
      success = false,
      message = message,
      rowsProcessed = None,
      duration = Some(duration),
      nodeResults = nodeResults
    )
  }

  private def completedNode(node: ExecutionNode, duration: Long): NodeExecutionResult =
    NodeExecutionResult(node.id, node.nodeType, "completed", duration = Some(duration))

  private def setupFailureResults(
    pipeline: ExecutionPipeline,
    failedNode: ExecutionNode,
    cause: Throwable,
    duration: Long
  ): Vector[NodeExecutionResult] =
    pipeline.nodes.map { node =>
      if (node.id == failedNode.id) NodeExecutionResult(node.id, node.nodeType, "failed", Some(cause.getMessage), Some(duration))
      else NodeExecutionResult(node.id, node.nodeType, "not_started")
    }

  private def validationFailureResults(workflow: Workflow): Vector[NodeExecutionResult] =
    workflow.nodes.toVector.map(node => NodeExecutionResult(node.id, node.nodeType, "not_started"))

  private def unknownResults(
    pipeline: ExecutionPipeline,
    cause: Throwable
  ): Vector[NodeExecutionResult] =
    pipeline.nodes.map(node => NodeExecutionResult(node.id, node.nodeType, "unknown", Some(cause.getMessage)))

  private def runtimeFailureResult(
    task: ExecutionTask,
    pipeline: ExecutionPipeline,
    failedNode: ExecutionNode,
    cause: Throwable,
    startTime: Long,
    onLog: String => Unit
  ): ExecutionResult = {
    val duration = System.currentTimeMillis() - startTime
    val message = s"执行失败: ${cause.getMessage}"
    val failedIndex = pipeline.nodes.indexWhere(_.id == failedNode.id)
    onLog(message)
    logger.error(s"Workflow execution failed: ${task.id} after ${duration}ms", cause)
    ExecutionResult(
      status = "failed",
      success = false,
      message = message,
      rowsProcessed = None,
      duration = Some(duration),
      nodeResults = pipeline.nodes.zipWithIndex.map { case (node, index) =>
        if (index == failedIndex) NodeExecutionResult(node.id, node.nodeType, "failed", Some(cause.getMessage), Some(duration))
        else NodeExecutionResult(node.id, node.nodeType, "unknown")
      },
      failure = Some(ExecutionFailure.from(cause,"execution"))
    )
  }

  private def unknownFailureResult(
    task: ExecutionTask,
    pipeline: ExecutionPipeline,
    cause: Throwable,
    startTime: Long,
    onLog: String => Unit
  ): ExecutionResult = {
    val duration = System.currentTimeMillis() - startTime
    val message = s"执行失败: ${cause.getMessage}"
    onLog(message)
    logger.error(s"Workflow execution failed: ${task.id} after ${duration}ms", cause)
    ExecutionResult(
      status = "failed",
      success = false,
      message = message,
      rowsProcessed = None,
      duration = Some(duration),
      nodeResults = unknownResults(pipeline, cause),
      failure = Some(ExecutionFailure.from(cause,"execution"))
    )
  }

  private final case class NodeRuntimeFailure(node: ExecutionNode, cause: Throwable)
    extends RuntimeException(cause)

  private final case class NodeSetupFailure(node: ExecutionNode, cause: Throwable)
    extends RuntimeException(cause)
}

object WorkflowExecutionEngine {
  sealed trait ReleaseStatus
  case object Released extends ReleaseStatus
  case object TimedOut extends ReleaseStatus
  case object CloseFailed extends ReleaseStatus
}
