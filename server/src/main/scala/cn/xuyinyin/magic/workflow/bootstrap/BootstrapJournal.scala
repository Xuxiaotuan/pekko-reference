package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.common.CborSerializable
import org.apache.pekko.actor.typed.{ActorRef,Behavior,Scheduler}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect,EventSourcedBehavior}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import scala.concurrent.{ExecutionContext,Future}
import scala.concurrent.duration._

object BootstrapJournal {
  sealed trait Command
  final case class Read(reply:ActorRef[BootstrapProgress]) extends Command
  final case class Write(expected:Long,value:BootstrapProgress,reply:ActorRef[Result]) extends Command
  final case class Cancel(reply:ActorRef[BootstrapProgress]) extends Command
  final case class Result(accepted:Boolean,state:BootstrapProgress)
  final case class Advanced(state:BootstrapProgress) extends CborSerializable
  def apply(id:String):Behavior[Command]=Behaviors.setup { _=>
    EventSourcedBehavior[Command,Advanced,BootstrapProgress](PersistenceId.ofUniqueId("bootstrap-v1-"+id),BootstrapProgress(),
      (s,c)=>c match {
        case Read(r)=>Effect.reply(r)(s)
        case Write(expected,n,r)=>
          val valid=expected==s.revision && n.revision==expected+1 && BootstrapPhases.allowed(s.phase,n.phase) &&
            (!s.cancelRequested || n.cancelRequested) && (s.definition.isEmpty || n.definition==s.definition) &&
            (s.executionId.isEmpty || n.executionId==s.executionId) && n.recoveryAttempts>=s.recoveryAttempts && n.recoveryAttempts<=3
          if(valid)Effect.persist(Advanced(n)).thenReply(r)(x=>Result(true,x))else Effect.reply(r)(Result(false,s))
        case Cancel(r)=>
          if(s.cancelRequested || s.phase=="CANCELED" || s.phase=="PAUSED")Effect.reply(r)(s)
          else Effect.persist(Advanced(s.copy(revision=s.revision+1,phase="CANCELING",cancelRequested=true))).thenReply(r)(identity)
      },(_,e)=>e.state)
  }
}

final class BootstrapJournalAccess(ref:ActorRef[BootstrapJournal.Command])(implicit ec:ExecutionContext,scheduler:Scheduler) {
  private implicit val timeout:Timeout=Timeout(10.seconds)
  def read():Future[BootstrapProgress]=ref.ask(BootstrapJournal.Read)
  def cancel():Future[BootstrapProgress]=ref.ask(BootstrapJournal.Cancel)
  def update(f:BootstrapProgress=>BootstrapProgress):Future[BootstrapProgress]={
    def loop(left:Int):Future[BootstrapProgress]=read().flatMap {s=>
      val proposed=f(s)
      val n=proposed.copy(revision=s.revision+1,phase=if(s.cancelRequested && proposed.phase!="CANCELED" && proposed.phase!="PAUSED")"CANCELING"else proposed.phase,
        cancelRequested=s.cancelRequested || proposed.cancelRequested)
      ref.ask[BootstrapJournal.Result](r=>BootstrapJournal.Write(s.revision,n,r)).flatMap {x=>
        if(x.accepted)Future.successful(x.state)
        else if(x.state.revision!=s.revision && left>0)loop(left-1)
        else Future.failed(new cn.xuyinyin.magic.workflow.engine.CdcExecutionException(
          cn.xuyinyin.magic.workflow.engine.ExecutionFailure("checkpoint_rejected","bootstrap-journal"),new IllegalStateException("bootstrap checkpoint explicitly rejected")))
      }
    }
    loop(8)
  }
}
