package cn.xuyinyin.magic.cdc.savepoint

import cn.xuyinyin.magic.cdc.platform.CdcTaskStore
import cn.xuyinyin.magic.connectors.mysql.source.capture.SharedMySQLCapture

/** Coordinates only the Runtime Savepoint boundary; table work remains in the existing scheduler. */
final class CdcSavepointCoordinator(
    tasks: CdcTaskStore,
    manifests: CdcSavepointStore,
    reader: SharedMySQLCapture,
    sourceLineage: String,
    ownerEpoch: String,
    pageSize: Int = 256) {
  require(pageSize > 0 && pageSize <= 1024 && sourceLineage.nonEmpty && ownerEpoch.nonEmpty)

  def create(savepointId: String): CdcSavepointHeader = synchronized {
    reader.beginSavepoint()
    try {
      val count    = tasks.savepointTaskCount(reader.store.captureId)
      val pages    = if (count == 0) 0 else (count + pageSize - 1) / pageSize
      val tableSet = tasks.savepointTableSetDigest(reader.store.captureId)
      val epoch    = reader.sealCaptureEpoch()
      val header   = CdcSavepointHeader(savepointId, epoch.captureId, sourceLineage, epoch, tableSet, pages, ownerEpoch, 1, "CREATING", None)
      manifests.create(header)
      var after: Option[(String, String)] = None
      var page                            = 0
      while (page < pages) {
        val result = tasks.savepointTaskPage(reader.store.captureId, after, pageSize)
        require(result.items.nonEmpty && result.items.size <= pageSize, "SAVEPOINT_TASK_PAGE_INVALID")
        manifests.appendPage(CdcSavepointPage(savepointId, page, result.items, CdcSavepointDigest.page(savepointId, page, result.items)))
        after = result.nextAfter; page += 1
      }
      manifests.seal(savepointId)
    } catch {
      case e: Throwable =>
        try manifests.fail(savepointId)
        catch { case _: Throwable => () }; throw e
    } finally reader.endSavepoint()
  }

  def restore(savepointId: String): Option[(CdcSavepointHeader, Vector[TableCheckpointRef])] = manifests.readSealed(savepointId)
}
