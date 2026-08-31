package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSource, NodeSource}
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class KafkaSource(
  resolver: KafkaTopicResolver = new DefaultKafkaTopicResolver(),
  clientAccess: KafkaClientAccess = new PekkoKafkaClientAccess(),
  nowMillis: () => Long = () => System.currentTimeMillis()
) extends NodeSource with CheckpointedNodeSource {
  override val nodeType: String = "kafka.consumer"

  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
    Source.failed(new UnsupportedOperationException("kafka.consumer requires checkpoint-aware execution"))

  override def discoverBoundary(
    node: WorkflowDSL.Node,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
    val config = KafkaSourceConfig.parse(node)
    val deadline = Math.addExact(nowMillis(), config.maxDuration.toMillis)
    resolver.resolve(config).flatMap { resolved =>
      val metadataReset = if (resumeFrom.nonEmpty) KafkaOffsetReset.Earliest else config.offsetReset
      clientAccess.partitionOffsets(resolved, metadataReset).map { discovered =>
        val previousOffsets = resumeFrom.map(checkpointOffsets(node, _)).getOrElse(Map.empty)
        val partitions = discovered.sortBy(_.partition).map { partition =>
          val start = previousOffsets.get(partition.partition) match {
            case Some(previous) if previous < partition.startOffset =>
              throw new IllegalArgumentException(
                s"Kafka retention gap for partition ${partition.partition}: previous offset $previous is below broker beginning offset ${partition.startOffset}"
              )
            case Some(previous) if previous > partition.endOffset =>
              throw new IllegalArgumentException(
                s"Kafka previous offset $previous for partition ${partition.partition} exceeds broker end offset ${partition.endOffset}"
              )
            case Some(previous) => previous
            case None if resumeFrom.nonEmpty && config.offsetReset == KafkaOffsetReset.Latest => partition.endOffset
            case None => partition.startOffset
          }
          partition.copy(startOffset = start)
        }
        val encoded = KafkaCheckpointCodec.encodeBoundary(KafkaBoundaryV1(
          resolved.topic,
          resolved.bootstrapServers,
          deadline,
          partitions
        ))
        onLog(s"冻结Kafka边界: ${resolved.topic}, ${partitions.size} 个分区")
        SnapshotBoundary(node.id, partitionId(resolved.topic), Some(encoded))
      }(blockingEc)
    }(blockingEc)
  }

  override def createBatches(
    node: WorkflowDSL.Node,
    executionId: String,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = {
    val config = KafkaSourceConfig.parse(node)
    val encodedBoundary = boundary.upperBound.getOrElse(
      throw new IllegalArgumentException("Kafka boundary is missing its upper bound"))
    val decodedBoundary = KafkaCheckpointCodec.decodeBoundary(encodedBoundary)
    validateBoundary(node, boundary, decodedBoundary)

    val boundaryOffsets = decodedBoundary.partitions.map(partition => partition.partition -> partition.startOffset).toMap
    val initial = resumeFrom match {
      case Some(checkpoint) =>
        validateCheckpoint(node, checkpoint)
        val previousBoundary = KafkaCheckpointCodec.decodeBoundary(checkpoint.cursor.upperBound)
        val previousCursor = KafkaCheckpointCodec.decodeCursor(checkpoint.cursor.value)
        KafkaCheckpointCodec.validateCursor(previousBoundary, previousCursor)
        if (checkpoint.cursor.upperBound == encodedBoundary) {
          KafkaCheckpointCodec.validateCursor(decodedBoundary, previousCursor)
          BatchState(previousCursor.nextOffsets, previousCursor.recordsConsumed, checkpoint.batchSequence + 1L)
        } else {
          BatchState(boundaryOffsets, 0L, checkpoint.batchSequence + 1L)
        }
      case None => BatchState(boundaryOffsets, 0L, 0L)
    }

    val remainingRecords = config.maxRecords - initial.recordsConsumed
    val remainingMillis = decodedBoundary.deadlineEpochMillis - nowMillis()
    if (remainingRecords <= 0L || remainingMillis <= 0L) Source.empty
    else {
      val resolved = ResolvedKafkaTopic(decodedBoundary.topic, decodedBoundary.bootstrapServers)
      val events = Source(decodedBoundary.partitions.sortBy(_.partition))
        .flatMapConcat { partition =>
          val start = initial.nextOffsets(partition.partition)
          clientAccess.records(resolved, partition.partition, start, partition.endOffset)
            .mapError {
              case failure: OffsetOutOfRangeException =>
                val translated = new OffsetOutOfRangeException(
                  s"Kafka offset out of range for partition ${partition.partition} at start offset $start",
                  failure.offsetOutOfRangePartitions()
                )
                translated.initCause(failure)
                translated
            }
            .map(KafkaValue.apply)
            .concat(Source.single(KafkaPartitionCompleted(partition.partition, partition.endOffset)))
        }
        .takeWhile(_ => nowMillis() < decodedBoundary.deadlineEpochMillis)
        .takeWithin(remainingMillis.millis)

      events.via(new KafkaBatchingStage(
        node.id,
        boundary.partitionId,
        executionId,
        encodedBoundary,
        initial,
        config.chunkSize,
        config.maxRecords
      ))
    }
  }

  private def checkpointOffsets(node: WorkflowDSL.Node, checkpoint: BatchCheckpoint): Map[Int, Long] = {
    validateCheckpoint(node, checkpoint)
    val previousBoundary = KafkaCheckpointCodec.decodeBoundary(checkpoint.cursor.upperBound)
    val previousCursor = KafkaCheckpointCodec.decodeCursor(checkpoint.cursor.value)
    KafkaCheckpointCodec.validateCursor(previousBoundary, previousCursor)
    if (checkpoint.partitionId != partitionId(previousBoundary.topic))
      throw new IllegalArgumentException("Kafka checkpoint partition does not match its boundary topic")
    previousCursor.nextOffsets
  }

  private def validateBoundary(
    node: WorkflowDSL.Node,
    boundary: SnapshotBoundary,
    decoded: KafkaBoundaryV1
  ): Unit = {
    require(boundary.sourceNodeId == node.id, "Kafka boundary source does not match node")
    require(boundary.partitionId == partitionId(decoded.topic), "Kafka boundary partition does not match topic")
  }

  private def validateCheckpoint(node: WorkflowDSL.Node, checkpoint: BatchCheckpoint): Unit = {
    require(checkpoint.sourceNodeId == node.id, "Kafka checkpoint source does not match node")
    require(checkpoint.cursor.kind == KafkaCheckpointCodec.CursorKind, "Kafka checkpoint cursor kind is unsupported")
  }

  private def partitionId(topic: String): String = s"kafka:$topic"

  private final case class BatchState(nextOffsets: Map[Int, Long], recordsConsumed: Long, sequence: Long)

  private sealed trait KafkaStreamEvent
  private final case class KafkaValue(record: KafkaRecord) extends KafkaStreamEvent
  private final case class KafkaPartitionCompleted(partition: Int, endOffset: Long) extends KafkaStreamEvent

  private final class KafkaBatchingStage(
    sourceNodeId: String,
    sourcePartitionId: String,
    executionId: String,
    encodedBoundary: String,
    initial: BatchState,
    chunkSize: Int,
    maxRecords: Long
  ) extends GraphStage[FlowShape[KafkaStreamEvent, SourceBatch]] {
    private val in = Inlet[KafkaStreamEvent]("KafkaBatchingStage.in")
    private val out = Outlet[SourceBatch]("KafkaBatchingStage.out")
    override val shape: FlowShape[KafkaStreamEvent, SourceBatch] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var nextOffsets = initial.nextOffsets
      private var lastEmittedOffsets = initial.nextOffsets
      private var recordsConsumed = initial.recordsConsumed
      private var sequence = initial.sequence
      private var rows = Vector.empty[String]

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })

      setHandler(in, new InHandler {
        override def onPush(): Unit = grab(in) match {
          case KafkaValue(record) =>
            if (record.value == null)
              throw new IllegalArgumentException(
                s"null Kafka value at partition ${record.partition} offset ${record.offset}"
              )
            nextOffsets = nextOffsets.updated(record.partition, record.offset + 1L)
            recordsConsumed += 1L
            rows :+= record.value
            if (recordsConsumed >= maxRecords) {
              cancel(in)
              emit(out, nextBatch(), () => completeStage())
            } else if (rows.size >= chunkSize) push(out, nextBatch())
            else pull(in)

          case KafkaPartitionCompleted(partition, endOffset) =>
            nextOffsets = nextOffsets.updated(partition, endOffset)
            pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          if (rows.nonEmpty || nextOffsets != lastEmittedOffsets)
            emit(out, nextBatch(), () => completeStage())
          else completeStage()
        }
      })

      private def nextBatch(): SourceBatch = {
        val cursor = SourceCursor(
          KafkaCheckpointCodec.CursorKind,
          KafkaCheckpointCodec.encodeCursor(KafkaCursorV1(nextOffsets, recordsConsumed)),
          encodedBoundary
        )
        val batch = SourceBatch(
          sourceNodeId,
          sourcePartitionId,
          sequence,
          BatchId.sha256(executionId, sourceNodeId, sourcePartitionId, sequence),
          cursor,
          rows
        )
        sequence += 1L
        rows = Vector.empty
        lastEmittedOffsets = nextOffsets
        batch
      }
    }
  }
}
