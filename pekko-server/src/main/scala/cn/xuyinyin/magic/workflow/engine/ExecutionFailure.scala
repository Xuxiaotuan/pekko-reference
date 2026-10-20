package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.common.CborSerializable
import java.sql.SQLException

/** Safe control data, never a Throwable, SQL text, credential or row payload. */
final case class ExecutionFailure(code: String, stage: String, batchId: Option[String] = None,
  releaseConfirmed: Boolean = true) extends CborSerializable

object ExecutionFailure {
  val CommitUnknown = "commit_outcome_unknown"
  val ProgressUnknown = "progress_outcome_unknown"
  val Transient = "transient_infrastructure"
  val ProtocolConflict = "protocol_conflict"
  val Unclassified = "unclassified_failure"
  val ReleaseUnknown = "local_release_unconfirmed"
  val BudgetExhausted = "automatic_recovery_exhausted"
  val Interrupted = "execution_interrupted"
  val Retryable: Set[String] = Set(CommitUnknown, ProgressUnknown, Transient, Interrupted)

  def from(error: Throwable, stage: String): ExecutionFailure = {
    val chain = Iterator.iterate(Option(error))(_.flatMap(e => Option(e.getCause)))
      .take(32).takeWhile(_.nonEmpty).flatten.toVector
    chain.collectFirst { case e: CdcExecutionException => e.failure }.getOrElse {
      val transient = chain.collect { case s: SQLException => s }.exists { s =>
        Option(s.getSQLState).exists(v => v.startsWith("08") || v == "40001") || Set(1205,1213).contains(s.getErrorCode)
      }
      ExecutionFailure(if (transient) Transient else Unclassified, stage)
    }
  }
}

final class CdcExecutionException(val failure: ExecutionFailure, cause: Throwable)
  extends IllegalStateException(Option(cause).flatMap(e => Option(e.getMessage)).getOrElse(failure.code), cause)
