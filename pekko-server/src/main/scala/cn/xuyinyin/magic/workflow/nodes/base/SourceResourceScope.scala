package cn.xuyinyin.magic.workflow.nodes.base

import org.apache.pekko.Done
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

final class SourceAcquisitionCleanupFailed(cause: Throwable, cleanup: Throwable)
  extends IllegalStateException("source acquisition failed and resource release is unconfirmed", cause) {
  addSuppressed(cleanup)
}

/** Reserves ownership before asynchronous acquisition; late resources cannot escape close. */
final class SourceResourceScope {
  private var closers = Vector.empty[() => Future[Done]]
  private var closing = false
  private val released = Promise[Done]()

  def acquire[R](create: () => Future[R], closeResource: R => Future[Done]): Future[R] = {
    val opened = Promise[R]()
    synchronized {
      if (closing) return Future.failed(new IllegalStateException("source session is closing"))
      closers :+= (() => opened.future.transformWith {
        case Success(resource) => try closeResource(resource) catch { case NonFatal(e) => Future.failed(e) }
        case Failure(e: SourceAcquisitionCleanupFailed) => Future.failed(e)
        case Failure(_) => Future.successful(Done)
      }(ExecutionContext.parasitic))
    }
    try create().onComplete(opened.tryComplete)(ExecutionContext.parasitic)
    catch { case NonFatal(e) => opened.tryFailure(e) }
    opened.future
  }

  def close(): Future[Done] = {
    val pending = synchronized {
      if (closing) None else { closing = true; Some(closers) }
    }
    pending.foreach { entries =>
      implicit val ec: ExecutionContext = ExecutionContext.parasitic
      // Observe all closes, including when one fails; never short-circuit release accounting.
      Future.sequence(entries.map(f => f().map[Either[Throwable, Done]](Right(_)).recover { case NonFatal(e) => Left(e) }))
        .foreach { outcomes =>
          outcomes.collectFirst { case Left(e) => e } match {
            case Some(error) => released.tryFailure(error)
            case None => released.trySuccess(Done)
          }
        }
    }
    released.future
  }
}
