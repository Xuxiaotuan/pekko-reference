package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.{MySQLCdcGroup, MySQLCdcGroupProtocol}
import java.sql.{Connection, PreparedStatement}
import java.util.concurrent.{Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import spray.json._

/** One source writer; routing changes, immutable records and their evidence share transactions.
  * No automatic truncation or owner takeover. SDK Offset tables remain SDK-owned.
  */
final class MySQLCaptureStore(open:()=>Connection,val captureId:String,val lineage:String,val maxBytes:Long) {
  require(captureId.matches("[A-Za-z0-9_-]{1,64}") && lineage.nonEmpty && maxBytes>0)
  val protocol=MySQLCdcGroupProtocol(captureId,"capture-v1",lineage)
  private val permits=new Semaphore(4)
  private val fenced=new AtomicBoolean(false)
  private def connection[A](f:Connection=>A):A={
    require(!fenced.get(),"capture store resource release unknown")
    require(permits.tryAcquire(5,TimeUnit.SECONDS),"capture store connection admission timed out")
    var c:Connection=null;var released=false
    try {c=open();f(c)} finally {
      try {if(c!=null)c.close();released=true} finally {if(released)permits.release() else fenced.set(true)}
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
      "CREATE TABLE IF NOT EXISTS pekko_capture_log(capture_id VARCHAR(64),seq BIGINT,source_key CHAR(64) NOT NULL,kind VARCHAR(16) NOT NULL,table_name VARCHAR(128) NOT NULL,subscription_id VARCHAR(128) NOT NULL,schema_id CHAR(64) NOT NULL,payload LONGTEXT NOT NULL,digest CHAR(64) NOT NULL,point_json TEXT NULL,resume_json LONGTEXT NULL,PRIMARY KEY(capture_id,seq),UNIQUE KEY occurrence(capture_id,source_key),INDEX deliveries(capture_id,subscription_id,seq))"
    ).foreach(sql=>update(c,sql+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"))
    require(rows(c,"SELECT COUNT(*) AS total FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('pekko_capture_readers','pekko_capture_schemas','pekko_capture_subscriptions','pekko_capture_log','pekko_capture_fences') AND engine='InnoDB'").head("total")=="5","capture state requires InnoDB")
    update(c,"INSERT IGNORE INTO pekko_capture_readers VALUES(?,?,1,0,NULL)",captureId,lineage)
    val head=rows(c,"SELECT lineage,next_seq,(SELECT COALESCE(MAX(seq),0) FROM pekko_capture_log WHERE capture_id=?) AS maximum FROM pekko_capture_readers WHERE capture_id=?",captureId,captureId).head
    require(head("lineage")==lineage,"capture lineage changed")
    require(head("next_seq").toLong>head("maximum").toLong,"capture head inconsistent with L")
  }
  private def appendRaw(c:Connection,key:String,kind:String,table:String,sub:String,schema:String,payload:String,
    point:Option[CapturePoint]=None,resume:String=""):Long={
    val digest=frameDigest(kind,table,sub,schema,payload,point.map(_.json))
    rows(c,"SELECT seq,digest FROM pekko_capture_log WHERE capture_id=? AND source_key=?",captureId,MySQLCdcGroup.hash(key)).headOption match {
      case Some(r)=>require(r("digest")==digest,"capture occurrence content conflict");r("seq").toLong
      case None=>
        val h=rows(c,"SELECT next_seq,used_bytes FROM pekko_capture_readers WHERE capture_id=?",captureId).head
        val n=h("next_seq").toLong; val bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong+resume.length+256
        require(h("used_bytes").toLong+bytes<=maxBytes,"capture log capacity exhausted")
        update(c,"INSERT INTO pekko_capture_log VALUES(?,?,?,?,?,?,?,?,?,?,?)",captureId,n,MySQLCdcGroup.hash(key),kind,table,sub,schema,payload,digest,point.map(_.json).orNull,resume)
        update(c,"UPDATE pekko_capture_readers SET next_seq=?,used_bytes=used_bytes+? WHERE capture_id=?",n+1,bytes,captureId);n
    }
  }
  private def frameDigest(kind:String,table:String,sub:String,schema:String,payload:String,point:Option[String]):String=
    MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsArray(Vector(kind,table,sub,schema,payload,point.getOrElse("")).map(JsString(_)))))
  def rememberProfile(profile:String):Long=tx {c=>
    appendRaw(c,"capture-profile","PROFILE","","","",MySQLCdcGroup.canonical(profile.parseJson))
  }
  private def frame(c:Connection,sequence:Long):Map[String,String]={
    val r=rows(c,"SELECT * FROM pekko_capture_log WHERE capture_id=? AND seq=?",captureId,sequence).headOption
      .getOrElse(throw new IllegalArgumentException("capture proof references a missing frame"))
    require(frameDigest(r("kind"),r("table_name"),r("subscription_id"),r("schema_id"),r("payload"),Option(r("point_json")))==r("digest"),"capture frame corrupted")
    r
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
  def subscription(id:String):Option[CaptureSubscription]=connection(find(_,id))
  def hasActive(table:String):Boolean=connection {c=>rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND status IN ('B_PENDING','CAPTURE_READY') LIMIT 1",captureId,table).nonEmpty}
  def cancelPending(id:String):Unit=tx {c=>
    val s=find(c,id).getOrElse(throw new IllegalArgumentException("unknown subscription"))
    require(s.status=="B_PENDING" || s.status=="CANCELED","capture already effective; K required")
    appendRaw(c,"cancel:"+id,"CANCEL",s.table,id,s.schemaId,id)
    update(c,"UPDATE pekko_capture_subscriptions SET status='CANCELED' WHERE capture_id=? AND request_id=?",captureId,id)
  }
  def markerId(id:String,stop:Boolean):String=MySQLCdcGroup.hash(captureId+"|"+(if(stop)"K" else "B")+"|"+id)
  def markerIntent(operationId:String,table:String,generation:String,stop:Boolean):Option[CaptureSubscription]=connection {c=>
    rows(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND generation=?",captureId,table,generation)
      .map(checked(c,_)).find(s=>markerId(s.requestId,stop)==operationId)
  }
  def register(id:String,table:String,generation:String,schemaId:String):CaptureSubscription=tx {c=>
    require(id.matches("[A-Za-z0-9_-]{1,128}") && table.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") && generation.matches("[A-Za-z0-9_-]{1,128}"))
    find(c,id) match {
      case Some(s)=>require(s.table==table && s.generation==generation && s.schemaId==schemaId,"subscription request conflict");s
      case None=>
        require(rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND generation=?",captureId,table,generation).isEmpty,"subscription generation already used")
        require(rows(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND table_name=? AND status IN ('B_PENDING','CAPTURE_READY')",captureId,table).isEmpty,"table already subscribed")
        require(rows(c,"SELECT schema_id FROM pekko_capture_schemas WHERE capture_id=? AND table_name=?",captureId,table).headOption.exists(_("schema_id")==schemaId),"schema not ready")
        update(c,"INSERT INTO pekko_capture_subscriptions(capture_id,request_id,table_name,generation,status,schema_id) VALUES(?,?,?,?,'B_PENDING',?)",captureId,id,table,generation,schemaId)
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
  def cover(offset:String,references:Vector[Long]=Vector.empty):Unit=tx {c=>
    def reference(r:Map[String,String]):JsValue=JsObject("sequence"->JsNumber(r("seq").toLong),"digest"->JsString(r("digest")))
    val parent=rows(c,"SELECT * FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' ORDER BY seq DESC LIMIT 1",captureId).headOption
    val payload=MySQLCdcGroup.canonical(JsObject("offset"->offsetKey(offset).parseJson,
      "parent"->parent.map(reference).getOrElse(JsNull),"references"->JsArray(references.distinct.sorted.map(n=>reference(frame(c,n))))))
    val ack=appendRaw(c,"ack:"+payload,"ACK",MySQLCdcGroup.hash(offsetKey(offset)),"","",payload,resume=offset)
    references.distinct.foreach(n=>update(c,"UPDATE pekko_capture_fences SET covered_seq=COALESCE(covered_seq,?) WHERE capture_id=? AND seq=?",ack,captureId,n))
    update(c,"UPDATE pekko_capture_readers SET frontier=? WHERE capture_id=?",offset,captureId)
  }
  private def offsetKey(offset:String):String={
    val f=offset.parseJson.asJsObject.fields.get("offset").getOrElse(offset.parseJson).asJsObject.fields
    MySQLCdcGroup.canonical(JsObject(f.filter {case(k,_)=>Set("file","pos","event","row","gtids","snapshot").contains(k)}))
  }
  def hasEvidence:Boolean=connection(c=>rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind IN ('ACK','BARRIER','GROUP') LIMIT 1",captureId).nonEmpty)
  def verifyOffset(offset:String):Unit=connection {c=>
    val tables=rows(c,"SELECT table_name FROM pekko_capture_subscriptions WHERE capture_id=? UNION SELECT table_name FROM pekko_capture_log WHERE capture_id=? AND kind='BARRIER'",captureId,captureId).map(_("table_name"))
    tables.foreach(routes(c,_))
    val proof=rows(c,"SELECT seq FROM pekko_capture_log WHERE capture_id=? AND kind='ACK' AND table_name=? ORDER BY seq DESC LIMIT 1",captureId,MySQLCdcGroup.hash(offsetKey(offset))).headOption
      .getOrElse(throw new IllegalArgumentException("D has no durable capture coverage"))
    verifyChain(c,proof("seq").toLong)
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
  def read(id:String,after:Long,limit:Int):Vector[CaptureLogEntry]={
    require(limit>0 && limit<=1000)
    connection {c=>
      require(find(c,id).exists(_.b.nonEmpty),"subscription not capture-ready")
      rows(c,"SELECT * FROM pekko_capture_log WHERE capture_id=? AND subscription_id=? AND kind='GROUP' AND seq>? ORDER BY seq LIMIT ?",captureId,id,after,limit).map {r=>
        require(frameDigest(r("kind"),r("table_name"),r("subscription_id"),r("schema_id"),r("payload"),Option(r("point_json")))==r("digest"),"capture frame corrupted")
        CaptureLogEntry(r("seq").toLong,id,r("schema_id"),MySQLCdcGroup.parse(r("payload"),protocol))
      }
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
    val f=fence(fenceId).getOrElse(throw new IllegalArgumentException("unknown read fence"))
    require(f.subscriptionId==id && f.covered,"bootstrap fence not covered")
    read(id,after,limit).takeWhile(_.sequence<f.sequence.get).map {entry=>
      require(!CapturePoint.from(entry.group.members.head._2).after(f.point.get),"L order exceeds bootstrap fence");entry}
  }
  def evidence:JsObject=connection {c=>
    def json(sql:String):JsArray=JsArray(rows(c,sql,captureId).map(r=>JsObject(r.map {case(k,v)=>k->Option(v).fold[JsValue](JsNull)(JsString(_))})))
    JsObject("reader"->json("SELECT * FROM pekko_capture_readers WHERE capture_id=?"),"subscriptions"->json("SELECT * FROM pekko_capture_subscriptions WHERE capture_id=?"),
      "schemas"->json("SELECT * FROM pekko_capture_schemas WHERE capture_id=?"),"fences"->json("SELECT * FROM pekko_capture_fences WHERE capture_id=?"),"log"->json("SELECT * FROM pekko_capture_log WHERE capture_id=? ORDER BY seq"))
  }
}
