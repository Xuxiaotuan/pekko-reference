package cn.xuyinyin.magic.workflow.scheduling

/** Keep operator commands compatible while the implementations live in CDC control. */
object SnapshotGuardianMain {
  def main(args:Array[String]):Unit=cn.xuyinyin.magic.cdc.control.SnapshotGuardianMain.main(args)
}

object SnapshotSupervisorMain {
  def main(args:Array[String]):Unit=cn.xuyinyin.magic.cdc.control.SnapshotSupervisorMain.main(args)
}
