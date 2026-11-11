package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.model.{CdcNode,CdcNodeAdapter,CdcPosition}
import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL.Node
import spray.json._

/** Compatibility boundary for the existing workflow-based MySQL sink. */
private[cdc] object CdcTargetNodeAdapter {
  def forTarget(settings:CdcCaptureSettings,table:String):Node = {
    val cdcNode = CdcNode("target","sink","mysql.cdc.apply","CDC platform target",CdcPosition(0,0),JsObject(
      "host"->JsString(settings.targetHost),"port"->JsNumber(settings.targetPort),
      "database"->JsString(settings.targetDatabase),"table"->JsString(table),
      "username"->JsString(settings.controlState.username),"password"->JsString(settings.controlState.password)))
    CdcNodeAdapter.toLegacy(cdcNode)
  }
}
