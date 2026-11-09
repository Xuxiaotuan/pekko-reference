package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node,Position}
import spray.json._

/** Compatibility boundary for the existing workflow-based MySQL sink. */
private[cdc] object CdcTargetNodeAdapter {
  def forTarget(settings:CdcCaptureSettings,table:String):Node =
    Node("target","sink","mysql.cdc.apply","CDC platform target",Position(0,0),JsObject(
      "host"->JsString(settings.targetHost),"port"->JsNumber(settings.targetPort),
      "database"->JsString(settings.targetDatabase),"table"->JsString(table),
      "username"->JsString(settings.controlState.username),"password"->JsString(settings.controlState.password)))
}
