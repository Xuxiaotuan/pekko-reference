package cn.xuyinyin.magic.datafusion

import com.typesafe.scalalogging.Logger
import org.apache.pekko.pattern.after
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import java.io.IOException
import java.net.ConnectException

/**
 * 重试策略
 * 
 * 提供自动重试机制，用于处理临时性故障
 */
object RetryPolicy {
  
  private val logger = Logger(getClass)
  
  /**
   * 判断异常是否可重试
   */
  def isRetryable(throwable: Throwable): Boolean = throwable match {
    case _: IOException => true
    case _: ConnectException => true
    case _: java.util.concurrent.TimeoutException => true
    case e if e.getMessage != null && e.getMessage.contains("UNAVAILABLE") => true
    case e if e.getMessage != null && e.getMessage.contains("Connection refused") => true
    case _ => false
  }
  
  /**
   * 带重试的Future执行
   *
   * @param maxRetries 最大重试次数
   * @param backoff 退避时间
   * @param f 要执行的函数
   * @param system Actor系统（用于调度）
   * @param ec 执行上下文
   * @tparam T 返回类型
   * @return Future结果
   */
  def withRetry[T](
    maxRetries: Int = 3,
    backoff: FiniteDuration = 1.second
  )(f: => Future[T])(implicit system: ActorSystem[_], ec: ExecutionContext): Future[T] = {
    
    def attempt(retriesLeft: Int): Future[T] = {
      f.recoverWith {
        case e if isRetryable(e) && retriesLeft > 0 =>
          logger.warn(s"Operation failed (${e.getClass.getSimpleName}: ${e.getMessage}), " +
            s"retrying... ($retriesLeft retries left)")
          
          // 指数退避
          val delay = backoff * (maxRetries - retriesLeft + 1)
          after(delay, system.classicSystem.scheduler)(attempt(retriesLeft - 1))
          
        case e =>
          logger.error(s"Operation failed after all retries: ${e.getMessage}", e)
          Future.failed(e)
      }
    }
    
    attempt(maxRetries)
  }
  
  /**
   * 带重试的同步操作
   *
   * @param maxRetries 最大重试次数
   * @param backoff 退避时间
   * @param f 要执行的函数
   * @tparam T 返回类型
   * @return 结果
   */
  def withRetrySync[T](
    maxRetries: Int = 3,
    backoff: FiniteDuration = 1.second
  )(f: => T): T = {
    
    def attempt(retriesLeft: Int): T = {
      try {
        f
      } catch {
        case e if isRetryable(e) && retriesLeft > 0 =>
          logger.warn(s"Operation failed (${e.getClass.getSimpleName}: ${e.getMessage}), " +
            s"retrying... ($retriesLeft retries left)")
          
          // 等待退避时间
          val delay = backoff * (maxRetries - retriesLeft + 1)
          Thread.sleep(delay.toMillis)
          
          attempt(retriesLeft - 1)
          
        case e =>
          logger.error(s"Operation failed after all retries: ${e.getMessage}", e)
          throw e
      }
    }
    
    attempt(maxRetries)
  }
  
  /**
   * 带重试和超时的Future执行
   *
   * @param maxRetries 最大重试次数
   * @param backoff 退避时间
   * @param timeout 超时时间
   * @param f 要执行的函数
   * @param system Actor系统
   * @param ec 执行上下文
   * @tparam T 返回类型
   * @return Future结果
   */
  def withRetryAndTimeout[T](
    maxRetries: Int = 3,
    backoff: FiniteDuration = 1.second,
    timeout: FiniteDuration = 30.seconds
  )(f: => Future[T])(implicit system: ActorSystem[_], ec: ExecutionContext): Future[T] = {
    
    import org.apache.pekko.pattern.after
    
    val timeoutFuture = after(timeout, system.classicSystem.scheduler) {
      Future.failed(new java.util.concurrent.TimeoutException(s"Operation timeout after $timeout"))
    }
    
    Future.firstCompletedOf(Seq(
      withRetry(maxRetries, backoff)(f),
      timeoutFuture
    ))
  }
}

/**
 * 重试统计信息
 */
case class RetryStats(
  totalAttempts: Int,
  successfulAttempts: Int,
  failedAttempts: Int,
  retriedAttempts: Int
) {
  def successRate: Double = if (totalAttempts > 0) {
    (successfulAttempts.toDouble / totalAttempts) * 100
  } else 0.0
  
  def retryRate: Double = if (totalAttempts > 0) {
    (retriedAttempts.toDouble / totalAttempts) * 100
  } else 0.0
  
  override def toString: String = {
    s"RetryStats(total=$totalAttempts, success=$successfulAttempts, " +
      s"failed=$failedAttempts, retried=$retriedAttempts, " +
      s"successRate=${successRate.formatted("%.1f")}%, " +
      s"retryRate=${retryRate.formatted("%.1f")}%)"
  }
}

/**
 * 可重试的操作包装器
 * 
 * 提供更高级的重试功能，包括统计信息收集
 */
class RetryableOperation[T](
  operation: => Future[T],
  maxRetries: Int = 3,
  backoff: FiniteDuration = 1.second
)(implicit system: ActorSystem[_], ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  private var stats = RetryStats(0, 0, 0, 0)
  
  /**
   * 执行操作
   */
  def execute(): Future[T] = {
    stats = stats.copy(totalAttempts = stats.totalAttempts + 1)
    
    RetryPolicy.withRetry(maxRetries, backoff)(operation).andThen {
      case Success(_) =>
        stats = stats.copy(successfulAttempts = stats.successfulAttempts + 1)
        
      case Failure(_) =>
        stats = stats.copy(failedAttempts = stats.failedAttempts + 1)
    }
  }
  
  /**
   * 获取统计信息
   */
  def getStats: RetryStats = stats
  
  /**
   * 重置统计信息
   */
  def resetStats(): Unit = {
    stats = RetryStats(0, 0, 0, 0)
  }
}
