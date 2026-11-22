package cn.xuyinyin.magic.cdc.persistence

import cn.xuyinyin.magic.cdc.compat.LegacyJournalReceiptAdapter
import cn.xuyinyin.magic.cdc.checkpoint.BatchCheckpoint
import cn.xuyinyin.magic.cdc.compat.WorkflowEvents.ExecutionCheckpointAdvanced
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.serialization.SerializationExtension

import java.sql.Connection

/** Final verifier: callers cannot replace the same-transaction Journal check with a no-op implementation. */
final class CdcJournalProgressVerifier private (uuid: String, database: String, system: ActorSystem[_], private[magic] val projectionConnection:()=>Connection) {
  private val serialization = SerializationExtension(system.classicSystem)

  def verifyOn(c: Connection, receipt: JournalCheckpointReceipt,
    workflowId: String, executionId: String, checkpoint: BatchCheckpoint): Unit = {
    require(LegacyJournalReceiptAdapter.isIssued(receipt) && !c.getAutoCommit && c.getTransactionIsolation == Connection.TRANSACTION_REPEATABLE_READ &&
      receipt.persistenceId == "workflow-" + workflowId && receipt.sequenceNr > 0 && workflowId.nonEmpty &&
      receipt.executionId == executionId && executionId.nonEmpty && receipt.checkpoint == checkpoint,
      "CDC Journal receipt identity conflict")
    JournalEventProof.verifyOn(c, uuid, database, receipt.persistenceId, receipt.sequenceNr) { (payload, serializerId, manifest) =>
      serialization.deserialize(payload, serializerId, manifest).get match {
        case event: ExecutionCheckpointAdvanced => event.executionId == executionId && event.checkpoint == checkpoint
        case _ => throw new IllegalArgumentException("unexpected CDC Journal event")
      }
    }
  }
}

private[magic] object CdcJournalProgressVerifier {
  def create(system: ActorSystem[_], openState: () => Connection, openJournal: () => Connection): CdcJournalProgressVerifier = {
    val cfg = system.settings.config
    require(cfg.getString("pekko.persistence.journal.plugin") == "jdbc-journal" &&
      cfg.getString("jdbc-journal.use-shared-db") == "slick", "unsupported Journal plugin")
    val declared = cfg.getString("jdbc-journal.tables.event_journal.schemaName")
    def identity(c: Connection): (String, String) = {
      val q = c.createStatement(); q.setQueryTimeout(5)
      try { val r = q.executeQuery("SELECT @@server_uuid,DATABASE()")
        try { require(r.next()); (r.getString(1), r.getString(2)) } finally r.close()
      } finally q.close()
    }
    val state = openState()
    try {
      val journal = openJournal()
      try {
        val actual = identity(state)
        require(actual._1 != null && actual._2 != null && identity(journal) == actual &&
          (declared.isEmpty || declared == actual._2), "CDC Journal and capture state differ")
        val q = state.prepareStatement("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='event_journal'")
        try { val r = q.executeQuery(); try require(r.next() && r.getString(1) == "InnoDB" && !r.next(), "CDC Journal table is not InnoDB") finally r.close() }
        finally q.close()
        val pk = state.prepareStatement("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='event_journal' AND index_name='PRIMARY' ORDER BY seq_in_index")
        try { val r = pk.executeQuery(); val b = Vector.newBuilder[String]; try while (r.next()) b += r.getString(1) finally r.close(); require(b.result() == Vector("persistence_id", "sequence_number"), "CDC Journal primary key changed") }
        finally pk.close()
        new CdcJournalProgressVerifier(actual._1, actual._2, system, openState)
      } finally journal.close()
    } finally state.close()
  }
}
