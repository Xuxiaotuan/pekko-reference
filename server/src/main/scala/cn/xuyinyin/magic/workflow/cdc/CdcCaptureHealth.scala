package cn.xuyinyin.magic.workflow.cdc

private[cdc] object CdcCaptureHealth {
  val MaxObservationAgeNanos:Long=java.util.concurrent.TimeUnit.SECONDS.toNanos(5L)
  def current(value:CdcCaptureView,observedNanos:Option[Long],nowNanos:Long,
    maxAgeNanos:Long=MaxObservationAgeNanos):CdcCaptureView={
    val fresh=observedNanos.exists(t=>nowNanos>=t && nowNanos-t<=maxAgeNanos)
    if(value.runtimeHealth=="HEALTHY" && !fresh)value.copy(runtimeHealth="STALE",reason=Some("CAPTURE_OBSERVATION_STALE"))else value
  }
}
