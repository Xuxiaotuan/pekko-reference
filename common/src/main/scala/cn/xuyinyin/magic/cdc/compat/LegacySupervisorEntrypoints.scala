package cn.xuyinyin.magic.workflow.scheduling

import cn.xuyinyin.magic.cdc.control.{SnapshotGuardianMain => CdcSnapshotGuardianMain, SnapshotSupervisorMain => CdcSnapshotSupervisorMain}

/** Keep operator commands compatible while the implementations live in CDC control. */
object SnapshotGuardianMain {
  def main(args: Array[String]): Unit = CdcSnapshotGuardianMain.main(args)
}

object SnapshotSupervisorMain {
  def main(args: Array[String]): Unit = CdcSnapshotSupervisorMain.main(args)
}
