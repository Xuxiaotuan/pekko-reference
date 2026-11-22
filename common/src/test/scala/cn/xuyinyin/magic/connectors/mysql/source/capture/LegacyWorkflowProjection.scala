package cn.xuyinyin.magic.connectors.mysql.source.capture

import java.sql.{Connection,PreparedStatement}
import cn.xuyinyin.magic.cdc.persistence.{JournalCheckpointReceipt,CdcJournalProgressVerifier}
import cn.xuyinyin.magic.cdc.checkpoint.BatchId
import spray.json._

/** Retained legacy experiment, not a production CDC projection API. */
private[magic] final class LegacyWorkflowProjection(open:()=>Connection,store:MySQLCaptureStore) {
  private val captureId=store.captureId
  private val lineage=store.lineage
  private def connection[A](f:Connection=>A):A={val c=open();try f(c)finally {store.clearProofConnection(c);c.close()}}
  private def bind(q:PreparedStatement,args:Seq[Any]):Unit=args.zipWithIndex.foreach {case(v,i)=>v match {
    case n:Long=>q.setLong(i+1,n)
    case n:Int=>q.setInt(i+1,n)
    case x=>q.setString(i+1,x.toString)
  }}
  private def rows(c:Connection,sql:String,args:Any*):Vector[Map[String,String]]={
    val q=c.prepareStatement(sql);q.setQueryTimeout(30);bind(q,args)
    try {val r=q.executeQuery();try {val b=Vector.newBuilder[Map[String,String]];val m=r.getMetaData
      while(r.next())b+=(1 to m.getColumnCount).map(i=>m.getColumnLabel(i)->r.getString(i)).toMap
      b.result()
    }finally r.close()}finally q.close()
  }
  private def update(c:Connection,sql:String,args:Any*):Int={val q=c.prepareStatement(sql);q.setQueryTimeout(30);bind(q,args);try q.executeUpdate()finally q.close()}
  private def frame(c:Connection,n:Long):Map[String,String]=store.proofFrame(c,n)
  /** Advance only from the actor's durable event and the matching captured group. */
  private[magic] def projectWorkflowVerified(consumer:String,subscription:String,node:String,floor:Long,
    receipt:JournalCheckpointReceipt,verifier:CdcJournalProgressVerifier):Unit=connection {c=>
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false)
    try {
      require(receipt!=null && verifier!=null && receipt.persistenceId.startsWith("workflow-") &&
        receipt.persistenceId.length>"workflow-".length,"workflow Journal proof missing")
      val checkpoint=receipt.checkpoint
      val cursor=checkpoint.cursor.value.parseJson.asJsObject.fields
      val partition=s"mysql-cdc:$captureId"
      require(checkpoint.sourceNodeId==node && checkpoint.partitionId==partition && checkpoint.cursor.kind=="mysql.capture.groups.v1" &&
        checkpoint.batchId==BatchId.sha256(receipt.executionId,node,partition,checkpoint.batchSequence) &&
        cursor.get("version").contains(JsNumber(1)) && cursor.get("captureId").contains(JsString(captureId)) &&
        cursor.get("subscriptionId").contains(JsString(subscription)) && cursor.get("sequence").contains(JsNumber(floor)) && floor>0 &&
        consumer==CaptureConsumerIdentity.workflow(captureId,subscription,node,receipt.executionId),"workflow consumer checkpoint identity conflict")
      val head=rows(c,"SELECT lineage,next_seq FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",captureId).head
      require(head("lineage")==lineage && floor<head("next_seq").toLong,"workflow capture lineage or floor conflict")
      val state=rows(c,"SELECT discovery_status,barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=? FOR UPDATE",captureId).head
      require(state("discovery_status")=="COMPLETE" && state("barrier_status")=="OPEN","verified workflow projection is not admitted")
      val registered=rows(c,"SELECT * FROM pekko_capture_consumers_v1 WHERE capture_id=? AND consumer_id=? FOR UPDATE",captureId,consumer).headOption
        .getOrElse(throw new IllegalArgumentException("workflow consumer missing"))
      require(registered("consumer_kind")=="WORKFLOW" && registered("subscription_id")==subscription && registered("source_node")==node &&
        registered("checkpoint_id")==checkpoint.batchId && registered("journal_revision").toLong==checkpoint.batchSequence &&
        floor>=registered("journal_floor").toLong && floor==registered("observed_floor").toLong,"workflow consumer registration conflict")
      val group=frame(c,floor)
      require(group("kind")=="GROUP" && group("subscription_id")==subscription,"workflow checkpoint frame conflict")
      verifier.verifyOn(c,receipt,receipt.persistenceId.substring("workflow-".length),receipt.executionId,checkpoint)
      update(c,"UPDATE pekko_capture_consumers_v1 SET journal_floor=? WHERE capture_id=? AND consumer_id=?",floor,captureId,consumer)
      c.commit()
    }catch {case e:Throwable=>try c.rollback()catch {case x:Throwable=>e.addSuppressed(x)};throw e}
  }

}

object LegacyWorkflowProjectionSyntax {
  implicit final class StoreOps(private val store:MySQLCaptureStore) extends AnyVal {
    def projectWorkflowVerified(consumer:String,subscription:String,node:String,floor:Long,
      receipt:JournalCheckpointReceipt,verifier:CdcJournalProgressVerifier):Unit=
      new LegacyWorkflowProjection(verifier.projectionConnection,store).projectWorkflowVerified(consumer,subscription,node,floor,receipt,verifier)
  }
}
