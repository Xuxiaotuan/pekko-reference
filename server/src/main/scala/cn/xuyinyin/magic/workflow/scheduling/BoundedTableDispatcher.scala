package cn.xuyinyin.magic.workflow.scheduling

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import org.apache.pekko.actor.typed.{ActorRef,Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import scala.concurrent.duration._
import scala.util.{Success,Failure}

/** One poll in flight; durable outstanding grants bound activation and survive coordinator restart. */
object BoundedTableDispatcher {
  sealed trait Command
  case object Poll extends Command
  private final case class Batch(grants: Vector[TableGrant]) extends Command
  private case object PollFailed extends Command
  def apply(runtime: TableSchedulingRuntime, region: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]],
    interval: FiniteDuration = 250.millis): Behavior[Command] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      require(interval > Duration.Zero)
      var polling=false
      timers.startTimerWithFixedDelay(Poll,Poll,interval)
      context.self ! Poll
      Behaviors.receiveMessage {
        case Poll if !polling =>
          polling=true
          context.pipeToSelf(runtime.dispatchBatch()) {
            case Success(grants) => Batch(grants)
            case Failure(_) => PollFailed
          }
          Behaviors.same
        case Batch(grants) =>
          polling=false
          grants.foreach(g => region ! ShardingEnvelope(g.task.workflowId,EventSourcedWorkflowActor.ActivateSnapshot(g)))
          Behaviors.same
        case PollFailed =>
          polling=false
          context.log.warn("Table scheduling poll failed; durable grants remain charged")
          Behaviors.same
        case _ => Behaviors.same
      }
    }
  }
}
