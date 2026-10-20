package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.checkpoint.{BatchId, BatchCheckpoint, BatchCommitResult, SnapshotBoundary, SourceBatch}
import cn.xuyinyin.magic.workflow.engine.{WorkflowExecutionEngine, ReliableRunContext, ExecutionResult}
import cn.xuyinyin.magic.workflow.TestNodeRegistry
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.nodes.sinks.{MySQLCdcApplySinkNode, MySQLCdcApplyConfig}
import cn.xuyinyin.magic.workflow.nodes.sources.{DebeziumEngineAccess, DebeziumEngineFactory, MySQLCdcSourceNode, MySQLCdcStateConfig}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import spray.json._

import java.util.Properties
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.CountDownLatch
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

/** Real production group Source/Sink/Actor; test-only observation and post-COMMIT gate.
  * bootstrap establishes a trusted initialized target and durable streaming D using the legacy path.
  */
object RealMySQLCdcGroupRecoveryProcess {
  private val testRegistry = new TestNodeRegistry

  import CdcJournalWindowSupport._

  def main(args: Array[String]): Unit = {
    require(args.length == 6, "expected: fresh|recover schema connector minimum-cut-rows")
    val mode = args(0); val schema = args(1); val connector = args(2)
    val batchSize = args(3); val fault = args(4); val lineage = args(5)
    require(Set("bootstrap", "fresh", "recover").contains(mode))
    validateSchema(schema)
    val settings = Settings.environment()
    val workflowId = s"workflow-$connector-${if (mode == "bootstrap") "bootstrap" else "groups"}"
    val armed = new AtomicBoolean(false)
    val readers = new AtomicInteger(0)
    val launches = new AtomicInteger(0)
    val repeated = new AtomicInteger(0)
    val closeGate = new CountDownLatch(1)
    val closeHeld = new AtomicBoolean(false)
    val oldContext = new AtomicReference[ReliableRunContext]()
    val lateProgress = new AtomicReference[BatchCheckpoint]()
    val journalHeld = new AtomicBoolean(false)
    val journalReply = Promise[Done]()
    val rejectionGate = Promise[Done]()
    val rejectionArmed = new AtomicBoolean(false)
    val rejectionUsed = new AtomicBoolean(false)
    val cancelCommitGate = Promise[BatchCommitResult]()
    val cancelCommitResult = new java.util.concurrent.atomic.AtomicReference[BatchCommitResult]()
    var observedExecutionId = ""
    val rawGate = new CountDownLatch(1)
    val proxy = if (mode == "fresh" && (Set("C0", "C1", "C2", "LATE_COMMIT", "LATE_ROLLBACK").contains(fault) || fault.startsWith("AUTO") || fault.startsWith("R")))
      Some(new MySQLCommitResponseProxy(settings.host, settings.port, (kind, fields) => emit(kind, fields.fields.toSeq: _*))) else None
    val config = ConfigFactory.parseMap(Map[String, AnyRef](
      "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url" -> settings.url(schema),
      "pekko-persistence-jdbc.shared-databases.slick.db.user" -> settings.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password" -> settings.password,
      // Keep the entire journal available for independent event deserialization.
      "pekko.workflow.event-sourcing.snapshot-every" -> Int.box(100000),
      "pekko.workflow.cdc-recovery.initial-backoff" -> (if (fault == "R1") "10s" else "200ms"),
      "pekko.workflow.session-control-wait-timeout" -> (if (fault == "R5") "2s" else "10s"),
      "pekko.loglevel" -> "WARN",
      "pekko.stdout-loglevel" -> "WARN"
    ).asJava).withFallback(ConfigFactory.load("application-test"))
    val kit = ActorTestKit(s"cdc-window-$mode-${ProcessHandle.current().pid()}", config)
    val ec: ExecutionContext = kit.system.executionContext

    val observedFactory = new DebeziumEngineFactory {
      override def create(properties: Properties): DebeziumEngineAccess = {
        // Real Debezium performs the split; no records, offsets or acknowledgements are fabricated.
        val reader = readers.incrementAndGet()
        val sizes = batchSize.split(":")
        properties.setProperty("max.batch.size", sizes(if (reader == 1) 0 else sizes.length - 1))
        if (Set("J2", "K2").contains(fault)) properties.setProperty("offset.flush.timeout.ms", "2000")
        emit("ENGINE_CONFIG", "reader" -> JsNumber(reader), "mode" -> JsString(mode),
          "maxBatchSize" -> JsString(properties.getProperty("max.batch.size")))
        val delegate = DebeziumEngineFactory.real.create(properties)
        new DebeziumEngineAccess {
          private var callback = 0L
          override def start(consumer: cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchConsumer): Future[Done] =
            delegate.start(new cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchConsumer {
              override def handleBatch(records: Vector[org.apache.kafka.connect.source.SourceRecord],
                handle: cn.xuyinyin.magic.workflow.nodes.sources.CdcBatchCommitHandle): Unit = {
                val id = callback; callback += 1
                emit("RAW_CALLBACK", "reader" -> JsNumber(reader), "callback" -> JsNumber(id), "records" -> JsArray(records.map(r =>
                  cn.xuyinyin.magic.workflow.nodes.sources.RealMySQLSourceRecordIdentityProbe.encode(r, connector))))
                consumer.handleBatch(records, new cn.xuyinyin.magic.workflow.nodes.sources.CdcBatchCommitHandle {
                  override def markProcessedAndFinished(): Unit = {
                    emit("RAW_ACK_BEGIN", "reader" -> JsNumber(reader), "callback" -> JsNumber(id))
                    handle.markProcessedAndFinished()
                    emit("RAW_ACK_RETURNED", "reader" -> JsNumber(reader), "callback" -> JsNumber(id))
                    val pkDelete = records.exists(_.headers().lastWithName("__debezium.newkey") != null)
                    if ((fault == "J2" || (Set("K1", "K2").contains(fault) && pkDelete)) && armed.compareAndSet(true, false)) {
                      emit("CUTPOINT", "callback" -> JsNumber(id), "fault" -> JsString(fault))
                      rawGate.await() // before the assembler can invoke the next original handle
                    }
                  }
                })
                emit("RAW_CALLBACK_RETURNED", "reader" -> JsNumber(reader), "callback" -> JsNumber(id))
              }
            })
          override def close(): Unit = {
            if (fault == "R5" && closeHeld.compareAndSet(true, false)) {
              emit("ENGINE_CLOSE_HELD", "reader" -> JsNumber(reader)); closeGate.await()
            }
            delegate.close(); emit("ENGINE_CLOSED", "reader" -> JsNumber(reader))
          }
        }
      }
    }
    testRegistry.registerSource(new MySQLCdcSourceNode(observedFactory, () =>
      MySQLCdcStateConfig(settings.url(schema), settings.user, settings.password,
        "debezium_offset_storage", "debezium_database_history", 0)) {
      override def openBatches(node: Node, executionId: String, boundary: SnapshotBoundary,
        resumeFrom: Option[BatchCheckpoint], onLog: String => Unit, closeEc: ExecutionContext)
        (implicit blockingEc: ExecutionContext): cn.xuyinyin.magic.workflow.nodes.base.BatchSourceSession = {
        emit("READER_RESUME", "executionId" -> JsString(executionId),
          "checkpoint" -> resumeFrom.map(checkpointJson).getOrElse(JsNull))
        val session = super.openBatches(node, executionId, boundary, resumeFrom, onLog, closeEc)
        session.copy(source = session.source.map { batch =>
          emit("SOURCE_BATCH", "reader" -> JsNumber(readers.get()), "batch" -> batchJson(batch))
          batch
        })
      }
      override def acknowledgeCommittedBatch(node: Node, batch: SourceBatch, onLog: String => Unit)
        (implicit blockingEc: ExecutionContext): Future[Done] = {
        emit("ACK_BEGIN", "batchId" -> JsString(batch.batchId))
        if (fault == "J1" && armed.compareAndSet(true, false)) {
          emit("CUTPOINT", "batch" -> batchJson(batch), "fault" -> JsString(fault))
          return Promise[Done]().future
        }
        super.acknowledgeCommittedBatch(node, batch, onLog).map { done =>
          emit("ACK_RETURNED", "batchId" -> JsString(batch.batchId))
          done
        }
      }
    })
    testRegistry.registerSink(new MySQLCdcApplySinkNode {
      override def openConnection(config: MySQLCdcApplyConfig): java.sql.Connection =
        super.openConnection(proxy.fold(config)(p => config.copy(host = "127.0.0.1", port = p.port)))
      override def commitBatch(node: Node, workflowId: String, executionId: String, batch: SourceBatch,
        transformedRows: Vector[String], onLog: String => Unit)
        (implicit blockingEc: ExecutionContext): Future[BatchCommitResult] = {
        emit("SINK_ATTEMPT", "batch" -> batchJson(batch))
        val inject = proxy.nonEmpty && (armed.compareAndSet(true, false) ||
          (fault == "R3" && repeated.getAndUpdate(v => math.max(0,v-1)) > 0))
        if (inject) {
          if (fault == "R3" && repeated.get() == 0) repeated.set(if (launches.get() == 1) 3 else 0)
          if (fault == "R5") closeHeld.set(true)
          val proxyMode = if (fault == "AUTO-B") "C0" else if (Set("C0","C1","C2").contains(fault)) fault else if (fault.startsWith("LATE_")) fault else "C2"
          proxy.get.arm(proxyMode, batchJson(batch))
        }
        super.commitBatch(node, workflowId, executionId, batch, transformedRows, onLog)
          .andThen {
            case Success(result) => emit("SINK_COMMITTED", "batch" -> batchJson(batch),
              "result" -> JsString(result.getClass.getSimpleName))
            case Failure(error) => emit("SINK_FAILED", "batch" -> batchJson(batch),
              "error" -> JsString(error.toString), "causes" -> JsArray(Iterator.iterate(error)(_.getCause)
                .takeWhile(_ != null).take(8).map(e => JsString(e.getClass.getName)).toVector))
          }
          .flatMap { result =>
            if (Set("cancel-T", "stop-T").contains(fault) && armed.compareAndSet(true, false)) {
              cancelCommitResult.set(result)
              emit("CUTPOINT", "batch" -> batchJson(batch), "fault" -> JsString(fault))
              cancelCommitGate.future
            } else if (fault == "after-T" && armed.compareAndSet(true, false)) {
              emit("CUTPOINT", "batch" -> batchJson(batch))
              Promise[BatchCommitResult]().future // The real engine cannot ask AdvanceCheckpoint yet.
            } else Future.successful(result)
          }
      }
    })
    lazy val engine = new WorkflowExecutionEngine(nodeRegistry = testRegistry.snapshot)(kit.system, ec) {
      override def execute(w: Workflow, c: ReliableRunContext, log: String => Unit): Future[ExecutionResult] = {
        observedExecutionId = c.executionId
        val launch = launches.incrementAndGet()
        emit("ATTEMPT_ENGINE_ENTRY", "launch" -> JsNumber(launch), "executionId" -> JsString(c.executionId))
        if (fault == "R2" && launch == 2) {
          emit("ATTEMPT_LAUNCH_HELD", "launch" -> JsNumber(launch))
          return Promise[ExecutionResult]().future
        }
        if (launch == 1) oldContext.set(c)
        else if (fault == "R4" && lateProgress.get() != null) {
          oldContext.get().checkpointCommitted(lateProgress.get()).onComplete {
            case Failure(_) => emit("STALE_PROGRESS_REJECTED")
            case Success(_) => emit("STALE_PROGRESS_ACCEPTED")
          }(ec)
        }
        val context = c.copy(checkpointCommitted = cp => {
          val reject = rejectionArmed.get() && (fault != "REJECT-J-RETRY" || launch > 1) && rejectionUsed.compareAndSet(false,true)
          if (reject) {
            // The real Actor validates this request; no reply/J result is fabricated.
            val sequence=cp.batchSequence+1
            val proposed=cp.copy(batchSequence=sequence,
              batchId=BatchId.sha256(c.executionId,cp.sourceNodeId,cp.partitionId,sequence))
            emit("J_REJECTION_READY", "checkpoint"->checkpointJson(cp), "proposed"->checkpointJson(proposed), "launch"->JsNumber(launch))
            rejectionGate.future.flatMap(_=>c.checkpointCommitted(proposed))(ec).andThen {
              case Failure(error) =>
                val failure=cn.xuyinyin.magic.workflow.engine.ExecutionFailure.from(error,"journal")
                emit("J_EXPLICIT_REJECTION", "code"->JsString(failure.code), "stage"->JsString(failure.stage),
                  "proposed"->checkpointJson(proposed))
              case Success(_) => emit("J_UNEXPECTED_ACCEPTANCE")
            }(ec)
          } else {
          c.checkpointCommitted(cp).flatMap { done =>
            emit("JOURNAL_CONFIRMED", "launch" -> JsNumber(launch), "checkpoint" -> checkpointJson(cp))
            if (fault == "R4" && journalHeld.compareAndSet(true, false)) {
              lateProgress.set(cp)
              emit("J_REPLY_HELD", "checkpoint" -> checkpointJson(cp))
              journalReply.future
            } else Future.successful(done)
          }(ec)
          }
        })
        super.execute(w,context,log)
      }
    }
    val actor = kit.spawn(EventSourcedWorkflowActor(workflowId, engine)(ec), "workflow")
    if (mode != "recover") {
      val replies = kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
      actor ! EventSourcedWorkflowActor.DefineWorkflow(workflow(settings, schema, connector, workflowId, mode, lineage), 0L, replies.ref)
      require(replies.receiveMessage(30.seconds).isInstanceOf[EventSourcedWorkflowActor.Defined])
      actor ! EventSourcedWorkflowActor.ExecuteManual("initial", replies.ref)
      val accepted = replies.receiveMessage(30.seconds).asInstanceOf[EventSourcedWorkflowActor.ExecutionAccepted]
      observedExecutionId = accepted.executionId
      emit("STARTED", "executionId" -> JsString(accepted.executionId))
    }
    emit("PROCESS_READY", "pid" -> JsNumber(ProcessHandle.current().pid()), "mode" -> JsString(mode),
      "jvmTimezone" -> JsString(java.util.TimeZone.getDefault.getID))
    try {
      Iterator.continually(StdIn.readLine()).takeWhile(_ != null).foreach {
        case "STOP_OWNER" =>
          val ended = kit.createTestProbe[Nothing]()
          actor ! EventSourcedWorkflowActor.Stop
          emit("OWNER_STOP_REQUESTED")
          Future {
            ended.expectTerminated(actor, 60.seconds)
            emit("OWNER_STOPPED", "liveRunners" -> JsNumber(Thread.getAllStackTraces.keySet().asScala.count(t =>
              t.isAlive && t.getName.startsWith("mysql-cdc-debezium-engine-"))))
          }(ExecutionContext.global)
        case "CANCEL" | "AWAIT_RELEASE" =>
          val budget = if (cancelCommitResult.get() != null && !cancelCommitGate.isCompleted) 100.millis else 10.seconds
          val status = scala.concurrent.Await.result(engine.cancelWithin(observedExecutionId, budget), budget + 5.seconds)
          emit("CANCEL_RESULT", "status" -> JsString(status.toString),
            "liveRunners" -> JsNumber(Thread.getAllStackTraces.keySet().asScala.count(t =>
              t.isAlive && t.getName.startsWith("mysql-cdc-debezium-engine-"))))
        case "RELEASE_T" =>
          require(cancelCommitResult.get() != null, "no committed batch at cancellation gate")
          cancelCommitGate.trySuccess(cancelCommitResult.get()); emit("T_RELEASED")
        case "ARM" =>
          if (fault.startsWith("REJECT-J")) rejectionArmed.set(true)
          if (fault == "R4") journalHeld.set(true) else if (fault != "REJECT-J") armed.set(true)
          emit("ARMED")
        case "DROP_RESPONSE" => proxy.get.dropHeldResponse()
        case "REJECT_J" => rejectionGate.trySuccess(Done)
        case "DROP_J" => journalReply.tryFailure(new java.util.concurrent.TimeoutException("test lost durable checkpoint reply"))
        case "RESOLVE_TX" => proxy.get.resolveHeldTransaction(); emit("TX_RESOLVE_REQUESTED")
        case "RELEASE_CLOSE" => closeGate.countDown(); emit("CLOSE_RELEASED")
        case "CRASH" => System.out.flush(); Runtime.getRuntime.halt(86)
        case "STATE" =>
          val reply = kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
          actor ! EventSourcedWorkflowActor.GetSummary(reply.ref)
          val summary = reply.receiveMessage(30.seconds)
          val current = summary.currentExecution
          val journal = withConnection(settings, schema) { connection =>
            val statement = connection.prepareStatement(
              "SELECT sequence_number, writer, event_ser_id, event_ser_manifest, event_payload " +
                "FROM event_journal WHERE persistence_id = ? ORDER BY sequence_number")
            statement.setString(1, s"workflow-$workflowId")
            val rows = statement.executeQuery()
            val events = Vector.newBuilder[JsValue]
            val serialization = SerializationExtension(kit.system.toClassic)
            try while (rows.next()) {
              val event = serialization.deserialize(rows.getBytes("event_payload"), rows.getInt("event_ser_id"),
                rows.getString("event_ser_manifest")).get
              events += JsObject(
                "journalSequence" -> JsNumber(rows.getLong("sequence_number")),
                "writer" -> JsString(rows.getString("writer")),
                "eventType" -> JsString(event.getClass.getSimpleName),
                "eventSerializerId" -> JsNumber(rows.getInt("event_ser_id")),
                "eventSerializerManifest" -> JsString(rows.getString("event_ser_manifest")),
                "eventPayloadBase64" -> JsString(java.util.Base64.getEncoder.encodeToString(rows.getBytes("event_payload"))),
                "recovery" -> (event match {
                  case v: ExecutionRecoveryScheduled => JsObject("nextAttempt" -> JsNumber(v.nextAttempt), "notBefore" -> JsNumber(v.notBefore), "reasonCode" -> JsString(v.reasonCode))
                  case v: ExecutionRecoveryAttemptStarted => JsObject("attempt" -> JsNumber(v.attempt))
                  case v: ExecutionRecoveryBlocked => JsObject("reasonCode" -> JsString(v.reasonCode))
                  case _ => JsNull
                }),
                "checkpoint" -> (event match {
                  case value: ExecutionCheckpointAdvanced => checkpointJson(value.checkpoint)
                  case _ => JsNull
                }))
            } finally { rows.close(); statement.close() }
            JsArray(events.result())
          }
          emit("STATE", "status" -> JsString(summary.status.value),
            "executionId" -> current.map(v => JsString(v.executionId)).getOrElse(JsNull),
            "workflowRevision" -> current.map(v => JsNumber(v.workflowRevision)).getOrElse(JsNull),
            "boundary" -> current.flatMap(_.boundary).map(b => JsObject("sourceNodeId"->JsString(b.sourceNodeId),
              "partitionId"->JsString(b.partitionId),"upperBound"->b.upperBound.map(JsString(_)).getOrElse(JsNull))).getOrElse(JsNull),
            "recovery" -> current.flatMap(_.recovery).map(r => JsObject(
              "attemptsStarted" -> JsNumber(r.attemptsStarted), "nextAttempt" -> JsNumber(r.nextAttempt),
              "phase" -> JsString(r.phase), "notBefore" -> JsNumber(r.notBefore), "reasonCode" -> JsString(r.reasonCode),
              "failureCode" -> r.failure.map(f => JsString(f.code)).getOrElse(JsNull),
              "batchId" -> r.failure.flatMap(_.batchId).map(JsString(_)).getOrElse(JsNull))).getOrElse(JsNull),
            "actorCheckpoints" -> JsArray(current.toVector.flatMap(_.checkpoints).map(checkpointJson)),
            "journal" -> journal)
        case other => throw new IllegalArgumentException(s"unsupported command: $other")
      }
    } finally { closeGate.countDown(); rawGate.countDown(); proxy.foreach(_.close()); kit.shutdownTestKit() }
  }

  private def workflow(settings: Settings, schema: String, connector: String, workflowId: String, mode: String, lineage: String): Workflow = {
    val connection = Map[String, JsValue]("host" -> JsString(settings.host), "port" -> JsNumber(settings.port),
      "database" -> JsString(schema), "username" -> JsString(settings.user),
      "passwordEnv" -> JsString("CDC_WINDOW_MYSQL_PASSWORD"))
    val source = Node("source-1", "source", "mysql.cdc", "real source", Position(0, 0), JsObject(connection ++ Map(
      "table" -> JsString("source_rows"), "connectorId" -> JsString(connector),
      "serverId" -> JsNumber(741819), "maxBatchSize" -> JsNumber(128), "pollIntervalMillis" -> JsNumber(1000)) ++
        (if (mode == "bootstrap") Map.empty[String, JsValue] else Map("groupProtocol" -> JsObject(
          "replicationId" -> JsString(connector), "generation" -> JsString("initialized-v1"),
          "sourceLineage" -> JsString(lineage), "initializedTarget" -> JsBoolean(true))))))
    val sink = Node("sink-1", "sink", "mysql.cdc.apply", "real target", Position(1, 0),
      JsObject(connection + ("table" -> JsString("target_rows"))))
    Workflow(workflowId, "CDC journal fault window", "isolated reproduction", "1", "test", Nil,
      List(source, sink), List(Edge("source-to-sink", source.id, sink.id)), WorkflowMetadata("test", "test"))
  }
}
