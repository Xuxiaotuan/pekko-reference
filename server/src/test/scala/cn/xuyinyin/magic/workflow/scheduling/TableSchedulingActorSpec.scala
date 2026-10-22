package cn.xuyinyin.magic.workflow.scheduling

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.execution._
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.snapshot._
import cn.xuyinyin.magic.workflow.spi._
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.apache.pekko.{Done,NotUsed}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink,Source}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds,Span}
import spray.json._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._

class TableSchedulingActorSpec extends AnyWordSpec with Matchers with Eventually {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout=Span(15,Seconds))
  "the managed Actor entry" should {
    Vector("revision", "confirmed-failure", "legacy-pause", "stale-disposition", "stop-disposition").foreach { boundary =>
    s"require grants, retain window identities and release local capacity at $boundary" in {
      val url="jdbc:h2:mem:actor-admission-"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:db/h2/pekko-persistence-schema.sql'"
      val cfg=ConfigFactory.parseString(s"""pekko.actor.provider=local
        pekko-persistence-jdbc.shared-databases.slick.db.url="$url"
        pekko-persistence-jdbc.shared-databases.slick.db.user="sa"
        pekko-persistence-jdbc.shared-databases.slick.db.password=""
        """).withFallback(ConfigFactory.load("application-test"))
      val kit=ActorTestKit("admission-actor",cfg)
      implicit val system:org.apache.pekko.actor.typed.ActorSystem[_]=kit.system
      implicit val ec:ExecutionContext=system.executionContext
      val hc=new HikariConfig();hc.setJdbcUrl(url);hc.setUsername("sa");hc.setMaximumPoolSize(2)
      val ds=new HikariDataSource(hc);val connection=ds.getConnection
      try {val in=getClass.getClassLoader.getResourceAsStream("db/h2/pekko-table-scheduling-schema.sql");val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(q=>{val s=connection.createStatement();try s.execute(q) finally s.close()})
      } finally connection.close()
      val runtime=new TableSchedulingRuntime(new TableSchedulingStore(ds,SchedulingLimits(2,2,8,2,64*1024*1024,8)),"node")
      val opens=new AtomicInteger();val commits=new AtomicInteger();val failSource=new java.util.concurrent.atomic.AtomicBoolean(false)
      val releaseCalls=new AtomicInteger()
      val oldReleased=scala.concurrent.Promise[Done]()
      val oldReply=scala.concurrent.Promise[Done]()
      val retryObserved=scala.concurrent.Promise[Done]()
      val readOpened=scala.concurrent.Promise[Done]()
      val readGate=scala.concurrent.Promise[Done]()
      val source=new NodeSource with CheckpointedNodeSource with SnapshotSplitSource {
        def nodeType="mysql.snapshot"
        def createSource(n:Node,l:String=>Unit):Source[String,NotUsed]=Source.empty
        def createBatches(n:Node,e:String,b:SnapshotBoundary,r:Option[BatchCheckpoint],l:String=>Unit)(implicit x:ExecutionContext):Source[SourceBatch,NotUsed]=Source.empty
        override def discoverBoundary(n:Node,r:Option[BatchCheckpoint],l:String=>Unit)(implicit x:ExecutionContext)=Future.successful(SnapshotBoundary(n.id,"old",Some("20")))
        def discoverSplit(n:Node,p:SnapshotSplitPlanState,lower:Option[String],i:Long)(implicit x:ExecutionContext)=Future.successful(Some(SnapshotSplit(s"${p.id}:$i",i,lower,(i+1).toString)))
        def openSplit(n:Node,e:String,p:SnapshotSplitPlanState,s:SnapshotSplit,c:Option[BatchCheckpoint],l:String=>Unit,ce:ExecutionContext)(implicit x:ExecutionContext)={
          opens.incrementAndGet();val b=SourceBatch(n.id,s.id,0,BatchId.sha256(e,n.id,s.id,0),SourceCursor(SnapshotSplitState.CursorKind,s.upper,s.upper),Vector("row"))
          val gated=boundary=="stop-disposition" || (boundary=="stale-disposition" && s.index>=16)
          if(gated)readOpened.trySuccess(Done)
          BatchSourceSession(if(failSource.get()) Source.failed(new IllegalArgumentException("non-retryable fixture source"))
            else if(gated)Source.future(readGate.future.map(_=>b)(x)) else Source.single(b),()=>Future.successful(Done))
        }
      }
      val sink=new NodeSink with CheckpointedNodeSink with SnapshotSplitSink {
        def nodeType="mysql.write"
        def createSink(n:Node,l:String=>Unit)(implicit x:ExecutionContext):Sink[String,Future[Done]]=Sink.ignore
        def validateReady(n:Node,l:String=>Unit)(implicit x:ExecutionContext)=Future.successful(Done)
        def validateSnapshotReady(n:Node)(implicit x:ExecutionContext)=Future.successful(Done)
        def commitBatch(n:Node,w:String,e:String,b:SourceBatch,rows:Vector[String],l:String=>Unit)(implicit x:ExecutionContext)={
          commits.incrementAndGet();Future.successful(Committed(BatchCheckpoint(b.sourceNodeId,b.partitionId,b.batchSequence,b.batchId,b.cursor,1,1)))
        }
      }
      val registry:NodeRegistry=NodeRegistry.builtins().withSource(source).withSink(sink)
      class GatedEngine extends WorkflowExecutionEngine((key:String)=>sys.env.get(key),registry)(system,ec) {
        override def tableScheduling=Some(runtime)
        override def finishScheduledSnapshot(id:String,disposition:String,released:Boolean,grantId:Option[String]):Future[Done]={
          if(Set("stale-disposition","stop-disposition").contains(boundary) && disposition=="ready" && released) {
            releaseCalls.incrementAndGet() match {
              case 1 => Future.failed(new java.sql.SQLException("fixed control-plane release failure"))
              case 2 if boundary=="stale-disposition" =>
                super.finishScheduledSnapshot(id,disposition,released,grantId).flatMap { _=>oldReleased.trySuccess(Done);oldReply.future }
              case _ => super.finishScheduledSnapshot(id,disposition,released,grantId).andThen {case _=>retryObserved.trySuccess(Done)}
            }
          } else super.finishScheduledSnapshot(id,disposition,released,grantId)
        }
      }
      val engine=new GatedEngine
      val config=JsObject("host"->JsString("mysql"),"port"->JsNumber(3306),"splitRows"->JsNumber(1),"chunkSize"->JsNumber(1),"maxPageBytes"->JsNumber(1024),"snapshotConsistency"->JsString("quiescent"))
      val s=Node("s","source","mysql.snapshot","s",Position(0,0),config);val t=s.copy(id="t",`type`="sink",nodeType="mysql.write")
      val workflow=Workflow("managed","managed","","1","test",Nil,List(s,t),List(Edge("e","s","t")),WorkflowMetadata("now","now",None,None))
      try {
        val refused=engine.execute(workflow,ReliableRunContext("without-grant",1L,None,Vector.empty,
          _=>Future.successful(Done),_=>Future.successful(Done)),_=>())
        intercept[IllegalStateException](Await.result(refused,5.seconds))
        Await.result(engine.cancel("without-grant"),5.seconds) shouldBe Done
        opens.get() shouldBe 0
        val queued=Await.result(runtime.submit(workflow,"run"),5.seconds)
        opens.get() shouldBe 0
        val first=runtime.store.claim(1).head
        val actor=kit.spawn(EventSourcedWorkflowActor("managed",engine))
        actor ! EventSourcedWorkflowActor.ActivateSnapshot(first)
        if(boundary=="stale-disposition") {
          eventually {releaseCalls.get() shouldBe 1}
          actor ! EventSourcedWorkflowActor.ActivateSnapshot(first)
          Await.result(oldReleased.future,5.seconds)
          val next=runtime.store.claim(1).head
          next.id should not be first.id
          actor ! EventSourcedWorkflowActor.ActivateSnapshot(next)
          Await.result(readOpened.future,5.seconds)
          Await.result(retryObserved.future,5.seconds)
          // An old disposition retry must not release the new window's running reservation.
          runtime.store.get("managed").get.state shouldBe "running"
          runtime.store.get("managed").get.grantId shouldBe Some(next.id)
          runtime.owns(next) shouldBe true
          oldReply.success(Done)
          readGate.success(Done)
          eventually {runtime.store.get("managed").get.state shouldBe "completed"}
          kit.createTestProbe[Any]().expectTerminated(actor,5.seconds)
        } else if(boundary=="stop-disposition") {
          Await.result(readOpened.future,5.seconds)
          eventually {opens.get() shouldBe 2}
          actor ! EventSourcedWorkflowActor.Stop
          val stopping=kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
          actor ! EventSourcedWorkflowActor.GetSummary(stopping.ref)
          stopping.receiveMessage(3.seconds).status.value shouldBe "stopping"
          readGate.success(Done)
          // Resource cancellation is real; only the first control SQL disposition is rejected.
          kit.createTestProbe[Any]().expectTerminated(actor,5.seconds)
          runtime.store.get("managed").get.state shouldBe "ready"
          eventually {runtime.attachedCount shouldBe 0}
          opens.get() shouldBe 2
          commits.get() shouldBe 0
        } else {
        eventually { runtime.store.get("managed").get.state shouldBe "ready" }
        kit.createTestProbe[Any]().expectTerminated(actor,10.seconds)
        opens.get() shouldBe 16;commits.get() shouldBe 16
        val second=runtime.store.claim(1).head
        second.task.executionId shouldBe queued.executionId
        val recovered=kit.spawn(EventSourcedWorkflowActor("managed",engine))
        recovered ! EventSourcedWorkflowActor.ActivateSnapshot(second)
        eventually { runtime.store.get("managed").get.state shouldBe "completed" }
        opens.get() shouldBe 20;commits.get() shouldBe 20
        kit.createTestProbe[Any]().expectTerminated(recovered,5.seconds)
        val reader=kit.spawn(EventSourcedWorkflowActor("managed",engine))
        val replies=kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        reader ! EventSourcedWorkflowActor.GetSummary(replies.ref)
        replies.receiveMessage(5.seconds).recentExecutions.map(_.executionId) shouldBe Vector(queued.executionId.get)
        kit.createTestProbe[Any]().expectTerminated(reader,5.seconds)
        if(boundary=="revision") {
          val revised=workflow.copy(name="revision-two")
          Await.result(runtime.submit(revised,"revision-run",2L),5.seconds)
          val owner2=kit.spawn(EventSourcedWorkflowActor("managed",engine))
          owner2 ! EventSourcedWorkflowActor.ActivateSnapshot(runtime.store.claim(1).head)
          eventually {runtime.store.get("managed").get.state shouldBe "ready"}
          kit.createTestProbe[Any]().expectTerminated(owner2,5.seconds)
          val owner3=kit.spawn(EventSourcedWorkflowActor("managed",engine))
          owner3 ! EventSourcedWorkflowActor.ActivateSnapshot(runtime.store.claim(1).head)
          eventually {runtime.store.get("managed").get.state shouldBe "completed"}
          kit.createTestProbe[Any]().expectTerminated(owner3,5.seconds)
          opens.get() shouldBe 40; commits.get() shouldBe 40
          val definitionActor=kit.spawn(EventSourcedWorkflowActor("managed",engine))
          val changes=kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
          definitionActor ! EventSourcedWorkflowActor.DefineWorkflow(revised.copy(name="legacy-out-of-band"),2L,changes.ref)
          changes.expectMessageType[EventSourcedWorkflowActor.DefinitionRejected]
          kit.stop(definitionActor)
        } else if(boundary=="confirmed-failure") {
          failSource.set(true)
          (1 to 3).foreach { i =>
            val failedId=s"failed-$i"
            Await.result(runtime.submit(workflow.copy(id=failedId),s"failure-$i"),5.seconds)
            val failed=kit.spawn(EventSourcedWorkflowActor(failedId,engine))
            failed ! EventSourcedWorkflowActor.ActivateSnapshot(runtime.store.claim(1).head)
            eventually {runtime.store.get(failedId).get.state shouldBe "blocked"}
            kit.createTestProbe[Any]().expectTerminated(failed,2.seconds)
            eventually {runtime.attachedCount shouldBe 0}
          }
          failSource.set(false)
          val healthy=workflow.copy(id="healthy-after-failures")
          Await.result(runtime.submit(healthy,"healthy"),5.seconds)
          val healthy1=kit.spawn(EventSourcedWorkflowActor(healthy.id,engine))
          healthy1 ! EventSourcedWorkflowActor.ActivateSnapshot(runtime.store.claim(1).head)
          eventually {runtime.store.get(healthy.id).get.state shouldBe "ready"}
          kit.createTestProbe[Any]().expectTerminated(healthy1,5.seconds)
          val healthy2=kit.spawn(EventSourcedWorkflowActor(healthy.id,engine))
          healthy2 ! EventSourcedWorkflowActor.ActivateSnapshot(runtime.store.claim(1).head)
          eventually {runtime.store.get(healthy.id).get.state shouldBe "completed"}
          kit.createTestProbe[Any]().expectTerminated(healthy2,5.seconds)
        } else {
          failSource.set(true)
          val legacyEngine=new WorkflowExecutionEngine((key:String)=>sys.env.get(key),registry)(system,ec) {
            override def tableScheduling=None
          }
          val legacy=kit.spawn(EventSourcedWorkflowActor("legacy-pause",legacyEngine))
          val reply=kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
          legacy ! EventSourcedWorkflowActor.DefineWorkflow(workflow.copy(id="legacy-pause"),0L,reply.ref)
          reply.expectMessageType[EventSourcedWorkflowActor.Defined]
          legacy ! EventSourcedWorkflowActor.ExecuteManual("pause",reply.ref)
          reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
          val summary=kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
          eventually {
            legacy ! EventSourcedWorkflowActor.GetSummary(summary.ref)
            val paused=summary.receiveMessage(1.second)
            paused.status.value shouldBe "snapshot_blocked"
            paused.currentExecution should not be empty
          }
          kit.stop(legacy)
        }
        }
      } finally { oldReply.trySuccess(Done);readGate.trySuccess(Done);ds.close();kit.shutdownTestKit() }
    }
    }
  }
}
