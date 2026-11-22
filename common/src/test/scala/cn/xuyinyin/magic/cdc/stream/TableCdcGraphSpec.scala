package cn.xuyinyin.magic.cdc.stream

import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint,Committed}
import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CdcCaptureCursor,CdcCapturedBatch,MySQLCaptureStore,SharedMySQLCapture}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.{Done,NotUsed}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._

class TableCdcGraphSpec extends AnyWordSpec with Matchers {
  "a table CDC graph" should {
    "materialize source, sink, journal and ACK as one independently stoppable stream" in {
      implicit val system:ActorSystem[Nothing]=ActorSystem[Nothing](Behaviors.empty,"table-graph-test")
      implicit val ec:ExecutionContext=system.executionContext
      implicit val materializer=SystemMaterializer(system).materializer
      val acknowledged=new AtomicInteger(0)
      val order=scala.collection.mutable.ArrayBuffer.empty[String]
      val batch=CdcCapturedBatch("source-1","mysql-cdc:capture",0,"batch-1",
        CdcCaptureCursor("mysql.capture.groups.v1",JsObject("sequence"->JsNumber(1),"version"->JsNumber(1)).compactPrint,"{}"),
        Vector("{\"id\":1}"))
      val capture=new FakeCapture(batch,acknowledged,Some(order))
      val journal=new RecordingJournal(Some(order))
      val sink=new TableGraphSink {
        override def commit(subscriptionId:String,executionId:String,b:CdcCapturedBatch)
          (implicit ec:ExecutionContext):Future[cn.xuyinyin.magic.cdc.checkpoint.BatchCommitResult]=
          { order.synchronized(order += "T"); Future.successful(Committed(BatchCheckpoint(b.sourceNodeId,b.partitionId,b.batchSequence,b.batchId,
            cn.xuyinyin.magic.connectors.mysql.source.capture.CdcCapturedBatchWorkflowAdapter.toWorkflow(b).cursor,
            b.rows.size,b.rows.size))) }
      }
      val graph=new TableCdcGraph(capture,TableGraphSettings("sub-1","source-1","execution-1","a"*64),sink,journal)
      val materialized=graph.materialize()
      Await.result(materialized.completion,5.seconds) shouldBe Done
      acknowledged.get() shouldBe 1
      journal.latest.map(_.batchId) shouldBe Some("batch-1")
      order.toVector shouldBe Vector("T","J","L")
      Await.result(materialized.stop(),5.seconds) shouldBe Done
      system.terminate()
      Await.result(system.whenTerminated,5.seconds)
    }

    "release table ownership after stop so the graph can be materialized again" in {
      implicit val system:ActorSystem[Nothing]=ActorSystem[Nothing](Behaviors.empty,"table-actor-test")
      implicit val ec:ExecutionContext=system.executionContext
      implicit val materializer=SystemMaterializer(system).materializer
      val batch=CdcCapturedBatch("source-1","mysql-cdc:capture",0,"batch-1",
        CdcCaptureCursor("mysql.capture.groups.v1",JsObject("sequence"->JsNumber(1),"version"->JsNumber(1)).compactPrint,"{}"),
        Vector("{\"id\":1}"))
      val capture=new FakeCapture(batch,new AtomicInteger(0))
      val journal=new RecordingJournal
      val sink=new TableGraphSink {
        override def commit(subscriptionId:String,executionId:String,b:CdcCapturedBatch)
          (implicit ec:ExecutionContext):Future[cn.xuyinyin.magic.cdc.checkpoint.BatchCommitResult]=
          Future.successful(Committed(BatchCheckpoint(b.sourceNodeId,b.partitionId,b.batchSequence,b.batchId,
            cn.xuyinyin.magic.connectors.mysql.source.capture.CdcCapturedBatchWorkflowAdapter.toWorkflow(b).cursor,
            b.rows.size,b.rows.size)))
      }
      val actor=system.systemActorOf(TableActor(()=>new TableCdcGraph(capture,
        TableGraphSettings("sub-1","source-1","execution-1","b"*64),sink,journal)),"table-owner")
      val probe=org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[TableActor.Reply]()(system)
      actor ! TableActor.Start(probe.ref)
      probe.expectMessage(TableActor.Started)
      actor ! TableActor.Status(probe.ref)
      probe.expectMessage(TableActor.Running(true))
      actor ! TableActor.Stop(probe.ref)
      probe.expectMessage(TableActor.Stopped)
      actor ! TableActor.Status(probe.ref)
      probe.expectMessage(TableActor.Running(false))
      actor ! TableActor.Start(probe.ref)
      probe.expectMessage(TableActor.Started)
      system.terminate()
      Await.result(system.whenTerminated,5.seconds)
    }
  }

  private final class RecordingJournal(order:Option[scala.collection.mutable.ArrayBuffer[String]]=None) extends TableGraphJournal {
    @volatile private var value:Option[BatchCheckpoint]=None
    override def persist(checkpoint:BatchCheckpoint)(implicit ec:ExecutionContext):Future[Long]={order.foreach(buffer => buffer.synchronized { buffer += "J" });value=Some(checkpoint);Future.successful(1L)}
    override def latest:Option[BatchCheckpoint]=value
  }

  private final class FakeCapture(batch:CdcCapturedBatch,acknowledged:AtomicInteger,order:Option[scala.collection.mutable.ArrayBuffer[String]]=None)
    extends SharedMySQLCapture(
      MySQLCdcSourceConfig("cap","127.0.0.1",3306,"db","source_rows","u","p",1,1,1),
      MySQLCdcStateConfig("jdbc:mysql://127.0.0.1/db","u","p","offsets","history",0),
      new MySQLCaptureStore(()=>null,"cap","lineage",1024L)) {
    override def tableSource(subscriptionId:String,sourceNodeId:String,executionId:String,after:Long,
      sequence:Long,batchSize:Int,consumerId:String)(implicit ec:ExecutionContext):Source[CdcCapturedBatch,NotUsed]=
      Source.single(batch)
    override def acknowledgeTableBatch(subscriptionId:String,sourceNodeId:String,consumerId:String,b:CdcCapturedBatch,
      journalRevision:Long)(implicit ec:ExecutionContext):Future[Done]={acknowledged.incrementAndGet();order.foreach(buffer => buffer.synchronized { buffer += "L" });Future.successful(Done)}
  }
}
