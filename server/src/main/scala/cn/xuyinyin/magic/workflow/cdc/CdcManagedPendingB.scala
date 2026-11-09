package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcSourceNode
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureBDescriptor,CaptureCheckpoint,CaptureControlFence,CaptureFenceBinding,MySQLCaptureStore}
import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.workflow.scheduling.SnapshotOwnerJdbc
import java.sql.Connection
import spray.json._

/** Candidate only: source/SDK checks cannot substitute for old-owner isolation. */
private[cdc] object CdcManagedPendingB {
  final class Verified private[CdcManagedPendingB] (val captureId:String,val taskId:String,val offset:String,
    val markerPresent:Boolean,val table:String,val generation:String,val sourceUuid:String,val sourceDatabase:String,
    val sourceFence:Option[CaptureFenceBinding])
  def verify(cfg:CdcCaptureSettings,taskId:String):Verified=verifyInternal(cfg,taskId,None,None,scanControls=true,bounded=false)
  /** Identity for an intent only: not a complete source-control proof. */
  def candidateForIntent(cfg:CdcCaptureSettings,taskId:String):Verified=verifyInternal(cfg,taskId,None,None,scanControls=false,bounded=true)
  /** For a single bounded page; the callback cannot outlive the source lock. */
  def withFencedCandidate(cfg:CdcCaptureSettings,taskId:String,binding:CaptureFenceBinding,isolation:CdcManagedIsolation.Verified)
    (use:(Verified,Connection,CaptureBDescriptor)=>Unit):Verified={
    require(binding!=null && use!=null,"source fence and action required")
    requireSourceIsolation(cfg,isolation)
    verifyInternal(cfg,taskId,Some(binding),Some(use),scanControls=false,bounded=true)
  }
  def requireSourceIsolation(cfg:CdcCaptureSettings,isolation:CdcManagedIsolation.Verified):Unit={
    require(cfg.sourceFenceV2 && isolation!=null,"source fence and guardian proof required")
    val endpoint=cfg.source.host.toLowerCase(java.util.Locale.ROOT)+":"+cfg.source.port
    val users=Set(cfg.source.username,cfg.sourceObserverUsername.getOrElse(throw new IllegalStateException("source observer missing")),
      cfg.sourceControllerUsername.getOrElse(throw new IllegalStateException("source controller missing")))
    require(users.size==3,"source writer, observer and controller must be distinct")
    val accounts=isolation.manifest.parseJson.asJsObject.fields("accounts").asInstanceOf[JsArray].elements
    users.foreach {user=>require(accounts.exists {entry=>val f=entry.asJsObject.fields
      f("endpoint")==JsString(endpoint) && f("serverUuid")==JsString(cfg.sourceLineage) && f("user")==JsString(user)
    },"guardian drain does not cover source account")}
  }
  private def verifyInternal(cfg:CdcCaptureSettings,taskId:String,fence:Option[CaptureFenceBinding],use:Option[(Verified,Connection,CaptureBDescriptor)=>Unit],scanControls:Boolean,bounded:Boolean):Verified={
    def state():Connection=SnapshotOwnerJdbc.open(CdcJdbc.withTimeouts(cfg.sdkState.jdbcUrl),cfg.sdkState.username,cfg.sdkState.password,"control",managed=true)
    val stateCheck=state()
    try {
      require(SnapshotOwnerJdbc.databaseIdentity(stateCheck)._1==cfg.stateLineage,"state lineage changed")
      val expectedDatabase=Option(new java.net.URI(cfg.sdkState.jdbcUrl.stripPrefix("jdbc:")).getPath).getOrElse("").stripPrefix("/")
      val q=stateCheck.prepareStatement("SELECT lineage,DATABASE() FROM pekko_capture_readers WHERE capture_id=?")
      try {q.setString(1,cfg.id);val r=q.executeQuery()
        try require(r.next() && r.getString(1)==cfg.sourceLineage && r.getString(2)==expectedDatabase && !r.next(),"capture state identity changed")
        finally r.close()
      } finally q.close()
    } finally stateCheck.close()
    def checkpoint():CaptureCheckpoint={
      val c=state()
      try {
        val table=MySQLCdcSourceNode.connectorStateTables(cfg.sdkState,cfg.id)._1
        val q=c.createStatement();q.setQueryTimeout(5)
        try {val r=q.executeQuery(s"SELECT offset_key,offset_val FROM `$table` WHERE offset_val IS NOT NULL LIMIT 2")
          val rows=Vector.newBuilder[(String,String)];try while(r.next())rows+=((r.getString(1),r.getString(2)))finally r.close()
          CaptureCheckpoint.decode(cfg.id,rows.result())
        }finally q.close()
      } finally c.close()
    }
    val initial=checkpoint()
    require(initial.status=="COMPLETE" && initial.value.nonEmpty,"completed D unavailable")
    val log=new MySQLCaptureStore(()=>state(),cfg.id,cfg.sourceLineage,cfg.maxLogBytes)
    val sub=log.subscription(taskId).getOrElse(throw new IllegalStateException("pending B subscription missing"))
    fence.foreach {f=>require(f.sourceUuid==cfg.sourceLineage && f.database==cfg.source.database &&
      f.operation==MySQLCdcGroup.hash(cfg.id+"|B|"+taskId) && f.action=="SUBSCRIBE" &&
      f.table==sub.table && f.generation==sub.generation,"source fence does not match pending B")}
    val descriptor=if(bounded)log.pendingBDescriptor(initial.value.get,taskId)else None
    val proof=if(bounded)None else if(sub.status=="CAPTURE_READY")log.recoverableBoundAt(initial.value.get,taskId)
      else if(sub.status=="B_PENDING")log.recoverablePendingProofAt(initial.value.get,taskId)else None
    require(if(bounded)descriptor.nonEmpty else proof.nonEmpty,"B does not match the durable D/ACK evidence")
    val url=s"jdbc:mysql://${cfg.source.host}:${cfg.source.port}/${cfg.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000"
    val source=SnapshotOwnerJdbc.open(url,if(fence.isDefined)cfg.sourceObserverUsername.get else cfg.source.username,
      if(fence.isDefined)cfg.sourceObserverPassword.get else cfg.source.password,"source",managed=true)
    val present=try {
      require(SnapshotOwnerJdbc.databaseIdentity(source)._1==cfg.sourceLineage,"source lineage changed")
      if(fence.isEmpty)source.setReadOnly(true)
      source.setTransactionIsolation(if(fence.isDefined)Connection.TRANSACTION_READ_COMMITTED else Connection.TRANSACTION_REPEATABLE_READ)
      source.setAutoCommit(false)
      fence.foreach {f=>
        val writer=SnapshotOwnerJdbc.open(url,cfg.source.username,cfg.source.password,"source",managed=true)
        try {
          writer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);writer.setAutoCommit(false)
          CaptureControlFence.protect(source,writer,f.sourceUuid,f.database)
          CaptureControlFence.lock(source,f)
        } finally writer.close()
      }
      val q=source.prepareStatement("SELECT action,table_name,generation FROM pekko_capture_control WHERE operation_id=?");q.setQueryTimeout(5)
      val found=try {
        q.setString(1,log.markerId(taskId,stop=false));val r=q.executeQuery()
        try {if(!r.next())false else {
          require(r.getString(1)=="SUBSCRIBE" && r.getString(2)==sub.table && r.getString(3)==sub.generation && !r.next(),"source B marker conflicts")
          true
        }}finally r.close()
      } finally q.close()
      if(proof.exists(_.mode!="PENDING") || descriptor.exists(_.status!="B_PENDING"))require(found,"bound B source marker missing")
      if(scanControls){
        val deadline=System.nanoTime()+15000000000L
        var cursor="";var total=0;var more=true
        while(more){
          require(System.nanoTime()<deadline,"source control proof deadline exceeded")
          val page=source.prepareStatement("SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id>? ORDER BY operation_id LIMIT 128")
          page.setQueryTimeout(5);page.setString(1,cursor)
          val values=try {val r=page.executeQuery();val b=Vector.newBuilder[(String,String,String,String)]
            try while(r.next())b+=((r.getString(1),r.getString(2),r.getString(3),r.getString(4)))finally r.close();b.result()
          }finally page.close()
          total+=values.size;require(total<=4096,"source control proof row budget exceeded")
          require(log.controlsBound(values,taskId,proof.get,deadline),"source control contains an unbound or uncovered marker")
          more=values.size==128;values.lastOption.foreach(v=>cursor=v._1)
        }
      }
      require(checkpoint()==initial,"D changed while checking pending B")
      use.foreach(_(new Verified(cfg.id,taskId,initial.value.get,found,sub.table,sub.generation,cfg.sourceLineage,cfg.source.database,fence),source,descriptor.get))
      source.rollback()
      found
    }finally source.close()
    require(checkpoint()==initial,"D changed while checking pending B")
    new Verified(cfg.id,taskId,initial.value.get,present,sub.table,sub.generation,cfg.sourceLineage,cfg.source.database,fence)
  }
}
