package cn.xuyinyin.magic.workflow.integration

/** Same real source-marker cuts as the historical bootstrap matrix, different and
  * explicitly named acceptance: source COMMIT uncertainty pauses, never convergence.
  * BootstrapMatrixSpec keeps the historical assertions under the explicit
  * BOOTSTRAP_LEGACY_MARKER_EXPECTATIONS=1 reproduction flag; its normal mode
  * uses this same safety contract for all source-marker uncertainty branches.
  */
class BootstrapMarkerUnknownContractSpec extends BootstrapMatrixSpec {
  override protected def markerUnknownContract:Boolean=true
}
