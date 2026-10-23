package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import scala.concurrent.duration._
import cn.xuyinyin.magic.workflow.scheduling.TableSchedulingRuntime
import scala.concurrent.Future
import scala.util.control.NonFatal
import spray.json._

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
    withSharding(shardRegion, 5.seconds,None)

  def withSharding(shardRegion: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]], scheduling: Option[TableSchedulingRuntime]): Behavior[Command] =
    withSharding(shardRegion,5.seconds,scheduling)

  private[actors] def withSharding(shardRegion: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]], replyTimeout: FiniteDuration): Behavior[Command] =
    withSharding(shardRegion,replyTimeout,None)

  private def withSharding(shardRegion: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]], replyTimeout: FiniteDuration,
    scheduling: Option[TableSchedulingRuntime]): Behavior[Command] =
    Behaviors.setup { context =>
      implicit val ec: scala.concurrent.ExecutionContext = context.executionContext
      require(replyTimeout.length > 0, "legacy definition reply timeout must be positive")
      def indexedExecution(id: String, request: String, reply: ActorRef[EventSourcedWorkflowActor.Reply])(fallback: => Unit): Unit = scheduling match {
        case None => fallback
        case Some(runtime) =>
          runtime.find(id).flatMap {
            case None => fallback; Future.successful(())
            case Some(t) if !Set("registered","completed").contains(t.state) =>
              reply ! (if(t.requestKey.contains(request)) EventSourcedWorkflowActor.DuplicateExecution(request,t.executionId.get)
                else EventSourcedWorkflowActor.AlreadyRunning(t.executionId.get)); Future.successful(())
            case Some(t) =>
              import WorkflowDSL.workflowFormat
              runtime.submit(t.definition.workflowJson.parseJson.convertTo[WorkflowDSL.Workflow],request,t.definition.revision)
                .map(saved => { reply ! EventSourcedWorkflowActor.ExecutionAccepted(saved.executionId.get); () })
          }.recover { case NonFatal(_) => reply ! EventSourcedWorkflowActor.SchedulingUnavailable("scheduling result unavailable; retry the same request id"); () }
      }
      def routeDefinition(workflow: WorkflowDSL.Workflow, expected: Long, reply: ActorRef[EventSourcedWorkflowActor.Reply]): Unit = {
        def deliver(): Unit = shardRegion ! ShardingEnvelope(workflow.id,EventSourcedWorkflowActor.DefineWorkflow(workflow,expected,reply))
        scheduling match {
          case None => deliver()
          case Some(runtime) =>
            val managed=workflow.nodes.exists(n=>n.nodeType=="mysql.snapshot" && n.config.fields.contains("splitRows"))
            runtime.find(workflow.id).flatMap { indexed =>
              if((indexed.nonEmpty || managed) && (expected>0L || !managed)) {
                reply ! EventSourcedWorkflowActor.DefinitionRejected(workflow.id,Vector("managed snapshot revisions must use the durable snapshot-tasks ingress"))
                Future.successful(())
              } else if(managed) {
                val registration=try runtime.register(workflow,1L) catch {case NonFatal(e)=>Future.failed(e)}
                registration.map { saved=>
                  if(runtime.store.ownership.nonEmpty)reply ! EventSourcedWorkflowActor.Defined(workflow.id,saved.definition.revision)
                  else deliver()
                }
              } else {deliver();Future.successful(())}
            }.recover {case NonFatal(_)=>reply ! EventSourcedWorkflowActor.SchedulingUnavailable("definition/index result unavailable; retry the same definition");()}
        }
      }
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
          routeDefinition(workflow,0L,definitionReply(workflow.id,replyTo))
          Behaviors.same
        case DefineWorkflow(workflow, expectedRevision, replyTo) =>
          routeDefinition(workflow,expectedRevision,replyTo)
          Behaviors.same
        case ExecuteWorkflow(workflowId, replyTo) =>
          shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.Execute(replyTo))
          Behaviors.same
        case ExecuteManual(workflowId, requestId, replyTo) =>
          indexedExecution(workflowId,requestId,replyTo) {
            shardRegion ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.ExecuteManual(requestId, replyTo))
          }
          Behaviors.same
        case ExecuteWorkflowScheduled(workflowId, scheduleId, scheduledAt, triggerId, replyTo) =>
          // Legacy Cron retains its original durable trigger/watermark semantics; connector IO still enters Actor admission.
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
