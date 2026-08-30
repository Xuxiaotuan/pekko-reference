package cn.xuyinyin.magic.workflow.checkpoint

import cn.xuyinyin.magic.common.CborSerializable

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final case class SourceCursor(
  kind: String,
  value: String,
  upperBound: String
) extends CborSerializable

final case class SnapshotBoundary(
  sourceNodeId: String,
  partitionId: String,
  upperBound: Option[String]
) extends CborSerializable

final case class SourceBatch(
  sourceNodeId: String,
  partitionId: String,
  batchSequence: Long,
  batchId: String,
  cursor: SourceCursor,
  rows: Vector[String]
)

final case class BatchCheckpoint(
  sourceNodeId: String,
  partitionId: String,
  batchSequence: Long,
  batchId: String,
  cursor: SourceCursor,
  sourceRowsScanned: Long,
  targetRowsWritten: Long
) extends CborSerializable

sealed trait BatchCommitResult
final case class Committed(checkpoint: BatchCheckpoint) extends BatchCommitResult
final case class AlreadyCommitted(checkpoint: BatchCheckpoint) extends BatchCommitResult

object BatchId {
  def sha256(executionId: String, sourceNodeId: String, partitionId: String, sequence: Long): String = {
    val bytes = s"$executionId|$sourceNodeId|$partitionId|$sequence".getBytes(StandardCharsets.UTF_8)
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString
  }
}
