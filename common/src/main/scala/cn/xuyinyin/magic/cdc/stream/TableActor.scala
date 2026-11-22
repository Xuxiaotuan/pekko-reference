package cn.xuyinyin.magic.cdc.stream

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.ExecutionContext

/**
 * Lightweight table lifecycle actor. It owns a single independently
 * materialized TableCdcGraph, not a JDBC connection or a dedicated thread.
 */
object TableActor {
  sealed trait Command
  final case class Start(replyTo: ActorRef[Reply])          extends Command
  final case class StartRecovered(replyTo: ActorRef[Reply]) extends Command
  final case class Stop(replyTo: ActorRef[Reply])           extends Command
  final case class Status(replyTo: ActorRef[Reply])         extends Command
  sealed trait Reply
  case object Started                      extends Reply
  case object Stopped                      extends Reply
  final case class Running(value: Boolean) extends Reply
  final case class Failed(error: String)   extends Reply

  def apply(graph: () => TableCdcGraph): Behavior[Command] = Behaviors.setup { context =>
    implicit val ec: ExecutionContext = context.system.executionContext
    sealed trait Lifecycle
    case object Idle                                                                                 extends Lifecycle
    case object Recovering                                                                           extends Lifecycle
    final case class Active(materialized: TableGraphMaterialized)                                    extends Lifecycle
    case object Stopping                                                                             extends Lifecycle
    final case class StopCompleted(replyTo: ActorRef[Reply])                                         extends Command
    final case class RecoveryStarted(materialized: TableGraphMaterialized, replyTo: ActorRef[Reply]) extends Command
    final case class RecoveryFailed(replyTo: ActorRef[Reply], error: String)                         extends Command
    var lifecycle: Lifecycle = Idle
    Behaviors.receiveMessage {
      case Start(replyTo) =>
        lifecycle match {
          case Active(_)  => replyTo ! Started
          case Stopping   => replyTo ! Failed("table graph is stopping")
          case Recovering => replyTo ! Failed("table graph is recovering")
          case Idle =>
            try { lifecycle = Active(graph().materialize()); replyTo ! Started }
            catch { case error: Throwable => replyTo ! Failed(error.toString) }
        }
        Behaviors.same
      case StartRecovered(replyTo) =>
        lifecycle match {
          case Active(_)  => replyTo ! Started
          case Stopping   => replyTo ! Failed("table graph is stopping")
          case Recovering => replyTo ! Failed("table graph is already recovering")
          case Idle =>
            lifecycle = Recovering
            try {
              graph().materializeRecovered().onComplete {
                case scala.util.Success(value) => context.self ! RecoveryStarted(value, replyTo)
                case scala.util.Failure(error) => context.self ! RecoveryFailed(replyTo, error.toString)
              }
            } catch { case error: Throwable => lifecycle = Idle; replyTo ! Failed(error.toString) }
        }
        Behaviors.same
      case Stop(replyTo) =>
        lifecycle match {
          case Idle       => replyTo ! Stopped
          case Recovering => replyTo ! Failed("table graph is recovering")
          case Stopping   => replyTo ! Failed("table graph is already stopping")
          case Active(value) =>
            lifecycle = Stopping
            value.stop().onComplete { _ => context.self ! StopCompleted(replyTo) }
        }
        Behaviors.same
      case StopCompleted(replyTo)          => lifecycle = Idle; replyTo ! Stopped; Behaviors.same
      case RecoveryStarted(value, replyTo) => lifecycle = Active(value); replyTo ! Started; Behaviors.same
      case RecoveryFailed(replyTo, error)  => lifecycle = Idle; replyTo ! Failed(error); Behaviors.same
      case Status(replyTo)                 => replyTo ! Running(lifecycle.isInstanceOf[Active]); Behaviors.same
    }
  }
}
