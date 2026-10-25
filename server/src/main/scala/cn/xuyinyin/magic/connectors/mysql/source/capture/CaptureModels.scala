package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.{MySQLCdcEnvelope, MySQLCdcGroup, MySQLCdcGroupProtocol}
import spray.json._

/** Source occurrence, not the opaque SDK resume offset or a Journal cursor. */
final case class CapturePoint(file: String, position: Long, row: Int) {
  require(file.matches(".+\\.[0-9]+") && position >= 0 && row >= 0, "invalid source occurrence")
  def after(other: CapturePoint): Boolean = {
    val a=file.lastIndexOf('.'); val b=other.file.lastIndexOf('.')
    require(file.take(a)==other.file.take(b), "incomparable binlog namespaces")
    val f=BigInt(file.drop(a+1)).compare(BigInt(other.file.drop(b+1)))
    f>0 || (f==0 && (position>other.position || (position==other.position && row>other.row)))
  }
  def json: String = MySQLCdcGroup.canonical(JsObject("file"->JsString(file),"position"->JsNumber(position),"row"->JsNumber(row)))
}
object CapturePoint {
  def parse(s:String):CapturePoint={val f=s.parseJson.asJsObject.fields
    CapturePoint(f("file").asInstanceOf[JsString].value,f("position").asInstanceOf[JsNumber].value.toLongExact,f("row").asInstanceOf[JsNumber].value.toIntExact)}
  def from(e:MySQLCdcEnvelope):CapturePoint=CapturePoint(e.source.file.get,e.source.position.get,e.source.row.get)
}
final case class CaptureSubscription(requestId:String,table:String,generation:String,status:String,
  schemaId:String,b:Option[CapturePoint],k:Option[CapturePoint],bSequence:Option[Long]) {
  def accepts(p:CapturePoint):Boolean=b.exists(p.after) && k.forall(end=> !p.after(end))
  // Capturing changes is deliberately not evidence of a completed target initialization.
  def initialized:Boolean=false
}
final case class CaptureSchema(table:String,id:String,definition:String)
final case class CaptureLogEntry(sequence:Long,subscriptionId:String,schemaId:String,group:MySQLCdcGroup)
