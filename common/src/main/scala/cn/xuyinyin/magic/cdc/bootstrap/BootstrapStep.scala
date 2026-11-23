package cn.xuyinyin.magic.cdc.bootstrap

import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureFence, CaptureSubscription}

/** Validate the exact F boundary before using it for replay, audit or activation. */
private[bootstrap] object BootstrapActivationFence {
  def verify(
      id: String,
      table: String,
      generation: String,
      schema: String,
      context: String,
      subscription: CaptureSubscription,
      fence: CaptureFence): Unit = {
    require(
      subscription.requestId == id && subscription.table == table && subscription.generation == generation &&
        subscription.schemaId == schema,
      "bootstrap subscription identity changed")
    require(
      fence.id == id + "_F" && fence.subscriptionId == id && fence.action == "F" &&
        fence.context == context && fence.covered && !fence.obsolete,
      "bootstrap activation fence identity changed")
    require(
      subscription.b.nonEmpty && subscription.bSequence.nonEmpty && fence.point.nonEmpty && fence.sequence.nonEmpty &&
        fence.sequence.get > subscription.bSequence.get && fence.point.get.after(subscription.b.get),
      "bootstrap activation fence must follow subscription B")
  }
}

/** Runtime/control-index evidence; deliberately not a new Bootstrap Journal payload. */
final case class BootstrapStepResult(
    progress: BootstrapProgress,
    didWork: Boolean,
    releaseConfirmed: Boolean,
    nextDelayMillis: Long,
    validationComplete: Boolean = true) {
  require(nextDelayMillis >= 0)
  def phase: String     = progress.phase
  def terminal: Boolean = phase == "PAUSED" || phase == "CANCELED"
  def paused: Boolean   = phase == "PAUSED"
}

object BootstrapStepContinuation {

  /**
   * A new Runner without a same-owner clean continuation has no proof that
   * any former pre-F Chunk still belongs to a valid read view. Completed
   * windows are intentionally included in this decision.
   */
  def requiresBaselineReset(progress: BootstrapProgress, hasContinuation: Boolean): Boolean =
    !hasContinuation && progress.phase != "STREAMING" && progress.phase != "CANCELED" &&
      progress.phase != "PAUSED" && (progress.planned || progress.window.nonEmpty || progress.index > 0)

  def windowId(bootstrapId: String, journalRevision: Long, index: Long, attempt: Int): String =
    bootstrapId + "_" + journalRevision + "_" + index + "_" + attempt

  /**
   * A clean scheduling boundary proves resource release, not that a former RR
   * view survived. A replacement Runner may reuse only sealed window contents.
   */
  def needsReadViewRecovery(progress: BootstrapProgress, windowSealed: Boolean): Boolean = {
    val lostView = progress.window.nonEmpty && !windowSealed
    if (lostView) require(progress.manifest.isEmpty && progress.appliedPage == 0, "accepted bootstrap seal missing; source reread forbidden")
    lostView
  }

  /**
   * A control-index clean outcome must also be bound to the same runtime owner by its caller.
   * A late Journal write is not a clean scheduling boundary. Cancel is the only accepted
   * concurrent control transition; it preserves the entire data-plane progress.
   */
  def matches(clean: BootstrapProgress, current: BootstrapProgress): Boolean = {
    val streamingProgress =
      clean.phase == "STREAMING" && current.phase == "STREAMING" &&
        current.revision > clean.revision &&
        (current.deltaAfter > clean.deltaAfter || current.batchSequence > clean.batchSequence) &&
        current.deltaAfter >= clean.deltaAfter && current.batchSequence >= clean.batchSequence &&
        current.copy(revision = clean.revision, deltaAfter = clean.deltaAfter, batchSequence = clean.batchSequence) == clean
    clean == current || streamingProgress ||
      (!clean.cancelRequested && current.cancelRequested && current.phase == "CANCELING" &&
        current.revision > clean.revision && current == clean.copy(revision = current.revision, phase = "CANCELING", cancelRequested = true))
  }
}
