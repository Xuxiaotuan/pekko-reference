package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchCommitResult, SnapshotBoundary, SourceBatch}
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.events.WorkflowEvents.ExecutionCheckpointAdvanced
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.nodes.sinks.MySQLCdcApplySinkNode
import cn.xuyinyin.magic.workflow.nodes.sources.{DebeziumEngineAccess, DebeziumEngineFactory, MySQLCdcSourceNode, MySQLCdcStateConfig}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.stream.scaladsl.Source
import spray.json._

import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

/** Real production group Source/Sink/Actor; test-only observation and post-COMMIT gate.
  * bootstrap establishes a trusted initialized target and durable streaming D using the legacy path.
  */
object RealMySQLCdcGroupRecoveryProcess {
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
    val config = ConfigFactory.parseMap(Map[String, AnyRef](
      "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url" -> settings.url(schema),
      "pekko-persistence-jdbc.shared-databases.slick.db.user" -> settings.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password" -> settings.password,
      // Keep the entire journal available for independent event deserialization.
      "pekko.workflow.event-sourcing.snapshot-every" -> Int.box(100000),
      "pekko.loglevel" -> "WARN",
      "pekko.stdout-loglevel" -> "WARN"
    ).asJava).withFallback(ConfigFactory.load("application-test"))
    val kit = ActorTestKit(s"cdc-window-$mode-${ProcessHandle.current().pid()}", config)
    val ec: ExecutionContext = kit.system.executionContext

    val observedFactory = new DebeziumEngineFactory {
      override def create(properties: Properties): DebeziumEngineAccess = {
        // Real Debezium performs the split; no records, offsets or acknowledgements are fabricated.
        properties.setProperty("max.batch.size", batchSize)
        emit("ENGINE_CONFIG", "mode" -> JsString(mode),
          "maxBatchSize" -> JsString(properties.getProperty("max.batch.size")))
        val delegate = DebeziumEngineFactory.real.create(properties)
        new DebeziumEngineAccess {
          private var callback = 0L
          override def start(consumer: cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchConsumer): Future[Done] =
            delegate.start(new cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchConsumer {
              override def handleBatch(records: Vector[org.apache.kafka.connect.source.SourceRecord],
                handle: cn.xuyinyin.magic.workflow.nodes.sources.CdcBatchCommitHandle): Unit = {
                val id = callback; callback += 1
                emit("RAW_CALLBACK", "callback" -> JsNumber(id), "records" -> JsArray(records.map(r =>
                  cn.xuyinyin.magic.workflow.nodes.sources.RealMySQLSourceRecordIdentityProbe.encode(r, connector))))
                consumer.handleBatch(records, new cn.xuyinyin.magic.workflow.nodes.sources.CdcBatchCommitHandle {
                  override def markProcessedAndFinished(): Unit = {
                    emit("RAW_ACK_BEGIN", "callback" -> JsNumber(id))
                    handle.markProcessedAndFinished()
                    emit("RAW_ACK_RETURNED", "callback" -> JsNumber(id))
                  }
                })
                emit("RAW_CALLBACK_RETURNED", "callback" -> JsNumber(id))
              }
            })
          override def close(): Unit = delegate.close()
        }
      }
    }
    NodeRegistry.registerSource(new MySQLCdcSourceNode(observedFactory, () =>
      MySQLCdcStateConfig(settings.url(schema), settings.user, settings.password,
        "debezium_offset_storage", "debezium_database_history", 0)) {
      override def createBatches(node: Node, executionId: String, boundary: SnapshotBoundary,
        resumeFrom: Option[BatchCheckpoint], onLog: String => Unit)
        (implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = {
        emit("READER_RESUME", "executionId" -> JsString(executionId),
          "checkpoint" -> resumeFrom.map(checkpointJson).getOrElse(JsNull))
        super.createBatches(node, executionId, boundary, resumeFrom, onLog).map { batch =>
          emit("SOURCE_BATCH", "batch" -> batchJson(batch))
          batch
        }
      }
      override def acknowledgeCommittedBatch(node: Node, batch: SourceBatch, onLog: String => Unit)
        (implicit blockingEc: ExecutionContext): Future[Done] = {
        emit("ACK_BEGIN", "batchId" -> JsString(batch.batchId))
        super.acknowledgeCommittedBatch(node, batch, onLog).map { done =>
          emit("ACK_RETURNED", "batchId" -> JsString(batch.batchId))
          done
        }
      }
    })
    NodeRegistry.registerSink(new MySQLCdcApplySinkNode {
      override def commitBatch(node: Node, workflowId: String, executionId: String, batch: SourceBatch,
        transformedRows: Vector[String], onLog: String => Unit)
        (implicit blockingEc: ExecutionContext): Future[BatchCommitResult] = {
        emit("SINK_ATTEMPT", "batch" -> batchJson(batch))
        super.commitBatch(node, workflowId, executionId, batch, transformedRows, onLog)
          .andThen {
            case Success(result) => emit("SINK_COMMITTED", "batch" -> batchJson(batch),
              "result" -> JsString(result.getClass.getSimpleName))
            case Failure(error) => emit("SINK_FAILED", "batch" -> batchJson(batch),
              "error" -> JsString(error.toString))
          }
          .flatMap { result =>
            if (fault == "after-T" && armed.compareAndSet(true, false)) {
              emit("CUTPOINT", "batch" -> batchJson(batch))
              Promise[BatchCommitResult]().future // The real engine cannot ask AdvanceCheckpoint yet.
            } else Future.successful(result)
          }
      }
    })
    val engine = new WorkflowExecutionEngine()(kit.system, ec)
    val actor = kit.spawn(EventSourcedWorkflowActor(workflowId, engine)(ec), "workflow")
    if (mode != "recover") {
      val replies = kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
      actor ! EventSourcedWorkflowActor.DefineWorkflow(workflow(settings, schema, connector, workflowId, mode, lineage), 0L, replies.ref)
      require(replies.receiveMessage(30.seconds).isInstanceOf[EventSourcedWorkflowActor.Defined])
      actor ! EventSourcedWorkflowActor.ExecuteManual("initial", replies.ref)
      val accepted = replies.receiveMessage(30.seconds).asInstanceOf[EventSourcedWorkflowActor.ExecutionAccepted]
      emit("STARTED", "executionId" -> JsString(accepted.executionId))
    }
    emit("PROCESS_READY", "pid" -> JsNumber(ProcessHandle.current().pid()), "mode" -> JsString(mode),
      "jvmTimezone" -> JsString(java.util.TimeZone.getDefault.getID))
    try {
      Iterator.continually(StdIn.readLine()).takeWhile(_ != null).foreach {
        case "ARM" => armed.set(true); emit("ARMED")
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
                "checkpoint" -> (event match {
                  case value: ExecutionCheckpointAdvanced => checkpointJson(value.checkpoint)
                  case _ => JsNull
                }))
            } finally { rows.close(); statement.close() }
            JsArray(events.result())
          }
          emit("STATE", "status" -> JsString(summary.status.value),
            "executionId" -> current.map(v => JsString(v.executionId)).getOrElse(JsNull),
            "actorCheckpoints" -> JsArray(current.toVector.flatMap(_.checkpoints).map(checkpointJson)),
            "journal" -> journal)
        case other => throw new IllegalArgumentException(s"unsupported command: $other")
      }
    } finally kit.shutdownTestKit()
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
