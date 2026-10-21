package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import scala.concurrent.duration._

/** Stateless ingress router for workflow entities. */
object WorkflowSupervisor {
  sealed trait Command

  /** Compatibility command; its reply is sent only after the entity answers. */
  final case class CreateWorkflow(workflow: WorkflowDSL.Workflow, replyTo: ActorRef[WorkflowCreated]) extends Command
  final case class DefineWorkflow(workflow: WorkflowDSL.Workflow, expectedRevision: Long, replyTo: ActorRef[EventSourcedWorkflowActor.Reply]) extends Command
  final case class ExecuteWorkflow(workflowId: String, replyTo: ActorRef[EventSourcedWorkflowActor.ExecutionResponse]) extends Command
  final case class ExecuteManual(workflowId: String, requestId: String, replyTo: ActorRef[EventSourcedWorkflowActor.Reply]) extends Command
  final case class ExecuteWorkflowScheduled(workflowId: String, scheduleId: String, scheduledAt: Long, triggerId: String, replyTo: ActorRef[EventSourcedWorkflowActor.Reply]) extends Command
  final case class GetWorkflowSummary(workflowId: String, replyTo: ActorRef[EventSourcedWorkflowActor.WorkflowSummary]) extends Command
  final case class GetWorkflowStatus(workflowId: String, replyTo: ActorRef[EventSourcedWorkflowActor.StatusResponse]) extends Command
  final case class GetExecutionHistory(workflowId: String, replyTo: ActorRef[EventSourcedWorkflowActor.ExecutionHistoryResponse]) extends Command
  final case class GetPagedExecutionHistory(workflowId: String, page: Int, pageSize: Int, replyTo: ActorRef[EventSourcedWorkflowActor.ExecutionHistoryResponse]) extends Command
  final case class StopWorkflow(workflowId: String) extends Command

  sealed trait WorkflowCreated { def workflowId: String }
  final case class WorkflowDefined(workflowId: String, actorRef: ActorRef[_]) extends WorkflowCreated
  final case class WorkflowRevisionConflict(workflowId: String, expectedRevision: Long, actualRevision: Long) extends WorkflowCreated
  final case class WorkflowDefinitionRejected(workflowId: String, errors: Vector[String]) extends WorkflowCreated

  def withSharding(shardRegion: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]]): Behavior[Command] =
    withSharding(shardRegion, 5.seconds)

  private[actors] def withSharding(shardRegion: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]], replyTimeout: FiniteDuration): Behavior[Command] =
    Behaviors.setup { context =>
      require(replyTimeout.length > 0, "legacy definition reply timeout must be positive")
      def definitionReply(workflowId: String, replyTo: ActorRef[WorkflowCreated]): ActorRef[EventSourcedWorkflowActor.Reply] =
        context.spawnAnonymous(Behaviors.setup[EventSourcedWorkflowActor.Reply] { replyContext =>
          replyContext.setReceiveTimeout(replyTimeout, EventSourcedWorkflowActor.DefinitionRejected(workflowId, Vector("definition result unknown: entity reply timed out")))
          Behaviors.receiveMessage[EventSourcedWorkflowActor.Reply] {
          case EventSourcedWorkflowActor.Defined(workflowId, _) =>
            replyTo ! WorkflowDefined(workflowId, shardRegion)
            Behaviors.stopped
          case EventSourcedWorkflowActor.RevisionConflict(workflowId, expectedRevision, actualRevision) =>
            replyTo ! WorkflowRevisionConflict(workflowId, expectedRevision, actualRevision)
            Behaviors.stopped
          case EventSourcedWorkflowActor.DefinitionRejected(workflowId, errors) =>
            replyTo ! WorkflowDefinitionRejected(workflowId, errors)
            Behaviors.stopped
          case _ =>
            replyTo ! WorkflowDefinitionRejected(workflowId, Vector("definition result unknown: unexpected entity reply"))
            Behaviors.stopped
        }})

      Behaviors.receiveMessage {
        case CreateWorkflow(workflow, replyTo) =>
          shardRegion ! ShardingEnvelope(workflow.id, EventSourcedWorkflowActor.DefineWorkflow(workflow, 0L, definitionReply(workflow.id, replyTo)))
          Behaviors.same
        case DefineWorkflow(workflow, expectedRevision, replyTo) =>
          shardRegion ! ShardingEnvelope(workflow.id, EventSourcedWorkflowActor.DefineWorkflow(workflow, expectedRevision, replyTo))
          Behaviors.same
        case ExecuteWorkflow(workflowId, replyTo) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.Execute(replyTo))
          Behaviors.same
        case ExecuteManual(workflowId, requestId, replyTo) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.ExecuteManual(requestId, replyTo))
          Behaviors.same
        case ExecuteWorkflowScheduled(workflowId, scheduleId, scheduledAt, triggerId, replyTo) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.ExecuteScheduled(scheduleId, scheduledAt, triggerId, replyTo))
          Behaviors.same
        case GetWorkflowSummary(workflowId, replyTo) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(replyTo))
          Behaviors.same
        case GetWorkflowStatus(workflowId, replyTo) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetStatus(replyTo))
          Behaviors.same
        case GetExecutionHistory(workflowId, replyTo) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetExecutionHistory(replyTo))
          Behaviors.same
        case GetPagedExecutionHistory(workflowId, page, pageSize, replyTo) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetExecutionHistory(page, pageSize, replyTo))
          Behaviors.same
        case StopWorkflow(workflowId) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.Stop)
          Behaviors.same
      }
    }
}
