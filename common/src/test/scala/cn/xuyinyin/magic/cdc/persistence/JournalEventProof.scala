package cn.xuyinyin.magic.cdc.persistence

import java.sql.Connection

/** Read and lock a journal suffix on the caller's transaction; this class cannot advance an L floor. */
private[persistence] object JournalEventProof {
  def verifyOn(c: Connection, uuid: String, database: String, persistenceId: String, sequenceNr: Long)
    (matches: (Array[Byte], Int, String) => Boolean): Unit = {
    require(!c.getAutoCommit && c.getTransactionIsolation == Connection.TRANSACTION_REPEATABLE_READ && sequenceNr > 0,
      "CDC Journal transaction is not repeatable read")
    val id = c.createStatement(); id.setQueryTimeout(5)
    try {
      val r = id.executeQuery("SELECT @@server_uuid,DATABASE()")
      try require(r.next() && r.getString(1) == uuid && r.getString(2) == database && !r.next(), "CDC Journal database changed")
      finally r.close()
    } finally id.close()
    val q = c.prepareStatement("SELECT sequence_number,deleted,event_payload,event_ser_id,event_ser_manifest FROM event_journal WHERE persistence_id=? AND sequence_number>=? ORDER BY sequence_number LIMIT 2 FOR UPDATE")
    q.setQueryTimeout(5); q.setString(1, persistenceId); q.setLong(2, sequenceNr)
    try {
      val r = q.executeQuery()
      try {
        require(r.next() && r.getLong(1) == sequenceNr && !r.getBoolean(2), "CDC Journal event missing or deleted")
        require(matches(r.getBytes(3), r.getInt(4), r.getString(5)) && !r.next(), "CDC Journal progress changed")
      } finally r.close()
    } finally q.close()
  }
}
