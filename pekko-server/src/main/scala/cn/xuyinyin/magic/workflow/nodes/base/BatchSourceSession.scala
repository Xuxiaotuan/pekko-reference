package cn.xuyinyin.magic.workflow.nodes.base

import cn.xuyinyin.magic.workflow.checkpoint.SourceBatch
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.scaladsl.Source
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/** Execution-local resources, deliberately absent from checkpoints and source identities. */
final case class BatchSourceSession(source: Source[SourceBatch, NotUsed], close: () => Future[Done])

object BatchSourceSession {
  /** Managed streams defer physical close until the Engine has drained its in-flight batch. */
  def resource[R](create: () => Future[R], read: R => Future[Option[SourceBatch]],
    closeResource: R => Future[Done], managed: Boolean, drainReads: Boolean = false): BatchSourceSession = {
    val scope = new SourceResourceScope
    val readLock = new AnyRef
    var pendingReads = Set.empty[Promise[Done]]
    var stopping = false
    def close(): Future[Done] = {
      val pending = readLock.synchronized { stopping = true; pendingReads.toVector.map(_.future) }
      implicit val ec: ExecutionContext = ExecutionContext.parasitic
      Future.sequence(pending).flatMap(_ => scope.close())
    }
    def readTracked(resource: R): Future[Option[SourceBatch]] = {
      if (!drainReads) return read(resource)
      val finished = Promise[Done]()
      val admitted = readLock.synchronized {
        if (stopping) false else { pendingReads += finished; true }
      }
      if (!admitted) Future.successful(None)
      else {
        val running = try read(resource) catch { case NonFatal(e) => Future.failed(e) }
        running.transform { result =>
          readLock.synchronized { pendingReads -= finished; finished.trySuccess(Done) }
          result
        }(ExecutionContext.parasitic)
      }
    }
    val source = Source.unfoldResourceAsync[SourceBatch, R](
      () => scope.acquire(create, closeResource), readTracked,
      _ => if (managed) Future.successful(Done) else close())
    BatchSourceSession(source, () => close())
  }
}
