package cn.xuyinyin.magic.cdc.control

/** Shared capacity policy for managed CDC and legacy quota migration. */
final case class SchedulingLimits(maxTables: Int, maxTablesPerNode: Int, maxConnectionsPerInstance: Int,
  maxStarts: Int, maxPageReservation: Long, candidatePage: Int) {
  require(maxTables > 0 && maxTablesPerNode > 0 && maxConnectionsPerInstance > 0 && maxStarts > 0 && maxPageReservation > 0)
  require(candidatePage > 0 && candidatePage <= 1000)
}
