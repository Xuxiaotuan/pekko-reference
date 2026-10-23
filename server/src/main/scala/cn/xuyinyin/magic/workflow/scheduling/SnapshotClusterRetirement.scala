package cn.xuyinyin.magic.workflow.scheduling

import org.apache.pekko.actor.typed.{Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{Cluster,Down}
import scala.concurrent.duration._
import scala.util.{Success,Failure}

/** Membership removal follows complete DB drain, including the old shared Journal/Snapshot pool. */
object SnapshotClusterRetirement {
  sealed trait Command
  private case object Poll extends Command
  private final case class Observed(owners:Vector[SnapshotOwner]) extends Command
  private case object Failed extends Command
  def apply(runtime:TableSchedulingRuntime):Behavior[Command]=Behaviors.setup { context=>
    Behaviors.withTimers { timers=>
      var busy=false;var after=""
      val cluster=Cluster(context.system)
      timers.startTimerWithFixedDelay(Poll,Poll,250.millis)
      Behaviors.receiveMessage {
        case Poll if !busy=>
          busy=true
          context.pipeToSelf(runtime.io(runtime.store.ownership.get.owners(after,100))) {
            case Success(owners)=>Observed(owners)
            case Failure(_)=>Failed
          };Behaviors.same
        case Observed(owners)=>
          busy=false;after=if(owners.size==100)owners.last.identity.bootId else ""
          owners.filter(_.phase=="db_drained").foreach { owner=>
            cluster.state.members.find(m=>m.roles.contains("snapshot-owner-"+owner.identity.bootId) &&
              owner.address.forall(_==s"${m.address}|${m.uniqueAddress.longUid}")).foreach { member=>
              if(member.uniqueAddress!=cluster.selfMember.uniqueAddress)cluster.manager ! Down(member.address)
            }
          };Behaviors.same
        case Failed=>busy=false;Behaviors.same
        case _=>Behaviors.same
      }
    }
  }
}
