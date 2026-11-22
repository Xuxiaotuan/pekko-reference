package cn.xuyinyin.magic.workflow.query

import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.RejectedExecutionException
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

class WorkflowQueryServiceSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with Eventually {
  private val kit = ActorTestKit("bounded-workflow-queries", ConfigFactory.parseString("""
    pekko.actor.provider = local
    pekko.workflow.query {
      summary-parallelism = 2
      max-concurrent-lists = 2
      timeout = 800ms
      summary-timeout = 10s
    }
  """).withFallback(ConfigFactory.load("application-test")))
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(org.scalatest.time.Span(2, org.scalatest.time.Seconds), org.scalatest.time.Span(20, org.scalatest.time.Millis))
  private implicit val ec: scala.concurrent.ExecutionContext = kit.system.executionContext
  override def afterAll(): Unit = { kit.shutdownTestKit(); super.afterAll() }
  private def await[A](future: Future[A]): A = Await.result(future, 3.seconds)
  private def summary(id: String) = EventSourcedWorkflowActor.WorkflowSummary(id, 1L, EventSourcedWorkflowActor.Completed, None, Vector.empty)

  "workflow list queries" should {
    "bound ask fan-out and preserve ID order despite reversed replies" in {
      val probe = kit.createTestProbe[WorkflowSupervisor.Command]()
      val service = new WorkflowQueryService(kit.system, probe.ref) {
        override protected[query] def persistenceIds(): Source[String, NotUsed] =
          Source(Vector("scheduler", "workflow-a", "workflow-b", "workflow-c", "workflow-d", "workflow-e", "workflow-f"))
      }
      val result = service.list(0, 6)
      for (_ <- 1 to 3) {
        val requests = probe.receiveMessages(2).map(_.asInstanceOf[WorkflowSupervisor.GetWorkflowSummary])
        probe.expectNoMessage(70.millis)
        requests.reverse.foreach(r => r.replyTo ! summary(r.workflowId))
      }
      await(result).items.map(_.workflowId) shouldBe Vector("a", "b", "c", "d", "e", "f")
    }

    "reject excess list requests and recover admission after ID streams finish" in {
      val probe = kit.createTestProbe[WorkflowSupervisor.Command]()
      val opened = new java.util.concurrent.ConcurrentLinkedQueue[Promise[Option[String]]]()
      val service = new WorkflowQueryService(kit.system, probe.ref) {
        override protected[query] def persistenceIds(): Source[String, NotUsed] =
          Source.maybe[String].mapMaterializedValue { p => opened.add(p); NotUsed }
      }
      val first = service.list(0, 1); val second = service.list(0, 1)
      eventually { opened.size() shouldBe 2 }
      await(service.list(0, 1).failed) shouldBe a[RejectedExecutionException]
      import scala.jdk.CollectionConverters._
      opened.iterator().asScala.foreach(_.trySuccess(None))
      await(first).items shouldBe empty; await(second).items shouldBe empty
      val next = service.list(0, 1)
      eventually { opened.size() shouldBe 3 }
      opened.iterator().asScala.toVector.last.trySuccess(None)
      await(next).items shouldBe empty
    }

    "cancel an idle ID stream at the overall query deadline" in {
      val probe = kit.createTestProbe[WorkflowSupervisor.Command]()
      val terminated = Promise[org.apache.pekko.Done]()
      val service = new WorkflowQueryService(kit.system, probe.ref) {
        override protected[query] def persistenceIds(): Source[String, NotUsed] =
          Source.maybe[String].watchTermination() { (_, done) => done.onComplete(terminated.tryComplete); NotUsed }
      }
      val result = service.list(0, 1)
      eventually {
        result.isCompleted shouldBe true
      }
      await(result.failed) shouldBe a[java.util.concurrent.TimeoutException]
      val ending = Await.ready(terminated.future, 3.seconds).value.get
      ending match {
        case scala.util.Success(value) => value shouldBe org.apache.pekko.Done
        case scala.util.Failure(error) => error shouldBe a[java.util.concurrent.TimeoutException]
      }
    }

    "keep timed-out requests' outstanding asks inside the global budget" in {
      val probe = kit.createTestProbe[WorkflowSupervisor.Command]()
      val service = new WorkflowQueryService(kit.system, probe.ref) {
        override protected[query] def persistenceIds(): Source[String, NotUsed] =
          Source(Vector("workflow-a", "workflow-b"))
      }
      val first = service.list(0, 2); val second = service.list(0, 2)
      val pending = probe.receiveMessages(4).map(_.asInstanceOf[WorkflowSupervisor.GetWorkflowSummary])
      await(first.failed) shouldBe a[java.util.concurrent.TimeoutException]
      await(second.failed) shouldBe a[java.util.concurrent.TimeoutException]
      service.inFlightSummaries shouldBe 4
      await(service.list(0, 1).failed) shouldBe a[RejectedExecutionException]
      probe.expectNoMessage(50.millis)
      pending.foreach(r => r.replyTo ! summary(r.workflowId))
      eventually { service.inFlightSummaries shouldBe 0 }
      val next = service.list(0, 1)
      val request = probe.expectMessageType[WorkflowSupervisor.GetWorkflowSummary]
      request.replyTo ! summary(request.workflowId)
      await(next).items.size shouldBe 1
    }

    "release admission when ID source construction fails" in {
      val probe = kit.createTestProbe[WorkflowSupervisor.Command]()
      val fail = new java.util.concurrent.atomic.AtomicBoolean(true)
      val service = new WorkflowQueryService(kit.system, probe.ref) {
        override protected[query] def persistenceIds(): Source[String, NotUsed] =
          if (fail.get()) throw new IllegalStateException("ID dependency rejected initialization") else Source.empty
      }
      (1 to 3).foreach(_ => await(service.list(0, 1).failed) shouldBe a[IllegalStateException])
      fail.set(false)
      await(service.list(0, 1)).items shouldBe empty
    }

    "normalize pagination and avoid querying IDs outside the selected page" in {
      val probe = kit.createTestProbe[WorkflowSupervisor.Command]()
      val service = new WorkflowQueryService(kit.system, probe.ref, pageSizeCap = 2) {
        override protected[query] def persistenceIds(): Source[String, NotUsed] =
          Source(Vector("scheduler", "workflow-a", "workflow-b", "workflow-c", "workflow-d"))
      }
      val result = service.list(1, Int.MaxValue)
      val requests = probe.receiveMessages(2).map(_.asInstanceOf[WorkflowSupervisor.GetWorkflowSummary])
      requests.foreach(r => r.replyTo ! summary(r.workflowId))
      val page = await(result)
      page.items.map(_.workflowId) shouldBe Vector("c", "d")
      page.page shouldBe 1; page.pageSize shouldBe 2
      probe.expectNoMessage(50.millis)
    }
  }
}
