package cn.xuyinyin.magic.cdc.control

import cn.xuyinyin.magic.cdc.guardian.SnapshotSupervisorSettings

/** Historical scheduling probes explicitly assemble their legacy store in test code. */
object LegacySupervisorHarness {
  def tableStore(settings:SnapshotSupervisorSettings):TableSchedulingStore=
    new TableSchedulingStore(settings.dataSource,settings.limits,ownershipEnabled=true)
}
