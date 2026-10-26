package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.workflow.bootstrap.BootstrapProgress
import scala.concurrent.Future
import spray.json._

final class CdcPlatformException(val code:String) extends IllegalStateException(code)
final case class CdcTaskRequest(requestId:String,captureId:String,table:String,generation:String,targetTable:String) {
  def validate():Unit={
    def valid(value:String,pattern:String)=value!=null && value.matches(pattern)
    if(!valid(requestId,"[A-Za-z0-9_-]{1,128}") || !valid(captureId,"[A-Za-z0-9_-]{1,64}") ||
      !valid(generation,"[A-Za-z0-9_-]{1,128}") || !valid(table,"[A-Za-z_][A-Za-z0-9_]{0,127}") ||
      !valid(targetTable,"[A-Za-z_][A-Za-z0-9_]{0,127}"))throw new CdcPlatformException("INVALID_DEFINITION")
  }
  def canonical:String=MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"requestId"->JsString(requestId),
    "captureId"->JsString(captureId),"table"->JsString(table),"generation"->JsString(generation),"targetTable"->JsString(targetTable)))
  def fingerprint:String=MySQLCdcGroup.hash(canonical)
  def taskId:String=MySQLCdcGroup.hash(captureId+"|"+requestId)
}
final case class CdcTaskView(taskId:String,requestId:String,captureId:String,table:String,generation:String,targetTable:String,
  definitionFingerprint:String,desiredState:String,controlRevision:Long,phase:String,journalRevision:Long,
  executionId:Option[String],captureReadiness:String,initializationEvidence:Option[String],runtimeHealth:String,
  releaseStatus:String,failureCode:Option[String],observedAt:Long,lastVerifiedAt:Option[Long],cancelAllowed:Boolean,cancellationEvidence:Option[String]=None)
final case class CdcTaskAcceptance(task:CdcTaskView,created:Boolean)
final case class CdcTaskPage(items:Vector[CdcTaskView],nextAfter:Option[String])
final case class CdcPlatformStatus(enabled:Boolean,ownerState:String,reason:Option[String],activeSteps:Int,maxActiveSteps:Int)
final case class CdcCaptureView(captureId:String,database:String,readiness:String,runtimeHealth:String,reason:Option[String])
trait CdcTaskService {
  def status:CdcPlatformStatus
  def captures():Future[Vector[CdcCaptureView]]
  def submit(request:CdcTaskRequest):Future[CdcTaskAcceptance]
  def get(taskId:String):Future[Option[CdcTaskView]]
  def listAfter(after:Option[String],limit:Int):Future[CdcTaskPage]
  def cancel(taskId:String):Future[CdcTaskView]
}
final case class CdcPlatformLimits(snapshotSteps:Int=2,tailSteps:Int=2,candidatePage:Int=32,maxPendingOperations:Int=64,
  tailDelayMillis:Long=500L,snapshotDelayMillis:Long=10L) {
  require(snapshotSteps>0 && tailSteps>0 && snapshotSteps+tailSteps<=32 && candidatePage>0 && candidatePage<=128 &&
    maxPendingOperations>0 && tailDelayMillis>0 && snapshotDelayMillis>=0)
}
private[cdc] final case class CdcTaskRecord(view:CdcTaskView,definition:CdcTaskRequest,grantId:Option[String],
  grantOwner:Option[String],dueAt:Long,continuation:Option[BootstrapProgress],workPool:String,schedulable:Boolean,wakeSequence:Long)
private[cdc] final case class CdcTaskGrant(id:String,owner:String,pool:String,task:CdcTaskRecord)
private[cdc] object CdcProgressJson extends DefaultJsonProtocol {
  implicit val progressFormat:RootJsonFormat[BootstrapProgress]=jsonFormat19(BootstrapProgress.apply)
  def encode(progress:BootstrapProgress):String=progress.toJson.compactPrint
  def decode(value:String):BootstrapProgress=value.parseJson.convertTo[BootstrapProgress]
}

final case class CdcDispatchResult(frames:Int,caughtUp:Boolean)
