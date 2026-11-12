package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.cdc.bootstrap.BootstrapJdbc
import java.sql.Connection
import spray.json._

/** Read-only capability for a controller-certified namespace; no Actor or Runtime inventory here. */
private[magic] object CaptureGcNamespace {
  final class Permit private[CaptureGcNamespace](val capture:String,val body:String,val phase:String) {
    private val f=body.parseJson.asJsObject.fields
    def matches(uuid:String,db:String,account:String,definer:String):Boolean=
      f("uuid")==JsString(uuid) && f("database")==JsString(db) && f("writer")==JsString(account) && f("definer")==JsString(definer)
    def verifyOn(c:Connection):Unit={
      val j=new BootstrapJdbc(()=>throw new IllegalStateException("borrowed connection only"))
      val r=j.query(c,"SELECT config_json,config_digest,phase FROM pekko_capture_gc_namespace_v1 WHERE capture_id=? FOR SHARE",capture).head
      require(r("config_json")==body && r("config_digest")==MySQLCdcGroup.hash(body) && r("phase")==phase,"GC namespace permit changed")
      val state=j.query(c,"SELECT discovery_status,barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=? FOR SHARE",capture).head
      require(if(phase=="COMPLETE")state("discovery_status")=="COMPLETE" && state("barrier_status")=="OPEN"
        else phase=="SCANNING" && state("barrier_status")=="GC_ENABLING","GC namespace admission changed")
    }
  }
  def load(c:Connection,capture:String,body:String):Permit={
    val j=new BootstrapJdbc(()=>throw new IllegalStateException("borrowed connection only"))
    val r=j.query(c,"SELECT config_json,config_digest,phase FROM pekko_capture_gc_namespace_v1 WHERE capture_id=?",capture).head
    require(r("phase")=="COMPLETE" && r("config_json")==body && r("config_digest")==MySQLCdcGroup.hash(body),"GC namespace configuration changed or incomplete")
    new Permit(capture,body,"COMPLETE")
  }
}
