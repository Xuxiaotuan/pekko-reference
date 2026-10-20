package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.{TestNodeRegistry, WorkflowFixtures}
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.engine._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

class WorkflowStopSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString("pekko.workflow.session-control-wait-timeout=200ms").withFallback(CdcCommitRecoverySpec.config))
  with AnyWordSpecLike with Matchers {
  import EventSourcedWorkflowActor._
  private implicit val ec: ExecutionContext = system.executionContext
  private def definition(id: String) = WorkflowFixtures.mysqlCdcWorkflow.copy(id=id,
    nodes=WorkflowFixtures.mysqlCdcWorkflow.nodes.map(n => if(n.`type`=="source") n.copy(config=JsObject(n.config.fields+
      ("groupProtocol"->JsObject("version"->JsNumber(1),"replicationId"->JsString(id),"generation"->JsString("g"),"sourceLineage"->JsString("l"))))) else n))
  private def watch(a: org.apache.pekko.actor.typed.ActorRef[Command]) = {
    val ended=createTestProbe[String]()
    spawn(Behaviors.setup[Boolean] { c => c.watchWith(a,true); Behaviors.receiveMessage { _ => ended.ref ! "stopped"; Behaviors.stopped } })
    ended
  }
  private def state(a: org.apache.pekko.actor.typed.ActorRef[Command]): WorkflowSummary = {
    val p=createTestProbe[WorkflowSummary]();a ! GetSummary(p.ref);p.receiveMessage()
  }
  "workflow stopping" should {
    "drain admitted T, J, ACK and actual close before termination without completing the CDC execution" in {
      val registry=new TestNodeRegistry
      val entered=Promise[SourceBatch]();val committed=Promise[BatchCommitResult]()
      val ackEntered=Promise[Done]();val ack=Promise[Done]();val closing=Promise[Done]();val closed=Promise[Done]()
      val source=new NodeSource with CheckpointedNodeSource {
        val nodeType="mysql.cdc"
        def createSource(n:WorkflowDSL.Node,l:String=>Unit):Source[String,NotUsed]=Source.empty
        override def discoverBoundary(n:WorkflowDSL.Node,cp:Option[BatchCheckpoint],l:String=>Unit)(implicit ec:ExecutionContext)=Future.successful(SnapshotBoundary(n.id,"p",Some("identity")))
        def createBatches(n:WorkflowDSL.Node,id:String,b:SnapshotBoundary,cp:Option[BatchCheckpoint],l:String=>Unit)(implicit ec:ExecutionContext):Source[SourceBatch,NotUsed]= {
          val seq=cp.fold(0L)(_.batchSequence+1)
          Source.single(SourceBatch(n.id,b.partitionId,seq,BatchId.sha256(id,n.id,b.partitionId,seq),SourceCursor("test","offset","identity"),Vector("row")))
            .concat(Source.maybe[SourceBatch]).mapMaterializedValue(_=>NotUsed)
        }
        override def openBatches(n:WorkflowDSL.Node,id:String,b:SnapshotBoundary,cp:Option[BatchCheckpoint],l:String=>Unit,closeEc:ExecutionContext)(implicit ec:ExecutionContext)=
          BatchSourceSession(createBatches(n,id,b,cp,l),()=>{closing.trySuccess(Done);closed.future})
        override def acknowledgeCommittedBatch(n:WorkflowDSL.Node,b:SourceBatch,l:String=>Unit)(implicit ec:ExecutionContext)={ackEntered.trySuccess(Done);ack.future}
      }
      val sink=new NodeSink with CheckpointedNodeSink {
        val nodeType="mysql.cdc.apply"
        def createSink(n:WorkflowDSL.Node,l:String=>Unit)(implicit ec:ExecutionContext):Sink[String,Future[Done]]=Sink.ignore
        def validateReady(n:WorkflowDSL.Node,l:String=>Unit)(implicit ec:ExecutionContext)=Future.successful(Done)
        def commitBatch(n:WorkflowDSL.Node,w:String,e:String,b:SourceBatch,r:Vector[String],l:String=>Unit)(implicit ec:ExecutionContext)={entered.trySuccess(b);committed.future}
      }
      registry.registerSource(source);registry.registerSink(sink)
      val engine=new WorkflowExecutionEngine(_=>Some("test"),registry.snapshot)
      val a=spawn(EventSourcedWorkflowActor("drained-owner",engine));val replies=createTestProbe[Reply]();val ended=watch(a)
      a ! DefineWorkflow(definition("drained-owner"),0,replies.ref);replies.expectMessage(Defined("drained-owner",1))
      a ! ExecuteManual("initial",replies.ref);val id=replies.expectMessageType[ExecutionAccepted].executionId
      val b=Await.result(entered.future,3.seconds);val cp=BatchCheckpoint(b.sourceNodeId,b.partitionId,b.batchSequence,b.batchId,b.cursor,1,1)
      try {
        a ! Stop;a ! Stop
        a ! ExecuteScheduled("daily",1,"trigger",replies.ref)
        replies.expectMessageType[DefinitionRejected].errors.mkString should include("stopping")
        a ! ExecuteManual("later",replies.ref);replies.expectMessageType[DefinitionRejected]
        state(a).status.value shouldBe "stopping"
        committed.success(Committed(cp));Await.result(ackEntered.future,3.seconds)
        state(a).currentExecution.get.checkpoints shouldBe Vector(cp)
        ended.expectNoMessage(100.millis)
        ack.success(Done);Await.result(closing.future,3.seconds)
        ended.expectNoMessage(350.millis) // greater than the Engine's 200ms close observation window
        state(a).currentExecution.get.executionId shouldBe id
        closed.success(Done);ended.expectMessage("stopped")
        val resumed=Promise[ReliableRunContext]()
        val replacement=new WorkflowExecutionEngine(nodeRegistry=registry.snapshot) {
          override def execute(w:WorkflowDSL.Workflow,c:ReliableRunContext,l:String=>Unit)={resumed.trySuccess(c);Promise[ExecutionResult]().future}
        }
        val next=spawn(EventSourcedWorkflowActor("drained-owner",replacement))
        val c=Await.result(resumed.future,3.seconds)
        c.executionId shouldBe id;c.checkpoints shouldBe Vector(cp)
        state(next).recentExecutions shouldBe empty
        testKit.stop(next)
      } finally {committed.trySuccess(Committed(cp));ack.trySuccess(Done);closed.trySuccess(Done);testKit.stop(a)}
    }
    "wait for an ordinary row execution result instead of treating an empty reliable registry as released" in {
      val entered=Promise[Done]();val running=Promise[ExecutionResult]()
      val engine=new WorkflowExecutionEngine() {
        override def execute(w:WorkflowDSL.Workflow,id:String,l:String=>Unit)={entered.trySuccess(Done);running.future}
      }
      val a=spawn(EventSourcedWorkflowActor("row-stop",engine));val replies=createTestProbe[Reply]();val ended=watch(a)
      a ! DefineWorkflow(WorkflowFixtures.linearWorkflow.copy(id="row-stop"),0,replies.ref);replies.expectMessageType[Defined]
      a ! ExecuteManual("first",replies.ref);replies.expectMessageType[ExecutionAccepted];Await.result(entered.future,3.seconds)
      try {a ! Stop;ended.expectNoMessage(150.millis);state(a).status.value shouldBe "stopping"
        running.success(ExecutionResult("completed",true,"done",Some(1)));ended.expectMessage("stopped")
        val recovered=spawn(EventSourcedWorkflowActor("row-stop",engine))
        createTestProbe[Boolean]().awaitAssert {state(recovered).status.value shouldBe "completed"}
        state(recovered).recentExecutions.last.status shouldBe "completed"
        testKit.stop(recovered)
      } finally {running.trySuccess(ExecutionResult("completed",true,"done",Some(1)));testKit.stop(a)}
    }
    for (synchronous <- Vector(false,true)) {
      s"retain the stopping fence when actual cancellation fails (synchronous=$synchronous)" in {
        val called=Promise[Done]()
        val engine=new WorkflowExecutionEngine() {
          override def execute(w:WorkflowDSL.Workflow,c:ReliableRunContext,l:String=>Unit)=Promise[ExecutionResult]().future
          override def cancel(id:String):Future[Done]={called.trySuccess(Done)
            if(synchronous) throw new IllegalStateException("not released") else Future.failed(new IllegalStateException("not released"))}
        }
        val id="stop-release-failed-"+synchronous
        val a=spawn(EventSourcedWorkflowActor(id,engine));val p=createTestProbe[Reply]();val ended=watch(a)
        a ! DefineWorkflow(definition(id),0,p.ref);p.expectMessageType[Defined]
        a ! ExecuteManual("first",p.ref);p.expectMessageType[ExecutionAccepted]
        try {a ! Stop;Await.result(called.future,3.seconds);ended.expectNoMessage(150.millis)
          state(a).status.value shouldBe "stopping"
          a ! ExecuteManual("new",p.ref);p.expectMessageType[DefinitionRejected]
          state(a).currentExecution should not be empty
        } finally testKit.stop(a)
      }
    }
    "honor an early Stop queued during recovery without launching a replacement" in {
      val calls=new AtomicInteger
      val engine=new WorkflowExecutionEngine() {
        override def execute(w:WorkflowDSL.Workflow,c:ReliableRunContext,l:String=>Unit)={calls.incrementAndGet();Promise[ExecutionResult]().future}
      }
      val id="stop-during-recovery";val first=spawn(EventSourcedWorkflowActor(id,engine));val p=createTestProbe[Reply]()
      first ! DefineWorkflow(definition(id),0,p.ref);p.expectMessageType[Defined]
      first ! ExecuteManual("first",p.ref);p.expectMessageType[ExecutionAccepted]
      createTestProbe[Boolean]().awaitAssert {calls.get() shouldBe 1}
      testKit.stop(first)
      val recovered=spawn(EventSourcedWorkflowActor(id,engine));val ended=watch(recovered)
      recovered ! Stop;ended.expectMessage("stopped");calls.get() shouldBe 1
    }
    "fence a recovery launch whose release check returns after Stop" in {
      val calls=new AtomicInteger;val first=Promise[ExecutionResult]();val checked=Promise[Done]()
      val check=Promise[WorkflowExecutionEngine.ReleaseStatus]();val cancelled=Promise[Done]()
      val engine=new WorkflowExecutionEngine() {
        override def execute(w:WorkflowDSL.Workflow,c:ReliableRunContext,l:String=>Unit)={calls.incrementAndGet();first.future}
        override def cancelWithin(id:String,timeout:FiniteDuration)={checked.trySuccess(Done);check.future}
        override def cancel(id:String)=cancelled.future
      }
      val a=spawn(EventSourcedWorkflowActor("stop-recovery-race",engine));val p=createTestProbe[Reply]();val ended=watch(a)
      a ! DefineWorkflow(definition("stop-recovery-race"),0,p.ref);p.expectMessageType[Defined]
      a ! ExecuteManual("first",p.ref);p.expectMessageType[ExecutionAccepted]
      first.success(ExecutionResult("failed",false,"unknown",None,failure=Some(ExecutionFailure(ExecutionFailure.CommitUnknown,"target-commit"))))
      Await.result(checked.future,3.seconds)
      try {a ! Stop;state(a).status.value shouldBe "stopping";check.success(WorkflowExecutionEngine.Released)
        ended.expectNoMessage(100.millis);calls.get() shouldBe 1
        state(a).currentExecution.get.recovery.get.attemptsStarted shouldBe 0
        cancelled.success(Done);ended.expectMessage("stopped")
      } finally {check.trySuccess(WorkflowExecutionEngine.Released);cancelled.trySuccess(Done);testKit.stop(a)}
    }
  }
}
