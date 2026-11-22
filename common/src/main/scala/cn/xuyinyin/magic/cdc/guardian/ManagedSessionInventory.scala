package cn.xuyinyin.magic.cdc.guardian

/** Read-only owner inventory consumed by the database drain observer. */
trait ManagedSessionInventory {
  def databaseUsers(owner: String, endpoint: String): Set[String]
  def knownSessions(owner: String, endpoint: String): Vector[Long]
}
