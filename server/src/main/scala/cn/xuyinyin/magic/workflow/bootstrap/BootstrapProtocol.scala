package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import spray.json._
import scala.collection.mutable

object BootstrapPhases {
  private val edges=Map("QUEUED"->Set("SCANNING"),"SCANNING"->Set("REPLAYING"),
    "REPLAYING"->Set("ACTIVATING"),"ACTIVATING"->Set("STREAMING"),"STREAMING"->Set.empty[String],
    "CANCELING"->Set("CANCELED"),"CANCELED"->Set.empty[String],"PAUSED"->Set.empty[String])
  def allowed(from:String,to:String):Boolean=edges.contains(from) && (from==to ||
    (from!="CANCELED" && to=="PAUSED") || (from!="CANCELED" && from!="PAUSED" && to=="CANCELING") || edges(from).contains(to))
}

/** Bounded logical contents, not a promise about total JVM heap. Never marks source groups applied. */
final class BootstrapFold(lower:Option[String],upper:String,maxBytes:Long,primaryKey:String="id") {
  private val rows=mutable.TreeMap.empty[BigDecimal,JsObject]
  private var bytes=0L
  private def key(row:JsObject):BigDecimal=row.fields(primaryKey) match {
    case JsNumber(n)=>n
    case JsString(n)=>BigDecimal(n)
    case _=>throw new IllegalArgumentException("invalid bootstrap key")
  }
  private def within(k:BigDecimal)=lower.forall(k>BigDecimal(_)) && k<=BigDecimal(upper)
  private def replace(k:BigDecimal,value:Option[JsObject]):Unit={
    rows.remove(k).foreach(r=>bytes-=MySQLCdcGroup.canonical(r).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
    value.foreach {r=>val n=MySQLCdcGroup.canonical(r).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      require(bytes+n<=maxBytes,"bootstrap window byte budget exhausted");rows(k)=r;bytes+=n}
  }
  def snapshot(values:Vector[JsObject]):Unit=values.foreach(r=>{val k=key(r);require(within(k),"snapshot outside window");replace(k,Some(r))})
  def event(op:String,k:JsObject,after:Option[JsObject]):Unit={
    val n=key(k)
    if(within(n))op match {
      case "d"=>replace(n,None)
      case "c"|"u"=>val value=after.getOrElse(throw new IllegalArgumentException("missing full row image"));require(key(value)==n);replace(n,Some(value))
      case _=>throw new IllegalArgumentException("unsupported bootstrap event")
    }
  }
  def result:Vector[JsObject]=rows.values.toVector
}

/** New persisted type. Existing workflow/snapshot payloads are deliberately unchanged. */
final case class BootstrapProgress(
  revision:Long=0L,phase:String="QUEUED",definition:String="",executionId:String="",
  upper:Option[String]=None,planned:Boolean=false,nextLower:Option[String]=None,index:Long=0L,
  attempt:Int=0,window:Option[String]=None,manifest:Option[String]=None,appliedPage:Int=0,
  fence:Option[String]=None,deltaAfter:Long=0L,batchSequence:Long=0L,
  completionRoot:String="",recoveryAttempts:Int=0,cancelRequested:Boolean=false,failure:Option[String]=None
) extends CborSerializable
