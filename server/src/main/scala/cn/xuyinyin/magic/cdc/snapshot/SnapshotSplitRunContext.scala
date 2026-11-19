package cn.xuyinyin.magic.cdc.snapshot

import scala.concurrent.Future

/** Runtime callbacks; state itself remains owned by the persistent journal. */
final case class SnapshotSplitRunContext(
  read: () => Future[Option[cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState]],
  update: cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitUpdate => Future[cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState]
)
