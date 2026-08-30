package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class WorkflowSupervisorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {
  "WorkflowSupervisor" should {
    "route one definition envelope carrying the caller reply reference" in {
      val shardRegion = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val supervisor = spawn(WorkflowSupervisor.withSharding(shardRegion.ref))
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()

      supervisor ! WorkflowSupervisor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)

      val envelope = shardRegion.expectMessageType[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
      envelope.entityId shouldBe WorkflowFixtures.linearWorkflow.id
      envelope.message shouldBe EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      shardRegion.expectNoMessage(200.millis)
    }

    "keep concurrent legacy definition replies with their original callers" in {
      val shardRegion = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val supervisor = spawn(WorkflowSupervisor.withSharding(shardRegion.ref))
      val first = createTestProbe[WorkflowSupervisor.WorkflowCreated]()
      val second = createTestProbe[WorkflowSupervisor.WorkflowCreated]()
      val firstWorkflow = WorkflowFixtures.linearWorkflow.copy(id = "first")
      val secondWorkflow = WorkflowFixtures.linearWorkflow.copy(id = "second")

      supervisor ! WorkflowSupervisor.CreateWorkflow(firstWorkflow, first.ref)
      supervisor ! WorkflowSupervisor.CreateWorkflow(secondWorkflow, second.ref)

      val firstEnvelope = shardRegion.receiveMessage()
      val secondEnvelope = shardRegion.receiveMessage()
      def defineReply(envelope: ShardingEnvelope[EventSourcedWorkflowActor.Command]) = envelope.message match {
        case EventSourcedWorkflowActor.DefineWorkflow(_, _, replyTo) => replyTo
        case other => fail(s"expected DefineWorkflow, received $other")
      }

      defineReply(secondEnvelope) ! EventSourcedWorkflowActor.Defined(secondEnvelope.entityId, 1L)
      defineReply(firstEnvelope) ! EventSourcedWorkflowActor.Defined(firstEnvelope.entityId, 1L)

      first.expectMessage(WorkflowSupervisor.WorkflowDefined("first", shardRegion.ref))
      second.expectMessage(WorkflowSupervisor.WorkflowDefined("second", shardRegion.ref))
      shardRegion.expectNoMessage(200.millis)
    }

    "route every Task 2 command in exactly one envelope" in {
      val shardRegion = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val supervisor = spawn(WorkflowSupervisor.withSharding(shardRegion.ref))
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val summary = createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
      val status = createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      val history = createTestProbe[EventSourcedWorkflowActor.ExecutionHistoryResponse]()
      def envelope(entityId: String, command: EventSourcedWorkflowActor.Command): ShardingEnvelope[EventSourcedWorkflowActor.Command] =
        ShardingEnvelope(entityId, command)

      supervisor ! WorkflowSupervisor.ExecuteManual("manual", "request-1", reply.ref)
      shardRegion.expectMessage(envelope("manual", EventSourcedWorkflowActor.ExecuteManual("request-1", reply.ref)))
      shardRegion.expectNoMessage(200.millis)

      supervisor ! WorkflowSupervisor.ExecuteWorkflowScheduled("scheduled", "daily", 1000L, "trigger-1", reply.ref)
      shardRegion.expectMessage(envelope("scheduled", EventSourcedWorkflowActor.ExecuteScheduled("daily", 1000L, "trigger-1", reply.ref)))
      shardRegion.expectNoMessage(200.millis)

      supervisor ! WorkflowSupervisor.GetWorkflowSummary("summary", summary.ref)
      shardRegion.expectMessage(envelope("summary", EventSourcedWorkflowActor.GetSummary(summary.ref)))
      shardRegion.expectNoMessage(200.millis)

      supervisor ! WorkflowSupervisor.GetWorkflowStatus("status", status.ref)
      shardRegion.expectMessage(envelope("status", EventSourcedWorkflowActor.GetStatus(status.ref)))
      shardRegion.expectNoMessage(200.millis)

      supervisor ! WorkflowSupervisor.GetExecutionHistory("history", history.ref)
      shardRegion.expectMessage(envelope("history", EventSourcedWorkflowActor.GetExecutionHistory(history.ref)))
      shardRegion.expectNoMessage(200.millis)

      supervisor ! WorkflowSupervisor.GetPagedExecutionHistory("paged-history", 2, 10, history.ref)
      shardRegion.expectMessage(envelope("paged-history", EventSourcedWorkflowActor.GetExecutionHistory(2, 10, history.ref)))
      shardRegion.expectNoMessage(200.millis)

      supervisor ! WorkflowSupervisor.StopWorkflow("stop")
      shardRegion.expectMessage(envelope("stop", EventSourcedWorkflowActor.Stop))
      shardRegion.expectNoMessage(200.millis)
    }

    "wait for the legacy entity reply before acknowledging a definition" in {
      val shardRegion = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val supervisor = spawn(WorkflowSupervisor.withSharding(shardRegion.ref))
      val caller = createTestProbe[WorkflowSupervisor.WorkflowCreated]()

      supervisor ! WorkflowSupervisor.CreateWorkflow(WorkflowFixtures.linearWorkflow, caller.ref)
      caller.expectNoMessage(200.millis)
      val envelope = shardRegion.receiveMessage()
      val entityReply = envelope.message match {
        case EventSourcedWorkflowActor.DefineWorkflow(_, _, replyTo) => replyTo
        case other => fail(s"expected DefineWorkflow, received $other")
      }
      entityReply ! EventSourcedWorkflowActor.Defined(WorkflowFixtures.linearWorkflow.id, 1L)

      caller.expectMessage(WorkflowSupervisor.WorkflowDefined(WorkflowFixtures.linearWorkflow.id, shardRegion.ref))
      shardRegion.expectNoMessage(200.millis)
    }

    "map legacy definition conflicts and rejections without sending a second envelope" in {
      val shardRegion = createTestProbe[ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
      val supervisor = spawn(WorkflowSupervisor.withSharding(shardRegion.ref))
      val conflictCaller = createTestProbe[WorkflowSupervisor.WorkflowCreated]()
      val rejectedCaller = createTestProbe[WorkflowSupervisor.WorkflowCreated]()
      val conflictWorkflow = WorkflowFixtures.linearWorkflow.copy(id = "conflict")
      val rejectedWorkflow = WorkflowFixtures.linearWorkflow.copy(id = "rejected")
      def definitionReply(envelope: ShardingEnvelope[EventSourcedWorkflowActor.Command]) = envelope.message match {
        case EventSourcedWorkflowActor.DefineWorkflow(_, _, replyTo) => replyTo
        case other => fail(s"expected DefineWorkflow, received $other")
      }

      supervisor ! WorkflowSupervisor.CreateWorkflow(conflictWorkflow, conflictCaller.ref)
      val conflictEnvelope = shardRegion.receiveMessage()
      definitionReply(conflictEnvelope) ! EventSourcedWorkflowActor.RevisionConflict("conflict", 0L, 1L)
      conflictCaller.expectMessage(WorkflowSupervisor.WorkflowRevisionConflict("conflict", 0L, 1L))
      shardRegion.expectNoMessage(200.millis)

      supervisor ! WorkflowSupervisor.CreateWorkflow(rejectedWorkflow, rejectedCaller.ref)
      val rejectedEnvelope = shardRegion.receiveMessage()
      definitionReply(rejectedEnvelope) ! EventSourcedWorkflowActor.DefinitionRejected("rejected", Vector("invalid"))
      rejectedCaller.expectMessage(WorkflowSupervisor.WorkflowDefinitionRejected("rejected", Vector("invalid")))
      shardRegion.expectNoMessage(200.millis)
    }
  }
}
