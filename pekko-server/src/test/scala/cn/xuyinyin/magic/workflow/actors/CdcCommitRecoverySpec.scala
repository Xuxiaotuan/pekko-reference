package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.engine._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._
import java.sql.DriverManager
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

object CdcCommitRecoverySpec {
  def config = {
    val url = s"jdbc:h2:mem:cdc-commit-recovery-${java.util.UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"
    val c = DriverManager.getConnection(url)
    val in = getClass.getClassLoader.getResourceAsStream("schema/h2/h2-create-schema.sql")
    val sql = try scala.io.Source.fromInputStream(in).mkString finally in.close()
    try { val s = c.createStatement(); try sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(s.execute) finally s.close() }
    finally c.close()
    ConfigFactory.parseString(s"""pekko.actor.provider=local
      |pekko.workflow.cdc-recovery.initial-backoff=20ms
      |pekko-persistence-jdbc.shared-databases.slick.db.url="$url"
      |""".stripMargin).withFallback(ConfigFactory.load("application-test"))
  }
}

class CdcCommitRecoverySpec extends ScalaTestWithActorTestKit(CdcCommitRecoverySpec.config)
  with AnyWordSpecLike with Matchers {
  import EventSourcedWorkflowActor._
  private implicit val ec: ExecutionContext = system.executionContext
  private case class Call(context: ReliableRunContext, result: Promise[ExecutionResult])
  private class Engine extends WorkflowExecutionEngine() {
    val calls = new LinkedBlockingQueue[Call]()
    val release = new AtomicReference[WorkflowExecutionEngine.ReleaseStatus](WorkflowExecutionEngine.Released)
    override def execute(w: WorkflowDSL.Workflow, c: ReliableRunContext, log: String => Unit): Future[ExecutionResult] = {
      val p = Promise[ExecutionResult](); calls.put(Call(c,p)); p.future
    }
    override def cancelWithin(id: String, timeout: FiniteDuration): Future[WorkflowExecutionEngine.ReleaseStatus] = Future.successful(release.get())
    def next(): Call = { val c = calls.poll(3,TimeUnit.SECONDS); assert(c != null,"expected next recovery attempt"); c }
  }
  private def definition(id: String) = WorkflowFixtures.mysqlCdcWorkflow.copy(id=id,
    nodes=WorkflowFixtures.mysqlCdcWorkflow.nodes.map { n =>
      if(n.`type`=="source") n.copy(config=JsObject(n.config.fields + ("groupProtocol" -> JsObject(
        "version"->JsNumber(1),"replicationId"->JsString(id),"generation"->JsString("stable"),"sourceLineage"->JsString("lineage"))))) else n
    })
  private def unknown(call: Call): Unit = call.result.success(ExecutionResult("failed",false,"commit unknown",None,
    failure=Some(ExecutionFailure(ExecutionFailure.CommitUnknown,"target-commit"))))
  private def start(id: String, e: Engine) = {
    val a=spawn(EventSourcedWorkflowActor(id,e));val p=createTestProbe[Reply]()
    a ! DefineWorkflow(definition(id),0L,p.ref);p.expectMessage(Defined(id,1L))
    a ! ExecuteManual("request",p.ref);val accepted=p.expectMessageType[ExecutionAccepted]
    (a,accepted,e.next())
  }
  private def summary(a: org.apache.pekko.actor.typed.ActorRef[Command], status: String): WorkflowSummary = {
    val p=createTestProbe[WorkflowSummary]()
    p.awaitAssert { a ! GetSummary(p.ref);val s=p.receiveMessage(); s.status.value shouldBe status;s }
  }

  "CDC commit recovery" should {
    "pause an explicit checkpoint rejection without spending a recovery attempt and retain it on restart" in {
      val e=new Engine;val(a,accepted,first)=start("explicit-checkpoint-rejection",e)
      val boundary=SnapshotBoundary("source-1","partition",Some("identity"))
      Await.result(first.context.initializeBoundary(boundary),3.seconds) shouldBe Done
      val gap=BatchCheckpoint("source-1","partition",1L,BatchId.sha256(accepted.executionId,"source-1","partition",1L),
        SourceCursor("groups","offset","identity"),1,1)
      val error=Await.result(first.context.checkpointCommitted(gap).failed,3.seconds)
      val descriptor=ExecutionFailure.from(error,"engine")
      descriptor.code shouldBe ExecutionFailure.ProtocolConflict
      descriptor.stage shouldBe "journal"
      descriptor.batchId shouldBe Some(gap.batchId)
      first.result.failure(error)
      val blocked=summary(a,"recovery_blocked").currentExecution.get
      blocked.executionId shouldBe accepted.executionId
      blocked.boundary shouldBe Some(boundary)
      blocked.checkpoints shouldBe empty
      blocked.recovery.get.attemptsStarted shouldBe 0
      blocked.recovery.get.failure shouldBe Some(descriptor)
      e.calls.poll(150,TimeUnit.MILLISECONDS) shouldBe null
      testKit.stop(a)
      val recovered=spawn(EventSourcedWorkflowActor("explicit-checkpoint-rejection",e))
      summary(recovered,"recovery_blocked").currentExecution.get shouldBe blocked
      e.calls.poll(150,TimeUnit.MILLISECONDS) shouldBe null
      testKit.stop(recovered)
    }
    "classify a rejected initialization as a protocol conflict" in {
      val e=new Engine;val(a,_,first)=start("explicit-initialization-rejection",e)
      val error=Await.result(first.context.initializeBoundary(SnapshotBoundary("wrong-source","p",Some("b"))).failed,3.seconds)
      val descriptor=ExecutionFailure.from(error,"engine")
      descriptor.code shouldBe ExecutionFailure.ProtocolConflict
      descriptor.stage shouldBe "initialization"
      first.result.failure(error)
      summary(a,"recovery_blocked").currentExecution.get.recovery.get.attemptsStarted shouldBe 0
      e.calls.poll(100,TimeUnit.MILLISECONDS) shouldBe null
      testKit.stop(a)
    }
    "accept an already persisted checkpoint without entering recovery" in {
      val e=new Engine;val(a,accepted,first)=start("accepted-checkpoint-duplicate",e)
      val boundary=SnapshotBoundary("source-1","partition",Some("identity"))
      Await.result(first.context.initializeBoundary(boundary),3.seconds) shouldBe Done
      val cp=BatchCheckpoint("source-1","partition",0L,BatchId.sha256(accepted.executionId,"source-1","partition",0L),
        SourceCursor("groups","offset","identity"),1,1)
      Await.result(first.context.checkpointCommitted(cp),3.seconds) shouldBe Done
      Await.result(first.context.checkpointCommitted(cp),3.seconds) shouldBe Done
      val state=summary(a,"running").currentExecution.get
      state.checkpoints shouldBe Vector(cp)
      state.recovery shouldBe None
      testKit.stop(a)
    }
    "retain a first nonretryable failure and its primary metadata across restart" in {
      val e=new Engine; val(a,accepted,first)=start("initial-block",e)
      val fault=ExecutionFailure(ExecutionFailure.CommitUnknown,"target-commit",Some("batch-proof"),releaseConfirmed=false)
      first.result.success(ExecutionResult("failed",false,"safe",None,failure=Some(fault)))
      val blocked=summary(a,"recovery_blocked").currentExecution.get
      blocked.executionId shouldBe accepted.executionId
      blocked.recovery.get.reasonCode shouldBe ExecutionFailure.ReleaseUnknown
      blocked.recovery.get.failure shouldBe Some(fault)
      testKit.stop(a)
      val recovered=spawn(EventSourcedWorkflowActor("initial-block",e))
      summary(recovered,"recovery_blocked").currentExecution.get.recovery.get.failure shouldBe Some(fault)
      e.calls.poll(100,TimeUnit.MILLISECONDS) shouldBe null
      testKit.stop(recovered)
    }
    "pause an initial unclassified failure instead of deleting the context" in {
      val e=new Engine;val(a,accepted,first)=start("initial-unclassified",e)
      first.result.success(ExecutionResult("failed",false,"validation failed",None))
      val blocked=summary(a,"recovery_blocked").currentExecution.get
      blocked.executionId shouldBe accepted.executionId
      blocked.recovery.get.reasonCode shouldBe ExecutionFailure.Unclassified
      blocked.recovery.get.attemptsStarted shouldBe 0
      testKit.stop(a)
    }
    "retry an unknown commit in the same execution instead of persisting failure" in {
      val e=new Engine;val(a,accepted,first)=start("unknown-commit",e)
      unknown(first);val second=e.next()
      second.context.executionId shouldBe accepted.executionId
      summary(a,"recovering").currentExecution.get.recovery.get.attemptsStarted shouldBe 1
      testKit.stop(a)
    }
    "persist the three attempt limit and retain context across actor restart" in {
      val e=new Engine;val(a,accepted,first)=start("bounded-recovery",e)
      unknown(first)
      for(_ <- 1 to 3) unknown(e.next())
      val blocked=summary(a,"recovery_blocked")
      blocked.currentExecution.get.executionId shouldBe accepted.executionId
      blocked.currentExecution.get.recovery.get.attemptsStarted shouldBe 3
      testKit.stop(a)
      val recovered=spawn(EventSourcedWorkflowActor("bounded-recovery",e))
      summary(recovered,"recovery_blocked").currentExecution.get.executionId shouldBe accepted.executionId
      e.calls.poll(150,TimeUnit.MILLISECONDS) shouldBe null
      testKit.stop(recovered)
    }
    "block when the previous local session has not been released" in {
      val e=new Engine;e.release.set(WorkflowExecutionEngine.TimedOut)
      val(a,_,first)=start("release-not-confirmed",e);unknown(first)
      summary(a,"recovery_blocked").currentExecution.get.recovery.get.reasonCode shouldBe ExecutionFailure.ReleaseUnknown
      e.calls.poll(100,TimeUnit.MILLISECONDS) shouldBe null
      testKit.stop(a)
    }
    "reject old attempt progress callbacks and keep the retry budget after new progress" in {
      val e=new Engine;val(a,_,first)=start("attempt-progress",e)
      val boundary=SnapshotBoundary("source-1","partition",Some("identity"))
      Await.result(first.context.initializeBoundary(boundary),3.seconds) shouldBe Done
      unknown(first);val second=e.next()
      val stale=Await.result(first.context.initializeBoundary(boundary).failed,3.seconds)
      stale.getMessage should include("attempt")
      val id=second.context.executionId
      val cp=BatchCheckpoint("source-1","partition",0L,BatchId.sha256(id,"source-1","partition",0L),SourceCursor("groups","offset","identity"),1,1)
      Await.result(second.context.checkpointCommitted(cp),3.seconds) shouldBe Done
      val state=summary(a,"running").currentExecution.get
      state.checkpoints shouldBe Vector(cp)
      state.recovery.get.attemptsStarted shouldBe 1
      testKit.stop(a)
    }
  }
}
