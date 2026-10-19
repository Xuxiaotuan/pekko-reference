package cn.xuyinyin.magic.workflow.engine

import com.typesafe.scalalogging.Logger
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/** Engine-local session ownership. Replacements wait for actual predecessor release;
  * a control-plane observation timeout never opens the fence.
  */
private[engine] final class ReliableExecutionSessions(logger: Logger)(
  implicit system: ActorSystem[_], ec: ExecutionContext
) {
  private val activeReliableExecutions = mutable.Map.empty[String, Vector[ReliableExecutionControl]]
  private val activeReliableExecutionsLock = new AnyRef

  def cancel(executionId: String): Future[Done] = {
    val releases = activeReliableExecutionsLock.synchronized {
      activeReliableExecutions.getOrElse(executionId, Vector.empty).map(_.cancel())
    }
    if (releases.isEmpty) Future.successful(Done)
    else Future.sequence(releases).map(_ => Done)
  }

  /** A timeout is an observation, never permission to replace an unreleased session. */
  def cancelWithin(executionId: String, timeout: FiniteDuration)(
    requestCancel: => Future[Done]
  ): Future[WorkflowExecutionEngine.ReleaseStatus] = {
    import WorkflowExecutionEngine._
    val controls = activeReliableExecutionsLock.synchronized {
      activeReliableExecutions.getOrElse(executionId, Vector.empty)
    }
    val result = Promise[ReleaseStatus]()
    val timer = system.scheduler.scheduleOnce(timeout, new Runnable {
      def run(): Unit = {
        val status = if (controls.exists(_.closeFailure.get() != null)) CloseFailed else TimedOut
        if (result.trySuccess(status)) logger.warn(s"Execution session not released: executionId=$executionId status=$status")
      }
    })(ec)
    requestCancel.onComplete {
      case Success(_) => if (result.trySuccess(Released)) timer.cancel()
      case Failure(_) => if (result.trySuccess(CloseFailed)) timer.cancel()
    }
    result.future
  }

  def activeCount: Int =
    activeReliableExecutionsLock.synchronized(activeReliableExecutions.valuesIterator.map(_.size).sum)

  def register(executionId: String): Future[ReliableExecutionControl] = {
    val (created, predecessorRelease) = activeReliableExecutionsLock.synchronized {
      val existing = activeReliableExecutions.getOrElse(executionId, Vector.empty)
      val created = new ReliableExecutionControl
      activeReliableExecutions.update(executionId, existing :+ created)
      created -> existing.lastOption.map(_.cancel()).getOrElse(Future.successful(Done))
    }
    predecessorRelease.map(_ => created)
  }

  def release(executionId: String, control: ReliableExecutionControl): Unit = {
    activeReliableExecutionsLock.synchronized {
      activeReliableExecutions.get(executionId).foreach { existing =>
        val remaining = existing.filterNot(_ eq control)
        if (remaining.isEmpty) activeReliableExecutions.remove(executionId)
        else activeReliableExecutions.update(executionId, remaining)
      }
    }
    control.release()
  }

}
