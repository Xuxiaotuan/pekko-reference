package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.{MySQLCdcGroup, MySQLCdcGroupProtocol}
import cn.xuyinyin.magic.cdc.recovery.{CdcExecutionException, ExecutionFailure}
import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapJournal,BootstrapJournalProgressVerifier}
import java.sql.{Connection, PreparedStatement}
import java.util.concurrent.{Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import spray.json._

/** Traverses the actual D ACK's immutable parent chain, with a bounded recovery budget. */
private[capture] object CaptureAckProof {
  final case class Result(anchorInChain:Boolean,barrierReferenced:Boolean,anchorOffsetMatches:Boolean,referenced:Set[Long])
  def inspect(head:Long,anchor:Long,barrier:Long,expectedOffset:String,read:Long=>Map[String,String]):Result={
    val deadline=System.nanoTime()+15000000000L
    var next=Option(head);var count=0;var referenceCount=0;var foundAnchor=false;var foundBarrier=false;var sameOffset=false
    val referenced=scala.collection.mutable.Set.empty[Long]
    while(next.nonEmpty){
      require(count<4096 && System.nanoTime()<deadline,"ACK proof budget exceeded")
      val seq=next.get;val current=read(seq);count+=1
      require(current("kind")=="ACK","ACK parent is not an ACK")
      val payload=current("payload").parseJson.asJsObject.fields
      val offset=MySQLCdcGroup.canonical(payload("offset"))
      require(current("table_name")==MySQLCdcGroup.hash(offset),"ACK offset binding conflict")
      if(seq==head)require(offset==expectedOffset,"actual D ACK offset conflict")
      if(seq==anchor){foundAnchor=true;sameOffset=offset==expectedOffset}
      def reference(v:JsValue):Long={
        require(System.nanoTime()<deadline,"ACK proof budget exceeded")
        val f=v.asJsObject.fields;val n=f("sequence").asInstanceOf[JsNumber].value.toLongExact
        require(n<seq,"capture proof cycle")
        val target=read(n);require(target("digest")==f("digest").asInstanceOf[JsString].value,"capture proof digest conflict")
        n
      }
      payload("references").asInstanceOf[JsArray].elements.foreach {value=>
        referenceCount+=1;require(referenceCount<=65536,"ACK reference proof budget exceeded")
        val n=reference(value);referenced+=n
        if(n==barrier){require(read(n)("kind")=="BARRIER","B reference is not a barrier");foundBarrier=true}
      }
      next=if(payload("parent")==JsNull)None else Some(reference(payload("parent")))
    }
    Result(foundAnchor,foundBarrier,sameOffset,referenced.toSet)
  }
}

/** Stable, bounded wake identities; each slot is one independently observable INSERT. */
object CaptureWake {
  val MaxAttempts=32
  def nextAvailable(exists:Int=>Boolean):Option[Int]=(0 until MaxAttempts).find(n=> !exists(n))
  def table(capture:String):String="wake:"+capture
  def generation(boot:String,slot:Int):String={
    require(boot.matches("[A-Za-z0-9-]{1,120}") && slot>=0 && slot<MaxAttempts)
    boot+"."+slot
  }
  def operation(capture:String,boot:String,slot:Int):String=MySQLCdcGroup.hash(capture+"|wake|"+generation(boot,slot))
  def decode(operation:String,table:String,generation:String):Option[(String,String)]={
    val parts=generation.split("\\.",-1)
    val capture=table.stripPrefix("wake:")
    if(!table.startsWith("wake:") || !capture.matches("[A-Za-z0-9_-]{1,64}") || parts.length!=2 ||
      !parts(0).matches("[A-Za-z0-9-]{1,120}") || !parts(1).matches("0|[1-9][0-9]?") ||
      parts(1).toInt>=MaxAttempts || operation!=operationId(capture,generation)) None
    else Some(capture->parts(0))
  }
  private def operationId(capture:String,generation:String):String=MySQLCdcGroup.hash(capture+"|wake|"+generation)
}

final case class BoundBProof(mode:String,referencedSequences:Set[Long])
/** Locally validated inputs for a subsequent ACK-chain proof; not itself a recovery proof. */
final case class CaptureBDescriptor(status:String,head:Long,anchor:Long,barrier:Option[Long],
  originalOffset:String,intentSeq:Long,intentDigest:String)

/** Full-frame archive checksums are separate from legacy ACK frame digests. */
private[capture] object CaptureArchiveV1 {
  val fields=Vector("capture_id","seq","source_key","kind","table_name","subscription_id","schema_id","payload","digest","point_json","resume_json")
  def leaf(lineage:String,from:Long,to:Long,row:Map[String,String]):String={
    val data=JsObject(fields.map(k=>k->Option(row.getOrElse(k,null)).fold[JsValue](JsNull)(JsString(_))).toMap)
    MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject("format"->JsString("capture-archive-frame-v1"),"lineage"->JsString(lineage),
      "from"->JsNumber(from),"to"->JsNumber(to),"frame"->data)))
  }
  def root(capture:String,lineage:String,from:Long,to:Long,leaves:Vector[String]):String={
    val data=JsObject("format"->JsString("capture-archive-range-v1"),"capture"->JsString(capture),
      "lineage"->JsString(lineage),"from"->JsNumber(from),"to"->JsNumber(to),"leaves"->JsArray(leaves.map(JsString(_))))
    MySQLCdcGroup.hash(MySQLCdcGroup.canonical(data))
  }
}

/** One source writer; routing changes, immutable records and their evidence share transactions.
  * No automatic truncation or owner takeover. SDK Offset tables remain SDK-owned.
  */
final class MySQLCaptureStore(open:()=>Connection,val captureId:String,val lineage:String,val maxBytes:Long) {
  require(captureId.matches("[A-Za-z0-9_-]{1,64}") && lineage.nonEmpty && maxBytes>0)
  val protocol=MySQLCdcGroupProtocol(captureId,"capture-v1",lineage)
  private val permits=new Semaphore(4)
  private val fenced=new AtomicBoolean(false)
  private val archiveAvailability=new java.util.IdentityHashMap[Connection,java.lang.Boolean]()
  private val gcAvailability=new java.util.IdentityHashMap[Connection,java.lang.Boolean]()
  def releaseConfirmed:Boolean= !fenced.get()
  private def connection[A](f:Connection=>A):A={
    if(fenced.get())throw new CdcExecutionException(
      ExecutionFailure(ExecutionFailure.ReleaseUnknown,"capture-store-close",releaseConfirmed=false),
      new IllegalStateException("capture store resource release unknown"))
    require(permits.tryAcquire(5,TimeUnit.SECONDS),"capture store connection admission timed out")
    var c:Connection=null;var released=false;var primary:Throwable=null
    try {c=open();f(c)} catch {case e:Throwable=>primary=e;throw e} finally {
      try {if(c!=null){archiveAvailability.synchronized(archiveAvailability.remove(c));gcAvailability.synchronized(gcAvailability.remove(c));c.close()};released=true}
      catch {case close:Throwable=>
        val unknown=new CdcExecutionException(
          ExecutionFailure(ExecutionFailure.ReleaseUnknown,"capture-store-close",releaseConfirmed=false),close)
        if(primary!=null)unknown.addSuppressed(primary)
        throw unknown
      } finally {if(released)permits.release() else fenced.set(true)}
    }
  }
  private def stmt[A](c:Connection,sql:String,args:Seq[Any])(f:PreparedStatement=>A):A={
    val q=c.prepareStatement(sql);q.setQueryTimeout(5)
    try {args.zipWithIndex.foreach {case(v,i)=>q.setObject(i+1,v)};f(q)} finally q.close()
  }
  private def update(c:Connection,sql:String,args:Any*):Int=stmt(c,sql,args)(_.executeUpdate())
  private def rows(c:Connection,sql:String,args:Any*):Vector[Map[String,String]]=stmt(c,sql,args) { q=>
    val r=q.executeQuery();try {
      val m=r.getMetaData;val out=Vector.newBuilder[Map[String,String]]
      while(r.next())out+= (1 to m.getColumnCount).map(i=>m.getColumnLabel(i)->r.getString(i)).toMap
      out.result()
    }finally r.close()
  }
  private def tx[A](f:Connection=>A):A=connection {c=>
    c.setAutoCommit(false)
    try {
      val head=rows(c,"SELECT lineage FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",captureId).head
      require(head("lineage")==lineage,"capture source lineage conflict")
      val a=f(c);c.commit();a
    } catch {case e:Throwable=>try c.rollback() catch {case r:Throwable=>e.addSuppressed(r)};throw e}
  }
  def location:(String,String)=connection {c=>
    val r=rows(c,"SELECT @@server_uuid AS uuid,DATABASE() AS db").head
    r("uuid")->r("db")
  }
  def initialize():Unit=connection {c=>
    Vector(
      "CREATE TABLE IF NOT EXISTS pekko_capture_readers(capture_id VARCHAR(64) PRIMARY KEY,lineage VARCHAR(255) NOT NULL,next_seq BIGINT NOT NULL,used_bytes BIGINT NOT NULL,frontier LONGTEXT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_capture_fences(capture_id VARCHAR(64),fence_id VARCHAR(128),operation_id CHAR(64) NOT NULL,subscription_id VARCHAR(128) NOT NULL,action VARCHAR(16) NOT NULL,context_json LONGTEXT NOT NULL,point_json TEXT NULL,seq BIGINT NULL,covered_seq BIGINT NULL,PRIMARY KEY(capture_id,fence_id),UNIQUE KEY operation(capture_id,operation_id))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_schemas(capture_id VARCHAR(64),table_name VARCHAR(128),schema_id CHAR(64) NOT NULL,definition LONGTEXT NOT NULL,PRIMARY KEY(capture_id,table_name))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_subscriptions(capture_id VARCHAR(64),request_id VARCHAR(128),table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,status VARCHAR(32) NOT NULL,schema_id CHAR(64) NOT NULL,b_point TEXT NULL,k_point TEXT NULL,b_seq BIGINT NULL,PRIMARY KEY(capture_id,request_id),INDEX table_route(capture_id,table_name))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_gc_state_v1(capture_id VARCHAR(64) PRIMARY KEY,discovery_status VARCHAR(16) NOT NULL,barrier_status VARCHAR(16) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_capture_consumers_v1(capture_id VARCHAR(64) NOT NULL,consumer_id CHAR(64) NOT NULL,consumer_kind VARCHAR(16) NOT NULL,subscription_id VARCHAR(128) NOT NULL,source_node VARCHAR(128) NOT NULL,journal_floor BIGINT NOT NULL,audit_floor BIGINT NOT NULL,observed_floor BIGINT NOT NULL,journal_revision BIGINT NOT NULL,checkpoint_id VARCHAR(128) NOT NULL,PRIMARY KEY(capture_id,consumer_id))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_log(capture_id VARCHAR(64),seq BIGINT,source_key CHAR(64) NOT NULL,kind VARCHAR(16) NOT NULL,table_name VARCHAR(128) NOT NULL,subscription_id VARCHAR(128) NOT NULL,schema_id CHAR(64) NOT NULL,payload LONGTEXT NOT NULL,digest CHAR(64) NOT NULL,point_json TEXT NULL,resume_json LONGTEXT NULL,PRIMARY KEY(capture_id,seq),UNIQUE KEY occurrence(capture_id,source_key),INDEX deliveries(capture_id,subscription_id,seq))"
    ).foreach(sql=>update(c,sql+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"))
    require(rows(c,"SELECT COUNT(*) AS total FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('pekko_capture_readers','pekko_capture_schemas','pekko_capture_subscriptions','pekko_capture_log','pekko_capture_fences','pekko_capture_gc_state_v1','pekko_capture_consumers_v1') AND engine='InnoDB'").head("total")=="7","capture state requires InnoDB")
    update(c,"INSERT IGNORE INTO pekko_capture_readers VALUES(?,?,1,0,NULL)",captureId,lineage)
    update(c,"INSERT IGNORE INTO pekko_capture_gc_state_v1 VALUES(?,'UNKNOWN','OPEN')",captureId)
    val head=rows(c,"SELECT lineage,next_seq,(SELECT COALESCE(MAX(seq),0) FROM pekko_capture_log WHERE capture_id=?) AS maximum FROM pekko_capture_readers WHERE capture_id=?",captureId,captureId).head
    require(head("lineage")==lineage,"capture lineage changed")
    require(head("next_seq").toLong>head("maximum").toLong,"capture head inconsistent with L")
    archiveAvailability.synchronized(archiveAvailability.remove(c))
  }
  private def appendRaw(c:Connection,key:String,kind:String,table:String,sub:String,schema:String,payload:String,
    point:Option[CapturePoint]=None,resume:String=""):Long={
    val digest=frameDigest(kind,table,sub,schema,payload,point.map(_.json))
    rows(c,s"SELECT seq,digest FROM ${logicalLog(c)} l WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash(key)).headOption match {
      case Some(r)=>
        require(r("digest")==digest,"capture occurrence content conflict")
        if(kind=="GROUP")require(frame(c,r("seq").toLong)("payload")==payload,"capture occurrence payload conflict")
        r("seq").toLong
      case None=>
        val enabled=archiveEnabled(c)
        if(enabled)require(rows(c,"SELECT seq FROM pekko_capture_archive_frames_v1 WHERE capture_id=? AND source_key=? LIMIT 1",captureId,MySQLCdcGroup.hash(key)).isEmpty,
          "archived occurrence missing from retained L; restore required")
        val sql=if(enabled)"SELECT r.next_seq,r.used_bytes,b.used_bytes AS archive_bytes FROM pekko_capture_readers r JOIN pekko_capture_archive_budget_v1 b ON b.capture_id=r.capture_id WHERE r.capture_id=?"
          else "SELECT next_seq,used_bytes FROM pekko_capture_readers WHERE capture_id=?"
        val h=rows(c,sql,captureId).head
        val n=h("next_seq").toLong; val bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong+resume.length+256
        val archiveBytes=if(enabled)h("archive_bytes").toLong else 0L
        require(archiveBytes>=0 && BigInt(h("used_bytes").toLong)+BigInt(archiveBytes)+BigInt(bytes)<=BigInt(maxBytes),"capture log capacity exhausted")
        update(c,"INSERT INTO pekko_capture_log VALUES(?,?,?,?,?,?,?,?,?,?,?)",captureId,n,MySQLCdcGroup.hash(key),kind,table,sub,schema,payload,digest,point.map(_.json).orNull,resume)
        update(c,"UPDATE pekko_capture_readers SET next_seq=?,used_bytes=used_bytes+? WHERE capture_id=?",n+1,bytes,captureId);n
    }
  }
  private def frameDigest(kind:String,table:String,sub:String,schema:String,payload:String,point:Option[String]):String=
    MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsArray(Vector(kind,table,sub,schema,payload,point.getOrElse("")).map(JsString(_)))))
  def rememberProfile(profile:String):Long=tx {c=>
    appendRaw(c,"capture-profile","PROFILE","","","",MySQLCdcGroup.canonical(profile.parseJson))
  }
  /** No archive tables or per-read joins are required for captures that never opted in. */
  private def archiveEnabled(c:Connection):Boolean={
    val cached=archiveAvailability.synchronized(Option(archiveAvailability.get(c)))
    cached match {
      case Some(value)=>value.booleanValue()
      case None=>
        val found=rows(c,"""SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND engine='InnoDB'
          AND table_name IN ('pekko_capture_archive_ranges_v1','pekko_capture_archive_frames_v1','pekko_capture_archive_budget_v1')""").head("n").toInt
        require(found==0 || found==3,"capture archive schema incomplete")
        val enabled=if(found==0)false else {
          val budget=rows(c,"SELECT used_bytes FROM pekko_capture_archive_budget_v1 WHERE capture_id=?",captureId).headOption
          if(budget.isEmpty){
            require(rows(c,"SELECT from_seq FROM pekko_capture_archive_ranges_v1 WHERE capture_id=? LIMIT 1",captureId).isEmpty &&
              rows(c,"SELECT seq FROM pekko_capture_archive_frames_v1 WHERE capture_id=? LIMIT 1",captureId).isEmpty,"archive budget missing behind retained evidence")
            false
          }else {
            val sum=rows(c,"SELECT COALESCE(SUM(stored_bytes),0) AS total FROM pekko_capture_archive_ranges_v1 WHERE capture_id=?",captureId).head("total").toLong
            require(budget.get("used_bytes").toLong>=0 && budget.get("used_bytes").toLong==sum,"capture archive budget conflict")
            true
          }
        }
        // This cache lasts only for the current connection/operation, not for the Store instance.
        archiveAvailability.synchronized(archiveAvailability.put(c,java.lang.Boolean.valueOf(enabled)))
        enabled
    }
  }
  private def createArchiveTables(c:Connection):Unit={
    Vector(
      "CREATE TABLE IF NOT EXISTS pekko_capture_archive_ranges_v1(capture_id VARCHAR(64) NOT NULL,from_seq BIGINT NOT NULL,to_seq BIGINT NOT NULL,lineage VARCHAR(255) NOT NULL,frame_count INT NOT NULL,stored_bytes BIGINT NOT NULL,root CHAR(64) NOT NULL,leaves_json LONGTEXT NOT NULL,PRIMARY KEY(capture_id,from_seq))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_archive_frames_v1(capture_id VARCHAR(64) NOT NULL,seq BIGINT NOT NULL,source_key CHAR(64) NOT NULL,kind VARCHAR(16) NOT NULL,table_name VARCHAR(128) NOT NULL,subscription_id VARCHAR(128) NOT NULL,schema_id CHAR(64) NOT NULL,payload LONGTEXT NOT NULL,digest CHAR(64) NOT NULL,point_json TEXT NULL,resume_json LONGTEXT NULL,from_seq BIGINT NOT NULL,PRIMARY KEY(capture_id,seq),UNIQUE KEY archived_occurrence(capture_id,source_key))",
      "CREATE TABLE IF NOT EXISTS pekko_capture_archive_budget_v1(capture_id VARCHAR(64) PRIMARY KEY,used_bytes BIGINT NOT NULL)"
    ).foreach(sql=>update(c,sql+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"))
    require(rows(c,"""SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND engine='InnoDB'
      AND table_name IN ('pekko_capture_archive_ranges_v1','pekko_capture_archive_frames_v1','pekko_capture_archive_budget_v1')""").head("n")=="3","capture archive requires InnoDB")
    if(rows(c,"SELECT used_bytes FROM pekko_capture_archive_budget_v1 WHERE capture_id=?",captureId).isEmpty){
      require(rows(c,"SELECT from_seq FROM pekko_capture_archive_ranges_v1 WHERE capture_id=? LIMIT 1",captureId).isEmpty &&
        rows(c,"SELECT seq FROM pekko_capture_archive_frames_v1 WHERE capture_id=? LIMIT 1",captureId).isEmpty,"archive budget missing behind retained evidence")
      update(c,"INSERT IGNORE INTO pekko_capture_archive_budget_v1 VALUES(?,0)",captureId)
    }
    archiveAvailability.synchronized(archiveAvailability.remove(c))
    require(archiveEnabled(c),"capture archive not initialized")
  }
  private val archiveCopies=CaptureArchiveV1.fields.map(k=>s",a.$k AS copy_$k").mkString
  private def logicalLog(c:Connection):String={
    val enabled=gcAvailability.synchronized {Option(gcAvailability.get(c)).map(_.booleanValue()).getOrElse {
      val found=rows(c,"SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='pekko_capture_gc_frames_v1'").head("n")=="1"
      gcAvailability.put(c,java.lang.Boolean.valueOf(found));found
    }}
    if(!enabled)"pekko_capture_log" else {
      val fields=CaptureArchiveV1.fields.mkString(",")
      s"(SELECT $fields,NULL AS gc_proof FROM pekko_capture_log UNION ALL SELECT $fields,proof_id AS gc_proof FROM pekko_capture_gc_frames_v1)"
    }
  }
  private def archiveSelect(c:Connection):String=frameSelect.replace("FROM pekko_capture_log l",s"FROM ${logicalLog(c)} l")
  private val frameSelect="""SELECT l.*,m.from_seq AS archive_from_seq,a.from_seq AS archive_frame_from_seq,m.to_seq AS archive_to_seq,m.lineage AS archive_lineage,
    m.frame_count AS archive_count,m.root AS archive_root,m.leaves_json AS archive_leaves
    """+archiveCopies+""" FROM pekko_capture_log l LEFT JOIN pekko_capture_archive_ranges_v1 m ON m.capture_id=l.capture_id
    AND m.from_seq=(SELECT MAX(mx.from_seq) FROM pekko_capture_archive_ranges_v1 mx WHERE mx.capture_id=l.capture_id AND mx.from_seq<=l.seq)
    AND m.to_seq>=l.seq LEFT JOIN pekko_capture_archive_frames_v1 a ON a.capture_id=l.capture_id AND a.seq=l.seq"""
  /** A compacted GROUP retains its L row and is reconstructed only from the same-schema target ledger. */
  private def materializeGroup(c:Connection,r:Map[String,String]):Map[String,String]={
    Option(r.getOrElse("gc_proof",null)).foreach {proof=>
      val saved=rows(c,"SELECT * FROM pekko_capture_gc_checkpoints_v1 WHERE capture_id=? AND proof_id=?",captureId,proof).headOption
        .getOrElse(throw new IllegalArgumentException("GC recovery certificate missing"))
      require(saved("phase")=="ACTIVE" && saved("subscription_id")==r("subscription_id"),"GC recovery certificate inactive or wrong subscription")
      val checkpoint=saved("proof_json").parseJson.asJsObject.fields
      require(checkpoint("capture")==JsString(captureId) && checkpoint("lineage")==JsString(lineage) &&
        MySQLCdcGroup.hash(MySQLCdcGroup.canonical(saved("proof_json").parseJson))==proof,"GC recovery certificate identity changed")
      val journal=rows(c,"SELECT HEX(event_payload) AS payload,deleted FROM event_journal WHERE persistence_id=? AND sequence_number=?",
        saved("journal_id"),saved("journal_sequence").toLong).headOption.getOrElse(throw new IllegalArgumentException("GC recovery Journal missing"))
      require(Set("0","false")(journal("deleted").toLowerCase(java.util.Locale.ROOT)) &&
        MySQLCdcGroup.hash(journal("payload").toLowerCase(java.util.Locale.ROOT))==saved("journal_digest"),"GC recovery Journal changed")
    }
    val payload=Option(r.getOrElse("payload",null)).getOrElse("")
    if(r.get("kind").contains("GROUP") && payload.startsWith(CaptureGroupTombstone.Prefix)){
      val tombstone=payload.stripPrefix(CaptureGroupTombstone.Prefix);val f=tombstone.parseJson.asJsObject.fields
      val targetGroup=f("targetGroup").asInstanceOf[JsString].value
      require(targetGroup.matches("[a-f0-9]{64}"),"invalid tombstone target group")
      val ledger=rows(c,"SELECT evidence FROM pekko_sync_cdc_group_ledger WHERE group_id=?",targetGroup)
      require(ledger.size==1,"compacted GROUP target ledger missing")
      val evidence=ledger.head("evidence");val fields=evidence.parseJson.asJsObject.fields
      val identity=MySQLCdcGroup.canonical(fields("identity"));val table=f("targetTable").asInstanceOf[JsString].value
      r.updated("payload",CaptureGroupTombstone.recover(r,tombstone,protocol,table,identity,evidence))
    }else r
  }
  private def checkedFrame(r:Map[String,String]):Map[String,String]={
    require(frameDigest(r("kind"),r("table_name"),r("subscription_id"),r("schema_id"),r("payload"),Option(r("point_json")))==r("digest"),"capture frame corrupted")
    require(Option(r("archive_from_seq"))==Option(r("archive_frame_from_seq")),"capture archive frame or range missing")
    Option(r("archive_from_seq")).foreach {first=>
      require(Option(r("archive_to_seq")).nonEmpty && r("archive_lineage")==lineage && r("capture_id")==captureId,"capture archive range missing or wrong lineage")
      val from=first.toLong;val to=r("archive_to_seq").toLong;val seq=r("seq").toLong
      val leaves=r("archive_leaves").parseJson.asInstanceOf[JsArray].elements.map(_.asInstanceOf[JsString].value).toVector
      val archived=CaptureArchiveV1.fields.map(k=>k->r("copy_"+k)).toMap
      require(to>=from && to-from<128 && leaves.size==to-from+1 && r("archive_count").toInt==leaves.size &&
        seq>=from && seq<=to && CaptureArchiveV1.root(captureId,lineage,from,to,leaves)==r("archive_root") &&
        leaves((seq-from).toInt)==CaptureArchiveV1.leaf(lineage,from,to,r) &&
        leaves((seq-from).toInt)==CaptureArchiveV1.leaf(lineage,from,to,archived),"capture archive proof conflict")
    }
    r.filter {case(k,_)=>CaptureArchiveV1.fields.contains(k)}
  }
  private def frame(c:Connection,sequence:Long):Map[String,String]={
    val enabled=archiveEnabled(c)
    val sql=if(enabled)archiveSelect(c)+" WHERE l.capture_id=? AND l.seq=?" else s"SELECT * FROM ${logicalLog(c)} l WHERE capture_id=? AND seq=?"
    val r=rows(c,sql,captureId,sequence).headOption.map(materializeGroup(c,_))
      .getOrElse(throw new IllegalArgumentException("capture proof references a missing frame"))
    if(enabled)checkedFrame(r)else {
      require(frameDigest(r("kind"),r("table_name"),r("subscription_id"),r("schema_id"),r("payload"),Option(r("point_json")))==r("digest"),"capture frame corrupted");r
    }
  }
  private[capture] def proofFrame(c:Connection,sequence:Long):Map[String,String]=frame(c,sequence)
  private[capture] def clearProofConnection(c:Connection):Unit={archiveAvailability.synchronized {archiveAvailability.remove(c)};gcAvailability.synchronized {gcAvailability.remove(c)};()}
  private[capture] def proofOffsetKey(offset:String):String=offsetKey(offset)

  /** Same head lock as append/admission/GC: newly admitted work cannot race a deletion plan. */
  private def protectGcAdmission(c:Connection,id:String,kind:String):Unit={
    if(rows(c,"SELECT 1 AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='pekko_capture_gc_pins_v1'").nonEmpty){
      val head=rows(c,"SELECT next_seq FROM pekko_capture_readers WHERE capture_id=?",captureId).head("next_seq").toLong
      if(update(c,"INSERT IGNORE INTO pekko_capture_gc_pins_v1 VALUES(?,?,?,?,0)",captureId,id,kind,head)==1)
        update(c,"UPDATE pekko_capture_gc_retention_v1 SET epoch=epoch+1 WHERE capture_id=?",captureId)
    }
  }
  /** Registration is durable before materialization. UNKNOWN history never enables GC. */
  private[magic] def registerConsumer(consumer:String,kind:String,subscription:String,node:String,resume:Long):Unit=tx {c=>
    require(consumer.matches("[a-f0-9]{64}") && Set("WORKFLOW","BOOTSTRAP")(kind) && subscription.nonEmpty &&
      subscription.length<=128 && node.nonEmpty && node.length<=128 && resume>=0,"invalid capture consumer")
    val barrier=rows(c,"SELECT discovery_status,barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=? FOR UPDATE",captureId).head
    require(Set("UNKNOWN","COMPLETE")(barrier("discovery_status")) && barrier("barrier_status")=="OPEN","capture consumer admission fenced")
    val existing=rows(c,"SELECT * FROM pekko_capture_consumers_v1 WHERE capture_id=? AND consumer_id=? FOR UPDATE",captureId,consumer).headOption
    existing match {
      case None=>
        if(rows(c,"SELECT 1 AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='pekko_capture_gc_retention_v1'").nonEmpty){
          val reclaimed=rows(c,"SELECT reclaimed_before FROM pekko_capture_gc_retention_v1 WHERE capture_id=?",captureId).head("reclaimed_before").toLong
          require(find(c,subscription).flatMap(_.bSequence).forall(_>=reclaimed) || resume>=reclaimed,
            "new consumer precedes protected recovery floor")
        }
        update(c,"INSERT INTO pekko_capture_consumers_v1 VALUES(?,?,?,?,?,0,0,0,0,'')",captureId,consumer,kind,subscription,node)
        protectGcAdmission(c,"consumer:"+consumer,"CONSUMER")
      case Some(r)=>require(r("consumer_kind")==kind && r("subscription_id")==subscription && r("source_node")==node &&
        resume>=r("journal_floor").toLong,"consumer identity or persisted Journal cursor changed")
    }
  }
  /** Invoke only after the corresponding Actor Journal write has acknowledged persistence. */
  private[magic] def projectConsumerJournal(consumer:String,kind:String,subscription:String,node:String,
    floor:Long,revision:Long,checkpointId:String):Unit=tx {c=>
    require(floor>=0 && revision>=0 && checkpointId!=null && checkpointId.length<=128,"invalid consumer Journal projection")
    val barrier=rows(c,"SELECT barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=? FOR UPDATE",captureId).head
    require(barrier("barrier_status")=="OPEN","capture consumer projection fenced")
    val r=rows(c,"SELECT * FROM pekko_capture_consumers_v1 WHERE capture_id=? AND consumer_id=? FOR UPDATE",captureId,consumer).headOption
      .getOrElse(throw new IllegalArgumentException("capture consumer not registered"))
    require(r("consumer_kind")==kind && r("subscription_id")==subscription && r("source_node")==node &&
      floor>=r("observed_floor").toLong && revision>=r("journal_revision").toLong,"consumer Journal observation regressed")
    if(revision==r("journal_revision").toLong && r("checkpoint_id").nonEmpty)
      require(floor==r("observed_floor").toLong && checkpointId==r("checkpoint_id"),"consumer Journal revision conflict")
    if(floor>0 && kind=="WORKFLOW"){
      val f=frame(c,floor);require(f("kind")=="GROUP" && f("subscription_id")==subscription,"consumer checkpoint frame conflict")
    }
    require(floor<rows(c,"SELECT next_seq FROM pekko_capture_readers WHERE capture_id=?",captureId).head("next_seq").toLong || floor==0,
      "consumer checkpoint beyond L")
    update(c,"UPDATE pekko_capture_consumers_v1 SET observed_floor=?,journal_revision=?,checkpoint_id=? WHERE capture_id=? AND consumer_id=?",
      floor,revision,checkpointId,captureId,consumer)
    // Observation is not a Journal proof: retention floors remain zero until verified from persisted events.
  }
  private[magic] def discoveryComplete:Boolean=connection {c=>
    rows(c,"SELECT discovery_status FROM pekko_capture_gc_state_v1 WHERE capture_id=?",captureId).headOption.exists(_("discovery_status")=="COMPLETE")
  }
  /** The event is read and range-locked in this same transaction before moving a retention floor. */
  private[magic] def projectBootstrapVerified(consumer:String,subscription:String,generation:String,definition:String,
    receipt:BootstrapJournal.PersistedProgress,verifier:BootstrapJournalProgressVerifier):Unit=connection {c=>
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false)
    try {
      val head=rows(c,"SELECT lineage,next_seq FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",captureId).head
      require(head("lineage")==lineage && consumer==CaptureConsumerIdentity.bootstrap(captureId,subscription,generation),"bootstrap consumer identity conflict")
      val state=rows(c,"SELECT discovery_status,barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=? FOR UPDATE",captureId).head
      require(state("discovery_status")=="COMPLETE" && state("barrier_status")=="OPEN","verified bootstrap projection is not admitted")
      val r=rows(c,"SELECT * FROM pekko_capture_consumers_v1 WHERE capture_id=? AND consumer_id=? FOR UPDATE",captureId,consumer).headOption
        .getOrElse(throw new IllegalArgumentException("bootstrap consumer missing"))
      require(r("consumer_kind")=="BOOTSTRAP" && r("subscription_id")==subscription && r("source_node")=="bootstrap",
        "bootstrap consumer registration conflict")
      verifier.verifyOn(c,receipt,captureId,subscription,definition)
      val floor=receipt.state.deltaAfter
      require(floor>=r("journal_floor").toLong && floor<=r("observed_floor").toLong && floor<head("next_seq").toLong,
        "verified bootstrap Journal floor conflict")
      update(c,"UPDATE pekko_capture_consumers_v1 SET journal_floor=? WHERE capture_id=? AND consumer_id=?",floor,captureId,consumer)
      c.commit()
    }catch {case e:Throwable=>try c.rollback()catch {case x:Throwable=>e.addSuppressed(x)};throw e}
  }

  /** Explicit offline archive; the owner lock prevents a running Reader racing opt-in. */
  def sealArchiveRange(from:Long,to:Long):String=connection {c=>
    val lockName="pekko_capture_"+captureId
    require(rows(c,"SELECT GET_LOCK(?,0) AS acquired",lockName).headOption.exists(_("acquired")=="1"),"capture archive requires inactive Reader")
    var primary:Throwable=null
    try {
      createArchiveTables(c)
      c.setAutoCommit(false)
      require(rows(c,"SELECT lineage FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",captureId).head("lineage")==lineage,"capture source lineage conflict")
      val result=sealArchiveOn(c,from,to)
      try c.commit()catch {case e:Throwable=>throw new CdcExecutionException(ExecutionFailure(ExecutionFailure.CommitUnknown,"capture-archive-commit"),e)}
      result
    }catch {case e:Throwable=>primary=e;try c.rollback()catch{case r:Throwable=>e.addSuppressed(r)};throw e}
    finally try {require(rows(c,"SELECT RELEASE_LOCK(?) AS released",lockName).headOption.exists(_("released")=="1"),"capture archive owner release unknown")}
      catch {case release:Throwable=>if(primary!=null)primary.addSuppressed(release)else throw release}
  }
  private def sealArchiveOn(c:Connection,from:Long,to:Long):String={
    require(from>0 && to>=from && to-from<128,"invalid capture archive range")
    val overlaps=rows(c,"SELECT from_seq,to_seq FROM pekko_capture_archive_ranges_v1 WHERE capture_id=? AND from_seq<=? AND to_seq>=? LIMIT 2",captureId,to,from)
    if(overlaps.nonEmpty){
      require(overlaps.size==1 && overlaps.head("from_seq").toLong==from && overlaps.head("to_seq").toLong==to,"capture archive range overlaps existing range")
      verifyArchiveOn(c,from,to)
    }else {
      val size=rows(c,"""SELECT COUNT(*) AS n,COALESCE(SUM(OCTET_LENGTH(payload)+COALESCE(OCTET_LENGTH(resume_json),0)+
        COALESCE(OCTET_LENGTH(point_json),0)+1024),0) AS bytes FROM pekko_capture_log WHERE capture_id=? AND seq>=? AND seq<=?""",captureId,from,to).head
      require(size("n").toLong==to-from+1 && size("bytes").toLong<=1048576,"capture archive range missing or exceeds byte budget")
      val head=rows(c,"SELECT used_bytes FROM pekko_capture_readers WHERE capture_id=?",captureId).head("used_bytes").toLong
      val archivedBytes=rows(c,"SELECT used_bytes FROM pekko_capture_archive_budget_v1 WHERE capture_id=? FOR UPDATE",captureId).head("used_bytes").toLong
      require(BigInt(head)+BigInt(archivedBytes)+BigInt(size("bytes"))<=BigInt(maxBytes),"capture archive capacity exhausted")
      val selected=rows(c,frameSelect+" WHERE l.capture_id=? AND l.seq>=? AND l.seq<=? ORDER BY l.seq",captureId,from,to).map(checkedFrame)
      require(selected.size==to-from+1 && selected.zipWithIndex.forall {case(r,i)=>r("seq").toLong==from+i},"capture archive range is not contiguous")
      val leaves=selected.map(r=>CaptureArchiveV1.leaf(lineage,from,to,r))
      val root=CaptureArchiveV1.root(captureId,lineage,from,to,leaves)
      val leafJson=MySQLCdcGroup.canonical(JsArray(leaves.map(JsString(_))))
      update(c,"INSERT INTO pekko_capture_archive_ranges_v1 VALUES(?,?,?,?,?,?,?,?)",captureId,from,to,lineage,selected.size,size("bytes").toLong,root,leafJson)
      selected.foreach {r=>
        update(c,"INSERT INTO pekko_capture_archive_frames_v1 VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
          captureId,r("seq").toLong,r("source_key"),r("kind"),r("table_name"),r("subscription_id"),r("schema_id"),r("payload"),r("digest"),r("point_json"),r("resume_json"),from)
      }
      update(c,"UPDATE pekko_capture_archive_budget_v1 SET used_bytes=used_bytes+? WHERE capture_id=?",size("bytes").toLong,captureId)
      verifyArchiveOn(c,from,to)
    }
  }
  def verifyArchiveRange(from:Long,to:Long):String=tx(c=>verifyArchiveOn(c,from,to))
  private def verifyArchiveOn(c:Connection,from:Long,to:Long):String={
    require(from>0 && to>=from && to-from<128,"invalid capture archive range")
    val m=rows(c,"SELECT * FROM pekko_capture_archive_ranges_v1 WHERE capture_id=? AND from_seq=? AND to_seq=?",captureId,from,to).headOption
      .getOrElse(throw new IllegalArgumentException("capture archive range missing"))
    require(m("lineage")==lineage && m("frame_count").toLong==to-from+1,"capture archive lineage or count conflict")
    val leaves=m("leaves_json").parseJson.asInstanceOf[JsArray].elements.map(_.asInstanceOf[JsString].value).toVector
    require(leaves.size==to-from+1 && CaptureArchiveV1.root(captureId,lineage,from,to,leaves)==m("root"),"capture archive root conflict")
    val archived=rows(c,"SELECT * FROM pekko_capture_archive_frames_v1 WHERE capture_id=? AND from_seq=? ORDER BY seq",captureId,from)
    require(archived.size==leaves.size && archived.zipWithIndex.forall {case(r,i)=>
      r("seq").toLong==from+i && CaptureArchiveV1.leaf(lineage,from,to,r)==leaves(i)
    },"capture archive frame conflict")
    archived.foreach(r=>frame(c,r("seq").toLong))
    m("root")
  }
  def schemaSequence(table:String,id:String):Long=connection {c=>
    rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("schema:"+table+":"+id)).head("seq").toLong}
  def validatePosition(id:String,sequence:Long):Unit=connection {c=>
    val r=frame(c,sequence)
    require(r("kind")=="GROUP" && r("subscription_id")==id,"checkpoint does not reference a retained subscription group")
  }
  def dropSchema(table:String):Long=tx {c=>
    require(rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND status IN ('B_PENDING','CAPTURE_READY')",captureId,table).isEmpty,"active captured table dropped; pause required")
    val n=appendRaw(c,"drop:"+table+":"+rows(c,"SELECT next_seq FROM pekko_capture_readers WHERE capture_id=?",captureId).head("next_seq"),"SCHEMA_DROP",table,"","",table)
    update(c,"DELETE FROM pekko_capture_schemas WHERE capture_id=? AND table_name=?",captureId,table);n
  }
  def barrierSequence(id:String,stop:Boolean):Option[Long]=connection {c=>
    rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash((if(stop)"K:"else "B:")+id)).headOption.map(_("seq").toLong)}
  def rememberSchema(table:String,definition:String):CaptureSchema=tx {c=>
    val canonical=MySQLCdcGroup.canonical(definition.parseJson);val id=MySQLCdcGroup.hash(canonical)
    val old=rows(c,"SELECT schema_id FROM pekko_capture_schemas WHERE capture_id=? AND table_name=?",captureId,table).headOption
    if(old.exists(_("schema_id")!=id)) {
      val active=rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND status IN ('B_PENDING','CAPTURE_READY')",captureId,table)
      require(active.isEmpty,"active capture schema changed; pause required")
    }
    appendRaw(c,"schema:"+table+":"+id,"SCHEMA",table,"",id,canonical)
    update(c,"INSERT INTO pekko_capture_schemas VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE schema_id=VALUES(schema_id),definition=VALUES(definition)",captureId,table,id,canonical)
    CaptureSchema(table,id,canonical)
  }
  private def schemaVersion(c:Connection,table:String,id:String):CaptureSchema={
    val record=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("schema:"+table+":"+id)).headOption
      .getOrElse(throw new IllegalArgumentException("sealed capture schema missing"))
    val f=frame(c,record("seq").toLong)
    require(f("kind")=="SCHEMA" && f("table_name")==table && f("schema_id")==id && MySQLCdcGroup.hash(f("payload"))==id,"sealed schema binding conflict")
    CaptureSchema(table,id,f("payload"))
  }
  def schemaVersion(table:String,id:String):CaptureSchema=connection(schemaVersion(_,table,id))
  def schema(table:String):Option[CaptureSchema]=connection {c=>rows(c,"SELECT * FROM pekko_capture_schemas WHERE capture_id=? AND table_name=?",captureId,table).headOption.map {r=>
    val sealedSchema=schemaVersion(c,table,r("schema_id"))
    require(sealedSchema.definition==r("definition"),"capture schema registry conflict")
    sealedSchema}}
  private def decode(r:Map[String,String]):CaptureSubscription=CaptureSubscription(r("request_id"),r("table_name"),r("generation"),r("status"),r("schema_id"),Option(r("b_point")).map(CapturePoint.parse),Option(r("k_point")).map(CapturePoint.parse),Option(r("b_seq")).map(_.toLong))
  private def checked(c:Connection,r:Map[String,String]):CaptureSubscription={
    val s=decode(r)
    require(Set("B_PENDING","CAPTURE_READY","CAPTURE_STOPPED","CANCELED").contains(s.status),"invalid capture subscription status")
    def barrier(stop:Boolean,point:CapturePoint):Unit={
      val key=MySQLCdcGroup.hash((if(stop)"K:"else "B:")+s.requestId)
      val record=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,key).headOption
        .getOrElse(throw new IllegalArgumentException("subscription barrier missing"))
      val f=frame(c,record("seq").toLong)
      val expected=MySQLCdcGroup.canonical(JsObject("requestId"->JsString(s.requestId),"generation"->JsString(s.generation),"point"->point.json.parseJson,"stop"->JsBoolean(stop)))
      require(f("kind")=="BARRIER" && f("table_name")==s.table && f("subscription_id")==s.requestId && f("schema_id")==s.schemaId && f("payload")==expected && f("point_json")==point.json,"subscription barrier binding conflict")
      if(!stop)require(s.bSequence.contains(record("seq").toLong),"subscription B sequence conflict")
    }
    s.b.foreach(barrier(false,_));s.k.foreach(barrier(true,_))
    require(s.b.isDefined==s.bSequence.isDefined,"incomplete subscription B")
    require(if(s.status=="CAPTURE_READY")s.b.nonEmpty && s.k.isEmpty else if(s.status=="CAPTURE_STOPPED")s.b.nonEmpty && s.k.nonEmpty else s.b.isEmpty && s.k.isEmpty,"subscription state conflicts with barriers")
    if(s.status=="CAPTURE_STOPPED")require(s.k.get.after(s.b.get),"subscription K precedes B")
    s
  }
  private def find(c:Connection,id:String):Option[CaptureSubscription]=rows(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND request_id=?",captureId,id).headOption.map(checked(c,_))
  private[capture] def subscriptionOn(c:Connection,id:String):Option[CaptureSubscription]=find(c,id)
  def subscription(id:String):Option[CaptureSubscription]=connection(find(_,id))
  /** Bounded startup inventory; unfinished fences cannot be interpreted as absent work. */
  def pendingBIdsAtMostTwo:Vector[String]=connection {c=>
    c.setAutoCommit(false)
    try {
      val h=rows(c,"SELECT lineage FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",captureId).head
      require(h("lineage")==lineage,"capture source lineage conflict")
      require(rows(c,"SELECT 1 FROM pekko_capture_fences WHERE capture_id=? AND seq IS NULL LIMIT 1",captureId).isEmpty,
        "unfinished capture fence prevents startup")
      rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' ORDER BY request_id LIMIT 2",captureId).map(_("request_id"))
    }finally c.rollback()
  }
  def hasActive(table:String):Boolean=connection {c=>rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND status IN ('B_PENDING','CAPTURE_READY') LIMIT 1",captureId,table).nonEmpty}
  private[capture] def cancelPending(id:String):Unit=tx {c=>
    val s=find(c,id).getOrElse(throw new IllegalArgumentException("unknown subscription"))
    require(s.status=="B_PENDING" || s.status=="CANCELED","capture already effective; K required")
    appendRaw(c,"cancel:"+id,"CANCEL",s.table,id,s.schemaId,id)
    update(c,"UPDATE pekko_capture_subscriptions SET status='CANCELED' WHERE capture_id=? AND request_id=?",captureId,id)
  }
  def markerId(id:String,stop:Boolean):String=MySQLCdcGroup.hash(captureId+"|"+(if(stop)"K" else "B")+"|"+id)
  def wakeId(ownerBoot:String):String=MySQLCdcGroup.hash(captureId+"|wake|"+ownerBoot)
  private def wakeOwner(c:Connection,operation:String,table:String,generation:String):Option[String]={
    val named=CaptureWake.decode(operation,table,generation)
    val legacy=if(table=="pekko_capture_control" && generation.matches("[A-Za-z0-9-]{1,128}")){
      val captures=rows(c,"SELECT capture_id FROM pekko_capture_readers WHERE lineage=? LIMIT 33",lineage)
      require(captures.size<=32,"wake capture inventory exceeds bound")
      captures.find(r=>operation==MySQLCdcGroup.hash(r("capture_id")+"|wake|"+generation)).map(r=>r("capture_id")->generation)
    }else None
    named.orElse(legacy).filter {case(capture,_)=>rows(c,"SELECT 1 FROM pekko_capture_readers WHERE capture_id=? AND lineage=? LIMIT 1",capture,lineage).nonEmpty}.map(_._2)
  }
  def validWake(operation:String,table:String,generation:String):Boolean=CaptureWake.decode(operation,table,generation).nonEmpty || connection(c=>wakeOwner(c,operation,table,generation).nonEmpty)
  def markerIntent(operationId:String,table:String,generation:String,stop:Boolean):Option[CaptureSubscription]=connection {c=>
    rows(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND generation=?",captureId,table,generation)
      .map(checked(c,_)).find(s=>markerId(s.requestId,stop)==operationId)
  }
  def register(id:String,table:String,generation:String,schemaId:String):CaptureSubscription=
    registerInternal(id,table,generation,schemaId,None)
  /** Record the verified SDK offset before any source marker write can be attempted. */
  def registerAnchored(id:String,table:String,generation:String,schemaId:String,offset:String):CaptureSubscription={
    val ack=verifiedOffsetSequence(offset)
    registerInternal(id,table,generation,schemaId,Some(offsetKey(offset)->ack))
  }
  private def registerInternal(id:String,table:String,generation:String,schemaId:String,anchor:Option[(String,Long)]):CaptureSubscription=tx {c=>
    require(id.matches("[A-Za-z0-9_-]{1,128}") && table.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") && generation.matches("[A-Za-z0-9_-]{1,128}"))
    find(c,id) match {
      case Some(s)=>require(s.table==table && s.generation==generation && s.schemaId==schemaId,"subscription request conflict");s
      case None=>
        require(rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND generation=?",captureId,table,generation).isEmpty,"subscription generation already used")
        require(rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND status IN ('B_PENDING','CAPTURE_READY')",captureId,table).isEmpty,"table already subscribed")
        require(rows(c,"SELECT schema_id FROM pekko_capture_schemas WHERE capture_id=? AND table_name=?",captureId,table).headOption.exists(_("schema_id")==schemaId),"schema not ready")
        update(c,"INSERT INTO pekko_capture_subscriptions(capture_id,request_id,table_name,generation,status,schema_id) VALUES(?,?,?,?,'B_PENDING',?)",captureId,id,table,generation,schemaId)
        protectGcAdmission(c,"subscription:"+id,"RECOVERY")
        anchor.foreach {case(offset,sequence)=>
          val ack=frame(c,sequence);require(ack("kind")=="ACK" && ack("table_name")==MySQLCdcGroup.hash(offset),"anchor ACK changed")
          verifyChain(c,sequence)
          val payload=MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"requestId"->JsString(id),
            "generation"->JsString(generation),"offset"->offset.parseJson,"ack"->JsNumber(sequence),"digest"->JsString(ack("digest"))))
          appendRaw(c,"B_INTENT:"+id,"B_INTENT",table,id,schemaId,payload)
        }
        find(c,id).get
    }
  }
  def bind(id:String,point:CapturePoint,resume:String,stop:Boolean):CaptureSubscription=tx {c=>
    val s=find(c,id).getOrElse(throw new IllegalArgumentException("unknown marker intent"))
    val previous=if(stop)s.k else s.b
    if(s.status=="CANCELED")s
    else if(previous.nonEmpty) {require(previous.contains(point),"barrier changed");s}
    else {
      require(if(stop)s.status=="CAPTURE_READY" else s.status=="B_PENDING","invalid barrier state")
      require(rows(c,"SELECT schema_id FROM pekko_capture_schemas WHERE capture_id=? AND table_name=?",captureId,s.table).head("schema_id")==s.schemaId,"barrier schema changed")
      val payload=MySQLCdcGroup.canonical(JsObject("requestId"->JsString(id),"generation"->JsString(s.generation),"point"->point.json.parseJson,"stop"->JsBoolean(stop)))
      val seq=appendRaw(c,(if(stop)"K:" else "B:")+id,"BARRIER",s.table,id,s.schemaId,payload,Some(point),resume)
      if(stop)update(c,"UPDATE pekko_capture_subscriptions SET status='CAPTURE_STOPPED',k_point=? WHERE capture_id=? AND request_id=?",point.json,captureId,id)
      else update(c,"UPDATE pekko_capture_subscriptions SET status='CAPTURE_READY',b_point=?,b_seq=? WHERE capture_id=? AND request_id=?",point.json,seq,captureId,id)
      find(c,id).get
    }
  }
  private def routes(c:Connection,table:String):Vector[CaptureSubscription]={
    val subscriptions=rows(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=?",captureId,table).map(checked(c,_))
    val bound=rows(c,"SELECT DISTINCT subscription_id FROM pekko_capture_log WHERE capture_id=? AND table_name=? AND kind='BARRIER'",captureId,table).map(_("subscription_id")).toSet
    require(bound.subsetOf(subscriptions.map(_.requestId).toSet),"durable barrier lost its subscription registry")
    subscriptions
  }
  def route(table:String,p:CapturePoint):Option[CaptureSubscription]=connection {c=>
    val found=routes(c,table).filter(_.accepts(p))
    require(found.size<=1,"overlapping routing intervals");found.headOption}
  def append(group:MySQLCdcGroup):Option[Long]=tx {c=>
    MySQLCdcGroup.parse(group.canonicalJson,protocol)
    val e=group.members.head._2;val p=CapturePoint.from(e)
    val selected=routes(c,e.source.table).filter(_.accepts(p))
    require(selected.size<=1,"overlapping capture intervals")
    selected.headOption.map {s=>
      appendRaw(c,"group:"+group.groupId,"GROUP",s.table,s.requestId,s.schemaId,group.canonicalJson,Some(p))
    }
  }
  def cover(offset:String,references:Vector[Long]=Vector.empty):Unit={coverSequence(offset,references);()}
  def coverSequence(offset:String,references:Vector[Long]=Vector.empty):Long=tx {c=>
    def reference(r:Map[String,String]):JsValue=JsObject("sequence"->JsNumber(r("seq").toLong),"digest"->JsString(r("digest")))
    val parent=rows(c,"SELECT * FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' ORDER BY seq DESC LIMIT 1",captureId).headOption
    val payload=MySQLCdcGroup.canonical(JsObject("offset"->offsetKey(offset).parseJson,
      "parent"->parent.map(reference).getOrElse(JsNull),"references"->JsArray(references.distinct.sorted.map(n=>reference(frame(c,n))))))
    val ack=appendRaw(c,"ack:"+payload,"ACK",MySQLCdcGroup.hash(offsetKey(offset)),"","",payload,resume=offset)
    references.distinct.foreach(n=>update(c,"UPDATE pekko_capture_fences SET covered_seq=COALESCE(covered_seq,?) WHERE capture_id=? AND seq=?",ack,captureId,n))
    update(c,"UPDATE pekko_capture_readers SET frontier=? WHERE capture_id=?",offset,captureId);ack
  }
  private def offsetKey(offset:String):String={
    val f=offset.parseJson.asJsObject.fields.get("offset").getOrElse(offset.parseJson).asJsObject.fields
    MySQLCdcGroup.canonical(JsObject(f.filter {case(k,_)=>Set("file","pos","event","row","gtids","snapshot").contains(k)}))
  }
  def hasEvidence:Boolean=connection(c=>rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind IN ('ACK','BARRIER','GROUP') LIMIT 1",captureId).nonEmpty)
  def verifyOffset(offset:String):Unit={verifiedOffsetSequence(offset);()}
  def verifiedOffsetSequence(offset:String):Long=connection {c=>
    val tables=rows(c,"SELECT table_name FROM pekko_capture_subscriptions WHERE capture_id=? UNION SELECT table_name FROM pekko_capture_log WHERE capture_id=? AND kind='BARRIER'",captureId,captureId).map(_("table_name"))
    tables.foreach(routes(c,_))
    val proof=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' AND table_name=? ORDER BY seq DESC LIMIT 1",captureId,MySQLCdcGroup.hash(offsetKey(offset))).headOption
      .getOrElse(throw new IllegalArgumentException("D has no durable capture coverage"))
    verifyChain(c,proof("seq").toLong);proof("seq").toLong
  }
  def lastAckSequence:Long=connection(c=>rows(c,"SELECT COALESCE(MAX(seq),0) AS n FROM pekko_capture_log WHERE capture_id=? AND kind='ACK'",captureId).head("n").toLong)
  /** Conservative proof: only the same completed, covered D may replay a newly anchored intent. */
  def recoverablePendingAt(offset:String):Vector[String]={
    val ack=verifiedOffsetSequence(offset);val canonical=offsetKey(offset)
    connection {c=>
      if(rows(c,"SELECT 1 FROM pekko_capture_fences WHERE capture_id=? AND seq IS NULL LIMIT 1",captureId).nonEmpty)Vector.empty
      else {
        val pending=rows(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' ORDER BY request_id",captureId).map(checked(c,_))
        val valid=pending.forall {s=>
          val record=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("B_INTENT:"+s.requestId)).headOption
          record.exists {r=>
            val f=frame(c,r("seq").toLong);val p=f("payload").parseJson.asJsObject.fields
            val anchored=p("ack").asInstanceOf[JsNumber].value.toLongExact;val a=frame(c,anchored)
            r("seq").toLong>anchored && a("kind")=="ACK" && a("table_name")==MySQLCdcGroup.hash(canonical) && ackAncestor(c,ack,anchored) &&
              rows(c,"SELECT 1 FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("B:"+s.requestId)).isEmpty &&
              f("kind")=="B_INTENT" && f("table_name")==s.table && f("subscription_id")==s.requestId && f("schema_id")==s.schemaId &&
              p.get("version").contains(JsNumber(1)) && p.get("requestId").contains(JsString(s.requestId)) &&
              p.get("generation").contains(JsString(s.generation)) && p.get("offset").contains(canonical.parseJson) &&
              p.get("digest").contains(JsString(a("digest")))
          }
        }
        if(valid)pending.map(_.requestId)else Vector.empty
      }
    }
  }
  /** The pending-B variant also returns the D ACK references for reverse control proof. */
  def pendingBDescriptor(offset:String,id:String):Option[CaptureBDescriptor]=connection {c=>
    c.setAutoCommit(false)
    try pendingBDescriptorOn(c,offset,id) finally c.rollback()
  }
  /** Caller owns the transaction; this only validates local identities, never the ACK ancestry. */
  private[magic] def pendingBDescriptorOn(c:Connection,offset:String,id:String):Option[CaptureBDescriptor]={
    require(!c.getAutoCommit,"B descriptor requires a state transaction")
    val head=rows(c,"SELECT lineage FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",captureId).head
    require(head("lineage")==lineage,"capture source lineage conflict")
    try {
      val canonical=offsetKey(offset)
      if(rows(c,"SELECT 1 FROM pekko_capture_fences WHERE capture_id=? AND seq IS NULL LIMIT 1",captureId).nonEmpty ||
        rows(c,"SELECT 1 FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' AND request_id<>? LIMIT 1",captureId,id).nonEmpty)None
      else find(c,id).filter(s=>s.status=="B_PENDING" || (s.status=="CAPTURE_READY" && s.b.nonEmpty && s.k.isEmpty && s.bSequence.nonEmpty)).flatMap {s=>
        if(s.status=="CAPTURE_READY" && rows(c,"SELECT 1 FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' LIMIT 1",captureId).nonEmpty)None
        else {
          val sealedSchema=schemaVersion(c,s.table,s.schemaId)
          require(rows(c,"SELECT definition FROM pekko_capture_schemas WHERE capture_id=? AND table_name=? AND schema_id=?",captureId,s.table,s.schemaId)
            .headOption.exists(_("definition")==sealedSchema.definition),"B descriptor schema conflict")
          val intent=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("B_INTENT:"+id)).headOption
          val latest=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' AND table_name=? ORDER BY seq DESC LIMIT 1",captureId,MySQLCdcGroup.hash(canonical)).headOption
          if(s.status=="B_PENDING" && rows(c,"SELECT 1 FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("B:"+id)).nonEmpty)None
          else for(i<-intent;h<-latest)yield {
            val seq=i("seq").toLong;val f=frame(c,seq);val p=f("payload").parseJson.asJsObject.fields
            val anchor=p("ack").asInstanceOf[JsNumber].value.toLongExact;val original=frame(c,anchor)
            val originalOffset=MySQLCdcGroup.canonical(original("payload").parseJson.asJsObject.fields("offset"))
            require(seq>anchor && s.bSequence.forall(_>seq) && f("kind")=="B_INTENT" && f("table_name")==s.table &&
              f("subscription_id")==id && f("schema_id")==s.schemaId && p.get("version").contains(JsNumber(1)) &&
              p.get("requestId").contains(JsString(id)) && p.get("generation").contains(JsString(s.generation)) &&
              p.get("offset").contains(originalOffset.parseJson) && p.get("digest").contains(JsString(original("digest"))) &&
              original("kind")=="ACK" && original("table_name")==MySQLCdcGroup.hash(originalOffset) &&
              (s.status!="B_PENDING" || originalOffset==canonical),"B descriptor intent conflict")
            CaptureBDescriptor(s.status,h("seq").toLong,anchor,s.bSequence,originalOffset,seq,f("digest"))
          }
        }
      }
    }finally clearProofConnection(c)
  }
  def recoverablePendingProofAt(offset:String,id:String):Option[BoundBProof]=connection {c=>
    val canonical=offsetKey(offset)
    if(rows(c,"SELECT 1 FROM pekko_capture_fences WHERE capture_id=? AND seq IS NULL LIMIT 1",captureId).nonEmpty ||
      rows(c,"SELECT 1 FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' AND request_id<>? LIMIT 1",captureId,id).nonEmpty)None
    else find(c,id).filter(_.status=="B_PENDING").flatMap {s=>
      val sealedSchema=schemaVersion(c,s.table,s.schemaId)
      require(rows(c,"SELECT definition FROM pekko_capture_schemas WHERE capture_id=? AND table_name=? AND schema_id=?",captureId,s.table,s.schemaId)
        .headOption.exists(_("definition")==sealedSchema.definition),"pending B schema registry conflict")
      val intent=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("B_INTENT:"+id)).headOption
      val latest=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' AND table_name=? ORDER BY seq DESC LIMIT 1",captureId,MySQLCdcGroup.hash(canonical)).headOption
      if(rows(c,"SELECT 1 FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("B:"+id)).nonEmpty)None
      else for(i<-intent;h<-latest;result<-{
        val sequence=i("seq").toLong;val f=frame(c,sequence);val p=f("payload").parseJson.asJsObject.fields
        val anchor=p("ack").asInstanceOf[JsNumber].value.toLongExact;val original=frame(c,anchor)
        require(sequence>anchor && f("kind")=="B_INTENT" && f("table_name")==s.table && f("subscription_id")==id && f("schema_id")==s.schemaId &&
          p.get("version").contains(JsNumber(1)) && p.get("requestId").contains(JsString(id)) && p.get("generation").contains(JsString(s.generation)) &&
          p.get("offset").contains(canonical.parseJson) && p.get("digest").contains(JsString(original("digest"))) &&
          original("kind")=="ACK" && original("table_name")==MySQLCdcGroup.hash(canonical),"pending B intent proof conflict")
        val proof=CaptureAckProof.inspect(h("seq").toLong,anchor,0L,canonical,n=>frame(c,n))
        if(proof.anchorInChain && proof.anchorOffsetMatches)Some(BoundBProof("PENDING",proof.referenced))else None
      })yield result
    }
  }
  /** B already bound in L: distinguish a referenced barrier from safe replay at the original D. */
  def recoverableBoundAt(offset:String,id:String):Option[BoundBProof]=connection {c=>
    val canonical=offsetKey(offset)
    if(rows(c,"SELECT 1 FROM pekko_capture_fences WHERE capture_id=? AND seq IS NULL LIMIT 1",captureId).nonEmpty ||
      rows(c,"SELECT 1 FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' LIMIT 1",captureId).nonEmpty)None
    else find(c,id).filter(s=>s.status=="CAPTURE_READY" && s.b.nonEmpty && s.k.isEmpty && s.bSequence.nonEmpty).flatMap {s=>
      val sealedSchema=schemaVersion(c,s.table,s.schemaId)
      require(rows(c,"SELECT definition FROM pekko_capture_schemas WHERE capture_id=? AND table_name=? AND schema_id=?",captureId,s.table,s.schemaId)
        .headOption.exists(_("definition")==sealedSchema.definition),"bound B schema registry conflict")
      val intent=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("B_INTENT:"+id)).headOption
      val latest=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' AND table_name=? ORDER BY seq DESC LIMIT 1",captureId,MySQLCdcGroup.hash(canonical)).headOption
      for(i<-intent;h<-latest;result<-{
        val sequence=i("seq").toLong;val f=frame(c,sequence);val p=f("payload").parseJson.asJsObject.fields
        val anchor=p("ack").asInstanceOf[JsNumber].value.toLongExact;val original=frame(c,anchor)
        val originalOffset=MySQLCdcGroup.canonical(original("payload").parseJson.asJsObject.fields("offset"))
        require(sequence>anchor && s.bSequence.get>sequence && f("kind")=="B_INTENT" && f("table_name")==s.table &&
          f("subscription_id")==id && f("schema_id")==s.schemaId && p.get("version").contains(JsNumber(1)) &&
          p.get("requestId").contains(JsString(id)) && p.get("generation").contains(JsString(s.generation)) &&
          p.get("offset").contains(originalOffset.parseJson) && p.get("digest").contains(JsString(original("digest"))) &&
          original("kind")=="ACK" && original("table_name")==MySQLCdcGroup.hash(originalOffset),"bound B intent proof conflict")
        val proof=CaptureAckProof.inspect(h("seq").toLong,anchor,s.bSequence.get,canonical,n=>frame(c,n))
        if(!proof.anchorInChain)None
        else if(proof.barrierReferenced)Some(BoundBProof("COVERED",proof.referenced))
        else if(proof.anchorOffsetMatches && originalOffset==canonical)Some(BoundBProof("REPLAY",proof.referenced))
        else None
      })yield result
    }
  }
  /** verifiedOffsetSequence already checked the full chain and referenced digests. */
  private def ackAncestor(c:Connection,latest:Long,ancestor:Long):Boolean={
    var next=Option(latest)
    while(next.nonEmpty && next.get>=ancestor){
      if(next.contains(ancestor))return true
      val fields=frame(c,next.get)("payload").parseJson.asJsObject.fields
      next=fields("parent") match {
        case JsNull=>None
        case value=>Some(value.asJsObject.fields("sequence").asInstanceOf[JsNumber].value.toLongExact)
      }
    }
    false
  }
  def recoveryInventory:(Boolean,Boolean,Boolean,Boolean)=connection {c=>
    def any(sql:String)=rows(c,sql,captureId).nonEmpty
    val head=rows(c,"SELECT next_seq,used_bytes,frontier FROM pekko_capture_readers WHERE capture_id=?",captureId).head
    val frames=rows(c,s"SELECT COUNT(*) AS n,COUNT(DISTINCT seq) AS distinct_n,COALESCE(MAX(seq),0) AS maximum FROM ${logicalLog(c)} l WHERE capture_id=?",captureId).head
    if(head("next_seq").toLong!=frames("maximum").toLong+1 || frames("n")!=frames("maximum") || frames("distinct_n")!=frames("n"))
      throw new CaptureAdmissionException("CAPTURE_LOG_HEAD_INCONSISTENT")
    val priorHead=head("next_seq").toLong>1 || head("used_bytes").toLong>0 || head("frontier")!=null
    val domain=priorHead || any("SELECT 1 FROM pekko_capture_log WHERE capture_id=? LIMIT 1") ||
      any("SELECT 1 FROM pekko_capture_subscriptions WHERE capture_id=? LIMIT 1") || any("SELECT 1 FROM pekko_capture_fences WHERE capture_id=? LIMIT 1") ||
      any("SELECT 1 FROM pekko_capture_schemas WHERE capture_id=? LIMIT 1")
    val evidence=head("frontier")!=null || any("SELECT 1 FROM pekko_capture_log WHERE capture_id=? AND kind IN ('ACK','BARRIER','GROUP') LIMIT 1")
    val bound=any("SELECT 1 FROM pekko_capture_subscriptions WHERE capture_id=? AND b_point IS NOT NULL LIMIT 1") ||
      any("SELECT 1 FROM pekko_capture_log WHERE capture_id=? AND kind IN ('BARRIER','GROUP') LIMIT 1")
    val pending=any("SELECT 1 FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' LIMIT 1") ||
      any("SELECT 1 FROM pekko_capture_fences WHERE capture_id=? AND seq IS NULL LIMIT 1")
    (domain,evidence,bound,pending)
  }
  /** Keyset page of immutable control rows required by retained boundaries. No new source writes. */
  def requiredControls(after:String,limit:Int):Vector[(String,String,String,String,String)]=connection {c=>
    require(limit>0 && limit<=128)
    val sql="""SELECT * FROM (
      SELECT CONCAT('B:',request_id) AS cursor_key,request_id AS identity_key,'SUBSCRIBE' AS action,table_name,generation
        FROM pekko_capture_subscriptions WHERE capture_id=? AND b_point IS NOT NULL
      UNION ALL SELECT CONCAT('K:',request_id),request_id,'STOP',table_name,generation
        FROM pekko_capture_subscriptions WHERE capture_id=? AND k_point IS NOT NULL
      UNION ALL SELECT CONCAT('F:',f.fence_id),f.fence_id,f.action,s.table_name,s.generation
        FROM pekko_capture_fences f JOIN pekko_capture_subscriptions s ON f.capture_id=s.capture_id AND f.subscription_id=s.request_id
        WHERE f.capture_id=? AND f.seq IS NOT NULL
      ) controls WHERE cursor_key>? ORDER BY cursor_key LIMIT ?"""
    rows(c,sql,captureId,captureId,captureId,after,limit).map {r=>
      val action=r("action");val operation=if(action=="SUBSCRIBE" || action=="STOP")markerId(r("identity_key"),action=="STOP")else fenceOperation(r("identity_key"))
      (r("cursor_key"),operation,action,r("table_name"),r("generation"))
    }
  }
  /** Reverse-check a bounded page of source control rows against retained bindings. */
  def controlsBound(controls:Vector[(String,String,String,String)],id:String,proof:BoundBProof,deadlineNanos:Long=Long.MaxValue):Boolean=connection {c=>
    require(controls.size<=128,"control proof page too large")
    controls.forall {case(operation,action,table,generation)=>
      require(System.nanoTime()<deadlineNanos,"source control proof deadline exceeded")
      if(action=="SUBSCRIBE" || action=="STOP"){
        val rowsForGeneration=rows(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND generation=? LIMIT 129",captureId,table,generation)
        require(rowsForGeneration.size<=128,"control proof generation too large")
        rowsForGeneration.map(checked(c,_)).find(s=>markerId(s.requestId,action=="STOP")==operation).exists {s=>
          val seq=if(action=="STOP" && s.k.nonEmpty)rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("K:"+s.requestId)).headOption.map(_("seq").toLong)
            else if(action=="STOP")None
            else s.bSequence
          (action=="SUBSCRIBE" && s.requestId==id && s.status=="B_PENDING" && proof.mode=="PENDING") ||
            seq.exists(n=>proof.referencedSequences(n) || (action=="SUBSCRIBE" && s.requestId==id && proof.mode=="REPLAY"))
        }
      }else if(Set("LOW","HIGH","F")(action)){
        rows(c,"SELECT fence_id FROM pekko_capture_fences WHERE capture_id=? AND operation_id=?",captureId,operation).headOption.exists {r=>
          val f=fenceOn(c,r("fence_id")).get;val sub=find(c,f.subscriptionId).get
          f.action==action && f.sequence.exists(proof.referencedSequences) && sub.table==table && sub.generation==generation
        }
      }else if(action=="WAKE"){
        wakeOwner(c,operation,table,generation).exists(boot=>
          rows(c,"SELECT 1 FROM pekko_cdc_managed_owner_v1 WHERE boot_id=? LIMIT 1",boot).nonEmpty)
      }else false
    }
  }
  /** Bounded source-control page against a COMPLETE proof, on the caller's state transaction. */
  private[magic] def completeModeOn(c:Connection,id:String,descriptor:CaptureBDescriptor,
    ack:CaptureAckPageIdentity,pages:CaptureAckPaging):String={
    require(ack.task==id && ack.head==descriptor.head && ack.anchor==descriptor.anchor && ack.intent==descriptor.intentDigest,
      "control page proof identity conflict")
    require(pendingBDescriptorOn(c,ack.offset,id).contains(descriptor),"B descriptor changed")
    val state=pages.statusOn(c,ack);require(state.done,"ACK proof incomplete")
    if(descriptor.status=="B_PENDING" && state.anchorMatchesOffset)"PENDING"
    else if(descriptor.status=="CAPTURE_READY" && descriptor.barrier.exists(pages.containsOn(c,ack,_)))"COVERED"
    else if(descriptor.status=="CAPTURE_READY" && state.anchorMatchesOffset && descriptor.originalOffset==offsetKey(ack.offset))"REPLAY"
    else throw new IllegalArgumentException("B is not covered by complete D ACK proof")
  }
  private[magic] def controlsBoundOn(c:Connection,controls:Vector[(String,String,String,String)],id:String,
    descriptor:CaptureBDescriptor,ack:CaptureAckPageIdentity,pages:CaptureAckPaging):Boolean={
    require(controls.size<=128,"control proof page too large")
    val mode=completeModeOn(c,id,descriptor,ack,pages)
    def covered(n:Long):Boolean=pages.containsOn(c,ack,n)
    try controls.forall {case(operation,action,table,generation)=>
      if(action=="SUBSCRIBE" || action=="STOP"){
        val found=rows(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND generation=? LIMIT 129",captureId,table,generation)
        require(found.size<=128,"control proof generation too large")
        found.map(checked(c,_)).find(s=>markerId(s.requestId,action=="STOP")==operation).exists {s=>
          val seq=if(action=="STOP" && s.k.nonEmpty)rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash("K:"+s.requestId)).headOption.map(_("seq").toLong)
            else if(action=="STOP")None else s.bSequence
          (action=="SUBSCRIBE" && s.requestId==id && s.status=="B_PENDING" && mode=="PENDING") ||
            seq.exists(n=>covered(n) || (action=="SUBSCRIBE" && s.requestId==id && mode=="REPLAY"))
        }
      }else if(Set("LOW","HIGH","F")(action)){
        rows(c,"SELECT * FROM pekko_capture_fences WHERE capture_id=? AND operation_id=?",captureId,operation).headOption.exists {r=>
          val sub=find(c,r("subscription_id")).get
          val seq=Option(r("seq")).map(_.toLong);val point=Option(r("point_json")).map(CapturePoint.parse)
          val ackSeq=Option(r("covered_seq")).map(_.toLong)
          seq.exists {n=>
            val f=frame(c,n);val expected=MySQLCdcGroup.canonical(JsObject("id"->JsString(r("fence_id")),"action"->JsString(r("action")),"context"->r("context_json").parseJson))
            require(f("kind")=="FENCE" && f("subscription_id")==sub.requestId && f("payload")==expected && f("point_json")==r("point_json"),"bootstrap fence binding conflict")
            ackSeq.exists {a=>
              val sourceAck=frame(c,a);val refs=sourceAck("payload").parseJson.asJsObject.fields("references").asInstanceOf[JsArray].elements
              sourceAck("kind")=="ACK" && pages.containsAckOn(c,ack,a) && refs.exists(v=>v.asJsObject.fields("sequence")==JsNumber(n) &&
                v.asJsObject.fields("digest")==JsString(f("digest"))) && covered(n) && point.exists(p=> !sub.k.exists(p.after))
            } && r("action")==action && sub.table==table && sub.generation==generation
          }
        }
      }else if(action=="WAKE")wakeOwner(c,operation,table,generation).exists(boot=>
        rows(c,"SELECT 1 FROM pekko_cdc_managed_owner_v1 WHERE boot_id=? LIMIT 1",boot).nonEmpty)
      else false
    }finally clearProofConnection(c)
  }
  private def verifyChain(c:Connection,head:Long):Unit={
    var next=Option(head)
    while(next.nonEmpty) {
      val current=frame(c,next.get);require(current("kind")=="ACK","invalid capture proof chain")
      val p=current("payload").parseJson.asJsObject.fields
      def check(v:JsValue):Long={val f=v.asJsObject.fields;val n=f("sequence").asInstanceOf[JsNumber].value.toLongExact
        require(n<current("seq").toLong,"capture proof cycle")
        require(frame(c,n)("digest")==f("digest").asInstanceOf[JsString].value,"capture proof digest conflict");n}
      p("references").asInstanceOf[JsArray].elements.foreach(check)
      next=if(p("parent")==JsNull)None else Some(check(p("parent")))
    }
  }
  def read(id:String,after:Long,limit:Int):Vector[CaptureLogEntry]=readInternal(id,after,limit,None)
  private[magic] def readForConsumer(id:String,after:Long,limit:Int,consumer:String):Vector[CaptureLogEntry]=
    readInternal(id,after,limit,Some(consumer))
  private def readInternal(id:String,after:Long,limit:Int,consumer:Option[String]):Vector[CaptureLogEntry]={
    require(limit>0 && limit<=1000)
    connection {c=>
      c.setAutoCommit(false)
      try {
      val head=rows(c,"SELECT lineage FROM pekko_capture_readers WHERE capture_id=? FOR SHARE",captureId).head
      require(head("lineage")==lineage,"capture source lineage conflict")
      val state=rows(c,"SELECT discovery_status,barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=?",captureId).head
      require(Set("UNKNOWN","COMPLETE")(state("discovery_status")) && state("barrier_status")=="OPEN","capture read fenced")
      require(state("discovery_status")!="COMPLETE" || consumer.nonEmpty,"capture consumer registration required")
      consumer.foreach {key=>
        val registered=rows(c,"SELECT subscription_id,audit_floor FROM pekko_capture_consumers_v1 WHERE capture_id=? AND consumer_id=?",captureId,key)
        require(registered.size==1 && registered.head("subscription_id")==id && after>=registered.head("audit_floor").toLong,
          "capture consumer missing or below retained floor")
      }
      require(find(c,id).exists(_.b.nonEmpty),"subscription not capture-ready")
      val enabled=archiveEnabled(c)
      val sql=if(enabled)archiveSelect(c)+" WHERE l.capture_id=? AND l.subscription_id=? AND l.kind='GROUP' AND l.seq>? ORDER BY l.seq LIMIT ?"
        else s"SELECT * FROM ${logicalLog(c)} l WHERE capture_id=? AND subscription_id=? AND kind='GROUP' AND seq>? ORDER BY seq LIMIT ?"
      rows(c,sql,captureId,id,after,limit).map {input=>
        val raw=materializeGroup(c,input)
        val r=if(enabled)checkedFrame(raw)else {
          require(frameDigest(raw("kind"),raw("table_name"),raw("subscription_id"),raw("schema_id"),raw("payload"),Option(raw("point_json")))==raw("digest"),"capture frame corrupted");raw
        }
        CaptureLogEntry(r("seq").toLong,id,r("schema_id"),MySQLCdcGroup.parse(r("payload"),protocol))
      }
      }finally c.rollback()
    }
  }
  def fenceOperation(id:String):String=MySQLCdcGroup.hash(captureId+"|bootstrap|"+id)
  def registerFence(id:String,subscriptionId:String,action:String,context:String):CaptureFence=tx {c=>
    require(id.matches("[A-Za-z0-9_-]{1,128}") && Set("LOW","HIGH","F").contains(action),"invalid bootstrap fence")
    val sub=find(c,subscriptionId).getOrElse(throw new IllegalArgumentException("unknown fence subscription"))
    require(sub.status=="CAPTURE_READY","fence requires active capture")
    val payload=MySQLCdcGroup.canonical(context.parseJson)
    val old=fenceOn(c,id)
    old match {
      case Some(f)=>require(f.subscriptionId==subscriptionId && f.action==action && f.context==payload,"bootstrap fence intent conflict");f
      case None=>
        update(c,"INSERT INTO pekko_capture_fences(capture_id,fence_id,operation_id,subscription_id,action,context_json) VALUES(?,?,?,?,?,?)",captureId,id,fenceOperation(id),subscriptionId,action,payload)
        fenceOn(c,id).get
    }
  }
  private def fenceOn(c:Connection,id:String):Option[CaptureFence]=rows(c,"SELECT * FROM pekko_capture_fences WHERE capture_id=? AND fence_id=?",captureId,id).headOption.map {r=>
    val seq=Option(r("seq")).map(_.toLong);val point=Option(r("point_json")).map(CapturePoint.parse)
    require(seq.isDefined==point.isDefined,"incomplete bootstrap fence")
    seq.foreach {n=>val f=frame(c,n)
      val expected=MySQLCdcGroup.canonical(JsObject("id"->JsString(id),"action"->JsString(r("action")),"context"->r("context_json").parseJson))
      require(f("kind")=="FENCE" && f("subscription_id")==r("subscription_id") && f("payload")==expected && f("point_json")==r("point_json"),"bootstrap fence binding conflict")}
    val covered=Option(r("covered_seq")).map {a=>
      verifyChain(c,a.toLong)
      val ack=frame(c,a.toLong);require(ack("kind")=="ACK","bootstrap coverage is not an ACK proof")
      val refs=ack("payload").parseJson.asJsObject.fields("references").asInstanceOf[JsArray].elements
      require(seq.exists(n=>refs.exists(v=>v.asJsObject.fields("sequence")==JsNumber(n) && v.asJsObject.fields("digest")==JsString(frame(c,n)("digest")))),"bootstrap coverage reference missing")
      true
    }.getOrElse(false)
    val obsolete=point.exists(p=>find(c,r("subscription_id")).flatMap(_.k).exists(p.after))
    CaptureFence(id,r("subscription_id"),r("action"),r("context_json"),point,seq,covered && !obsolete,obsolete)
  }
  def fence(id:String):Option[CaptureFence]=connection(fenceOn(_,id))
  def fenceIntent(operation:String,table:String,generation:String):Option[(CaptureFence,CaptureSubscription)]=connection {c=>
    rows(c,"SELECT fence_id FROM pekko_capture_fences WHERE capture_id=? AND operation_id=?",captureId,operation).headOption.flatMap(r=>fenceOn(c,r("fence_id"))).map {f=>
      val sub=find(c,f.subscriptionId).get;require(sub.table==table && sub.generation==generation,"bootstrap marker identity conflict");f->sub}
  }
  def bindFence(id:String,point:CapturePoint,resume:String):Long=tx {c=>
    val f=fenceOn(c,id).getOrElse(throw new IllegalArgumentException("unknown bootstrap fence"))
    val sub=find(c,f.subscriptionId).get
    f.sequence match {
      case Some(n)=>require(f.point.contains(point),"bootstrap fence moved");n
      case None=>
        require(sub.b.exists(point.after),"bootstrap fence precedes capture interval")
        val payload=MySQLCdcGroup.canonical(JsObject("id"->JsString(id),"action"->JsString(f.action),"context"->f.context.parseJson))
        val n=appendRaw(c,"fence:"+id,"FENCE",sub.table,sub.requestId,sub.schemaId,payload,Some(point),resume)
        update(c,"UPDATE pekko_capture_fences SET point_json=?,seq=? WHERE capture_id=? AND fence_id=?",point.json,n,captureId,id);n
    }
  }
  def readThrough(id:String,after:Long,fenceId:String,limit:Int):Vector[CaptureLogEntry]={
    readThroughInternal(id,after,fenceId,limit,None)
  }
  private[magic] def readThroughForConsumer(id:String,after:Long,fenceId:String,limit:Int,consumer:String):Vector[CaptureLogEntry]={
    readThroughInternal(id,after,fenceId,limit,Some(consumer))
  }
  private def readThroughInternal(id:String,after:Long,fenceId:String,limit:Int,consumer:Option[String]):Vector[CaptureLogEntry]={
    val f=fence(fenceId).getOrElse(throw new IllegalArgumentException("unknown read fence"))
    require(f.subscriptionId==id && f.covered,"bootstrap fence not covered")
    readInternal(id,after,limit,consumer).takeWhile(_.sequence<f.sequence.get).map {entry=>
      require(!CapturePoint.from(entry.group.members.head._2).after(f.point.get),"L order exceeds bootstrap fence");entry}
  }
  def evidence:JsObject=connection {c=>
    def json(sql:String):JsArray=JsArray(rows(c,sql,captureId).map(r=>JsObject(r.map {case(k,v)=>k->Option(v).fold[JsValue](JsNull)(JsString(_))})))
    val enabled=archiveEnabled(c)
    val sql=if(enabled)archiveSelect(c)+" WHERE l.capture_id=? ORDER BY l.seq" else s"SELECT * FROM ${logicalLog(c)} l WHERE capture_id=? ORDER BY seq"
    val log=JsArray(rows(c,sql,captureId).map(materializeGroup(c,_)).map(r=>if(enabled)checkedFrame(r)else r).map(r=>JsObject(r.map {case(k,v)=>k->Option(v).fold[JsValue](JsNull)(JsString(_))})))
    JsObject("reader"->json("SELECT * FROM pekko_capture_readers WHERE capture_id=?"),"subscriptions"->json("SELECT * FROM pekko_capture_subscriptions WHERE capture_id=?"),
      "schemas"->json("SELECT * FROM pekko_capture_schemas WHERE capture_id=?"),"fences"->json("SELECT * FROM pekko_capture_fences WHERE capture_id=?"),"log"->log)
  }
}
