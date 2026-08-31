package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.consumer.{ConsumerConfig, OffsetOutOfRangeException}
import org.apache.kafka.common.TopicPartition
import org.apache.pekko.{NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import spray.json.{JsNumber, JsObject, JsString}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class KafkaSourceSpec extends STSpec {
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty[Nothing],
    "kafka-source-spec",
    ConfigFactory.parseString(
      """pekko.actor.provider = local
        |pekko.coordinated-shutdown.exit-jvm = off""".stripMargin
    ).withFallback(ConfigFactory.load("application-test"))
  )
  private implicit val ec: ExecutionContext = system.executionContext

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
    super.afterAll()
  }

  "KafkaSource" should {
    "consume partitions in numeric order and emit deterministic batches" in {
      val access = FakeKafkaClientAccess(
        boundaries = Vector(
          KafkaPartitionBoundary(1, 0, 2),
          KafkaPartitionBoundary(0, 0, 3)
        ),
        recordsByPartition = Map(
          0 -> Vector(KafkaRecord(0, 0, "p0-0"), KafkaRecord(0, 1, "p0-1"), KafkaRecord(0, 2, "p0-2")),
          1 -> Vector(KafkaRecord(1, 0, "p1-0"), KafkaRecord(1, 1, "p1-1"))
        )
      )
      val source = new KafkaSource(StaticResolver("events", "kafka:9092"), access, () => 1000L)
      val boundary = Await.result(source.discoverBoundary(kafkaNode(chunkSize = 2), None, _ => ()), 2.seconds)
      val batches = Await.result(
        source.createBatches(kafkaNode(chunkSize = 2), "execution-1", boundary, None, _ => ()).runWith(Sink.seq),
        2.seconds
      )

      batches.map(_.rows) shouldBe Seq(Vector("p0-0", "p0-1"), Vector("p0-2", "p1-0"), Vector("p1-1"))
      batches.map(_.batchSequence) shouldBe Seq(0L, 1L, 2L)
      batches.map(_.batchId) shouldBe Seq(
        BatchId.sha256("execution-1", "source-1", "kafka:events", 0L),
        BatchId.sha256("execution-1", "source-1", "kafka:events", 1L),
        BatchId.sha256("execution-1", "source-1", "kafka:events", 2L)
      )
      KafkaCheckpointCodec.decodeCursor(batches.last.cursor.value).nextOffsets shouldBe Map(0 -> 3L, 1 -> 2L)
      batches.foreach(_.cursor.upperBound shouldBe boundary.upperBound.get)
    }

    "retain the consumed count when resuming the same execution boundary" in {
      val persistedBoundary = boundary(
        deadline = 121000L,
        partitions = Vector(KafkaPartitionBoundary(1, 0, 3), KafkaPartitionBoundary(0, 0, 3))
      )
      val resume = checkpoint(
        persistedBoundary,
        sequence = 1L,
        nextOffsets = Map(0 -> 3L, 1 -> 1L),
        recordsConsumed = 4L
      )
      val access = FakeKafkaClientAccess(
        boundaries = Vector.empty,
        recordsByPartition = Map(
          0 -> Vector(KafkaRecord(0, 0, "p0-0"), KafkaRecord(0, 1, "p0-1"), KafkaRecord(0, 2, "p0-2")),
          1 -> Vector(KafkaRecord(1, 0, "p1-0"), KafkaRecord(1, 1, "p1-1"), KafkaRecord(1, 2, "p1-2"))
        )
      )
      val source = new KafkaSource(StaticResolver("events", "kafka:9092"), access, () => 1000L)

      val batches = Await.result(
        source.createBatches(kafkaNode(chunkSize = 2), "execution-1", persistedBoundary, Some(resume), _ => ()).runWith(Sink.seq),
        2.seconds
      )

      batches.map(_.rows) shouldBe Seq(Vector("p1-1", "p1-2"))
      batches.map(_.batchSequence) shouldBe Seq(2L)
      KafkaCheckpointCodec.decodeCursor(batches.head.cursor.value) shouldBe
        KafkaCursorV1(Map(0 -> 3L, 1 -> 3L), recordsConsumed = 6L)
    }

    "copy prior offsets into a new scheduled boundary and restart the per-run count" in {
      val previousBoundary = boundary(
        deadline = 1000L,
        partitions = Vector(KafkaPartitionBoundary(0, 0, 3), KafkaPartitionBoundary(1, 0, 2))
      )
      val previous = checkpoint(
        previousBoundary,
        sequence = 2L,
        nextOffsets = Map(0 -> 3L, 1 -> 2L),
        recordsConsumed = 5L
      )
      val access = FakeKafkaClientAccess(
        boundaries = Vector(KafkaPartitionBoundary(1, 0, 3), KafkaPartitionBoundary(0, 0, 5)),
        recordsByPartition = Map(
          0 -> Vector(KafkaRecord(0, 3, "p0-3"), KafkaRecord(0, 4, "p0-4")),
          1 -> Vector(KafkaRecord(1, 2, "p1-2"))
        )
      )
      val source = new KafkaSource(StaticResolver("events", "kafka:9092"), access, () => 2000L)

      val nextBoundary = Await.result(source.discoverBoundary(kafkaNode(chunkSize = 2), Some(previous), _ => ()), 2.seconds)
      val decodedBoundary = KafkaCheckpointCodec.decodeBoundary(nextBoundary.upperBound.get)
      val batches = Await.result(
        source.createBatches(kafkaNode(chunkSize = 2), "execution-2", nextBoundary, Some(previous), _ => ()).runWith(Sink.seq),
        2.seconds
      )

      decodedBoundary.partitions shouldBe Vector(
        KafkaPartitionBoundary(0, 3, 5),
        KafkaPartitionBoundary(1, 2, 3)
      )
      batches.map(_.rows) shouldBe Seq(Vector("p0-3", "p0-4"), Vector("p1-2"))
      batches.map(_.batchSequence) shouldBe Seq(3L, 4L)
      KafkaCheckpointCodec.decodeCursor(batches.head.cursor.value).recordsConsumed shouldBe 2L
      KafkaCheckpointCodec.decodeCursor(batches.last.cursor.value).recordsConsumed shouldBe 3L
    }

    "stop exactly at the configured record limit" in {
      val records = Vector.tabulate(10)(offset => KafkaRecord(0, offset.toLong, s"value-$offset"))
      val access = FakeKafkaClientAccess(Vector(KafkaPartitionBoundary(0, 0, 10)), Map(0 -> records))
      val source = new KafkaSource(StaticResolver("events", "kafka:9092"), access, () => 1000L)
      val node = kafkaNode(chunkSize = 2, maxRecords = 3)
      val discovered = Await.result(source.discoverBoundary(node, None, _ => ()), 2.seconds)

      val batches = Await.result(source.createBatches(node, "execution-limit", discovered, None, _ => ()).runWith(Sink.seq), 2.seconds)

      batches.flatMap(_.rows) shouldBe Vector("value-0", "value-1", "value-2")
      KafkaCheckpointCodec.decodeCursor(batches.last.cursor.value).recordsConsumed shouldBe 3L
    }

    "emit no rows when the persisted deadline has been reached" in {
      val resolver = StaticResolver("events", "kafka:9092")
      val access = FakeKafkaClientAccess(
        Vector(KafkaPartitionBoundary(0, 0, 1)),
        Map(0 -> Vector(KafkaRecord(0, 0, "late")))
      )
      val source = new KafkaSource(resolver, access, () => 1000L)
      val persistedBoundary = boundary(1000L, Vector(KafkaPartitionBoundary(0, 0, 1)))

      val batches = Await.result(
        source.createBatches(kafkaNode(), "execution-deadline", persistedBoundary, None, _ => ()).runWith(Sink.seq),
        2.seconds
      )

      batches shouldBe empty
      resolver.calls.get() shouldBe 0
      access.metadataCalls.get() shouldBe 0
    }

    "fail precisely when a prior next offset is below the broker beginning offset" in {
      val previousBoundary = boundary(1000L, Vector(KafkaPartitionBoundary(0, 0, 5)))
      val previous = checkpoint(previousBoundary, sequence = 0L, nextOffsets = Map(0 -> 2L), recordsConsumed = 2L)
      val access = FakeKafkaClientAccess(Vector(KafkaPartitionBoundary(0, 5, 10)), Map.empty)
      val source = new KafkaSource(StaticResolver("events", "kafka:9092"), access, () => 2000L)

      val failure = intercept[IllegalArgumentException] {
        Await.result(source.discoverBoundary(kafkaNode(), Some(previous), _ => ()), 2.seconds)
      }

      failure.getMessage should include("retention gap")
      failure.getMessage should include("2")
      failure.getMessage should include("5")
    }

    "fail a null Kafka value with its partition and offset" in {
      val access = FakeKafkaClientAccess(
        boundaries = Vector.empty,
        recordsByPartition = Map(0 -> Vector(KafkaRecord(0, 7, null)))
      )
      val source = new KafkaSource(StaticResolver("events", "kafka:9092"), access, () => 1000L)
      val persistedBoundary = boundary(121000L, Vector(KafkaPartitionBoundary(0, 7, 8)))

      val failure = intercept[IllegalArgumentException] {
        Await.result(
          source.createBatches(kafkaNode(), "execution-null", persistedBoundary, None, _ => ()).runWith(Sink.seq),
          2.seconds
        )
      }

      failure.getMessage should include("null Kafka value")
      failure.getMessage should include("partition 0")
      failure.getMessage should include("offset 7")
    }

    "use a persisted boundary without resolver or metadata discovery" in {
      val resolver = StaticResolver("events", "kafka:9092")
      val access = FakeKafkaClientAccess(
        boundaries = Vector(KafkaPartitionBoundary(0, 100, 200)),
        recordsByPartition = Map(0 -> Vector(KafkaRecord(0, 0, "persisted")))
      )
      val source = new KafkaSource(resolver, access, () => 1000L)
      val persistedBoundary = boundary(121000L, Vector(KafkaPartitionBoundary(0, 0, 1)))

      val batches = Await.result(
        source.createBatches(kafkaNode(), "execution-persisted", persistedBoundary, None, _ => ()).runWith(Sink.seq),
        2.seconds
      )

      batches.flatMap(_.rows) shouldBe Vector("persisted")
      resolver.calls.get() shouldBe 0
      access.metadataCalls.get() shouldBe 0
    }

    "fail an expired persisted start offset without resolver or metadata rediscovery" in {
      val resolver = StaticResolver("events", "kafka:9092")
      val metadataCalls = new AtomicInteger(0)
      val access = new KafkaClientAccess {
        override def partitionOffsets(topic: ResolvedKafkaTopic, reset: KafkaOffsetReset)
          (implicit ec: ExecutionContext): Future[Vector[KafkaPartitionBoundary]] = {
          metadataCalls.incrementAndGet()
          Future.successful(Vector.empty)
        }

        override def records(
          topic: ResolvedKafkaTopic,
          partition: Int,
          startOffset: Long,
          endOffset: Long
        ): Source[KafkaRecord, NotUsed] =
          Source.failed(new OffsetOutOfRangeException(
            Map(new TopicPartition(topic.topic, partition) -> Long.box(startOffset)).asJava
          ))
      }
      val source = new KafkaSource(resolver, access, () => 1000L)
      val persistedBoundary = boundary(121000L, Vector(KafkaPartitionBoundary(0, 2, 5)))
      val resume = checkpoint(persistedBoundary, sequence = 0L, nextOffsets = Map(0 -> 2L), recordsConsumed = 0L)

      val failure = intercept[OffsetOutOfRangeException] {
        Await.result(
          source.createBatches(kafkaNode(), "execution-expired", persistedBoundary, Some(resume), _ => ()).runWith(Sink.seq),
          2.seconds
        )
      }

      failure.getMessage should include("partition 0")
      failure.getMessage should include("start offset 2")
      resolver.calls.get() shouldBe 0
      metadataCalls.get() shouldBe 0
    }

    "complete sparse partitions by consumer position and continue to later partitions" in {
      val factory = new ScriptedKafkaPartitionPollerFactory(Map(
        0 -> Vector(
          KafkaPoll(Vector(KafkaRecord(0, 0, "p0-0")), position = 1L),
          KafkaPoll(Vector(KafkaRecord(0, 5, "p0-5")), position = 6L),
          KafkaPoll(Vector.empty, position = 10L)
        ),
        1 -> Vector(KafkaPoll(Vector(KafkaRecord(1, 0, "p1-0")), position = 1L))
      ))
      val access = new PekkoKafkaClientAccess(factory)
      val topic = ResolvedKafkaTopic("events", "kafka:9092")

      val records = Await.result(
        access.records(topic, partition = 0, startOffset = 0L, endOffset = 10L)
          .concat(access.records(topic, partition = 1, startOffset = 0L, endOffset = 1L))
          .runWith(Sink.seq),
        2.seconds
      )

      records.map(_.value) shouldBe Seq("p0-0", "p0-5", "p1-0")
      factory.pollsByPartition shouldBe Map(0 -> 3, 1 -> 1)
      factory.closedPartitions shouldBe Set(0, 1)
      factory.settings.foreach { settings =>
        settings.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG) shouldBe "false"
        settings.getProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG) shouldBe "none"
      }
    }

    "persist consumer-position progress after sparse trailing gaps" in {
      val factory = new ScriptedKafkaPartitionPollerFactory(Map(
        0 -> Vector(
          KafkaPoll(Vector(KafkaRecord(0, 0, "p0-0")), position = 1L),
          KafkaPoll(Vector(KafkaRecord(0, 5, "p0-5")), position = 6L),
          KafkaPoll(Vector.empty, position = 10L)
        ),
        1 -> Vector(KafkaPoll(Vector(KafkaRecord(1, 0, "p1-0")), position = 1L))
      ))
      val access = new PekkoKafkaClientAccess(factory)
      val source = new KafkaSource(StaticResolver("events", "kafka:9092"), access, () => 1000L)
      val persistedBoundary = boundary(
        deadline = 121000L,
        partitions = Vector(
          KafkaPartitionBoundary(0, 0L, 10L),
          KafkaPartitionBoundary(1, 0L, 1L)
        )
      )

      val batches = Await.result(
        source.createBatches(
          kafkaNode(chunkSize = 10),
          "execution-sparse",
          persistedBoundary,
          None,
          _ => ()
        ).runWith(Sink.seq),
        2.seconds
      )

      batches.flatMap(_.rows) shouldBe Vector("p0-0", "p0-5", "p1-0")
      KafkaCheckpointCodec.decodeCursor(batches.last.cursor.value).nextOffsets shouldBe
        Map(0 -> 10L, 1 -> 1L)
      factory.closedPartitions shouldBe Set(0, 1)
    }

    "close the manually assigned consumer when downstream cancels" in {
      val factory = new ScriptedKafkaPartitionPollerFactory(Map(
        0 -> Vector(
          KafkaPoll(
            Vector(KafkaRecord(0, 0, "first"), KafkaRecord(0, 1, "buffered")),
            position = 2L
          ),
          KafkaPoll(Vector.empty, position = 10L)
        )
      ))
      val access = new PekkoKafkaClientAccess(factory)

      val records = Await.result(
        access.records(ResolvedKafkaTopic("events", "kafka:9092"), 0, 0L, 10L)
          .take(1L)
          .runWith(Sink.seq),
        2.seconds
      )

      records.map(_.value) shouldBe Seq("first")
      factory.pollsByPartition(0) should (be >= 1 and be <= 2)
      factory.closedPartitions shouldBe Set(0)
    }

    "reject the legacy row stream without reliable execution context" in {
      val source = new KafkaSource()

      val failure = intercept[UnsupportedOperationException] {
        Await.result(source.createSource(kafkaNode(), _ => ()).runWith(Sink.seq), 2.seconds)
      }

      failure.getMessage should include("checkpoint-aware")
    }
  }

  private def kafkaNode(
    chunkSize: Int = 10,
    maxRecords: Long = 50L,
    maxDurationSeconds: Long = 120L
  ): WorkflowDSL.Node = WorkflowDSL.Node(
    id = "source-1",
    `type` = "source",
    nodeType = "kafka.consumer",
    label = "Kafka",
    position = WorkflowDSL.Position(0, 0),
    config = JsObject(
      "topic" -> JsString("events"),
      "brokers" -> JsString("kafka:9092"),
      "offsetReset" -> JsString("earliest"),
      "chunkSize" -> JsNumber(chunkSize),
      "maxRecords" -> JsNumber(maxRecords),
      "maxDurationSeconds" -> JsNumber(maxDurationSeconds)
    )
  )

  private def boundary(
    deadline: Long,
    partitions: Vector[KafkaPartitionBoundary]
  ): SnapshotBoundary = {
    val encoded = KafkaCheckpointCodec.encodeBoundary(KafkaBoundaryV1(
      topic = "events",
      bootstrapServers = "kafka:9092",
      deadlineEpochMillis = deadline,
      partitions = partitions
    ))
    SnapshotBoundary("source-1", "kafka:events", Some(encoded))
  }

  private def checkpoint(
    boundary: SnapshotBoundary,
    sequence: Long,
    nextOffsets: Map[Int, Long],
    recordsConsumed: Long
  ): BatchCheckpoint = BatchCheckpoint(
    sourceNodeId = boundary.sourceNodeId,
    partitionId = boundary.partitionId,
    batchSequence = sequence,
    batchId = BatchId.sha256("execution-previous", boundary.sourceNodeId, boundary.partitionId, sequence),
    cursor = SourceCursor(
      KafkaCheckpointCodec.CursorKind,
      KafkaCheckpointCodec.encodeCursor(KafkaCursorV1(nextOffsets, recordsConsumed)),
      boundary.upperBound.get
    ),
    sourceRowsScanned = recordsConsumed,
    targetRowsWritten = recordsConsumed
  )

  private final case class StaticResolver(topic: String, brokers: String) extends KafkaTopicResolver {
    val calls = new AtomicInteger(0)

    override def resolve(config: KafkaSourceConfig)(implicit ec: ExecutionContext): Future[ResolvedKafkaTopic] = {
      calls.incrementAndGet()
      Future.successful(ResolvedKafkaTopic(topic, brokers))
    }
  }

  private final case class FakeKafkaClientAccess(
    boundaries: Vector[KafkaPartitionBoundary],
    recordsByPartition: Map[Int, Vector[KafkaRecord]]
  ) extends KafkaClientAccess {
    val metadataCalls = new AtomicInteger(0)

    override def partitionOffsets(topic: ResolvedKafkaTopic, reset: KafkaOffsetReset)
      (implicit ec: ExecutionContext): Future[Vector[KafkaPartitionBoundary]] = {
      metadataCalls.incrementAndGet()
      Future.successful(boundaries)
    }

    override def records(
      topic: ResolvedKafkaTopic,
      partition: Int,
      startOffset: Long,
      endOffset: Long
    ): Source[KafkaRecord, NotUsed] =
      Source(recordsByPartition.getOrElse(partition, Vector.empty)
        .filter(record => record.offset >= startOffset && record.offset < endOffset))
  }

  private final class ScriptedKafkaPartitionPollerFactory(
    scripts: Map[Int, Vector[KafkaPoll]]
  ) extends KafkaPartitionPollerFactory {
    private val pollCounts = mutable.Map.empty[Int, Int]
    private val closed = mutable.Set.empty[Int]
    val settings: mutable.Buffer[ConsumerSettings[String, String]] = mutable.Buffer.empty

    def pollsByPartition: Map[Int, Int] = pollCounts.toMap
    def closedPartitions: Set[Int] = closed.toSet

    override def open(
      consumerSettings: ConsumerSettings[String, String],
      topicPartition: TopicPartition,
      startOffset: Long
    ): KafkaPartitionPoller = {
      settings += consumerSettings
      val partition = topicPartition.partition()
      val remaining = mutable.Queue.from(scripts.getOrElse(partition, Vector.empty))
      new KafkaPartitionPoller {
        override def poll(): KafkaPoll = {
          pollCounts.update(partition, pollCounts.getOrElse(partition, 0) + 1)
          remaining.dequeue()
        }

        override def close(): Unit = closed += partition
      }
    }
  }
}
