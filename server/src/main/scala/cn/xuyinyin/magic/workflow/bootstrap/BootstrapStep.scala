package cn.xuyinyin.magic.workflow.bootstrap

/** Runtime/control-index evidence; deliberately not a new Bootstrap Journal payload. */
final case class BootstrapStepResult(progress:BootstrapProgress,didWork:Boolean,releaseConfirmed:Boolean,
  nextDelayMillis:Long,validationComplete:Boolean=true) {
  require(nextDelayMillis>=0)
  def phase:String=progress.phase
  def terminal:Boolean=phase=="PAUSED" || phase=="CANCELED"
  def paused:Boolean=phase=="PAUSED"
}

object BootstrapStepContinuation {
  /** A clean scheduling boundary proves resource release, not that a former RR
    * view survived. A replacement Runner may reuse only sealed window contents.
    */
  def needsReadViewRecovery(progress:BootstrapProgress,windowSealed:Boolean):Boolean={
    val lostView=progress.window.nonEmpty && !windowSealed
    if(lostView)require(progress.manifest.isEmpty && progress.appliedPage==0,
      "accepted bootstrap seal missing; source reread forbidden")
    lostView
  }

  /** A control-index clean outcome must also be bound to the same runtime owner by its caller.
    * A late Journal write is not a clean scheduling boundary. Cancel is the only accepted
    * concurrent control transition; it preserves the entire data-plane progress.
    */
  def matches(clean:BootstrapProgress,current:BootstrapProgress):Boolean=
    clean==current || (!clean.cancelRequested && current.cancelRequested && current.phase=="CANCELING" &&
      current.revision>clean.revision && current==clean.copy(revision=current.revision,phase="CANCELING",cancelRequested=true))
}
