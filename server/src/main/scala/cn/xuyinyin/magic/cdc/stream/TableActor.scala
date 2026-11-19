package cn.xuyinyin.magic.cdc.stream

import org.apache.pekko.actor.typed.{ActorRef,Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.ExecutionContext

/**
  * Lightweight table lifecycle actor. It owns a single independently
  * materialized TableCdcGraph, not a JDBC connection or a dedicated thread.
  */
object TableActor {
  sealed trait Command
  final case class Start(replyTo:ActorRef[Reply]) extends Command
  final case class Stop(replyTo:ActorRef[Reply]) extends Command
  final case class Status(replyTo:ActorRef[Reply]) extends Command
  sealed trait Reply
  case object Started extends Reply
  case object Stopped extends Reply
  final case class Running(value:Boolean) extends Reply
  final case class Failed(error:String) extends Reply

  def apply(graph:()=>TableCdcGraph):Behavior[Command]=Behaviors.setup {context=>
    implicit val ec:ExecutionContext=context.system.executionContext
    var materialized:Option[TableGraphMaterialized]=None
    Behaviors.receiveMessage {
      case Start(replyTo)=>
        materialized match {
          case Some(_) => replyTo ! Started
          case None =>
            try {materialized=Some(graph().materialize());replyTo ! Started}
            catch {case error:Throwable=>replyTo ! Failed(error.toString)}
        }
        Behaviors.same
      case Stop(replyTo)=>
        materialized match {
          case None => replyTo ! Stopped
          case Some(value)=>value.stop().onComplete {case _=>replyTo ! Stopped}
        }
        Behaviors.same
      case Status(replyTo)=>replyTo ! Running(materialized.nonEmpty);Behaviors.same
    }
  }
}
