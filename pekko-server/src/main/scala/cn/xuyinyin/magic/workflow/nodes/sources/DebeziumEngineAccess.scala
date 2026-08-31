package cn.xuyinyin.magic.workflow.nodes.sources

import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import ch.qos.logback.core.spi.FilterReply
import io.debezium.embedded.Connect
import io.debezium.engine.format.ChangeEventFormat
import io.debezium.engine.{DebeziumEngine, RecordChangeEvent}
import org.apache.kafka.connect.source.SourceRecord
import org.apache.pekko.Done
import org.slf4j.{LoggerFactory, Marker}

import java.util.IdentityHashMap
import java.util.Properties
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.concurrent.{CountDownLatch, Executors, ThreadFactory, TimeUnit}
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

trait DebeziumBatchConsumer {
  def handleBatch(records: Vector[SourceRecord], commitHandle: CdcBatchCommitHandle): Unit
}

trait DebeziumEngineAccess extends AutoCloseable {
  def start(consumer: DebeziumBatchConsumer): Future[Done]
}

trait DebeziumEngineFactory {
  def create(properties: Properties): DebeziumEngineAccess
}

object DebeziumEngineFactory {
  val real: DebeziumEngineFactory = new DebeziumEngineFactory {
    override def create(properties: Properties): DebeziumEngineAccess =
      new RealDebeziumEngineAccess(properties)
  }
}

private[sources] final class DebeziumRecordCommitHandle(
  records: java.util.List[RecordChangeEvent[SourceRecord]],
  committer: DebeziumEngine.RecordCommitter[RecordChangeEvent[SourceRecord]]
) extends CdcBatchCommitHandle {
  override def markProcessedAndFinished(): Unit = {
    records.asScala.foreach(committer.markProcessed)
    committer.markBatchFinished()
  }
}

private[sources] object RealDebeziumEngineAccess {
  type Engine = DebeziumEngine[RecordChangeEvent[SourceRecord]]
  type EngineBuilder = (
    DebeziumEngine.ChangeConsumer[RecordChangeEvent[SourceRecord]],
    DebeziumEngine.CompletionCallback
  ) => Engine

  private val ThreadSequence = new AtomicLong(0L)
  private val CloseTimeoutNanos = TimeUnit.SECONDS.toNanos(2L)
  private val CloseRetryMillis = 10L

  private[sources] trait LogBoundary extends AutoCloseable

  private object NoLogBoundary extends LogBoundary {
    override def close(): Unit = ()
  }

  private final class SecretDenyFilter(secrets: Vector[String]) extends TurboFilter {
    override def decide(
      marker: Marker,
      logger: Logger,
      level: Level,
      format: String,
      params: Array[AnyRef],
      error: Throwable
    ): FilterReply = {
      val loggerName = Option(logger).map(_.getName).getOrElse("")
      if (loggerName != "io.debezium" && !loggerName.startsWith("io.debezium.")) {
        return FilterReply.NEUTRAL
      }
      val argumentContainsSecret = Option(params).exists(_.exists {
        case throwable: Throwable => throwableContainsSecret(throwable)
        case value => containsSecret(String.valueOf(value))
      })
      if (containsSecret(format) || argumentContainsSecret || throwableContainsSecret(error)) {
        FilterReply.DENY
      } else FilterReply.NEUTRAL
    }

    private def containsSecret(value: String): Boolean =
      value != null && secrets.exists(value.contains)

    private def throwableContainsSecret(error: Throwable): Boolean = {
      val visited = new IdentityHashMap[Throwable, java.lang.Boolean]()
      def loop(current: Throwable): Boolean = {
        if (current == null || visited.put(current, java.lang.Boolean.TRUE) != null) false
        else {
          containsSecret(current.getMessage) ||
          current.getSuppressed.exists(loop) ||
          loop(current.getCause)
        }
      }
      loop(error)
    }
  }

  def installLogBoundary(properties: Properties): LogBoundary = {
    val secrets = properties.stringPropertyNames().asScala.toVector
      .filter(_.toLowerCase(java.util.Locale.ROOT).contains("password"))
      .flatMap(key => Option(properties.getProperty(key)))
      .filter(_.nonEmpty)
      .distinct
    if (secrets.isEmpty) NoLogBoundary
    else LoggerFactory.getILoggerFactory match {
      case context: LoggerContext =>
        val filter = new SecretDenyFilter(secrets)
        filter.setContext(context)
        filter.start()
        context.addTurboFilter(filter)
        new LogBoundary {
          private val removed = new AtomicBoolean(false)
          override def close(): Unit = {
            if (removed.compareAndSet(false, true)) {
              context.getTurboFilterList.remove(filter)
              filter.stop()
            }
          }
        }
      case _ =>
        throw new IllegalStateException("secure Debezium logging boundary is unavailable")
    }
  }

  def defaultEngineBuilder(properties: Properties): EngineBuilder = (consumer, completion) =>
    DebeziumEngine
      .create[SourceRecord, Connect](ChangeEventFormat.of[Connect](classOf[Connect]))
      .using(properties)
      .using(completion)
      .notifying(consumer)
      .build()

  def threadFactory(): ThreadFactory = (runnable: Runnable) => {
    val thread = new Thread(
      runnable,
      s"mysql-cdc-debezium-engine-${ThreadSequence.incrementAndGet()}"
    )
    thread.setDaemon(true)
    thread
  }

  def safeFailure(error: Throwable): IllegalStateException = {
    val kind = Option(error).map(_.getClass.getSimpleName).filter(_.nonEmpty).getOrElse("unknown")
    new IllegalStateException(s"Debezium engine terminated exceptionally ($kind)")
  }

  def unexpectedTermination(): IllegalStateException =
    new IllegalStateException("Debezium engine terminated unexpectedly")

  def closeFailure(error: Throwable): IllegalStateException = {
    val kind = Option(error).map(_.getClass.getSimpleName).filter(_.nonEmpty).getOrElse("unknown")
    new IllegalStateException(s"Debezium engine close failed ($kind)")
  }

  def isStartingTasksFailure(error: Throwable): Boolean =
    error.isInstanceOf[IllegalStateException] &&
      Option(error.getMessage).exists(_.toLowerCase(java.util.Locale.ROOT).contains("tasks are starting"))
}

final class RealDebeziumEngineAccess private[sources] (
  properties: Properties,
  buildEngine: RealDebeziumEngineAccess.EngineBuilder
) extends DebeziumEngineAccess {
  import RealDebeziumEngineAccess._

  def this(properties: Properties) =
    this(properties, RealDebeziumEngineAccess.defaultEngineBuilder(properties))

  private val started = new AtomicBoolean(false)
  private val closed = new AtomicBoolean(false)
  private val closeRequested = new AtomicBoolean(false)
  private val engine = new AtomicReference[Engine]()
  private val logBoundary = new AtomicReference[LogBoundary](NoLogBoundary)
  private val closeError = new AtomicReference[IllegalStateException]()
  private val runnerFinished = new CountDownLatch(1)
  private val completion = Promise[Done]()
  private val executor = Executors.newSingleThreadExecutor(threadFactory())

  override def start(consumer: DebeziumBatchConsumer): Future[Done] = {
    require(consumer != null, "Debezium batch consumer must not be null")
    if (!started.compareAndSet(false, true)) {
      return Future.failed(new IllegalStateException("Debezium engine has already been started"))
    }
    if (closed.get()) {
      executor.shutdown()
      return Future.failed(new IllegalStateException("Debezium engine access is closed"))
    }

    val changeConsumer = new DebeziumEngine.ChangeConsumer[RecordChangeEvent[SourceRecord]] {
      override def handleBatch(
        delivered: java.util.List[RecordChangeEvent[SourceRecord]],
        committer: DebeziumEngine.RecordCommitter[RecordChangeEvent[SourceRecord]]
      ): Unit = {
        if (delivered == null || delivered.isEmpty) {
          throw new IllegalArgumentException("Debezium delivered an empty callback batch")
        }
        val records = delivered.asScala.toVector.map { event =>
          if (event == null || event.record() == null) {
            throw new IllegalArgumentException("Debezium delivered a null source record")
          }
          event.record()
        }
        consumer.handleBatch(records, new DebeziumRecordCommitHandle(delivered, committer))
      }

      override def supportsTombstoneEvents(): Boolean = true
    }
    val callback = new DebeziumEngine.CompletionCallback {
      override def handle(success: Boolean, message: String, error: Throwable): Unit = {
        if (success && error == null && closeRequested.get()) completion.trySuccess(Done)
        else if (success && error == null) completion.tryFailure(unexpectedTermination())
        else completion.tryFailure(safeFailure(error))
      }
    }

    try {
      logBoundary.set(installLogBoundary(properties))
      val created = buildEngine(changeConsumer, callback)
      if (created == null) throw new IllegalStateException("Debezium engine builder returned null")
      engine.set(created)
      executor.execute(() => {
        try {
          created.run()
          if (closeRequested.get()) completion.trySuccess(Done)
          else completion.tryFailure(unexpectedTermination())
        } catch {
          case _: InterruptedException if closeRequested.get() =>
            completion.trySuccess(Done)
            Thread.currentThread().interrupt()
          case NonFatal(error) => completion.tryFailure(safeFailure(error))
        } finally {
          runnerFinished.countDown()
          logBoundary.getAndSet(NoLogBoundary).close()
        }
      })
    } catch {
      case NonFatal(error) =>
        completion.tryFailure(safeFailure(error))
        runnerFinished.countDown()
        logBoundary.getAndSet(NoLogBoundary).close()
        executor.shutdown()
    }
    completion.future
  }

  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      val engineAlreadyCompleted = completion.isCompleted
      closeRequested.set(true)
      val current = engine.get()
      var failure: IllegalStateException = null
      try {
        if (current != null && !engineAlreadyCompleted && runnerFinished.getCount != 0L) {
          closeEngineWithRetry(current)
        }
      } catch {
        case NonFatal(error) =>
          failure = closeFailure(error)
          closeError.compareAndSet(null, failure)
          completion.tryFailure(failure)
      } finally {
        if (failure == null) executor.shutdown()
        else executor.shutdownNow()
        try {
          if (!executor.awaitTermination(2L, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            if (!executor.awaitTermination(2L, TimeUnit.SECONDS) && failure == null) {
              failure = new IllegalStateException("Debezium engine close failed (executor did not terminate)")
              closeError.compareAndSet(null, failure)
              completion.tryFailure(failure)
            }
          }
        } catch {
          case _: InterruptedException =>
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            if (failure == null) {
              failure = new IllegalStateException("Debezium engine close failed (interrupted)")
              closeError.compareAndSet(null, failure)
              completion.tryFailure(failure)
            }
        }
      }
      if (failure != null) throw failure
    } else {
      val failure = closeError.get()
      if (failure != null) throw failure
    }
  }

  private def closeEngineWithRetry(current: Engine): Unit = {
    val deadline = System.nanoTime() + CloseTimeoutNanos
    var retry = true
    while (retry) {
      try {
        current.close()
        retry = false
      } catch {
        case NonFatal(error) if isStartingTasksFailure(error) && System.nanoTime() < deadline =>
          try Thread.sleep(CloseRetryMillis)
          catch {
            case interrupted: InterruptedException =>
              Thread.currentThread().interrupt()
              throw interrupted
          }
        case NonFatal(error) => throw error
      }
    }
  }
}
