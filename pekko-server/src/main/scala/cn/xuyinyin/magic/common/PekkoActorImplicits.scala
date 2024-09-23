package cn.xuyinyin.magic.common

import org.apache.pekko.Done
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.typed.receptionist.Receptionist.Listing
import org.apache.pekko.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, DispatcherSelector, Settings, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.stream.{KillSwitches, SharedKillSwitch, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.{Keep, RunnableGraph, Sink, Source}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

/**
 * Actor implicit conversion function, class.
 *
 * @author Xuxiaotuan
 */
object PekkoActorImplicits {

  def settings(implicit context: ActorContext[_]): Settings = context.system.settings

  def receptionist(implicit context: ActorContext[_]): ActorRef[Receptionist.Command] = context.system.receptionist

  /**
   * Implicit conversion for [[Receptionist.Find]].
   * {{{
   *  // from
   *  receptionist ! Receptionist.Find(serviceKey, adapter)
   * // to
   * receptionist ! serviceKey -> adapter
   * }}}
   */
  def receptionistFindConversion[K, U](expression: (ServiceKey[K], ActorRef[Listing])): Receptionist.Command =
    Receptionist.find(expression._1, expression._2)

  /**
   * Actor Behavior enhancement.
   */
  implicit class BehaviorWrapper[T](behavior: Behavior[T]) {

    /**
     *  Behaviors.supervise.onFailure
     */
    def onFailure[Thr <: Throwable](strategy: SupervisorStrategy)(implicit tag: ClassTag[Thr] = ClassTag(classOf[Throwable])): Behavior[T] = {
      Behaviors.supervise(behavior).onFailure[Thr](strategy)
    }

    /**
     * Call func before behavior start.
     * @return
     */
    def beforeIt(func: => Unit): Behavior[T] = {
      func
      behavior
    }
  }

  implicit class RichAddress(addr: Address) {
    def hostPort = s"${addr.host}:${addr.port}"
  }

  implicit class PekkoStreamSourceWrapper[A, M](source: Source[A, M]) {
    def withKillHook: RunnableGraph[(UniqueKillSwitch, Future[Done])] = {
      source
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
    }

    def withKillShareHook(sid: String): RunnableGraph[(SharedKillSwitch, Future[Done])] = {
      source
        .viaMat(KillSwitches.shared(s"${sid}-kill-switch").flow)(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
    }
  }

  def lookupDispatcher(path: String)(implicit ctx: ActorContext[_]): ExecutionContextExecutor = {
    ctx.system.dispatchers.lookup(DispatcherSelector.fromConfig(path))
  }
}