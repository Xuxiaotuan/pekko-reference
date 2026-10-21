package cn.xuyinyin.magic.workflow.snapshot

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.workflow.checkpoint.BatchCheckpoint
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final case class SnapshotSplitPlanState(id: String, sourceNodeId: String, fingerprint: String,
  upper: Option[String], pageRows: Int, splitRows: Int, parallelism: Int, maxPageBytes: Int) extends CborSerializable
final case class SnapshotSplit(id: String, index: Long, lower: Option[String], upper: String) extends CborSerializable
final case class SnapshotSplitProgress(split: SnapshotSplit, checkpoint: Option[BatchCheckpoint] = None,
  completed: Boolean = false, attempts: Int = 0, blocked: Option[String] = None, started: Boolean = false) extends CborSerializable
final case class SnapshotSplitState(plan: SnapshotSplitPlanState, splits: Vector[SnapshotSplitProgress] = Vector.empty,
  nextIndex: Long = 0L, nextLower: Option[String] = None, enumerated: Boolean = false,
  rowsCommitted: Long = 0L) extends CborSerializable

@com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "_type")
sealed trait SnapshotSplitUpdate extends CborSerializable
final case class InitializeSnapshotSplits(plan: SnapshotSplitPlanState) extends SnapshotSplitUpdate
final case class RegisterSnapshotSplit(split: SnapshotSplit) extends SnapshotSplitUpdate
final case class StartSnapshotSplit(id: String) extends SnapshotSplitUpdate
final case class CommitSnapshotPage(checkpoint: BatchCheckpoint, attempt: Int = 0) extends SnapshotSplitUpdate
final case class CompleteSnapshotSplit(id: String, attempt: Int = 0) extends SnapshotSplitUpdate
final case class RetrySnapshotSplit(id: String, attempt: Int) extends SnapshotSplitUpdate
final case class BlockSnapshotSplit(id: String, reason: String) extends SnapshotSplitUpdate
case object RetireSnapshotWindow extends SnapshotSplitUpdate
case object FinishSnapshotEnumeration extends SnapshotSplitUpdate

object SnapshotSplitState {
  val CursorKind = "mysql.snapshot-split.v1"
  val WindowSize = 16
  def sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  // Length framing makes different row boundaries distinguishable without retaining another page copy.
  def rowsDigest(rows: Vector[String]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    rows.foreach { row =>
      val bytes = row.getBytes(StandardCharsets.UTF_8)
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    }
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }
  def advance(current: Option[SnapshotSplitState], update: SnapshotSplitUpdate): Either[String, SnapshotSplitState] = {
    def invalid = Left("snapshot split update conflicts with durable state")
    update match {
      case InitializeSnapshotSplits(p) if current.isEmpty =>
        if (p.id.nonEmpty && p.sourceNodeId.nonEmpty && p.pageRows > 0 && p.splitRows >= p.pageRows &&
          p.parallelism > 0 && p.parallelism <= 8 && p.maxPageBytes > 0) Right(SnapshotSplitState(p)) else invalid
      case InitializeSnapshotSplits(p) => current.filter(_.plan == p).toRight("snapshot plan changed")
      case _ => current.toRight("snapshot plan is not initialized").flatMap { s =>
        def change(id: String)(f: SnapshotSplitProgress => Option[SnapshotSplitProgress]): Either[String, SnapshotSplitState] =
          s.splits.indexWhere(_.split.id == id) match {
            case -1 => invalid
            case i => f(s.splits(i)).map(p => s.copy(splits = s.splits.updated(i, p))).toRight("snapshot split progress rejected")
          }
        update match {
          case RegisterSnapshotSplit(split) if s.splits.exists(_.split == split) => Right(s)
          case RegisterSnapshotSplit(split) =>
            val lower = s.splits.lastOption.map(_.split.upper).orElse(s.nextLower)
            if (!s.enumerated && s.splits.size < WindowSize && split.index == s.nextIndex + s.splits.size &&
              split.id == s"${s.plan.id}:${split.index}" && split.lower == lower &&
              s.plan.upper.exists(u => BigDecimal(split.upper) <= BigDecimal(u)) &&
              lower.forall(l => BigDecimal(split.upper) > BigDecimal(l)))
              Right(s.copy(splits = s.splits :+ SnapshotSplitProgress(split))) else invalid
          case StartSnapshotSplit(id) => change(id)(p => Option.when(!p.completed && !p.started && p.blocked.isEmpty)(p.copy(started = true)))
          case CommitSnapshotPage(c, attempt) =>
            change(c.partitionId) { p =>
              val same = p.checkpoint.contains(c)
              val next = c.batchSequence == p.checkpoint.fold(0L)(_.batchSequence + 1L)
              val lower = p.checkpoint.map(_.cursor.value).orElse(p.split.lower)
              Option.when(!p.completed && p.blocked.isEmpty && attempt == p.attempts && c.sourceNodeId == s.plan.sourceNodeId &&
                c.cursor.kind == CursorKind && c.cursor.upperBound == p.split.upper &&
                (same || (next && BigDecimal(c.cursor.value) <= BigDecimal(p.split.upper) &&
                  lower.forall(l => BigDecimal(c.cursor.value) > BigDecimal(l)))))(p.copy(checkpoint = Some(c)))
            }.map { next =>
              if (s.splits.find(_.split.id == c.partitionId).flatMap(_.checkpoint).contains(c)) next
              else next.copy(rowsCommitted = s.rowsCommitted + c.targetRowsWritten)
            }
          case CompleteSnapshotSplit(id, attempt) => change(id)(p =>
            Option.when(p.blocked.isEmpty && p.attempts == attempt)(p.copy(completed = true)))
          case RetrySnapshotSplit(id, attempt) => change(id)(p =>
            Option.when(!p.completed && p.blocked.isEmpty && attempt == p.attempts + 1 && attempt <= 3)(p.copy(attempts = attempt, started = true)))
          case BlockSnapshotSplit(id, reason) => change(id)(p => Option.when(!p.completed && reason.length <= 128)(p.copy(blocked = Some(reason))))
          case RetireSnapshotWindow if s.splits.nonEmpty && s.splits.forall(_.completed) =>
            Right(s.copy(splits = Vector.empty, nextIndex = s.nextIndex + s.splits.size, nextLower = Some(s.splits.last.split.upper)))
          case FinishSnapshotEnumeration if s.splits.isEmpty => Right(s.copy(enumerated = true))
          case _ => invalid
        }
      }
    }
  }
}
