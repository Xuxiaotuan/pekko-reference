package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.{MySQLCdcGroup,MySQLCdcGroupProtocol}
import spray.json._

/** Reconstructs a capture GROUP from a retained, verified target ledger row.
  * This is an integrity codec, not a deletion permit or a target-retention guarantee.
  */
private[magic] object CaptureGroupTombstone {
  val Prefix="pekko:tombstone:v1:"
  /** Logical payload saving only; database page/file reclamation is a separate measurement. */
  def worthwhile(payload:String,tombstone:String,minimumSaving:Int):Boolean={
    require(minimumSaving>=128,"compaction minimum saving must be at least 128 bytes")
    payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong-
      (Prefix+tombstone).getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong>=minimumSaving
  }
  private val fields=Vector("capture_id","seq","source_key","kind","table_name","subscription_id","schema_id","digest","point_json","resume_json")
  private def digest(row:Map[String,String],payload:String):String=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsArray(
    Vector(row("kind"),row("table_name"),row("subscription_id"),row("schema_id"),payload,Option(row("point_json")).getOrElse("")).map(JsString(_)))))
  private def ledger(evidence:String,targetTable:String,identity:String,protocol:MySQLCdcGroupProtocol):MySQLCdcGroup={
    val f=evidence.parseJson.asJsObject.fields
    require(f.keySet==Set("identity","targetTable","group") && f("targetTable")==JsString(targetTable) &&
      MySQLCdcGroup.canonical(f("identity"))==MySQLCdcGroup.canonical(identity.parseJson),"target ledger identity or table conflict")
    MySQLCdcGroup.parse(MySQLCdcGroup.canonical(f("group")),protocol)
  }
  def seal(row:Map[String,String],capture:MySQLCdcGroupProtocol,generation:String,
    targetTable:String,identity:String,evidence:String):String={
    require(fields.forall(row.contains) && row.get("payload").exists(_!=null) && row("kind")=="GROUP" &&
      row("capture_id")==capture.replicationId && generation.nonEmpty && targetTable.nonEmpty,"invalid capture GROUP frame")
    val original=MySQLCdcGroup.parse(row("payload"),capture)
    require(row("source_key")==MySQLCdcGroup.hash("group:"+original.groupId) &&
      row("table_name")==original.members.head._2.source.table && row("point_json")==CapturePoint.from(original.members.head._2).json &&
      row("digest")==digest(row,row("payload")),"capture GROUP frame identity or digest conflict")
    val targetProtocol=MySQLCdcGroupProtocol(row("subscription_id"),generation,capture.sourceLineage)
    val target=ledger(evidence,targetTable,identity,targetProtocol)
    val expected=MySQLCdcGroup(MySQLCdcGroup.id(targetProtocol,original.members.head._2),original.members)
    require(target.canonicalJson==expected.canonicalJson,"target ledger is not a reversible copy of the capture GROUP")
    // Frame metadata remains in L; only the payload column is replaced by this compact value.
    MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"generation"->JsString(generation),
      "captureGroup"->JsString(original.groupId),"targetGroup"->JsString(target.groupId),
      "payloadHash"->JsString(MySQLCdcGroup.hash(row("payload"))),"targetTable"->JsString(targetTable),
      "identityHash"->JsString(MySQLCdcGroup.hash(MySQLCdcGroup.canonical(identity.parseJson)))))
  }
  def recover(row:Map[String,String],tombstone:String,capture:MySQLCdcGroupProtocol,targetTable:String,identity:String,evidence:String):String={
    val t=tombstone.parseJson.asJsObject.fields
    require(fields.forall(row.contains) && t.keySet==Set("version","generation","captureGroup","targetGroup","payloadHash","targetTable","identityHash") &&
      t("version")==JsNumber(1) && t("targetTable")==JsString(targetTable) &&
      t("identityHash")==JsString(MySQLCdcGroup.hash(MySQLCdcGroup.canonical(identity.parseJson))),"tombstone identity conflict")
    val generation=t("generation").asInstanceOf[JsString].value
    val target=ledger(evidence,targetTable,identity,MySQLCdcGroupProtocol(row("subscription_id"),generation,capture.sourceLineage))
    require(t("targetGroup")==JsString(target.groupId),"target group changed")
    val restored=MySQLCdcGroup(MySQLCdcGroup.id(capture,target.members.head._2),target.members).canonicalJson
    require(t("captureGroup")==JsString(MySQLCdcGroup.parse(restored,capture).groupId) &&
      t("payloadHash")==JsString(MySQLCdcGroup.hash(restored)) && row("digest")==digest(row,restored),"reconstructed capture payload conflict")
    require(seal(row+("payload"->restored),capture,generation,targetTable,identity,evidence)==MySQLCdcGroup.canonical(tombstone.parseJson),"tombstone binding conflict")
    restored
  }
}
