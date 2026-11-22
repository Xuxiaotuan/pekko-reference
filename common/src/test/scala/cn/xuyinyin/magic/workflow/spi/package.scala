package cn.xuyinyin.magic.workflow

/**
 * Source compatibility for historical Workflow callers.
 * New CDC code imports cn.xuyinyin.magic.cdc.compat.nodes directly.
 */
package object spi {
  type BatchSourceSession = cn.xuyinyin.magic.cdc.compat.nodes.BatchSourceSession
  val BatchSourceSession = cn.xuyinyin.magic.cdc.compat.nodes.BatchSourceSession
  type CheckpointedNodeSource = cn.xuyinyin.magic.cdc.compat.nodes.CheckpointedNodeSource
  type CheckpointedNodeSink = cn.xuyinyin.magic.cdc.compat.nodes.CheckpointedNodeSink
  type NodeSource = cn.xuyinyin.magic.cdc.compat.nodes.NodeSource
  type NodeSink = cn.xuyinyin.magic.cdc.compat.nodes.NodeSink
  type NodeTransform = cn.xuyinyin.magic.cdc.compat.nodes.NodeTransform
  type SnapshotSplitSink = cn.xuyinyin.magic.cdc.compat.nodes.SnapshotSplitSink
  type SnapshotSplitSource = cn.xuyinyin.magic.cdc.compat.nodes.SnapshotSplitSource
  type SourceAcquisitionCleanupFailed = cn.xuyinyin.magic.cdc.compat.nodes.SourceAcquisitionCleanupFailed
  type SourceResourceScope = cn.xuyinyin.magic.cdc.compat.nodes.SourceResourceScope
}
