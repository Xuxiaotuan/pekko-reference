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
  private[workflow] final case class ResumePendingB(expected:Long,executionId:String,definition:String,permit:String,reply:ActorRef[String]) extends Command
  private[workflow] final case class ResumePendingBWithReceipt(expected:Long,executionId:String,definition:String,permit:String,digest:String,reply:ActorRef[String]) extends Command
  private[workflow] final case class CompleteRecoveryReceipt(expected:Long,permit:String,digest:String,reply:ActorRef[String]) extends Command
  private[workflow] final case class ReadPersistedRecoveryReceipt(reply:ActorRef[Option[PersistedRecoveryReceipt]]) extends Command
  private[workflow] final case class ReadPersistedProgress(reply:ActorRef[Option[PersistedProgress]]) extends Command
  private final case class ResumeChecked(expected:Long,executionId:String,definition:String,permit:String,accepted:Option[Boolean],reply:ActorRef[String]) extends Command
  private final case class ResumeReceiptChecked(expected:Long,executionId:String,definition:String,permit:String,digest:String,accepted:Option[Boolean],reply:ActorRef[String]) extends Command
  private final case class CompletionChecked(expected:Long,permit:String,digest:String,accepted:Option[Boolean],reply:ActorRef[String]) extends Command
  final class PersistedRecoveryReceipt private[BootstrapJournal] (val persistenceId:String,val sequenceNr:Long,val revision:Long,
    val executionId:String,val permitId:String,val receiptDigest:String,val phase:String)
  final class PersistedProgress private[BootstrapJournal] (val persistenceId:String,val sequenceNr:Long,val state:BootstrapProgress)
  final case class Result(accepted:Boolean,state:BootstrapProgress)
  final case class Advanced(state:BootstrapProgress) extends CborSerializable
  def apply(id:String,validatePendingB:(String,BootstrapProgress)=>Future[Boolean]=(_,_)=>Future.successful(false),
    validateReceipt:(String,String,BootstrapProgress)=>Future[Boolean]=(_,_,_)=>Future.successful(false),
    validateConsumed:(String,String,BootstrapProgress)=>Future[Boolean]=(_,_,_)=>Future.successful(false)):Behavior[Command]=Behaviors.setup { ctx=>
    def eligible(s:BootstrapProgress,expected:Long,execution:String,definition:String):Boolean=
      s.revision==expected && ((s.phase=="PAUSED" && s.failure.contains("local_release_unconfirmed")) ||
        (s.phase=="QUEUED" && s.failure.isEmpty)) &&
        execution.nonEmpty && s.executionId==execution && definition.nonEmpty && s.definition==definition && !s.cancelRequested &&
        !s.planned && s.upper.isEmpty && s.nextLower.isEmpty && s.index==0 && s.attempt==0 && s.window.isEmpty &&
        s.manifest.isEmpty && s.appliedPage==0 && s.fence.isEmpty && s.deltaAfter==0 && s.batchSequence==0 && s.completionRoot.isEmpty
    EventSourcedBehavior[Command,Advanced,BootstrapProgress](PersistenceId.ofUniqueId("bootstrap-v1-"+id),BootstrapProgress(),
      (s,c)=>c match {
        case Read(r)=>Effect.reply(r)(s)
        case ReadPersistedProgress(r)=>
          val sequence=EventSourcedBehavior.lastSequenceNumber(ctx)
          Effect.reply(r)(if(sequence>0)Some(new PersistedProgress("bootstrap-v1-"+id,sequence,s))else None)
        case ReadPersistedRecoveryReceipt(r)=>
          val evidence=for(p<-s.recoveryPermit;d<-s.recoveryReceiptDigest if s.executionId.nonEmpty && !s.cancelRequested &&
            s.phase=="RECOVERY_PENDING")yield new PersistedRecoveryReceipt("bootstrap-v1-"+id,EventSourcedBehavior.lastSequenceNumber(ctx),s.revision,s.executionId,p,d,s.phase)
          Effect.reply(r)(evidence)
        case Write(expected,n,r)=>
          val valid=s.phase!="RECOVERY_PENDING" && expected==s.revision && n.revision==expected+1 && BootstrapPhases.allowed(s.phase,n.phase) &&
            (!s.cancelRequested || n.cancelRequested) && (s.definition.isEmpty || n.definition==s.definition) &&
            (s.executionId.isEmpty || n.executionId==s.executionId) && n.recoveryPermit==s.recoveryPermit && n.recoveryReceiptDigest==s.recoveryReceiptDigest &&
            n.recoveryAttempts>=s.recoveryAttempts && n.recoveryAttempts<=3
          if(valid)Effect.persist(Advanced(n)).thenReply(r)(x=>Result(true,x))else Effect.reply(r)(Result(false,s))
        case Cancel(r)=>
          if(s.cancelRequested || s.phase=="CANCELED" || s.phase=="PAUSED" || s.phase=="RECOVERY_PENDING")Effect.reply(r)(s)
          else Effect.persist(Advanced(s.copy(revision=s.revision+1,phase="CANCELING",cancelRequested=true))).thenReply(r)(identity)
        case ResumePendingB(expected,execution,definition,permit,r)=>
          if(!eligible(s,expected,execution,definition) || s.recoveryReceiptDigest.nonEmpty || !permit.matches("[A-Za-z0-9_-]{1,128}"))Effect.reply(r)("REJECTED")
          else {
            val checked=try validatePendingB(permit,s) catch {case scala.util.control.NonFatal(e)=>Future.failed(e)}
            ctx.pipeToSelf(checked)(result=>ResumeChecked(expected,execution,definition,permit,result.toOption,r))
            Effect.none
          }
        case ResumeChecked(expected,execution,definition,permit,accepted,r)=>
          if(accepted.isEmpty)Effect.reply(r)("UNKNOWN")
          else if(!accepted.contains(true) || !eligible(s,expected,execution,definition))Effect.reply(r)("REJECTED")
          else Effect.persist(Advanced(s.copy(revision=s.revision+1,phase="QUEUED",failure=None,recoveryPermit=Some(permit)))).thenReply(r)(_=>"ACCEPTED")
        case ResumePendingBWithReceipt(expected,execution,definition,permit,digest,r)=>
          if(!eligible(s,expected,execution,definition) || !permit.matches("[A-Za-z0-9_-]{1,128}") || !digest.matches("[a-f0-9]{64}") ||
            s.recoveryReceiptDigest.exists(_!=digest) || s.recoveryPermit.exists(_!=permit))Effect.reply(r)("REJECTED")
          else {
            val checked=try validateReceipt(permit,digest,s) catch {case scala.util.control.NonFatal(e)=>Future.failed(e)}
            ctx.pipeToSelf(checked)(result=>ResumeReceiptChecked(expected,execution,definition,permit,digest,result.toOption,r))
            Effect.none
          }
        case ResumeReceiptChecked(expected,execution,definition,permit,digest,accepted,r)=>
          if(accepted.isEmpty)Effect.reply(r)("UNKNOWN")
          else if(!accepted.contains(true) || !eligible(s,expected,execution,definition) ||
            s.recoveryReceiptDigest.exists(_!=digest) || s.recoveryPermit.exists(_!=permit))Effect.reply(r)("REJECTED")
          else Effect.persist(Advanced(s.copy(revision=s.revision+1,phase="RECOVERY_PENDING",failure=None,recoveryPermit=Some(permit),
            recoveryReceiptDigest=Some(digest)))).thenReply(r)(_=>"ACCEPTED")
        case CompleteRecoveryReceipt(expected,permit,digest,r)=>
          if(s.phase!="RECOVERY_PENDING" || s.revision!=expected || !s.recoveryPermit.contains(permit) ||
            !s.recoveryReceiptDigest.contains(digest) || s.cancelRequested)Effect.reply(r)("REJECTED")
          else {
            val checked=try validateConsumed(permit,digest,s) catch {case scala.util.control.NonFatal(e)=>Future.failed(e)}
            ctx.pipeToSelf(checked)(result=>CompletionChecked(expected,permit,digest,result.toOption,r))
            Effect.none
          }
        case CompletionChecked(expected,permit,digest,accepted,r)=>
          if(accepted.isEmpty)Effect.reply(r)("UNKNOWN")
          else if(!accepted.contains(true) || s.phase!="RECOVERY_PENDING" || s.revision!=expected || s.cancelRequested ||
            !s.recoveryPermit.contains(permit) || !s.recoveryReceiptDigest.contains(digest))Effect.reply(r)("REJECTED")
          else Effect.persist(Advanced(s.copy(revision=s.revision+1,phase="QUEUED"))).thenReply(r)(_=>"ACCEPTED")
      },(_,e)=>e.state)
  }
}

final class BootstrapJournalAccess(ref:ActorRef[BootstrapJournal.Command])(implicit ec:ExecutionContext,scheduler:Scheduler) {
  private implicit val timeout:Timeout=Timeout(10.seconds)
  def read():Future[BootstrapProgress]=ref.ask(BootstrapJournal.Read)
  def cancel():Future[BootstrapProgress]=ref.ask(BootstrapJournal.Cancel)
  private[workflow] def resumePendingB(expected:Long,executionId:String,definition:String,permit:String):Future[String]=
    ref.ask(r=>BootstrapJournal.ResumePendingB(expected,executionId,definition,permit,r))
  private[workflow] def resumePendingBWithReceipt(expected:Long,executionId:String,definition:String,permit:String,digest:String):Future[String]=
    ref.ask(r=>BootstrapJournal.ResumePendingBWithReceipt(expected,executionId,definition,permit,digest,r))
  private[workflow] def persistedRecoveryReceipt():Future[Option[BootstrapJournal.PersistedRecoveryReceipt]]=
    ref.ask(BootstrapJournal.ReadPersistedRecoveryReceipt)
  private[workflow] def persistedProgress():Future[Option[BootstrapJournal.PersistedProgress]]=
    ref.ask(BootstrapJournal.ReadPersistedProgress)
  private[workflow] def completeRecoveryReceipt(expected:Long,permit:String,digest:String):Future[String]=
    ref.ask(r=>BootstrapJournal.CompleteRecoveryReceipt(expected,permit,digest,r))
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
