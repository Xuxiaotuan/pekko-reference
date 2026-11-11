package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.scheduler.ScheduleCalculator.Definition
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import java.time.Instant
import scala.concurrent.duration._

object SchedulerCoordinator {
  private val PersistenceKey = "scheduler-coordinator"
  private val MaxRetries = 8

  sealed trait Command extends CborSerializable
  sealed trait ScheduleReply extends CborSerializable
  final case class Schedule(id: String, workflowId: String, definition: Definition, enabled: Boolean = true) extends CborSerializable
  final case class PendingTrigger(triggerId: String, scheduleId: String, workflowId: String, scheduledAt: Long, retries: Int = 0, exhausted: Boolean = false) extends CborSerializable
  final case class State(schedules: Vector[Schedule] = Vector.empty, pendingTriggers: Vector[PendingTrigger] = Vector.empty) extends CborSerializable

  final case class Add(schedule: Schedule, replyTo: ActorRef[ScheduleReply]) extends Command
  final case class Update(schedule: Schedule, replyTo: ActorRef[ScheduleReply]) extends Command
  final case class Pause(scheduleId: String, replyTo: ActorRef[ScheduleReply]) extends Command
  final case class Resume(scheduleId: String, replyTo: ActorRef[ScheduleReply]) extends Command
  final case class Remove(scheduleId: String, replyTo: ActorRef[ScheduleReply]) extends Command
  final case class ListSchedules(replyTo: ActorRef[Schedules]) extends Command
  final case class Fire(scheduleId: String, scheduledAt: Long) extends Command
  final case class GetState(replyTo: ActorRef[State]) extends Command
  final case class GetDiagnostics(replyTo: ActorRef[Diagnostics]) extends Command

  final case class ScheduleAdded(scheduleId: String) extends ScheduleReply
  final case class ScheduleUpdated(scheduleId: String) extends ScheduleReply
  final case class ScheduleRemoved(scheduleId: String) extends ScheduleReply
  final case class ScheduleRejected(reason: String) extends ScheduleReply
  final case class Schedules(values: Vector[Schedule]) extends CborSerializable
  final case class Diagnostics(replyActorCount: Int) extends CborSerializable

  private final case class Due(scheduleId: String, scheduledAt: Long) extends Command
  private final case class Retry(triggerId: String) extends Command
  private final case class WorkflowReplied(triggerId: String, reply: EventSourcedWorkflowActor.Reply) extends Command
  private case object StopReplyActor

  sealed trait Event extends CborSerializable
  final case class ScheduleUpserted(schedule: Schedule) extends Event
  final case class SchedulePaused(scheduleId: String) extends Event
  final case class ScheduleResumed(scheduleId: String) extends Event
  final case class ScheduleRemovedEvent(scheduleId: String) extends Event
  final case class TriggerPrepared(trigger: PendingTrigger) extends Event
  final case class TriggerRetryScheduled(triggerId: String, retries: Int) extends Event
  final case class TriggerExhausted(triggerId: String) extends Event
  final case class TriggerAcknowledged(triggerId: String) extends Event

  def apply(
    shardRegion: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]],
    clock: () => Instant = () => Instant.now(),
    retryBase: FiniteDuration = 250.millis,
    persistenceId: String = PersistenceKey,
    replyActorTTL: Option[FiniteDuration] = None
  ): Behavior[Command] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      def scheduleKey(scheduleId: String): String = s"schedule:$scheduleId"
      def retryKey(triggerId: String): String = s"retry:$triggerId"
      def armSchedule(schedule: Schedule, from: Instant = clock()): Unit =
        if (schedule.enabled) {
          val next = ScheduleCalculator.next(schedule.definition, from)
          val delay = math.max(0L, next.toEpochMilli - clock().toEpochMilli).millis
          timers.startSingleTimer(scheduleKey(schedule.id), Due(schedule.id, next.toEpochMilli), delay)
        }
      def retryDelay(retries: Int): FiniteDuration = {
        val multiplier = 1L << math.min(retries, 20)
        (math.min(retryBase.toMillis * multiplier, 30000L)).millis
      }
      def replyActorTtl(trigger: PendingTrigger): FiniteDuration =
        replyActorTTL.getOrElse(math.min(retryDelay(trigger.retries).toMillis + 50L, 30000L).millis)
      def deliver(trigger: PendingTrigger): Unit = {
        val replyTo = context.spawnAnonymous(Behaviors.withTimers[Any] { timers =>
          timers.startSingleTimer(StopReplyActor, replyActorTtl(trigger))
          Behaviors.receiveMessage {
            case reply: EventSourcedWorkflowActor.Reply =>
              context.self ! WorkflowReplied(trigger.triggerId, reply)
              Behaviors.stopped
            case StopReplyActor => Behaviors.stopped
            case _ => Behaviors.unhandled
          }
        }).narrow[EventSourcedWorkflowActor.Reply]
        shardRegion ! ShardingEnvelope(trigger.workflowId, EventSourcedWorkflowActor.ExecuteScheduled(trigger.scheduleId, trigger.scheduledAt, trigger.triggerId, replyTo))
        if (!trigger.exhausted) timers.startSingleTimer(retryKey(trigger.triggerId), Retry(trigger.triggerId), retryDelay(trigger.retries))
      }

      EventSourcedBehavior[Command, Event, State](
        PersistenceId.ofUniqueId(persistenceId),
        State(),
        (state, command) => command match {
          case Add(schedule, replyTo) if state.schedules.exists(_.id == schedule.id) =>
            replyTo ! ScheduleRejected(s"schedule already exists: ${schedule.id}"); Effect.none
          case Add(schedule, replyTo) =>
            ScheduleCalculator.validate(schedule.definition) match {
              case Left(reason) => replyTo ! ScheduleRejected(reason); Effect.none
              case Right(_) => Effect.persist(ScheduleUpserted(schedule)).thenRun((updated: State) => armSchedule(updated.schedules.find(_.id == schedule.id).get)).thenReply(replyTo)(_ => ScheduleAdded(schedule.id))
            }
          case Update(schedule, replyTo) if !state.schedules.exists(_.id == schedule.id) =>
            replyTo ! ScheduleRejected(s"schedule not found: ${schedule.id}"); Effect.none
          case Update(schedule, replyTo) =>
            ScheduleCalculator.validate(schedule.definition) match {
              case Left(reason) => replyTo ! ScheduleRejected(reason); Effect.none
              case Right(_) => Effect.persist(ScheduleUpserted(schedule)).thenRun { updated: State =>
                timers.cancel(scheduleKey(schedule.id))
                armSchedule(updated.schedules.find(_.id == schedule.id).get)
              }.thenReply(replyTo)(_ => ScheduleUpdated(schedule.id))
            }
          case Pause(scheduleId, replyTo) if state.schedules.exists(_.id == scheduleId) =>
            Effect.persist(SchedulePaused(scheduleId)).thenRun((_: State) => timers.cancel(scheduleKey(scheduleId))).thenReply(replyTo)(_ => ScheduleUpdated(scheduleId))
          case Resume(scheduleId, replyTo) if state.schedules.exists(_.id == scheduleId) =>
            Effect.persist(ScheduleResumed(scheduleId)).thenRun((updated: State) => armSchedule(updated.schedules.find(_.id == scheduleId).get)).thenReply(replyTo)(_ => ScheduleUpdated(scheduleId))
          case Remove(scheduleId, replyTo) if state.schedules.exists(_.id == scheduleId) =>
            val pendingTriggerIds = state.pendingTriggers.collect { case trigger if trigger.scheduleId == scheduleId => trigger.triggerId }
            Effect.persist(ScheduleRemovedEvent(scheduleId)).thenRun { (_: State) =>
              timers.cancel(scheduleKey(scheduleId))
              pendingTriggerIds.foreach(triggerId => timers.cancel(retryKey(triggerId)))
            }.thenReply(replyTo)(_ => ScheduleRemoved(scheduleId))
          case Pause(scheduleId, replyTo) => replyTo ! ScheduleRejected(s"schedule not found: $scheduleId"); Effect.none
          case Resume(scheduleId, replyTo) => replyTo ! ScheduleRejected(s"schedule not found: $scheduleId"); Effect.none
          case Remove(scheduleId, replyTo) => replyTo ! ScheduleRejected(s"schedule not found: $scheduleId"); Effect.none
          case ListSchedules(replyTo) => replyTo ! Schedules(state.schedules); Effect.none
          case GetState(replyTo) => replyTo ! state; Effect.none
          case GetDiagnostics(replyTo) => replyTo ! Diagnostics(context.children.size); Effect.none
          case Due(scheduleId, scheduledAt) =>
            state.schedules.find(schedule => schedule.id == scheduleId && schedule.enabled) match {
              case Some(schedule) =>
                val trigger = PendingTrigger(s"$scheduleId-$scheduledAt", scheduleId, schedule.workflowId, scheduledAt)
                if (state.pendingTriggers.exists(_.triggerId == trigger.triggerId)) Effect.none
                else Effect.persist(TriggerPrepared(trigger)).thenRun { (_: State) =>
                  armSchedule(schedule, Instant.ofEpochMilli(scheduledAt))
                  deliver(trigger)
                }
              case None => Effect.none
            }
          case Fire(scheduleId, scheduledAt) =>
            state.schedules.find(schedule => schedule.id == scheduleId && schedule.enabled) match {
              case Some(schedule) =>
                val trigger = PendingTrigger(s"$scheduleId-$scheduledAt", scheduleId, schedule.workflowId, scheduledAt)
                if (state.pendingTriggers.exists(_.triggerId == trigger.triggerId)) Effect.none
                else Effect.persist(TriggerPrepared(trigger)).thenRun(_ => deliver(trigger))
              case None => Effect.none
            }
          case WorkflowReplied(triggerId, _: EventSourcedWorkflowActor.ExecutionAccepted | _: EventSourcedWorkflowActor.DuplicateExecution | _: EventSourcedWorkflowActor.AlreadyRunning) if state.pendingTriggers.exists(_.triggerId == triggerId) =>
            Effect.persist(TriggerAcknowledged(triggerId)).thenRun(_ => timers.cancel(retryKey(triggerId)))
          case WorkflowReplied(_, _) => Effect.none
          case Retry(triggerId) => state.pendingTriggers.find(_.triggerId == triggerId) match {
            case Some(trigger) if trigger.retries < MaxRetries =>
              val retries = trigger.retries + 1
              Effect.persist(TriggerRetryScheduled(triggerId, retries)).thenRun((updated: State) => deliver(updated.pendingTriggers.find(_.triggerId == triggerId).get))
            case Some(_) => Effect.persist(TriggerExhausted(triggerId)).thenRun((_: State) => timers.cancel(retryKey(triggerId)))
            case None => Effect.none
          }
        },
        (state, event) => event match {
          case ScheduleUpserted(schedule) => state.copy(schedules = state.schedules.filterNot(_.id == schedule.id) :+ schedule)
          case SchedulePaused(scheduleId) => state.copy(schedules = state.schedules.map(schedule => if (schedule.id == scheduleId) schedule.copy(enabled = false) else schedule))
          case ScheduleResumed(scheduleId) => state.copy(schedules = state.schedules.map(schedule => if (schedule.id == scheduleId) schedule.copy(enabled = true) else schedule))
          case ScheduleRemovedEvent(scheduleId) => state.copy(schedules = state.schedules.filterNot(_.id == scheduleId), pendingTriggers = state.pendingTriggers.filterNot(_.scheduleId == scheduleId))
          case TriggerPrepared(trigger) => state.copy(pendingTriggers = state.pendingTriggers :+ trigger)
          case TriggerRetryScheduled(triggerId, retries) => state.copy(pendingTriggers = state.pendingTriggers.map(trigger => if (trigger.triggerId == triggerId) trigger.copy(retries = retries) else trigger))
          case TriggerExhausted(triggerId) => state.copy(pendingTriggers = state.pendingTriggers.filterNot(_.triggerId == triggerId))
          case TriggerAcknowledged(triggerId) => state.copy(pendingTriggers = state.pendingTriggers.filterNot(_.triggerId == triggerId))
        }
      ).receiveSignal {
        case (state: State, RecoveryCompleted) =>
          state.schedules.filter(_.enabled).foreach(armSchedule(_))
          state.pendingTriggers.filterNot(_.exhausted).foreach(deliver(_))
      }
    }
  }
}
