package cn.xuyinyin.magic.cdc.model

import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL

/** Explicit boundary for old persisted Workflow node values. */
object CdcNodeAdapter {
  def fromLegacy(node: WorkflowDSL.Node): CdcNode =
    CdcNode(node.id, node.`type`, node.nodeType, node.label,
      CdcPosition(node.position.x, node.position.y), node.config, node.style)

  def toLegacy(node: CdcNode): WorkflowDSL.Node =
    WorkflowDSL.Node(node.id, node.kind, node.nodeType, node.label,
      WorkflowDSL.Position(node.position.x, node.position.y), node.config, node.style)
}
