package cn.xuyinyin.magic.cdc.stream

import org.apache.pekko.actor.typed.{ActorRef,Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

/** Lightweight control plane for independently materialized table graphs. */
object TableGraphRegistry {
  sealed trait Command
  final case class Add(tableId:String,graph:()=>TableCdcGraph,replyTo:ActorRef[Reply]) extends Command
  final case class Remove(tableId:String,replyTo:ActorRef[Reply]) extends Command
  final case class Status(tableId:String,replyTo:ActorRef[Reply]) extends Command
  final case class StopAll(replyTo:ActorRef[Reply]) extends Command

  private final case class Started(tableId:String,actor:ActorRef[TableActor.Command],replyTo:ActorRef[Reply]) extends Command
  private final case class StartFailed(tableId:String,replyTo:ActorRef[Reply],reason:String) extends Command
  private final case class Removed(tableId:String,replyTo:ActorRef[Reply]) extends Command
  private final case class RemoveFailed(tableId:String,replyTo:ActorRef[Reply],reason:String) extends Command
  private final case class RemovedForAll(tableId:String) extends Command

  sealed trait Reply
  case object Added extends Reply
  case object RemovedReply extends Reply
  case object AllStoppedReply extends Reply
  final case class Running(value:Boolean) extends Reply
  case object Absent extends Reply
  final case class Rejected(reason:String) extends Reply

  def apply():Behavior[Command]=Behaviors.setup {context=>
    var tables=Map.empty[String,ActorRef[TableActor.Command]]
    var starting=Map.empty[String,ActorRef[TableActor.Command]]
    var stoppingAll:Option[ActorRef[Reply]]=None

    Behaviors.receiveMessage {
      case Add(_,_,replyTo) if stoppingAll.nonEmpty =>
        replyTo ! Rejected("table graph registry is stopping")
        Behaviors.same
      case Add(tableId,graph,replyTo) if tables.contains(tableId) || starting.contains(tableId) =>
        replyTo ! Rejected("table graph already exists")
        Behaviors.same
      case Add(tableId,graph,replyTo) =>
        val actor=context.spawnAnonymous(TableActor(graph))
        starting+=tableId->actor
        val probe=context.spawnAnonymous(Behaviors.receiveMessage[TableActor.Reply] {
          case TableActor.Started => context.self ! Started(tableId,actor,replyTo);Behaviors.stopped
          case TableActor.Failed(error) => context.self ! StartFailed(tableId,replyTo,error);Behaviors.stopped
          case _ => Behaviors.same
        })
        actor ! TableActor.Start(probe)
        Behaviors.same
      case Started(tableId,actor,replyTo) =>
        starting-=tableId
        tables+=tableId->actor
        replyTo ! Added
        Behaviors.same
      case StartFailed(tableId,replyTo,reason) =>
        starting.get(tableId).foreach(context.stop)
        starting-=tableId
        replyTo ! Rejected(reason)
        Behaviors.same
      case Remove(tableId,replyTo) =>
        if(stoppingAll.nonEmpty) replyTo ! Rejected("table graph registry is stopping")
        else tables.get(tableId) match {
          case None => replyTo ! Absent
          case Some(actor) =>
            val probe=context.spawnAnonymous(Behaviors.receiveMessage[TableActor.Reply] {
              case TableActor.Stopped => context.self ! Removed(tableId,replyTo);Behaviors.stopped
              case TableActor.Failed(error) => context.self ! RemoveFailed(tableId,replyTo,error);Behaviors.stopped
              case _ => Behaviors.same
            })
            actor ! TableActor.Stop(probe)
        }
        Behaviors.same
      case Removed(tableId,replyTo) =>
        tables.get(tableId).foreach(context.stop)
        tables-=tableId
        replyTo ! RemovedReply
        Behaviors.same
      case RemoveFailed(_,replyTo,reason) =>
        replyTo ! Rejected(reason)
        Behaviors.same
      case Status(tableId,replyTo) =>
        tables.get(tableId) match {
          case None => replyTo ! Absent
          case Some(actor) =>
            val probe=context.spawnAnonymous(Behaviors.receiveMessage[TableActor.Reply] {
              case TableActor.Running(value) => replyTo ! Running(value);Behaviors.stopped
              case _ => Behaviors.same
            })
            actor ! TableActor.Status(probe)
        }
        Behaviors.same
      case StopAll(replyTo) =>
        if(stoppingAll.nonEmpty || starting.nonEmpty) replyTo ! Rejected("table graph registry is changing")
        else if(tables.isEmpty) replyTo ! AllStoppedReply
        else {
          stoppingAll=Some(replyTo)
          tables.foreach {case(tableId,actor)=>
            val probe=context.spawnAnonymous(Behaviors.receiveMessage[TableActor.Reply] {
              case TableActor.Stopped => context.self ! RemovedForAll(tableId);Behaviors.stopped
              case _ => Behaviors.same
            })
            actor ! TableActor.Stop(probe)
          }
        }
        Behaviors.same
      case RemovedForAll(tableId) =>
        tables.get(tableId).foreach(context.stop)
        tables-=tableId
        if(tables.isEmpty) {
          stoppingAll.foreach(_ ! AllStoppedReply)
          stoppingAll=None
        }
        Behaviors.same
    }
  }
}
