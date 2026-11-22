package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.{MySQLCdcGroup,MySQLCdcGroupProtocol}
import spray.json._

/** Runtime-only CDC values. Persisted checkpoint types remain in their original package. */
final case class CdcCaptureCursor(kind:String,value:String,upperBound:String)
final case class CdcCapturedBatch(sourceNodeId:String,partitionId:String,batchSequence:Long,batchId:String,
  cursor:CdcCaptureCursor,rows:Vector[String])

/** Bounded reads from L without depending on the workflow node or execution SPI. */
final class CapturedLogReader(store:MySQLCaptureStore,database:String) {
  private val kind="mysql.capture.groups.v1"
  def identity(id:String):String={
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
  def resumePosition(id:String,cursor:CdcCaptureCursor):Long={
    require(cursor.kind==kind && cursor.upperBound==identity(id),"capture checkpoint identity mismatch")
    val f=cursor.value.parseJson.asJsObject.fields
    require(f("captureId")==JsString(store.captureId) && f("subscriptionId")==JsString(id) && f("version")==JsNumber(1),"capture cursor namespace mismatch")
    val n=f("sequence").asInstanceOf[JsNumber].value.toLongExact;require(n>0);store.validatePosition(id,n);n
  }
  def readBatch(id:String,after:Long,limit:Int,sourceNodeId:String,executionId:String,sequence:Long,consumer:Option[String]=None):Option[CdcCapturedBatch]={
    val sub=store.subscription(id).get;val entries=consumer.map(store.readForConsumer(id,after,limit,_)).getOrElse(store.read(id,after,limit))
    if(entries.isEmpty)None else {
      val protocol=MySQLCdcGroupProtocol(id,sub.generation,store.lineage)
      val groups=entries.map {r=>require(r.schemaId==sub.schemaId,"capture row schema mismatch")
        MySQLCdcGroup(MySQLCdcGroup.id(protocol,r.group.members.head._2),r.group.members).canonicalJson}
      val cursor=CdcCaptureCursor(kind,MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"captureId"->JsString(store.captureId),
        "subscriptionId"->JsString(id),"sequence"->JsNumber(entries.last.sequence))),identity(id))
      val part=s"mysql-cdc:${store.captureId}"
      val batchId=MySQLCdcGroup.hash(s"$executionId|$sourceNodeId|$part|$sequence")
      Some(CdcCapturedBatch(sourceNodeId,part,sequence,batchId,cursor,groups))
    }
  }
}
