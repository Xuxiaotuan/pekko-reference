package cn.xuyinyin.magic.common

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.Try

/**
 * Enhancement function for scala Future.
 *
 * @author Xuxiaotuan
 */
object FutureImplicits {

  private val defaultTimeout: Duration = 5.minutes

  val Inf: Duration.Infinite = Duration.Inf

  implicit class Wrapper[T](future: Future[T]) {

    /**
     * Await the result of the Future.
     *
     * @param timeout blocking timeout time
     * @throws TimeoutException when the call times out
     */
    @throws(classOf[TimeoutException])
    @throws(classOf[InterruptedException])
    private def waitResult(timeout: Duration): T = Await.result(future, timeout)
    private def waitResult: T                    = waitResult(Duration.Inf)

    /**
     * Same as [[waitResult]]
     */
    def sync: T                    = waitResult(Duration.Inf)
    def sync(timeout: Duration): T = waitResult(timeout)

    /**
     * Await the result wrapped by [[Try]] of the Future.
     *
     * @param timeout blocking timeout time
     */
    private def tryWaitResult(timeout: Duration): Try[T] = Try(Await.result(future, timeout))
    private def tryWaitResult: Try[T]                    = tryWaitResult(defaultTimeout)

    /**
     * Same as [[tryWaitResult]]
     */
    def trySync(timeout: Duration): Try[T] = tryWaitResult(timeout)
    def trySync: Try[T]                    = tryWaitResult(defaultTimeout)

  }

  /**
   * Convenience method for Thread.sleep
   */
  def sleep(delay: Duration): Unit = Thread.sleep(delay.toMillis)

}
