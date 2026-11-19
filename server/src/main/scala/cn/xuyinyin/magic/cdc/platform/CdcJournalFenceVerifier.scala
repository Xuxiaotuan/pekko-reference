package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.bootstrap.BootstrapJournal
import cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import java.sql.Connection

/** Constructed only after comparing the actual Journal and control JDBC connections. */
private[cdc] final class CdcJournalFenceVerifier private (uuid:String,database:String,system:ActorSystem[_]) {
  private val serialization=SerializationExtension(system.classicSystem)
  private def identity(c:Connection):(String,String)={
    val q=c.createStatement();q.setQueryTimeout(5)
    try {val r=q.executeQuery("SELECT @@server_uuid,DATABASE()");try {require(r.next());(r.getString(1),r.getString(2))}finally r.close()}finally q.close()
  }
  /** Locks the matching key and its following range in the caller's state transaction. */
  def verifyOn(c:Connection,e:BootstrapJournal.PersistedRecoveryReceipt):Unit={
    require(BootstrapJournal.isActorIssuedRecovery(e) && !c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_REPEATABLE_READ &&
      identity(c)==(uuid,database),"journal fence requires the pinned MySQL transaction")
    require(e.sequenceNr>0 && e.phase=="RECOVERY_PENDING","invalid journal receipt sequence")
    val q=c.prepareStatement("SELECT sequence_number,deleted,event_payload,event_ser_id,event_ser_manifest FROM event_journal WHERE persistence_id=? AND sequence_number>=? ORDER BY sequence_number FOR UPDATE")
    q.setQueryTimeout(5);q.setString(1,e.persistenceId);q.setLong(2,e.sequenceNr)
    try {
      val r=q.executeQuery()
      try {
        require(r.next() && r.getLong(1)==e.sequenceNr && !r.getBoolean(2),"latest persisted journal event missing or deleted")
        val event=serialization.deserialize(r.getBytes(3),r.getInt(4),r.getString(5)).get match {
          case value:BootstrapJournal.Advanced=>value
          case _=>throw new IllegalArgumentException("unexpected journal event type")
        }
        val s=event.state
        require(s.revision==e.revision && s.phase==e.phase && s.executionId==e.executionId && !s.cancelRequested &&
          s.recoveryPermit.contains(e.permitId) && s.recoveryReceiptDigest.contains(e.receiptDigest),"persisted journal receipt mismatch")
        require(!r.next(),"journal has a newer persisted event")
      } finally r.close()
    } finally q.close()
  }
}
private[cdc] object CdcJournalFenceVerifier {
  def create(system:ActorSystem[_],openState:()=>Connection):CdcJournalFenceVerifier={
    val cfg=system.settings.config
    require(cfg.getString("pekko.persistence.journal.plugin")=="jdbc-journal" &&
      cfg.getString("jdbc-journal.use-shared-db")=="slick","unsupported journal plugin")
    val j=cfg.getConfig("pekko-persistence-jdbc.shared-databases.slick.db")
    val configuredSchema=cfg.getString("jdbc-journal.tables.event_journal.schemaName")
    def database(c:Connection):(String,String)={
      val q=c.createStatement();q.setQueryTimeout(5)
      try {val r=q.executeQuery("SELECT @@server_uuid,DATABASE()");try {require(r.next());(r.getString(1),r.getString(2))}finally r.close()}finally q.close()
    }
    val state=openState()
    try {
      val journal=ManagedCdcJdbc.open(j.getString("url"),j.getString("user"),j.getString("password"),"journal",managed=true)
      try {
        val actual=database(journal)
        require(actual._1!=null && actual._2!=null && database(state)==actual &&
          (configuredSchema.isEmpty || configuredSchema==actual._2),"journal and state database differ")
        val q=state.prepareStatement("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='event_journal'")
        try {val r=q.executeQuery();try {require(r.next() && r.getString(1)=="InnoDB" && !r.next(),"journal table is not InnoDB")}finally r.close()}finally q.close()
        val pk=state.prepareStatement("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='event_journal' AND index_name='PRIMARY' ORDER BY seq_in_index")
        try {val r=pk.executeQuery();val columns=Vector.newBuilder[String];try while(r.next())columns+=r.getString(1)finally r.close()
          require(columns.result()==Vector("persistence_id","sequence_number"),"journal primary key changed")
        } finally pk.close()
        new CdcJournalFenceVerifier(actual._1,actual._2,system)
      } finally journal.close()
    } finally state.close()
  }
}
