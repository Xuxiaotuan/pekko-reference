package cn.xuyinyin.magic.workflow.engine {
  import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
  import cn.xuyinyin.magic.workflow.nodes.base.{NodeSink, NodeSource}

  private[workflow] object BoundedKafkaRecoveryRegistryCleanup {
    def unregister(source: NodeSource): Unit = NodeRegistry.unregisterSource(source.nodeType, source)
    def unregister(sink: NodeSink): Unit = NodeRegistry.unregisterSink(sink.nodeType, sink)
  }
}

package cn.xuyinyin.magic.workflow.nodes.sources {
  import org.apache.pekko.NotUsed
  import org.apache.pekko.stream.scaladsl.Source

  import java.util.concurrent.atomic.AtomicInteger
  import scala.concurrent.{ExecutionContext, Future}

  private[workflow] final class InMemoryKafkaFixture(initial: Map[Int, Vector[KafkaRecord]]) {
    private val recordsByPartition = scala.collection.mutable.Map.from(initial)
    private val resolverCallCount = new AtomicInteger(0)
    private val metadataCallCount = new AtomicInteger(0)

    private val resolver = new KafkaTopicResolver {
      override def resolve(config: KafkaSourceConfig)(implicit ec: ExecutionContext): Future[ResolvedKafkaTopic] = {
        resolverCallCount.incrementAndGet()
        Future.successful(ResolvedKafkaTopic(config.topic, "kafka:9092"))
      }
    }

    private val access = new KafkaClientAccess {
      override def partitionOffsets(topic: ResolvedKafkaTopic, reset: KafkaOffsetReset)
        (implicit ec: ExecutionContext): Future[Vector[KafkaPartitionBoundary]] = {
        metadataCallCount.incrementAndGet()
        val snapshot = synchronized(recordsByPartition.toVector)
        Future.successful(snapshot.map { case (partition, records) =>
          val beginning = records.headOption.map(_.offset).getOrElse(0L)
          val end = records.lastOption.map(_.offset + 1L).getOrElse(beginning)
          KafkaPartitionBoundary(
            partition,
            if (reset == KafkaOffsetReset.Latest) end else beginning,
            end
          )
        }.sortBy(_.partition))
      }

      override def records(
        topic: ResolvedKafkaTopic,
        partition: Int,
        startOffset: Long,
        endOffset: Long
      ): Source[KafkaRecord, NotUsed] = {
        val snapshot = synchronized(recordsByPartition.getOrElse(partition, Vector.empty))
        Source(snapshot.filter(record => record.offset >= startOffset && record.offset < endOffset))
      }
    }

    val source: KafkaSource = new KafkaSource(resolver, access, () => 1000L)

    def append(partition: Int, value: String): Unit = synchronized {
      val current = recordsByPartition.getOrElse(partition, Vector.empty)
      val nextOffset = current.lastOption.map(_.offset + 1L).getOrElse(0L)
      recordsByPartition.update(partition, current :+ KafkaRecord(partition, nextOffset, value))
    }

    def resolverCalls: Int = resolverCallCount.get()
    def metadataCalls: Int = metadataCallCount.get()
  }
}

package cn.xuyinyin.magic.workflow.integration {

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.engine.{BoundedKafkaRecoveryRegistryCleanup, WorkflowExecutionEngine}
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, CheckpointedNodeSource, NodeSink}
import cn.xuyinyin.magic.workflow.nodes.sources.{InMemoryKafkaFixture, KafkaRecord}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.{JsNumber, JsObject, JsString}

import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.io.{Source => IoSource}

object BoundedKafkaRecoverySpec {
  private val h2Url =
    s"jdbc:h2:mem:bounded-kafka-recovery-${java.util.UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"

  def config: Config = synchronized {
    Class.forName("org.h2.Driver")
    val connection = DriverManager.getConnection(h2Url)
    try {
      val statement = connection.createStatement()
      try {
        statement.execute("DROP ALL OBJECTS")
        val input = Option(getClass.getClassLoader.getResourceAsStream("schema/h2/h2-create-schema.sql"))
          .getOrElse(throw new IllegalStateException("Pekko Persistence JDBC H2 schema resource is unavailable"))
        val sql = try IoSource.fromInputStream(input).mkString finally input.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      } finally statement.close()
    } finally connection.close()

    ConfigFactory.parseString(
      s"""pekko-persistence-jdbc.shared-databases.slick.db.url = \"$h2Url\"
         |pekko.workflow.event-sourcing.snapshot-every = 100
         |pekko.coordinated-shutdown.exit-jvm = off""".stripMargin
    ).withFallback(ConfigFactory.load("application-test"))
  }

  final case class CommitAttempt(
    executionId: String,
    batchSequence: Long,
    batchId: String,
    rows: Vector[String],
    result: String
  )

  final case class AttemptTrace(
    committedBatchIds: Vector[String],
    expectedBatchIds: Vector[String],
    sourceRows: Vector[String]
  )
}

class BoundedKafkaRecoverySpec
    extends ScalaTestWithActorTestKit(BoundedKafkaRecoverySpec.config)
    with AnyWordSpecLike
    with OptionValues
    with Matchers {
  import BoundedKafkaRecoverySpec._

  private implicit val ec: ExecutionContext = system.executionContext

  "bounded Kafka reliable execution" should {
    "replay a committed unacknowledged batch and seed only new records into the next schedule" in {
      val builtIn = NodeRegistry.findSource("kafka.consumer").value
      builtIn shouldBe a[CheckpointedNodeSource]
      val legacyFailure = intercept[UnsupportedOperationException] {
        Await.result(builtIn.createSource(kafkaNode, _ => ()).runWith(Sink.seq), 2.seconds)
      }
      legacyFailure.getMessage should include("checkpoint-aware")

      val kafka = new InMemoryKafkaFixture(initialRecords)
      val sink = new CheckpointLedgerSink
      NodeRegistry.registerSource(kafka.source)
      NodeRegistry.registerSink(sink)

      var firstEntity: Option[org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command]] = None
      var recoveredEntity: Option[org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command]] = None
      try {
        val workflowId = s"bounded-kafka-${java.util.UUID.randomUUID()}"
        val engine = new WorkflowExecutionEngine()
        val first = spawn(EventSourcedWorkflowActor(workflowId, engine), s"$workflowId-before-crash")
        firstEntity = Some(first)
        val replies = createTestProbe[EventSourcedWorkflowActor.Reply]()

        first ! EventSourcedWorkflowActor.DefineWorkflow(workflow(workflowId), 0L, replies.ref)
        replies.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
        first ! EventSourcedWorkflowActor.ExecuteManual("first-run", replies.ref)
        val firstExecutionId = replies.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted].executionId

        Await.result(sink.secondBatchCommitted, 5.seconds)
        val resolverCallsAfterBoundaryPersistence = kafka.resolverCalls
        resolverCallsAfterBoundaryPersistence shouldBe 1
        kafka.metadataCalls shouldBe 1
        eventuallyCurrent(first) { execution =>
          execution.executionId shouldBe firstExecutionId
          execution.boundary should not be empty
          execution.checkpoints.map(_.batchSequence) shouldBe Vector(0L)
        }

        testKit.stop(first)
        firstEntity = None
        sink.releaseSecondBatch()

        val recovered = spawn(EventSourcedWorkflowActor(workflowId, engine), s"$workflowId-after-crash")
        recoveredEntity = Some(recovered)
        eventuallyCompleted(recovered, firstExecutionId)

        kafka.resolverCalls shouldBe resolverCallsAfterBoundaryPersistence
        kafka.metadataCalls shouldBe 1

        val firstAttempts = sink.attemptsFor(firstExecutionId)
        val firstAttempt = AttemptTrace(
          firstAttempts.take(2).map(_.batchId),
          Vector(0L, 1L).map(sequence => BatchId.sha256(firstExecutionId, "source-1", "kafka:events", sequence)),
          firstAttempts.take(2).flatMap(_.rows)
        )
        val recoveredAttempt = AttemptTrace(
          firstAttempts.drop(2).map(_.batchId),
          Vector(1L, 2L).map(sequence => BatchId.sha256(firstExecutionId, "source-1", "kafka:events", sequence)),
          firstAttempts.drop(2).flatMap(_.rows)
        )

        firstAttempt.committedBatchIds shouldBe firstAttempt.expectedBatchIds
        recoveredAttempt.committedBatchIds.head shouldBe firstAttempt.expectedBatchIds(1)
        recoveredAttempt.committedBatchIds shouldBe recoveredAttempt.expectedBatchIds
        firstAttempts.map(_.result) shouldBe Vector("Committed", "Committed", "AlreadyCommitted", "Committed")
        firstAttempts.map(_.batchSequence) shouldBe Vector(0L, 1L, 1L, 2L)
        firstAttempts(2).rows shouldBe firstAttempts(1).rows
        sink.targetIds.distinct shouldBe sink.targetIds
        sink.targetIds.sorted shouldBe (1 to 25).map(index => f"event-$index%04d").toVector
        sink.ledgerCount shouldBe 3

        kafka.append(0, "event-0026")
        kafka.append(1, "event-0027")
        recovered ! EventSourcedWorkflowActor.ExecuteScheduled(
          "bounded-kafka-schedule",
          scheduledAt = 2000L,
          triggerId = "scheduled-run-2",
          replies.ref
        )
        val secondExecutionId = replies.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted].executionId
        secondExecutionId should not be firstExecutionId
        eventuallyCompleted(recovered, secondExecutionId)

        val secondAttempts = sink.attemptsFor(secondExecutionId)
        val secondRun = AttemptTrace(
          secondAttempts.map(_.batchId),
          Vector(BatchId.sha256(secondExecutionId, "source-1", "kafka:events", 3L)),
          secondAttempts.flatMap(_.rows)
        )
        secondRun.sourceRows shouldBe Vector("event-0026", "event-0027")
        secondRun.committedBatchIds shouldBe secondRun.expectedBatchIds
        secondAttempts.map(_.batchSequence) shouldBe Vector(3L)
        sink.targetIds.distinct shouldBe sink.targetIds
        sink.targetIds.sorted shouldBe (1 to 27).map(index => f"event-$index%04d").toVector
        sink.ledgerCount shouldBe 4
        kafka.resolverCalls shouldBe resolverCallsAfterBoundaryPersistence + 1
        kafka.metadataCalls shouldBe 2
      } finally {
        firstEntity.foreach(entity => testKit.stop(entity))
        recoveredEntity.foreach(entity => testKit.stop(entity))
        sink.releaseSecondBatch()
        BoundedKafkaRecoveryRegistryCleanup.unregister(kafka.source)
        BoundedKafkaRecoveryRegistryCleanup.unregister(sink)
      }
    }
  }

  private def eventuallyCurrent(
    entity: org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command]
  )(assertion: EventSourcedWorkflowActor.ExecutionState => Unit): Unit = eventuallySummary(entity) { summary =>
    assertion(summary.currentExecution.value)
  }

  private def eventuallyCompleted(
    entity: org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command],
    executionId: String
  ): Unit = eventuallySummary(entity) { summary =>
    summary.currentExecution shouldBe empty
    summary.recentExecutions.find(_.executionId == executionId).value.status shouldBe "completed"
  }

  private def eventuallySummary(
    entity: org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command]
  )(assertion: EventSourcedWorkflowActor.WorkflowSummary => Unit): Unit = {
    val deadline = 8.seconds.fromNow
    var lastFailure: Option[Throwable] = None
    while (deadline.hasTimeLeft()) {
      val probe = createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
      entity ! EventSourcedWorkflowActor.GetSummary(probe.ref)
      try {
        assertion(probe.receiveMessage(500.millis))
        return
      } catch {
        case error: Throwable =>
          lastFailure = Some(error)
          Thread.sleep(50L)
      }
    }
    throw lastFailure.getOrElse(new AssertionError("workflow summary did not reach the expected state"))
  }

  private def workflow(id: String): WorkflowDSL.Workflow = WorkflowDSL.Workflow(
    id = id,
    name = "bounded Kafka recovery",
    description = "in-process recovery regression",
    version = "1",
    author = "test",
    tags = Nil,
    nodes = List(
      kafkaNode,
      WorkflowDSL.Node(
        "sink-1",
        "sink",
        CheckpointLedgerSink.NodeType,
        "Checkpoint ledger",
        WorkflowDSL.Position(1, 0),
        JsObject.empty
      )
    ),
    edges = List(WorkflowDSL.Edge("source-to-sink", "source-1", "sink-1")),
    metadata = WorkflowDSL.WorkflowMetadata("2026-08-30", "2026-08-30")
  )

  private def kafkaNode: WorkflowDSL.Node = WorkflowDSL.Node(
    id = "source-1",
    `type` = "source",
    nodeType = "kafka.consumer",
    label = "Kafka",
    position = WorkflowDSL.Position(0, 0),
    config = JsObject(
      "topic" -> JsString("events"),
      "gravitino" -> JsObject(
        "uri" -> JsString("http://gravitino:8090"),
        "metalake" -> JsString("pekko"),
        "catalog" -> JsString("bigdata-kafka")
      ),
      "offsetReset" -> JsString("earliest"),
      "chunkSize" -> JsNumber(10),
      "maxRecords" -> JsNumber(50),
      "maxDurationSeconds" -> JsNumber(120)
    )
  )

  private def initialRecords: Map[Int, Vector[KafkaRecord]] = Map(
    0 -> (1 to 9).map(index => KafkaRecord(0, index - 1L, f"event-$index%04d")).toVector,
    1 -> (10 to 17).map(index => KafkaRecord(1, index - 10L, f"event-$index%04d")).toVector,
    2 -> (18 to 25).map(index => KafkaRecord(2, index - 18L, f"event-$index%04d")).toVector
  )

  private object CheckpointLedgerSink {
    val NodeType = "test.kafka-checkpoint-ledger"
  }

  private final class CheckpointLedgerSink extends NodeSink with CheckpointedNodeSink {
    override val nodeType: String = CheckpointLedgerSink.NodeType
    private val ledger = mutable.LinkedHashMap.empty[String, BatchCheckpoint]
    private val targets = mutable.ArrayBuffer.empty[String]
    private val commitAttempts = mutable.ArrayBuffer.empty[CommitAttempt]
    private val crashInjected = new AtomicBoolean(false)
    private val secondBatchCommittedPromise = Promise[Unit]()
    private val releaseSecondBatchPromise = Promise[Unit]()

    def secondBatchCommitted: Future[Unit] = secondBatchCommittedPromise.future
    def releaseSecondBatch(): Unit = releaseSecondBatchPromise.trySuccess(())
    def ledgerCount: Int = synchronized(ledger.size)
    def targetIds: Vector[String] = synchronized(targets.toVector)
    def attemptsFor(executionId: String): Vector[CommitAttempt] =
      synchronized(commitAttempts.filter(_.executionId == executionId).toVector)

    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
      (implicit ec: ExecutionContext): Sink[String, Future[Done]] =
      throw new AssertionError("reliable execution used the legacy sink")

    override def validateReady(node: WorkflowDSL.Node, onLog: String => Unit)
      (implicit blockingEc: ExecutionContext): Future[Done] = Future.successful(Done)

    override def commitBatch(
      node: WorkflowDSL.Node,
      workflowId: String,
      executionId: String,
      batch: SourceBatch,
      transformedRows: Vector[String],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] = synchronized {
      val checkpoint = BatchCheckpoint(
        batch.sourceNodeId,
        batch.partitionId,
        batch.batchSequence,
        batch.batchId,
        batch.cursor,
        batch.rows.size.toLong,
        transformedRows.size.toLong
      )

      ledger.get(batch.batchId) match {
        case Some(existing) if existing == checkpoint =>
          commitAttempts += CommitAttempt(executionId, batch.batchSequence, batch.batchId, batch.rows, "AlreadyCommitted")
          Future.successful(AlreadyCommitted(existing))
        case Some(_) => Future.failed(new IllegalStateException(s"conflicting batch ledger entry ${batch.batchId}"))
        case None =>
          ledger.put(batch.batchId, checkpoint)
          targets ++= transformedRows
          commitAttempts += CommitAttempt(executionId, batch.batchSequence, batch.batchId, batch.rows, "Committed")
          if (batch.batchSequence == 1L && crashInjected.compareAndSet(false, true)) {
            secondBatchCommittedPromise.trySuccess(())
            releaseSecondBatchPromise.future.map(_ => Committed(checkpoint))(blockingEc)
          } else Future.successful(Committed(checkpoint))
      }
    }
  }
}
}
