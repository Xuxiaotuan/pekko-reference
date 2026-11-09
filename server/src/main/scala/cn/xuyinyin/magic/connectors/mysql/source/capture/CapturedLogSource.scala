package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.spi.{NodeSource,CheckpointedNodeSource,BatchSourceSession}
import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor,WorkflowJournalProgressVerifier}
import org.apache.pekko.{Done,NotUsed}
import org.apache.pekko.stream.scaladsl.Source
import scala.concurrent.{ExecutionContext,Future}
import scala.concurrent.duration._
import spray.json._

/** Bounded, independently checkpointed application of L. Capturing is owned elsewhere.
  * Phase 1 only permits operator-established target baselines; no online bootstrap claim.
  */
final class CapturedLogSource(store:MySQLCaptureStore,database:String,
  journalVerifier:Option[WorkflowJournalProgressVerifier]=None) extends NodeSource with CheckpointedNodeSource {
  val nodeType="mysql.cdc.log"
  private val reader=new CapturedLogReader(store,database)
  private def request(node:WorkflowDSL.Node):String={
    require(node.config.fields.get("initializedTarget").contains(JsBoolean(true)),"trusted target baseline required; capture-ready is not initialized")
    node.config.fields("subscriptionId").asInstanceOf[JsString].value
  }
  private def identity(id:String):String=reader.identity(id)
  def resumePosition(id:String,cursor:SourceCursor):Long=
    reader.resumePosition(id,CdcCapturedBatchWorkflowAdapter.cursorFromWorkflow(cursor))
  def readBatch(id:String,after:Long,limit:Int,sourceNodeId:String,executionId:String,sequence:Long,consumer:Option[String]=None):Option[SourceBatch]=
    reader.readBatch(id,after,limit,sourceNodeId,executionId,sequence,consumer).map(CdcCapturedBatchWorkflowAdapter.toWorkflow(_))
  override def createSource(node:WorkflowDSL.Node,onLog:String=>Unit):Source[String,NotUsed]=
    throw new UnsupportedOperationException("capture log requires checkpoint execution")
  override def discoverBoundary(node:WorkflowDSL.Node,resume:Option[BatchCheckpoint],onLog:String=>Unit)(implicit ec:ExecutionContext):Future[SnapshotBoundary]=Future {
    val id=request(node);val bound=SnapshotBoundary(node.id,s"mysql-cdc:${store.captureId}",Some(identity(id)))
    resume.foreach {c=>require(c.sourceNodeId==node.id && c.partitionId==bound.partitionId);resumePosition(id,c.cursor)}
    bound
  }
  override def createBatches(node:WorkflowDSL.Node,executionId:String,boundary:SnapshotBoundary,resume:Option[BatchCheckpoint],onLog:String=>Unit)(implicit ec:ExecutionContext):Source[SourceBatch,NotUsed]=
    session(node,executionId,boundary,resume).source
  override def openBatches(node:WorkflowDSL.Node,executionId:String,boundary:SnapshotBoundary,resume:Option[BatchCheckpoint],onLog:String=>Unit,closeEc:ExecutionContext)(implicit ec:ExecutionContext):BatchSourceSession=
    session(node,executionId,boundary,resume)
  override def acknowledgeCommittedBatch(node:WorkflowDSL.Node,batch:SourceBatch,onLog:String=>Unit)(implicit ec:ExecutionContext):Future[Done]=Future {
    val id=request(node);val consumer=batch.acknowledgementId.getOrElse(throw new IllegalArgumentException("capture consumer identity missing"))
    require(batch.sourceNodeId==node.id && batch.partitionId==s"mysql-cdc:${store.captureId}","capture consumer checkpoint identity conflict")
    val floor=resumePosition(id,batch.cursor)
    store.projectConsumerJournal(consumer,"WORKFLOW",id,node.id,floor,batch.batchSequence,batch.batchId)
    Done
  }
  override def acknowledgeCommittedBatchWithReceipt(node:WorkflowDSL.Node,batch:SourceBatch,
    receipt:Option[EventSourcedWorkflowActor.PersistedCheckpoint],onLog:String=>Unit)(implicit ec:ExecutionContext):Future[Done]=
    Future {
      receipt.foreach { proof=>
        val checkpoint=proof.checkpoint
        require(checkpoint.sourceNodeId==batch.sourceNodeId && checkpoint.partitionId==batch.partitionId &&
          checkpoint.batchSequence==batch.batchSequence && checkpoint.batchId==batch.batchId && checkpoint.cursor==batch.cursor,
          "workflow Journal receipt does not match acknowledged batch")
      }
    }.flatMap(_=>acknowledgeCommittedBatch(node,batch,onLog)).flatMap { _=>
      (receipt,journalVerifier) match {
        case (Some(proof),Some(verifier)) if store.discoveryComplete => Future {
          val id=request(node);val consumer=batch.acknowledgementId.getOrElse(throw new IllegalArgumentException("capture consumer identity missing"))
          store.projectWorkflowVerified(consumer,id,node.id,resumePosition(id,batch.cursor),proof,verifier)
          Done
        }
        case _=>Future.successful(Done)
      }
    }
  private def session(node:WorkflowDSL.Node,executionId:String,boundary:SnapshotBoundary,resume:Option[BatchCheckpoint])(implicit ec:ExecutionContext):BatchSourceSession={
    val id=request(node);require(boundary.upperBound.contains(identity(id)) && boundary.sourceNodeId==node.id && boundary.partitionId==s"mysql-cdc:${store.captureId}","capture boundary mismatch")
    var after=resume.map(c=>resumePosition(id,c.cursor)).getOrElse(0L)
    val consumer=CaptureConsumerIdentity.workflow(store.captureId,id,node.id,executionId)
    store.registerConsumer(consumer,"WORKFLOW",id,node.id,after)
    var sequence=resume.map(_.batchSequence+1).getOrElse(0L)
    val limit=node.config.fields.get("batchSize").map(_.asInstanceOf[JsNumber].value.toIntExact).getOrElse(128)
    require(limit>0 && limit<=1000)
    val lock=new AnyRef;var closed=false;var reading:Future[Option[SourceBatch]]=Future.successful(None)
    val source=Source.tick(Duration.Zero,100.millis,()).mapAsync(1) {_=>lock.synchronized {
      reading=if(closed)Future.successful(None)else Future(readBatch(id,after,limit,node.id,executionId,sequence,Some(consumer)).map(_.copy(acknowledgementId=Some(consumer))))
      reading
    }}.mapConcat {batch=>batch.foreach {b=>after=resumePosition(id,b.cursor);sequence+=1};batch.toList}.mapMaterializedValue(_=>NotUsed)
    BatchSourceSession(source,()=>lock.synchronized {closed=true;reading.map(_=>Done)(ExecutionContext.parasitic)})
  }
}
