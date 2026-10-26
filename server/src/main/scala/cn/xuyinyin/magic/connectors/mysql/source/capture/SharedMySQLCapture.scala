package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.cdc.{MySQLCdcEnvelope,MySQLCdcGroup}
import org.apache.kafka.connect.data.Struct
import org.apache.kafka.connect.source.SourceRecord
import org.apache.pekko.Done
import spray.json._
import java.sql.{Connection,DriverManager}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext,Future}
import scala.jdk.CollectionConverters._
import scala.util.Failure

/** Single managed owner. A subscription becoming CAPTURE_READY does not initialize its target. */
class SharedMySQLCapture(source:MySQLCdcSourceConfig,state:MySQLCdcStateConfig,val store:MySQLCaptureStore,
  factory:DebeziumEngineFactory=DebeziumEngineFactory.real,
  observe:(String,JsObject)=>Unit=(_,_)=>(),
  snapshotMonitor:Option[CaptureSnapshotMonitor]=None) extends AutoCloseable {
  require(source.connectorId==store.captureId,"capture namespace mismatch")
  private val started=new AtomicBoolean(false);private val closed=new AtomicBoolean(false)
  private val stopping=new AtomicBoolean(false)
  private val startup=new CaptureStartupState(java.util.UUID.randomUUID().toString)
  private val snapshots=snapshotMonitor.getOrElse(CaptureSnapshotMonitor.platform(store.captureId))
  @volatile private var confirmedAckSequence=0L
  private val error=new AtomicReference[Throwable]()
  private val inside=new AtomicBoolean(false)
  private val callbackFailed=new AtomicBoolean(false)
  private var engine:DebeziumEngineAccess=null
  private var completion:Future[Done]=null
  private var owner:Connection=null
  private var ownerId=0L
  private var profileSequence=0L
  private var nextCallback=0L
  private case class Pending(id:Long,offset:String,handle:CdcBatchCommitHandle,var remaining:Int,bytes:Long,references:mutable.Set[Long])
  private val pending=mutable.Queue.empty[Pending]
  private val assemblers=mutable.Map.empty[String,MySQLCdcGroupAssembler]
  private case class Marker(id:String,table:String,generation:String,action:String,point:CapturePoint,done:Long=>Unit)
  private val markers=mutable.Map.empty[String,Marker]
  private val commandLock=new AnyRef
  private val closeLock=new AnyRef
  private var closeFailure:Throwable=null
  private val groupCoverage=mutable.Map.empty[String,Long]
  def failure:Option[Throwable]=Option(error.get())
  private def active():Unit={
    require(!closed.get(),"shared capture closed")
    if(!started.get())throw new CaptureAdmissionException("CAPTURE_STARTING")
    failure.foreach(throw _)
  }
  private def releaseUnknown(stage:String,cause:Throwable):cn.xuyinyin.magic.workflow.engine.CdcExecutionException=
    new cn.xuyinyin.magic.workflow.engine.CdcExecutionException(
      cn.xuyinyin.magic.workflow.engine.ExecutionFailure(cn.xuyinyin.magic.workflow.engine.ExecutionFailure.ReleaseUnknown,stage,releaseConfirmed=false),cause)
  protected def sourceConnection():Connection=DriverManager.getConnection(
    s"jdbc:mysql://${source.host}:${source.port}/${source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000",source.username,source.password)
  private def withSourceConnection[A](f:Connection=>A):A={
    val c=sourceConnection();var primary:Throwable=null
    try f(c) catch {case e:Throwable=>primary=e;throw e}
    finally try c.close() catch {case release:Throwable=>
      val unknown=releaseUnknown("capture-source-close",release)
      if(primary!=null)unknown.addSuppressed(primary)
      error.compareAndSet(null,unknown);throw unknown
    }
  }
  private def stateConnection():Connection=DriverManager.getConnection(state.jdbcUrl,state.username,state.password)
  private def query(c:Connection,sql:String):Vector[String]={val q=c.createStatement();q.setQueryTimeout(5)
    try {val r=q.executeQuery(sql);try {val b=Vector.newBuilder[String];while(r.next())b+=r.getString(1);b.result()}finally r.close()}finally q.close()}
  private def update(c:Connection,sql:String):Unit={val q=c.createStatement();q.setQueryTimeout(5);try {q.executeUpdate(sql);()}finally q.close()}
  private def assertOwner():Unit=synchronized {
    try require(owner!=null && query(owner,s"SELECT IS_USED_LOCK('pekko_capture_${store.captureId}')").headOption.contains(ownerId.toString),"capture owner control lost")
    catch {case scala.util.control.NonFatal(e)=>
      val unknown=releaseUnknown("capture-owner-control",e);error.compareAndSet(null,unknown);startup.fail();throw unknown
    }
  }
  private def stateTableExists(c:Connection,name:String):Boolean={
    require(name.matches("[A-Za-z0-9_]+"),"invalid checkpoint table")
    query(c,s"SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$name' LIMIT 1").nonEmpty
  }
  private def readCheckpoint():CaptureCheckpoint={
    val c=stateConnection()
    try {
      val table=MySQLCdcSourceNode.connectorStateTables(state,store.captureId)._1
      if(!stateTableExists(c,table))CaptureCheckpoint.missing else {
        val q=c.createStatement();q.setQueryTimeout(5)
        try {val r=q.executeQuery(s"SELECT offset_key,offset_val FROM `$table` WHERE offset_val IS NOT NULL LIMIT 2")
          val rows=Vector.newBuilder[(String,String)]
          try while(r.next())rows+=r.getString(1)->r.getString(2) finally r.close()
          CaptureCheckpoint.decode(store.captureId,rows.result())
        }finally q.close()
      }
    }finally try c.close()catch {case e:Throwable=>throw releaseUnknown("capture-checkpoint-close",e)}
  }
  private def validateRetainedControls():Unit=withSourceConnection {c=>
    var after="";var more=true
    while(more){val page=store.requiredControls(after,128);more=page.nonEmpty
      page.foreach {case(cursor,operation,action,table,generation)=>
        val q=c.prepareStatement("SELECT action,table_name,generation FROM pekko_capture_control WHERE operation_id=?");q.setQueryTimeout(5)
        try {q.setString(1,operation);val r=q.executeQuery()
          try {if(!r.next() || r.getString(1)!=action || r.getString(2)!=table || r.getString(3)!=generation)
            throw new CaptureAdmissionException("CONTROL_ROW_MISSING_OR_CHANGED")
          }finally r.close()
        }finally q.close()
        after=cursor
      }
    }
  }
  def startupEvidence:CaptureStartupEvidence=commandLock.synchronized {
    if(stopping.get() || closed.get())startup.stop()
    else if(failure.nonEmpty)startup.fail()
    else if(Set("STARTING","ADMISSION_READY").contains(startup.evidence.phase)) {
      try {
        assertOwner()
        val checkpoint=readCheckpoint()
        if(checkpoint.status=="INVALID")throw new CaptureAdmissionException("CHECKPOINT_INVALID")
        val sequence=checkpoint.value.filter(_=>checkpoint.status=="COMPLETE").map {value=>
          try store.verifiedOffsetSequence(value) catch {
            case scala.util.control.NonFatal(e) if !store.releaseConfirmed=>throw releaseUnknown("capture-store",e)
            case scala.util.control.NonFatal(_) if startup.evidence.phase=="STARTING"=>0L
          }
        }.getOrElse(0L)
        startup.observed(snapshots.status,checkpoint,sequence,confirmedAckSequence)
      }catch {case e:Throwable=>error.compareAndSet(null,e);startup.fail()}
    }
    startup.evidence
  }
  private def requireAdmission():Unit={
    active();assertOwner()
    val evidence=startupEvidence
    failure.foreach(throw _)
    if(evidence.phase!="ADMISSION_READY")throw new CaptureAdmissionException(evidence.reasonCode.getOrElse("CAPTURE_STARTING"))
  }
  def start():Future[Done]=commandLock.synchronized {
    require(started.compareAndSet(false,true),"capture already started")
    if(stopping.get() || closed.get())throw new CaptureAdmissionException("CAPTURE_STOPPING")
    try {
      var controlPresent=false
      val sourceUuid=withSourceConnection {c=>
        val uuid=query(c,"SELECT @@server_uuid").head
        require(uuid==store.lineage,"source lineage changed")
        require(query(c,"SELECT @@binlog_format").head=="ROW" && query(c,"SELECT @@binlog_row_image").head=="FULL","ROW/FULL required")
        controlPresent=query(c,"SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='pekko_capture_control' LIMIT 1").nonEmpty
        uuid
      }
      owner=stateConnection()
      val stateUuid=query(owner,"SELECT @@server_uuid").head
      require(stateUuid!=sourceUuid,"capture state must use a different MySQL instance: transaction metadata feedback")
      require(store.location==(stateUuid->query(owner,"SELECT DATABASE()").head),"capture L must use the owner state server and database")
      require(query(owner,s"SELECT GET_LOCK('pekko_capture_${store.captureId}',0)").head=="1","another shared Reader owns this capture")
      ownerId=query(owner,"SELECT CONNECTION_ID()").head.toLong
      val (existing,hasEvidence,hasBoundaries,unbound)=store.recoveryInventory
      val checkpoint=readCheckpoint()
      val (offsetTable,historyTable)=MySQLCdcSourceNode.connectorStateTables(state,store.captureId)
      val historyPresent=stateTableExists(owner,historyTable) && query(owner,s"SELECT 1 FROM `$historyTable` LIMIT 1").nonEmpty
      val covered=checkpoint.value.exists(v=>try {store.verifyOffset(v);true}catch {
        case scala.util.control.NonFatal(e) if !store.releaseConfirmed=>throw releaseUnknown("capture-store",e)
        case scala.util.control.NonFatal(_)=>false
      })
      startup.starting(checkpoint.status,store.lastAckSequence)
      val facts=CaptureRecoveryFacts(existing,hasEvidence,hasBoundaries,unbound,controlPresent,historyPresent,checkpoint,covered)
      CaptureStartupGuard.classify(facts).foreach {reason=>startup.pause(reason);throw new CaptureAdmissionException(reason)}
      if(existing && controlPresent)validateRetainedControls()
      if(!controlPresent)withSourceConnection(c=>update(c,"CREATE TABLE IF NOT EXISTS pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL)"))
      snapshots.beforeStart()
      profileSequence=store.rememberProfile(JsObject("version"->JsNumber(1),"database"->JsString(source.database),
        "sourceLineage"->JsString(sourceUuid),"stateLineage"->JsString(query(owner,"SELECT @@server_uuid").head),
        "offsetTable"->JsString(state.offsetTable),"historyTable"->JsString(state.historyTable),
        "maxLogBytes"->JsNumber(store.maxBytes),"decoder"->JsString("debezium-3.6.1/capture-v1")).compactPrint)
      val props=MySQLCdcSourceNode.connectorProperties(source.copy(table="pekko_capture_control",groupProtocol=None),state)
      props.setProperty("snapshot.mode","no_data")
      props.setProperty("table.include.list",java.util.regex.Pattern.quote(source.database)+"\\..*")
      props.setProperty("include.schema.changes","true")
      props.setProperty("provide.transaction.metadata","true")
      props.setProperty("skipped.operations","none")
      props.setProperty("offset.flush.timeout.ms","2000")
      props.setProperty("max.queue.size.in.bytes",(16L*1024*1024).toString)
      props.setProperty("schema.history.internal.store.only.captured.databases.ddl","true")
      props.setProperty("heartbeat.interval.ms","1000")
      if(stopping.get())throw new CaptureAdmissionException("CAPTURE_STOPPING")
      engine=factory.create(props)
      val running=engine.start(new DebeziumBatchConsumer {def handleBatch(rs:Vector[SourceRecord],h:CdcBatchCommitHandle):Unit=accept(rs,h)})
      completion=running
      running.onComplete {
        case Failure(e)=>error.compareAndSet(null,e);startup.fail()
        case _ if !stopping.get()=>error.compareAndSet(null,new IllegalStateException("capture engine terminated unexpectedly"));startup.fail()
        case _=>()
      }(ExecutionContext.parasitic)
      running
    }catch {case e:Throwable=>
      e match {case a:CaptureAdmissionException=>startup.pause(a.code);case _=>startup.fail()}
      var problem=if(!store.releaseConfirmed)releaseUnknown("capture-store",e) else e
      if(owner!=null)try {owner.close();owner=null}catch {case close:Throwable=>
        problem=releaseUnknown("capture-start-owner-close",close);problem.addSuppressed(e)
      }
      error.compareAndSet(null,problem)
      throw problem
    }
  }
  private[source] def accept(records:Vector[SourceRecord],handle:CdcBatchCommitHandle):Unit={
    require(inside.compareAndSet(false,true),"concurrent shared callbacks unsupported")
    try {
      active();assertOwner()
      require(records.nonEmpty && pending.size<8,"retained callback budget exceeded")
      val bytes=records.iterator.map(r=>Option(r.value()).fold(0L)(_.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong)).sum
      require(pending.map(_.bytes).sum+bytes<=16L*1024*1024,"retained callback bytes exceeded")
      val p=Pending(nextCallback,MySQLCdcSourceNode.cursorValue(records.last),handle,records.size,bytes,mutable.Set(profileSequence))
      nextCallback+=1;pending.enqueue(p)
      observe("RAW_CALLBACK",JsObject("id"->JsNumber(p.id),"records"->JsNumber(records.size),"offset"->p.offset.parseJson))
      records.foreach {r=>
        require(r.sourcePartition().asScala.toMap==Map("server"->source.connectorId),"source partition changed")
        val done=()=>{p.remaining-=1;require(p.remaining>=0,"duplicate record coverage")}
        val v=r.value() match {case s:Struct=>Some(s);case null=>None;case _=>throw new IllegalArgumentException("unsupported source record")}
        val name=Option(r.valueSchema()).flatMap(s=>Option(s.name())).getOrElse("")
        if(v.isEmpty)done()
        else if(name=="io.debezium.connector.mysql.SchemaChangeValue") {p.references++=rememberSchemas(v.get);done()}
        else if(name=="io.debezium.connector.common.TransactionMetadataValue") {
          val value=v.get
          if(value.getString("status")=="END") markers.remove(value.getString("id")).foreach {m=>
            require(value.getInt64("event_count")==1L,"control marker requires its own transaction")
            val ordinary=m.action=="SUBSCRIBE" || m.action=="STOP"
            val barrier=if(ordinary) {
              val subscription=store.subscription(m.id).getOrElse(throw new IllegalArgumentException("marker without intent"))
              require(subscription.table==m.table && subscription.generation==m.generation,"marker intent conflict")
              store.bind(m.id,m.point,MySQLCdcSourceNode.cursorValue(r),m.action=="STOP")
              assemblers.remove(m.table).foreach(_.close())
              store.barrierSequence(m.id,m.action=="STOP")
            }else Some(store.bindFence(m.id,m.point,MySQLCdcSourceNode.cursorValue(r)))
            barrier.foreach(p.references.add)
            m.done(barrier.getOrElse(0L))
            observe(if(ordinary){if(m.action=="STOP")"K_BOUND"else "B_BOUND"}else m.action+"_BOUND",JsObject("requestId"->JsString(m.id),"point"->m.point.json.parseJson))
          }
          done()
        } else if(name=="io.debezium.connector.common.Heartbeat")done()
        else {
          val value=v.get;val src=value.getStruct("source")
          require(src.getString("db")==source.database,"unexpected captured database")
          val table=src.getString("table")
          if(table=="pekko_capture_control") {
            val e=MySQLCdcEnvelope.decode(r,source.connectorId).fold(x=>throw new IllegalArgumentException(x.message),_.get)
            require(value.getString("op")=="c","control markers are immutable inserts")
            val a=value.getStruct("after");val id=a.getString("operation_id")
            val action=a.getString("action")
            require(Set("SUBSCRIBE","STOP","LOW","HIGH","F").contains(action),"unknown control action")
            val intent=if(action=="SUBSCRIBE" || action=="STOP")store.markerIntent(id,a.getString("table_name"),a.getString("generation"),action=="STOP").map(s=>s.requestId)
              else store.fenceIntent(id,a.getString("table_name"),a.getString("generation")).map {case(f,_)=>require(f.action==action,"bootstrap action changed");f.id}
            intent match {
              case None=>done()
              case Some(intentId)=>
                val transaction=value.getStruct("transaction").getString("id")
                require(!markers.contains(transaction),"multiple markers in one transaction")
                markers(transaction)=Marker(intentId,a.getString("table_name"),a.getString("generation"),action,CapturePoint.from(e),n=>{if(n>0)p.references+=n;done()})
            }
          } else if(value.getString("op")=="t") {
            require(!store.hasActive(table),"TRUNCATE requires explicit reinitialization; capture paused")
            done()
          }
          else {
            val point=CapturePoint(src.getString("file"),src.getInt64("pos"),src.getInt32("row"))
            if(store.route(table,point).isEmpty)done()
            else {
              // Debezium transaction metadata increments its per-collection counter between PK halves.
              // It is delivery context, not part of the physical row-event restart identity.
              val normalized=r.sourceOffset().asScala.filterNot {case(k,_)=>k.startsWith("transaction_data_collection_order_")}.toMap.asJava
              val copy=new SourceRecord(r.sourcePartition(),normalized,r.topic(),r.kafkaPartition(),r.keySchema(),r.key(),r.valueSchema(),r.value(),r.timestamp(),r.headers())
              val envelope=MySQLCdcEnvelope.decode(copy,source.connectorId).fold(x=>throw new IllegalArgumentException(x.message),_.get)
              val groupId=MySQLCdcGroup.id(store.protocol,envelope)
              val assembler=assemblers.getOrElseUpdate(table,new MySQLCdcGroupAssembler(
                source.copy(table=table,groupProtocol=Some(store.protocol)),(groups,_,_)=>groups.foreach {g=>
                  val group=MySQLCdcGroup.parse(g,store.protocol)
                  groupCoverage(group.groupId)=store.append(group).getOrElse(throw new IllegalArgumentException("group lost its durable route"))
                },_=>()))
              assembler.accept(Vector(copy),new CdcBatchCommitHandle {def markProcessedAndFinished():Unit={p.references+=groupCoverage(groupId);done()}})
              groupCoverage.remove(groupId)
            }
          }
        }
      }
      while(pending.headOption.exists(_.remaining==0)) {
        val ready=pending.head;assertOwner();val ack=store.coverSequence(ready.offset,ready.references.toVector)
        observe("RAW_ACK_BEGIN",JsObject("id"->JsNumber(ready.id),"offset"->ready.offset.parseJson))
        ready.handle.markProcessedAndFinished()
        confirmedAckSequence=ack
        pending.dequeue();observe("RAW_ACK_RETURNED",JsObject("id"->JsNumber(ready.id)))
      }
    }catch {case e:Throwable=>
      val problem=if(!store.releaseConfirmed)releaseUnknown("capture-store",e)else e
      callbackFailed.set(true);error.compareAndSet(null,problem);throw problem
    }finally inside.set(false)
  }
  private def rememberSchemas(v:Struct):Vector[Long]={
    if(v.getString("databaseName")!=source.database)return Vector.empty
    val references=Vector.newBuilder[Long]
    val changes=v.getArray[Struct]("tableChanges").asScala
    changes.foreach {change=>
      val id=change.getString("id");val table=id.split("\\.").last.replace("\"","").replace("`","")
      if(change.getString("type")!="DROP") {
        val schema=store.rememberSchema(table,schemaJson(change.getStruct("table")).compactPrint)
        references+=store.schemaSequence(table,schema.id)
      }else if(Option(v.getStruct("source").get("snapshot")).forall(x=>x.toString=="false"))references+=store.dropSchema(table)
    }
    references.result()
  }
  private def schemaJson(value:Any):JsValue=value match {
    case null=>JsNull
    case s:Struct=>JsObject(s.schema().fields().asScala.map(f=>f.name()->schemaJson(s.get(f))).toMap)
    case s:String=>JsString(s)
    case b:java.lang.Boolean=>JsBoolean(b.booleanValue())
    case n:java.lang.Number=>JsNumber(BigDecimal(n.toString))
    case xs:java.util.List[_]=>JsArray(xs.asScala.toVector.map(schemaJson))
    case m:java.util.Map[_,_]=>JsObject(m.asScala.map {case(k,v)=>k.toString->schemaJson(v)}.toMap)
    case bytes:Array[Byte]=>JsString(java.util.Base64.getEncoder.encodeToString(bytes))
    case _=>throw new IllegalArgumentException("unsupported captured schema descriptor")
  }
  def subscribe(id:String,table:String,generation:String):CaptureSubscription=commandLock.synchronized {
    active();assertOwner()
    store.subscription(id).foreach {existing=>
      require(existing.table==table && existing.generation==generation,"subscription request conflict")
      return existing // Query/idempotency never rewrites an existing B, including B_PENDING.
    }
    requireAdmission()
    val schema=store.schema(table).getOrElse(throw new IllegalArgumentException("BLOCKED_SCHEMA: Reader schema not ready"))
    val expected=schema.definition.parseJson.asJsObject.fields
    withSourceConnection {c=>
      val cols=c.getMetaData.getColumns(source.database,null,table,null)
      val actual=Vector.newBuilder[(String,Int)]
      try {while(cols.next())if(cols.getString("TABLE_NAME")==table && cols.getString("TABLE_CAT")==source.database)actual+=cols.getString("COLUMN_NAME")->cols.getInt("DATA_TYPE")}finally cols.close()
      val wanted=expected("columns").asInstanceOf[JsArray].elements.map {x=>val f=x.asJsObject.fields;f("name").asInstanceOf[JsString].value->f("jdbcType").asInstanceOf[JsNumber].value.toInt}.toVector
      require(actual.result()==wanted,"BLOCKED_SCHEMA: JDBC and Reader schema differ")
      require(expected("primaryKeyColumnNames").asInstanceOf[JsArray].elements.size==1,"one primary key required")
      val keys=c.getMetaData.getPrimaryKeys(source.database,null,table)
      val actualKeys=Vector.newBuilder[(Int,String)]
      try {while(keys.next())if(keys.getString("TABLE_NAME")==table)actualKeys+=keys.getInt("KEY_SEQ")->keys.getString("COLUMN_NAME")}finally keys.close()
      require(actualKeys.result().sortBy(_._1).map(x=>JsString(x._2))==expected("primaryKeyColumnNames").asInstanceOf[JsArray].elements,"BLOCKED_SCHEMA: Reader and JDBC primary keys differ")
    }
    requireAdmission() // The admitted in-flight command may finish while close waits for commandLock.
    observe("SUBSCRIBE_ADMITTED",JsObject("requestId"->JsString(id)))
    val s=store.register(id,table,generation,schema.id)
    if(s.status=="B_PENDING")writeMarker(s,stop=false)
    s
  }
  def unsubscribe(id:String):Unit=commandLock.synchronized {active();assertOwner();val s=store.subscription(id).get
    require(s.status=="CAPTURE_READY" || s.status=="CAPTURE_STOPPED","subscription not ready to stop")
    if(s.status=="CAPTURE_READY"){requireAdmission();writeMarker(s,stop=true)}}
  def requestFence(id:String,subscriptionId:String,action:String,context:String):CaptureFence=commandLock.synchronized {
    active();assertOwner()
    store.fence(id).filter(_.sequence.nonEmpty).foreach {f=>
      require(f.subscriptionId==subscriptionId && f.action==action && f.context==MySQLCdcGroup.canonical(context.parseJson),"fence request conflict")
      return f
    }
    requireAdmission()
    val f=store.registerFence(id,subscriptionId,action,context)
    if(f.sequence.isEmpty) {
      val sub=store.subscription(subscriptionId).get
      writeControl(store.fenceOperation(id),action,sub.table,sub.generation)
    }
    f
  }
  private def writeMarker(s:CaptureSubscription,stop:Boolean):Unit=
    writeControl(store.markerId(s.requestId,stop),if(stop)"STOP"else "SUBSCRIBE",s.table,s.generation)
  private def writeControl(operation:String,action:String,table:String,generation:String):Unit={
    var stage="NOT_STARTED"
    try withSourceConnection {c=>
      stage="WRITING"
      c.setAutoCommit(false)
      val q=c.prepareStatement("INSERT INTO pekko_capture_control VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE operation_id=operation_id")
      q.setQueryTimeout(5)
      try {q.setString(1,operation);q.setString(2,action);q.setString(3,table);q.setString(4,generation);q.executeUpdate()}finally q.close()
      stage="COMMITTING";c.commit();stage="COMMITTED"
    }catch {case e:Throwable=>
      val code=if(stage=="COMMITTING")"MARKER_COMMIT_UNKNOWN" else if(stage=="COMMITTED")"MARKER_COMMITTED_RELEASE_UNKNOWN" else "MARKER_WRITE_UNPROVEN"
      val failure=releaseUnknown("source-marker:"+code,e);error.compareAndSet(null,failure);startup.pause(code);throw failure
    }
  }
  override def close():Unit=closeLock.synchronized {
    if(stopping.compareAndSet(false,true))observe("CAPTURE_STOP_REQUESTED",JsObject())
    startup.stop()
    if(closed.get()) {if(closeFailure!=null)throw closeFailure}
    else {
      try commandLock.synchronized {
    closed.set(true)
    // Release the owner connection only after the original Engine close is confirmed.
    // A callback failure starts SDK shutdown asynchronously; do not race a second stop request.
    if(callbackFailed.get() && completion!=null)scala.concurrent.Await.ready(completion,scala.concurrent.duration.Duration(5,"seconds"))
    if(engine!=null)engine.close()
    assemblers.values.foreach(_.close());assemblers.clear();pending.clear();markers.clear()
    if(owner!=null){owner.close();owner=null}
      }catch {case e:Throwable=>closeFailure=e;throw e}
    }
  }
}
