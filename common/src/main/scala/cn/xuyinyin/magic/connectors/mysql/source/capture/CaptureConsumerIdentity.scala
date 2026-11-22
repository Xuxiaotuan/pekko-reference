package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import spray.json._

private[magic] object CaptureConsumerIdentity {
  def bootstrap(capture:String,subscription:String,generation:String):String=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject(
    "kind"->JsString("bootstrap"),"capture"->JsString(capture),"subscription"->JsString(subscription),"generation"->JsString(generation))))
  def workflow(capture:String,subscription:String,node:String,execution:String):String=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsObject(
    "capture"->JsString(capture),"subscription"->JsString(subscription),"node"->JsString(node),"execution"->JsString(execution))))
}
