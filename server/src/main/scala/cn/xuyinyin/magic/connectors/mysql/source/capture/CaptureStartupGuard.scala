package cn.xuyinyin.magic.connectors.mysql.source.capture

import java.lang.management.ManagementFactory
import javax.management.ObjectName
import spray.json._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Runtime-only observations. Existing persistence payloads and SDK offsets are unchanged. */
final case class CaptureCheckpoint(status: String, value: Option[String], reason: Option[String] = None)
object CaptureCheckpoint {
  val missing: CaptureCheckpoint = CaptureCheckpoint("MISSING", None)
  def decode(captureId: String, rows: Vector[(String,String)]): CaptureCheckpoint = {
    if(rows.isEmpty) return missing
    try {
      require(rows.size==1,"checkpoint partition is not unique")
      val (key,value)=rows.head
      require(key.parseJson==JsArray(JsString(captureId),JsObject("server"->JsString(captureId))),"checkpoint partition mismatch")
      val f=value.parseJson.asJsObject.fields
      require(f.get("file").exists {case JsString(s)=>s.matches(".+\\.[0-9]+");case _=>false},"checkpoint file missing")
      require(f.get("pos").exists {case JsNumber(n)=>n.isValidLong && n>=4;case _=>false},"checkpoint position invalid")
      Vector("row","event").foreach(k=>require(f.get(k).forall {case JsNumber(n)=>n.isValidLong && n>=0;case _=>false},"checkpoint skip invalid"))
      val status=f.get("snapshot") match {
        case Some(JsString("INITIAL"))=>"INITIAL"
        case None | Some(JsBoolean(false)) | Some(JsString("false"))=>"COMPLETE"
        case _=>throw new IllegalArgumentException("unknown checkpoint snapshot state")
      }
      CaptureCheckpoint(status,Some(value))
    }catch {case NonFatal(_)=>CaptureCheckpoint("INVALID",None,Some("CHECKPOINT_INVALID"))}
  }
}

final case class CaptureRecoveryFacts(existingDomain:Boolean,hasEvidence:Boolean,hasBoundaries:Boolean,
  hasUnboundMarkers:Boolean,controlPresent:Boolean,historyPresent:Boolean,
  checkpoint:CaptureCheckpoint,coverageVerified:Boolean)

final class CaptureAdmissionException(val code:String,cause:Throwable=null)
  extends IllegalStateException(code,cause)

object CaptureStartupGuard {
  def classify(f:CaptureRecoveryFacts):Option[String]={
    if(f.existingDomain && !f.controlPresent)Some("CONTROL_TABLE_MISSING")
    else if(f.checkpoint.status=="INVALID")Some("CHECKPOINT_INVALID")
    else if(f.hasBoundaries && f.checkpoint.status=="INITIAL")Some("INITIAL_WITH_COMMITMENT")
    else if(f.hasUnboundMarkers)Some("UNBOUND_MARKER_UNPROVEN")
    else if((f.hasEvidence || f.hasBoundaries) && f.checkpoint.status=="MISSING")Some("CHECKPOINT_MISSING")
    else if((f.hasEvidence || f.hasBoundaries || f.checkpoint.status=="COMPLETE") && !f.historyPresent)Some("SCHEMA_HISTORY_MISSING")
    else if(f.checkpoint.status!="MISSING" && !f.coverageVerified)Some("CHECKPOINT_UNCOVERED")
    else None
  }
  def ready(snapshot:String,loaded:String,current:CaptureCheckpoint,floor:Long,durableAck:Long,confirmedAck:Long):Boolean=
    (snapshot=="COMPLETED" || (snapshot=="SKIPPED" && loaded=="COMPLETE")) && current.status=="COMPLETE" &&
      durableAck>floor && durableAck<=confirmedAck
}

final case class CaptureStartupEvidence(runtimeAttemptId:String,phase:String,reasonCode:Option[String],
  loadedCheckpoint:String,snapshot:String,currentCheckpoint:String,ackFloor:Long,durableAck:Long,confirmedAck:Long)

final class CaptureStartupState(attempt:String) {
  private var current=CaptureStartupEvidence(attempt,"NEW",None,"MISSING","UNKNOWN","MISSING",0,0,0)
  def evidence:CaptureStartupEvidence=synchronized(current)
  def starting(loaded:String,floor:Long):Unit=synchronized {
    if(current.phase=="NEW")current=current.copy(phase="STARTING",loadedCheckpoint=loaded,ackFloor=floor)
  }
  def pause(reason:String):Unit=synchronized {
    if(current.phase!="STOPPING")current=current.copy(phase="PAUSED_UNPROVEN",reasonCode=Some(reason))
  }
  def fail():Unit=synchronized {
    if(current.phase!="STOPPING" && current.phase!="PAUSED_UNPROVEN")current=current.copy(phase="FAILED",reasonCode=Some("CAPTURE_FAILED"))
  }
  def stop():Unit=synchronized {current=current.copy(phase="STOPPING",reasonCode=Some("CAPTURE_STOPPING"))}
  def observed(snapshot:String,checkpoint:CaptureCheckpoint,durable:Long,confirmed:Long):Unit=synchronized {
    if(Set("STARTING","ADMISSION_READY").contains(current.phase)) {
      val allowed=CaptureStartupGuard.ready(snapshot,current.loadedCheckpoint,checkpoint,current.ackFloor,durable,confirmed)
      current=current.copy(phase=if(allowed)"ADMISSION_READY" else "STARTING",reasonCode=if(allowed)None else Some("CAPTURE_STARTING"),
        snapshot=snapshot,currentCheckpoint=checkpoint.status,durableAck=durable,confirmedAck=confirmed)
    }
  }
}

/** Debezium 3.6.1 metrics, scoped to a namespace absent before this single Engine attempt. */
trait CaptureSnapshotMonitor {
  def beforeStart():Unit
  def status:String
}
object CaptureSnapshotMonitor {
  def platform(captureId:String):CaptureSnapshotMonitor=new CaptureSnapshotMonitor {
    private val server=ManagementFactory.getPlatformMBeanServer
    private def names=server.queryNames(new ObjectName("debezium.mysql:*"),null).asScala.toVector
      .filter(n=>Option(n.getKeyProperty("server")).contains(captureId))
    def beforeStart():Unit=require(names.isEmpty,"stale capture metrics namespace")
    def status:String=try {
      val snapshot=names.filter(n=>Option(n.getKeyProperty("context")).contains("snapshot"))
      if(snapshot.size!=1)"UNKNOWN" else {
        def flag(name:String)=server.getAttribute(snapshot.head,name)==java.lang.Boolean.TRUE
        if(flag("SnapshotAborted"))"ABORTED"
        else if(flag("SnapshotRunning"))"RUNNING"
        else if(flag("SnapshotCompleted"))"COMPLETED"
        else if(flag("SnapshotSkipped"))"SKIPPED"
        else "UNKNOWN"
      }
    }catch {case NonFatal(_)=>"UNKNOWN"}
  }
}
