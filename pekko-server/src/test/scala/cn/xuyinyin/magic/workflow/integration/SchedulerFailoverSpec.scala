package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.scheduler.ScheduleCalculator.FixedRate
import cn.xuyinyin.magic.workflow.scheduler.SchedulerCoordinator
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.typed.{Cluster, ClusterSingleton, ClusterSingletonSettings, SingletonActor}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import scala.concurrent.duration._

class SchedulerFailoverSpec extends AnyWordSpec with Matchers with Eventually {
  implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(40, Seconds), interval = Span(250, Millis))

  "the coordinator-role Scheduler Singleton" should {
    "redeliver an unacknowledged trigger after host loss without accepting it twice" in {
      val database = MultiNodeTestSupport.newDatabase("scheduler-failover-")
      val clusterName = s"task8-scheduler-${java.util.UUID.randomUUID()}"
      val singletonName = s"SchedulerCoordinator-${java.util.UUID.randomUUID()}"
      val schedulerPersistenceId = s"scheduler-failover-${java.util.UUID.randomUUID()}"
      val workflowId = s"scheduled-${java.util.UUID.randomUUID()}"
      val scheduleId = s"schedule-${java.util.UUID.randomUUID()}"
      val scheduledAt = 1787961601000L
      var node1: ActorTestKit = null
      var node2: ActorTestKit = null

      try {
        node1 = ActorTestKit(clusterName, MultiNodeTestSupport.nodeConfig(database.url))
        MultiNodeTestSupport.joinSelf(node1)
        eventually {
          Cluster(node1.system).selfMember.status shouldBe MemberStatus.Up
        }

        implicit val node1ExecutionContext = node1.system.executionContext
        val region1 = WorkflowSharding.init(node1.system, new WorkflowExecutionEngine()(node1.system, node1ExecutionContext))(node1ExecutionContext)
        val singleton1 = ClusterSingleton(node1.system).init(
          SingletonActor(
            SchedulerCoordinator(region1, retryBase = 10.seconds, persistenceId = schedulerPersistenceId),
            singletonName
          ).withSettings(ClusterSingletonSettings(node1.system).withRole("coordinator"))
        )

        node2 = ActorTestKit(clusterName, MultiNodeTestSupport.nodeConfig(database.url))
        MultiNodeTestSupport.join(node2, node1)
        implicit val node2ExecutionContext = node2.system.executionContext
        val region2 = WorkflowSharding.init(node2.system, new WorkflowExecutionEngine()(node2.system, node2ExecutionContext))(node2ExecutionContext)
        val singleton2 = ClusterSingleton(node2.system).init(
          SingletonActor(
            SchedulerCoordinator(region2, retryBase = 10.seconds, persistenceId = schedulerPersistenceId),
            singletonName
          ).withSettings(ClusterSingletonSettings(node2.system).withRole("coordinator"))
        )

        eventually {
          Cluster(node1.system).state.members.count(_.status == MemberStatus.Up) shouldBe 2
          Cluster(node2.system).state.members.count(_.status == MemberStatus.Up) shouldBe 2
        }

        val addReply = node1.createTestProbe[SchedulerCoordinator.ScheduleReply]()
        singleton1 ! SchedulerCoordinator.Add(
          SchedulerCoordinator.Schedule(scheduleId, workflowId, FixedRate(1.hour)),
          addReply.ref
        )
        addReply.expectMessage(SchedulerCoordinator.ScheduleAdded(scheduleId))

        singleton1 ! SchedulerCoordinator.Fire(scheduleId, scheduledAt)
        eventually {
          val stateReply = node1.createTestProbe[SchedulerCoordinator.State]()
          singleton1 ! SchedulerCoordinator.GetState(stateReply.ref)
          val state = stateReply.receiveMessage(3.seconds)
          state.pendingTriggers.map(_.triggerId) should contain(s"$scheduleId-$scheduledAt")
        }

        val node1Address = Cluster(node1.system).selfMember.address
        MultiNodeTestSupport.terminate(node1)
        MultiNodeTestSupport.downFrom(node2, node1Address)
        eventually {
          Cluster(node2.system).state.members.map(_.address) shouldBe Set(Cluster(node2.system).selfMember.address)
        }

        val defineReply = node2.createTestProbe[EventSourcedWorkflowActor.Reply]()
        region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, defineReply.ref))
        defineReply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))

        eventually {
          val stateReply = node2.createTestProbe[SchedulerCoordinator.State]()
          singleton2 ! SchedulerCoordinator.GetState(stateReply.ref)
          stateReply.receiveMessage(5.seconds).pendingTriggers shouldBe empty
        }

        eventually {
          val summaryReply = node2.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
          region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(summaryReply.ref))
          val summary = summaryReply.receiveMessage(5.seconds)
          summary.status shouldBe EventSourcedWorkflowActor.Completed
          summary.recentExecutions should have size 1
        }

        val duplicateReply = node2.createTestProbe[EventSourcedWorkflowActor.Reply]()
        region2 ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.ExecuteScheduled(scheduleId, scheduledAt, s"$scheduleId-$scheduledAt", duplicateReply.ref)
        )
        duplicateReply.expectMessageType[EventSourcedWorkflowActor.DuplicateExecution]

        val finalSummaryReply = node2.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(finalSummaryReply.ref))
        finalSummaryReply.receiveMessage(5.seconds).recentExecutions should have size 1
      } finally {
        if (node1 != null) MultiNodeTestSupport.shutdown(node1)
        if (node2 != null) MultiNodeTestSupport.shutdown(node2)
        MultiNodeTestSupport.cleanupDatabase(database)
      }
      Files.exists(database.directory) shouldBe false
    }
  }
}
