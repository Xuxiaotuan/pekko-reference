package cn.xuyinyin.magic.cdc.model

import spray.json.JsObject

/** CDC runtime node definition, independent from the historical Workflow graph. */
final case class CdcNode(
  id: String,
  kind: String,
  nodeType: String,
  label: String,
  position: CdcPosition,
  config: JsObject,
  style: Option[JsObject] = None
)

final case class CdcPosition(x: Double, y: Double)
