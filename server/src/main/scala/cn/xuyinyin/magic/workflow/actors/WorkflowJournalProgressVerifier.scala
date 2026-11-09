package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.checkpoint.BatchCheckpoint
import cn.xuyinyin.magic.workflow.events.WorkflowEvents.ExecutionCheckpointAdvanced

import java.sql.Connection
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension

/** Verifies one actor-issued progress receipt against the latest persisted same-schema event. */
final class WorkflowJournalProgressVerifier private (uuid:String,database:String,system:ActorSystem[_]) {
  private val serialization=SerializationExtension(system.classicSystem)
  def verifyOn(c:Connection,receipt:EventSourcedWorkflowActor.PersistedCheckpoint,workflowId:String,executionId:String,
    checkpoint:BatchCheckpoint):Unit={
    require(!c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_REPEATABLE_READ &&
      receipt.persistenceId=="workflow-"+workflowId && receipt.sequenceNr>0 && workflowId.nonEmpty &&
      receipt.executionId==executionId && executionId.nonEmpty && receipt.checkpoint==checkpoint,"workflow Journal receipt identity conflict")
    val id=c.createStatement();id.setQueryTimeout(5)
    try {val r=id.executeQuery("SELECT @@server_uuid,DATABASE()");try {
      require(r.next() && r.getString(1)==uuid && r.getString(2)==database && !r.next(),"workflow Journal database changed")
    }finally r.close()}finally id.close()
    val q=c.prepareStatement("SELECT sequence_number,deleted,event_payload,event_ser_id,event_ser_manifest FROM event_journal WHERE persistence_id=? AND sequence_number>=? ORDER BY sequence_number LIMIT 2 FOR UPDATE")
    q.setQueryTimeout(5);q.setString(1,receipt.persistenceId);q.setLong(2,receipt.sequenceNr)
    try {val r=q.executeQuery();try {
      require(r.next() && r.getLong(1)==receipt.sequenceNr && !r.getBoolean(2),"latest workflow Journal event missing or deleted")
      val event=serialization.deserialize(r.getBytes(3),r.getInt(4),r.getString(5)).get match {
        case x:ExecutionCheckpointAdvanced=>x
        case _=>throw new IllegalArgumentException("unexpected workflow Journal event")
      }
      require(event.executionId==executionId && event.checkpoint==checkpoint && !r.next(),"workflow Journal progress changed")
    }finally r.close()}finally q.close()
  }
}
private[magic] object WorkflowJournalProgressVerifier {
  def create(system:ActorSystem[_],openState:()=>Connection,openJournal:()=>Connection):WorkflowJournalProgressVerifier={
    val cfg=system.settings.config
    require(cfg.getString("pekko.persistence.journal.plugin")=="jdbc-journal" && cfg.getString("jdbc-journal.use-shared-db")=="slick",
      "unsupported Journal plugin")
    val declared=cfg.getString("jdbc-journal.tables.event_journal.schemaName")
    def identity(c:Connection):(String,String)={val q=c.createStatement();q.setQueryTimeout(5);try {val r=q.executeQuery("SELECT @@server_uuid,DATABASE()")
      try {require(r.next());(r.getString(1),r.getString(2))}finally r.close()}finally q.close()}
    val state=openState();try {val journal=openJournal();try {
      val actual=identity(state);require(actual._1!=null && actual._2!=null && identity(journal)==actual && (declared.isEmpty || declared==actual._2),
        "workflow Journal and capture state differ")
      val q=state.prepareStatement("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='event_journal'")
      try {val r=q.executeQuery();try {require(r.next() && r.getString(1)=="InnoDB" && !r.next(),"workflow Journal table is not InnoDB")}finally r.close()}finally q.close()
      val pk=state.prepareStatement("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='event_journal' AND index_name='PRIMARY' ORDER BY seq_in_index")
      try {val r=pk.executeQuery();val b=Vector.newBuilder[String];try while(r.next())b+=r.getString(1)finally r.close();require(b.result()==Vector("persistence_id","sequence_number"),"workflow Journal primary key changed")}finally pk.close()
      new WorkflowJournalProgressVerifier(actual._1,actual._2,system)
    }finally journal.close()}finally state.close()
  }
}
