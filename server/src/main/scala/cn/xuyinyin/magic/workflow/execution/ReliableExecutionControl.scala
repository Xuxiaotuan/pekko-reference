package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.workflow.spi.BatchSourceSession
import org.apache.pekko.Done
import org.apache.pekko.stream.UniqueKillSwitch

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/** One execution's batch admission, drain, and physical release confirmation.
  * Does not decide T/J/ACK ordering or persist workflow state.
  */
private[execution] final class ReliableExecutionControl(implicit ec: ExecutionContext) {
  private val cancelled = new AtomicBoolean(false)
  private val killSwitch = new AtomicReference[UniqueKillSwitch]()
  private val shutdownStarted = new AtomicBoolean(false)
  private val released = Promise[Done]()
  val closeFailure = new AtomicReference[Throwable]()
  @volatile var sanitizeCloseFailure: Throwable => Throwable = error => error
  private var sessions = Vector.empty[BatchSourceSession]
  private var inFlight = Set.empty[Promise[Done]]
  private var draining = false

  def installSession(session: BatchSourceSession): Unit = synchronized {
    require(!draining, "execution session is draining")
    sessions :+= session
  }

  def isCancellationRequested: Boolean = cancelled.get()

  def removeSession(session: BatchSourceSession): Unit = synchronized {
    sessions = sessions.filterNot(_ eq session)
  }

  def processBatch(work: => Future[Long]): Future[Long] = {
    val finished = Promise[Done]()
    val admitted = synchronized {
      if (cancelled.get() || draining) false
      else { inFlight += finished; true }
    }
    if (!admitted) Future.successful(0L)
    else {
      val running = try work catch { case NonFatal(e) => Future.failed(e) }
      running.transform { outcome =>
        synchronized { inFlight -= finished; finished.trySuccess(Done) }
        outcome
      }
    }
  }

  def drainAndClose(): Future[Done] = {
    val (pending, resources) = synchronized {
      draining = true
      inFlight.toVector.map(_.future) -> sessions
    }
    Future.sequence(pending).flatMap { _ =>
      Future.sequence(resources.map(s => s.close().map[Either[Throwable, Done]](Right(_))
        .recover { case NonFatal(e) => Left(e) })).flatMap { outcomes =>
        outcomes.collectFirst { case Left(e) => e } match {
          case Some(e) => Future.failed(e)
          case None => Option(closeFailure.get()).fold[Future[Done]](Future.successful(Done))(Future.failed)
        }
      }
    }
  }


  def cancel(): Future[Done] = {
    cancelled.set(true)
    shutdownIfReady()
    released.future
  }

  def continueIfActive(): Future[Done] =
    if (cancelled.get()) Future.failed(new IllegalStateException("workflow execution cancelled"))
    else Future.successful(Done)

  def install(value: UniqueKillSwitch): Unit = {
    if (!killSwitch.compareAndSet(null, value)) {
      throw new IllegalStateException("reliable execution kill switch is already installed")
    }
    shutdownIfReady()
  }

  def release(): Unit = released.trySuccess(Done)

  private def shutdownIfReady(): Unit = {
    val current = killSwitch.get()
    if (cancelled.get() && current != null && shutdownStarted.compareAndSet(false, true)) {
      current.shutdown()
    }
  }
}
