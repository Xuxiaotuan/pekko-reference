package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.{MySQLCdcGroup,MySQLCdcGroupProtocol}
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.spi.{NodeSource,CheckpointedNodeSource,BatchSourceSession}
import org.apache.pekko.{Done,NotUsed}
import org.apache.pekko.stream.scaladsl.Source
import scala.concurrent.{ExecutionContext,Future}
import scala.concurrent.duration._
import spray.json._

/** Bounded, independently checkpointed application of L. Capturing is owned elsewhere.
  * Phase 1 only permits operator-established target baselines; no online bootstrap claim.
  */
final class CapturedLogSource(store:MySQLCaptureStore,database:String) extends NodeSource with CheckpointedNodeSource {
  val nodeType="mysql.cdc.log"
  private val kind="mysql.capture.groups.v1"
  private def request(node:WorkflowDSL.Node):String={
    require(node.config.fields.get("initializedTarget").contains(JsBoolean(true)),"trusted target baseline required; capture-ready is not initialized")
    node.config.fields("subscriptionId").asInstanceOf[JsString].value
  }
  private def identity(id:String):String={
    val s=store.subscription(id).getOrElse(throw new IllegalArgumentException("unknown capture subscription"));require(s.b.nonEmpty,"subscription not capture-ready")
    val schema=store.schemaVersion(s.table,s.schemaId)
    val f=schema.definition.parseJson.asJsObject.fields
    val pk=f("primaryKeyColumnNames").asInstanceOf[JsArray].elements
    require(pk.size==1,"one primary key required")
    val columns=JsArray(f("columns").asInstanceOf[JsArray].elements.map(c=>JsObject(c.asJsObject.fields.filter {case(k,_)=>Set("name","jdbcType","typeName").contains(k)})))
    MySQLCdcGroup.canonical(JsObject("version"->JsNumber(2),"connectorId"->JsString(store.captureId),"database"->JsString(database),
      "table"->JsString(s.table),"primaryKey"->pk.head,"columns"->columns,"schemaFingerprint"->JsString(s.schemaId),
      "groupProtocol"->MySQLCdcGroupProtocol(id,s.generation,store.lineage).json))
  }
  def resumePosition(id:String,cursor:SourceCursor):Long={
    require(cursor.kind==kind && cursor.upperBound==identity(id),"capture checkpoint identity mismatch")
    val f=cursor.value.parseJson.asJsObject.fields
    require(f("captureId")==JsString(store.captureId) && f("subscriptionId")==JsString(id) && f("version")==JsNumber(1),"capture cursor namespace mismatch")
    val n=f("sequence").asInstanceOf[JsNumber].value.toLongExact;require(n>0);store.validatePosition(id,n);n
  }
  def readBatch(id:String,after:Long,limit:Int,sourceNodeId:String,executionId:String,sequence:Long):Option[SourceBatch]={
    val sub=store.subscription(id).get;val entries=store.read(id,after,limit)
    if(entries.isEmpty)None else {
      val protocol=MySQLCdcGroupProtocol(id,sub.generation,store.lineage)
      val groups=entries.map {r=>require(r.schemaId==sub.schemaId,"capture row schema mismatch")
        MySQLCdcGroup(MySQLCdcGroup.id(protocol,r.group.members.head._2),r.group.members).canonicalJson}
      val cursor=SourceCursor(kind,MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"captureId"->JsString(store.captureId),
        "subscriptionId"->JsString(id),"sequence"->JsNumber(entries.last.sequence))),identity(id))
      val part=s"mysql-cdc:${store.captureId}"
      Some(SourceBatch(sourceNodeId,part,sequence,BatchId.sha256(executionId,sourceNodeId,part,sequence),cursor,groups))
    }
  }
  override def createSource(node:WorkflowDSL.Node,onLog:String=>Unit):Source[String,NotUsed]=
    throw new UnsupportedOperationException("capture log requires checkpoint execution")
  override def discoverBoundary(node:WorkflowDSL.Node,resume:Option[BatchCheckpoint],onLog:String=>Unit)(implicit ec:ExecutionContext):Future[SnapshotBoundary]=Future {
    val id=request(node);val bound=SnapshotBoundary(node.id,s"mysql-cdc:${store.captureId}",Some(identity(id)))
    resume.foreach {c=>require(c.sourceNodeId==node.id && c.partitionId==bound.partitionId);resumePosition(id,c.cursor)}
    bound
  }
  override def createBatches(node:WorkflowDSL.Node,executionId:String,boundary:SnapshotBoundary,resume:Option[BatchCheckpoint],onLog:String=>Unit)(implicit ec:ExecutionContext):Source[SourceBatch,NotUsed]=
    session(node,executionId,boundary,resume).source
  override def openBatches(node:WorkflowDSL.Node,executionId:String,boundary:SnapshotBoundary,resume:Option[BatchCheckpoint],onLog:String=>Unit,closeEc:ExecutionContext)(implicit ec:ExecutionContext):BatchSourceSession=
    session(node,executionId,boundary,resume)
  private def session(node:WorkflowDSL.Node,executionId:String,boundary:SnapshotBoundary,resume:Option[BatchCheckpoint])(implicit ec:ExecutionContext):BatchSourceSession={
    val id=request(node);require(boundary.upperBound.contains(identity(id)) && boundary.sourceNodeId==node.id && boundary.partitionId==s"mysql-cdc:${store.captureId}","capture boundary mismatch")
    var after=resume.map(c=>resumePosition(id,c.cursor)).getOrElse(0L)
    var sequence=resume.map(_.batchSequence+1).getOrElse(0L)
    val limit=node.config.fields.get("batchSize").map(_.asInstanceOf[JsNumber].value.toIntExact).getOrElse(128)
    require(limit>0 && limit<=1000)
    val lock=new AnyRef;var closed=false;var reading:Future[Option[SourceBatch]]=Future.successful(None)
    val source=Source.tick(Duration.Zero,100.millis,()).mapAsync(1) {_=>lock.synchronized {
      reading=if(closed)Future.successful(None)else Future(readBatch(id,after,limit,node.id,executionId,sequence))
      reading
    }}.mapConcat {batch=>batch.foreach {b=>after=resumePosition(id,b.cursor);sequence+=1};batch.toList}.mapMaterializedValue(_=>NotUsed)
    BatchSourceSession(source,()=>lock.synchronized {closed=true;reading.map(_=>Done)(ExecutionContext.parasitic)})
  }
}
