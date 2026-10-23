package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.scheduling._
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import spray.json._
import CdcJournalWindowSupport._

/** Laboratory metadata only. No new production schema, serializers or recovery transitions. */
private[integration] final class GuardianProbeStore(schema:String,role:String) extends AutoCloseable {
  val settings=Settings.environment()
  val hc=new HikariConfig();hc.setJdbcUrl(settings.url(schema)+s"&connectionAttributes=pekko_owner:$role,pekko_role:control&connectTimeout=3000&socketTimeout=10000")
  hc.setUsername(settings.user);hc.setPassword(settings.password);hc.setMaximumPoolSize(3);hc.setMinimumIdle(0)
  val ds=new HikariDataSource(hc)
  val store=new TableSchedulingStore(ds,SchedulingLimits(2,1,4,1,24576,16),ownershipEnabled=true)
  val ha=store.ownership.get
  private def update(c:java.sql.Connection,sql:String,args:String*):Unit={
    val q=c.prepareStatement(sql);try {args.zipWithIndex.foreach{case(v,i)=>q.setString(i+1,v)};q.executeUpdate();()}finally q.close()
  }
  def epoch(controller:String):Long={
    val c=ds.getConnection
    try {c.setAutoCommit(false);val rows=query(c,"SELECT epoch FROM guardian_probe_controller WHERE id=1 FOR UPDATE")
      val next=rows.elements.head.asJsObject.fields("epoch").asInstanceOf[JsString].value.toLong+1
      update(c,"UPDATE guardian_probe_controller SET epoch=?,controller=? WHERE id=1",next.toString,controller);c.commit();next
    }catch{case e:Exception=>c.rollback();throw e}finally c.close()
  }
  def prepare(boot:String,controllerEpoch:Long):Unit={
    val c=ds.getConnection
    try {c.setAutoCommit(false);val control=query(c,"SELECT epoch,guardian FROM guardian_probe_controller WHERE id=1 FOR UPDATE").elements.head.asJsObject
      require(control.fields("epoch")==JsString(controllerEpoch.toString),"stale laboratory controller epoch")
      require(query(c,"SELECT boot FROM guardian_probe_launches WHERE phase<>'retired'").elements.isEmpty,"laboratory worker quota remains charged")
      update(c,"INSERT INTO guardian_probe_launches (boot,epoch,guardian,phase) VALUES (?,?,?,'prepared')",boot,controllerEpoch.toString,control.fields("guardian").asInstanceOf[JsString].value);c.commit()
    }catch{case e:Exception=>c.rollback();throw e}finally c.close()
  }
  def phase(boot:String,value:String):Unit={val c=ds.getConnection;try update(c,"UPDATE guardian_probe_launches SET phase=? WHERE boot=?",value,boot)finally c.close()}
  def launches:JsArray={val c=ds.getConnection;try query(c,"SELECT * FROM guardian_probe_launches ORDER BY boot")finally c.close()}
  def capture:JsObject={
    val c=ds.getConnection
    try JsObject("data"->OwnerIsolationProbeSupport.capture(settings,schema),"owners"->query(c,"SELECT * FROM pekko_snapshot_owners ORDER BY owner_id"),
      "permissions"->query(c,"SELECT * FROM pekko_snapshot_writer_permissions ORDER BY workflow_id"),
      "audit"->query(c,"SELECT * FROM pekko_snapshot_ownership_audit ORDER BY event_sequence"),
      "launches"->query(c,"SELECT * FROM guardian_probe_launches ORDER BY boot"),"controller"->query(c,"SELECT * FROM guardian_probe_controller"))finally c.close()
  }
  def close():Unit=ds.close()
}
