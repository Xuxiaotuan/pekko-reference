package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.MySQLSnapshotSourceNode
import cn.xuyinyin.magic.connectors.mysql.sink.MySQLSinkNode
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.execution._
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.snapshot._
import cn.xuyinyin.magic.workflow.spi.BatchSourceSession
import cn.xuyinyin.magic.workflow.engine.ExecutionResult
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariDataSource
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.Done
import spray.json._
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Test-only gates around real production adapters, a real JDBC Journal and optional MySQL wire proxy. */
object RealMySQLSnapshotSplitProcess {
  import CdcJournalWindowSupport._
  def main(args: Array[String]): Unit = {
    val mode = args(0); val schema = args(1); val workflowId = args(2); val fault = args(3)
    val settings = Settings.environment()
    val config = ConfigFactory.parseMap(Map[String, AnyRef](
      "pekko.actor.provider" -> "local", "pekko.remote.artery.enabled" -> Boolean.box(false),
      "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url" -> settings.url(schema),
      "pekko-persistence-jdbc.shared-databases.slick.db.user" -> settings.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password" -> settings.password,
      "pekko.workflow.event-sourcing.snapshot-every" -> Int.box(if (fault == "SNAPSHOT_RECOVERY") 5 else 100000),
      "pekko.loglevel" -> "WARN", "pekko.stdout-loglevel" -> "WARN"
    ).asJava).withFallback(ConfigFactory.load("application-test"))
    val kit = ActorTestKit(s"snapshot-splits-${ProcessHandle.current().pid()}", config)
    implicit val ec: ExecutionContext = kit.system.executionContext
    implicit val system: org.apache.pekko.actor.typed.ActorSystem[_] = kit.system
    val armed = new AtomicBoolean(mode == "fresh")
    val held = Promise[Done]()
    val proxyGate = Promise[Done]()
    val targetCloseArmed = new AtomicBoolean(false)
    val drainFaultArmed = new AtomicBoolean(mode == "fresh")
    val targetHeld = Promise[Done]()
    val proxy = if (mode == "fresh" && Set("COMMIT_LOST", "COMMIT_ABSENT").contains(fault))
      Some(new MySQLCommitResponseProxy(settings.host, settings.port, (k, f) => emit(k, f.fields.toSeq: _*))) else None
    def cut(kind: String, fields: (String, JsValue)*): Future[Done] = {
      emit("CUTPOINT", (fields :+ ("window" -> JsString(kind))): _*); held.future
    }
    def batchJson(b: SourceBatch): JsObject = JsObject("split" -> JsString(b.partitionId), "sequence" -> JsNumber(b.batchSequence),
      "batchId" -> JsString(b.batchId), "cursor" -> JsString(b.cursor.value), "rows" -> JsArray(b.rows.map(_.parseJson)))
    val pools = new java.util.concurrent.ConcurrentLinkedQueue[HikariDataSource]()
    val source = new MySQLSnapshotSourceNode {
      override def createSplitDataSource(h: String, p: Int, d: String, u: String, pw: String): HikariDataSource = {
        val delegate = super.createSplitDataSource(h, p, d, u, pw)
        val ds = if (fault == "QUERY_TIMEOUT" && mode == "fresh") new HikariDataSource() {
          override def getConnection: java.sql.Connection = {
            val connection = delegate.getConnection
            java.lang.reflect.Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[java.sql.Connection]), (_, method, args) => {
              val forwarded = Option(args).getOrElse(Array.empty[AnyRef]).clone()
              if (method.getName == "prepareStatement" && forwarded.nonEmpty && forwarded(0).isInstanceOf[String] &&
                forwarded(0).toString.startsWith("SELECT `id`,") && armed.compareAndSet(true, false)) {
                forwarded(0) = forwarded(0).toString.replace("WHERE ", "WHERE SLEEP(31)=0 AND ")
                emit("SLOW_QUERY_INJECTED", "queryTimeoutSeconds" -> JsNumber(30))
              }
              try method.invoke(connection, forwarded: _*)
              catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
            }).asInstanceOf[java.sql.Connection]
          }
          override def close(): Unit = delegate.close()
          override def isClosed: Boolean = delegate.isClosed
        } else delegate
        pools.add(ds); ds
      }
      override def createDataSource(h: String, p: Int, d: String, u: String, pw: String): HikariDataSource = {
        val ds = super.createDataSource(h, p, d, u, pw); pools.add(ds); ds
      }
      override def openSplit(n: Node, e: String, p: SnapshotSplitPlanState, split: SnapshotSplit,
        checkpoint: Option[BatchCheckpoint], log: String => Unit, closeEc: ExecutionContext)
        (implicit blockingEc: ExecutionContext): BatchSourceSession = {
        emit("READER_OPEN", "split" -> JsString(split.id), "resume" -> checkpoint.map(c => JsString(c.cursor.value)).getOrElse(JsNull))
        val delegate = super.openSplit(n, e, p, split, checkpoint, log, closeEc)(blockingEc)
        BatchSourceSession(delegate.source.mapAsync(1) { batch =>
          emit("PAGE_READ", batchJson(batch).fields.toSeq: _*)
          if (Set("READ_T", "STOP_READ_T").contains(fault) && split.index == 0 && batch.batchSequence == 1 && drainFaultArmed.compareAndSet(true, false))
            targetHeld.future.flatMap { _ =>
              emit("READ_FAILURE_DURING_T", "batch" -> batchJson(batch))
              Future.failed[SourceBatch](new java.sql.SQLTransientConnectionException("test next-page read observation failed while target pending", "08006"))
            }(ec)
          else Future.successful(batch)
        },
          () => delegate.close().map { done => emit("READER_CLOSED", "split" -> JsString(split.id)); done }(ec))
      }
    }
    val sink = new MySQLSinkNode {
      override def createSplitDataSource(h: String, p: Int, d: String, u: String, pw: String): HikariDataSource = {
        val delegate = super.createSplitDataSource(if (proxy.nonEmpty) "127.0.0.1" else h, proxy.fold(p)(_.port), d, u, pw)
        if (fault != "TARGET_CLOSE") delegate else new HikariDataSource() {
          override def getConnection: java.sql.Connection = delegate.getConnection
          override def close(): Unit = {
            delegate.close()
            if (targetCloseArmed.compareAndSet(true, false)) {
              emit("TARGET_CLOSE_OBSERVATION_FAILED")
              throw new IllegalStateException("test failed target close observation after physical cleanup")
            }
          }
        }
      }
      override def commitBatch(n: Node, w: String, e: String, batch: SourceBatch, rows: Vector[String], log: String => Unit)
        (implicit blockingEc: ExecutionContext): Future[BatchCommitResult] = {
        if (proxy.nonEmpty && !batch.partitionId.endsWith(":0") && !proxyGate.isCompleted)
          return proxyGate.future.flatMap(_ => commitBatch(n, w, e, batch, rows, log)(blockingEc))(ec)
        if (fault == "TARGET_CLOSE" && batch.partitionId.endsWith(":0") && batch.batchSequence == 0 && armed.compareAndSet(true, false))
          targetCloseArmed.set(true)
        val inject = batch.partitionId.endsWith(":0") && batch.batchSequence == 0 &&
          Set("T_J", "SNAPSHOT_RECOVERY", "STOP_T", "READ_T", "STOP_READ_T", "CHANGED", "COMMIT_LOST", "COMMIT_ABSENT", "RETRY_EXHAUSTED").contains(fault) &&
          (if (fault == "RETRY_EXHAUSTED") true else armed.compareAndSet(true, false))
        if (inject && proxy.nonEmpty) proxy.get.arm(if (fault == "COMMIT_ABSENT") "C0" else "C2", batchJson(batch))
        val committed = super.commitBatch(n, w, e, batch, rows, log)(blockingEc)
        if (inject && proxy.nonEmpty) committed.onComplete(_ => proxyGate.trySuccess(Done))(ec)
        committed.flatMap { result =>
          emit("T_CONFIRMED", (batchJson(batch).fields.toSeq :+ ("replay" -> JsBoolean(result.isInstanceOf[AlreadyCommitted]))): _*)
          if (inject && Set("T_J", "SNAPSHOT_RECOVERY", "STOP_T", "READ_T", "STOP_READ_T", "CHANGED").contains(fault)) {
            targetHeld.trySuccess(Done)
            cut(fault, "batch" -> batchJson(batch)).map(_ => result)(ec)
          }
          else if (inject && fault == "RETRY_EXHAUSTED") Future.failed(new java.sql.SQLTransientConnectionException("test lost post-T observation", "08006"))
          else Future.successful(result)
        }(ec)
      }
    }
    val engine = new WorkflowExecutionEngine(nodeRegistry = NodeRegistry.builtins().withSource(source).withSink(sink)) {
      override def execute(w: Workflow, context: ReliableRunContext, log: String => Unit): Future[ExecutionResult] = {
        val wrapped = context.splitContext.map { original => original.copy(update = update => {
          val eligible = update match {
            case _: InitializeSnapshotSplits => fault == "PLAN"
            case RegisterSnapshotSplit(s) => fault == "REGISTER_BEFORE" && s.index == 1
            case CommitSnapshotPage(c, _) => Set("J_LOST", "REJECT_J").contains(fault) && c.partitionId.endsWith(":0")
            case CompleteSnapshotSplit(id, _) => Set("EOF", "EOF_J_LOST").contains(fault) && id.endsWith(":0")
            case _ => false
          }
          val inject = eligible && armed.compareAndSet(true, false)
          if (inject && Set("REGISTER_BEFORE", "EOF").contains(fault)) cut(fault).flatMap(_ => original.update(update))
          else if (inject && fault == "REJECT_J") {
            val page = update.asInstanceOf[CommitSnapshotPage]
            original.update(page.copy(checkpoint = page.checkpoint.copy(batchId = "invalid")))
          } else original.update(update).flatMap { state =>
            emit("J_CONFIRMED", "update" -> JsString(update.getClass.getSimpleName), "state" -> stateJson(state))
            if (inject) cut(fault).map(_ => state) else Future.successful(state)
          }
        }) }
        super.execute(w, context.copy(splitContext = wrapped), message => emit("ENGINE_LOG", "message" -> JsString(message)))
      }
    }
    val actor = kit.spawn(EventSourcedWorkflowActor(workflowId, engine))
    if (mode == "fresh") {
      val connection = Map[String, JsValue]("host" -> JsString(settings.host), "port" -> JsNumber(settings.port),
        "database" -> JsString(schema), "username" -> JsString(settings.user), "passwordEnv" -> JsString("CDC_WINDOW_MYSQL_PASSWORD"))
      val fields = Vector("id", "label", "amount", "note", "updated_at", "revision").map(JsString(_))
      val sourceNode = Node("source", "source", "mysql.snapshot", "source", Position(0, 0), JsObject(connection ++ Map(
        "table" -> JsString("source_rows"), "columns" -> JsArray(fields), "primaryKey" -> JsString("id"),
        "chunkSize" -> JsNumber(if (fault == "WINDOWS") 2 else 3), "splitRows" -> JsNumber(if (fault == "WINDOWS") 3 else 8),
        "splitParallelism" -> JsNumber(2), "snapshotConsistency" -> JsString("quiescent"),
        "maxPageBytes" -> JsNumber(if (fault == "PAGE_BYTES") 1 else 4096))))
      val sinkNode = Node("sink", "sink", "mysql.write", "sink", Position(0, 0), JsObject(connection ++ Map(
        "table" -> JsString("target_rows"), "batchSize" -> JsNumber(3), "mode" -> JsString("insert"))))
      val workflow = Workflow(workflowId, workflowId, "snapshot split recovery fixture", "1", "test", Nil, List(sourceNode, sinkNode),
        List(Edge("edge", "source", "sink")), WorkflowMetadata("now", "now", None, None))
      val replies = kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
      actor ! EventSourcedWorkflowActor.DefineWorkflow(workflow, 0, replies.ref); replies.receiveMessage(30.seconds)
      actor ! EventSourcedWorkflowActor.ExecuteManual("snapshot-initial", replies.ref)
      emit("EXECUTION", "reply" -> JsString(replies.receiveMessage(30.seconds).toString))
    }
    emit("PROCESS_READY")
    try scala.io.Source.stdin.getLines().foreach {
      case "DROP_RESPONSE" => proxy.get.dropHeldResponse()
      case "DROP_J" => held.tryFailure(new java.util.concurrent.TimeoutException("lost persisted J response"))
      case "RELEASE" => held.trySuccess(Done)
      case "CRASH" => System.out.flush(); Runtime.getRuntime.halt(86)
      case "STATE" =>
        val replies = kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        actor ! EventSourcedWorkflowActor.GetSummary(replies.ref)
        val summary = replies.receiveMessage(30.seconds)
        val journal = withConnection(settings, schema) { c =>
          val statement = c.prepareStatement("SELECT sequence_number,event_ser_id,event_ser_manifest,event_payload FROM event_journal WHERE persistence_id=? ORDER BY sequence_number")
          statement.setString(1, s"workflow-$workflowId")
          val rs = statement.executeQuery(); val serialization = SerializationExtension(kit.system.toClassic)
          val result = Vector.newBuilder[JsValue]
          try while (rs.next()) {
            val bytes = rs.getBytes("event_payload")
            val event = serialization.deserialize(bytes, rs.getInt("event_ser_id"), rs.getString("event_ser_manifest")).get
            result += JsObject("sequence" -> JsNumber(rs.getLong(1)), "manifest" -> JsString(rs.getString("event_ser_manifest")),
              "payloadBase64" -> JsString(java.util.Base64.getEncoder.encodeToString(bytes)),
              "type" -> JsString(event.getClass.getSimpleName), "update" -> (event match {
                case v: SnapshotSplitsUpdated => JsString(v.update.getClass.getSimpleName)
                case _ => JsNull
              }))
          } finally { rs.close(); statement.close() }
          JsArray(result.result())
        }
        emit("STATE", "status" -> JsString(summary.status.value),
          "executionId" -> summary.currentExecution.map(e => JsString(e.executionId)).getOrElse(JsNull),
          "journal" -> journal, "allSourcePoolsClosed" -> JsBoolean(pools.iterator().asScala.forall(_.isClosed)))
      case "STOP" => actor ! EventSourcedWorkflowActor.Stop
      case "WAIT_STOP" =>
        kit.createTestProbe[Any]().expectTerminated(actor, 30.seconds)
        emit("OWNER_STOPPED", "allSourcePoolsClosed" -> JsBoolean(pools.iterator().asScala.forall(_.isClosed)))
      case _ => ()
    } finally { held.trySuccess(Done); proxy.foreach(_.close()); kit.shutdownTestKit() }
  }
  private def stateJson(s: SnapshotSplitState): JsObject = JsObject(
    "planId" -> JsString(s.plan.id), "nextIndex" -> JsNumber(s.nextIndex), "enumerated" -> JsBoolean(s.enumerated),
    "rowsCommitted" -> JsNumber(s.rowsCommitted), "splits" -> JsArray(s.splits.map(p => JsObject(
      "id" -> JsString(p.split.id), "lower" -> p.split.lower.map(JsString(_)).getOrElse(JsNull), "upper" -> JsString(p.split.upper),
      "done" -> JsBoolean(p.completed), "attempts" -> JsNumber(p.attempts), "blocked" -> p.blocked.map(JsString(_)).getOrElse(JsNull),
      "sequence" -> p.checkpoint.map(c => JsNumber(c.batchSequence)).getOrElse(JsNull),
      "cursor" -> p.checkpoint.map(c => JsString(c.cursor.value)).getOrElse(JsNull)))))
}
