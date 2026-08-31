package cn.xuyinyin.magic.workflow.nodes.sources

import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture, ExecutionException}

trait CdcBatchCommitHandle {
  def markProcessedAndFinished(): Unit
}

final case class BridgedCdcBatch(
  deliveryToken: Long,
  batchId: String,
  rows: Vector[String],
  cursorValue: String
)

/**
 * Bridges Debezium's blocking callback to a later stream acknowledgement.
 *
 * A published callback waits until its delivered batch is acknowledged or failed.
 * The commit handle is retained only in the private queue entry and is never part
 * of [[BridgedCdcBatch]]. Closing is idempotent: it fails every unclaimed waiter,
 * preserves an acknowledgement already claimed for its callback, clears buffered
 * work, wakes a blocked taker, and rejects all later operations.
 */
final class DebeziumBatchBridge {
  private final class BatchEntry(
    val deliveryToken: Long,
    val batchId: String,
    val rows: Vector[String],
    val cursorValue: String,
    val commitHandle: CdcBatchCommitHandle,
    val completion: CompletableFuture[Unit]
  )

  private val queue = new ArrayBlockingQueue[Option[BatchEntry]](1)
  private val monitor = new AnyRef
  private var closed = false
  private var pending = Map.empty[Long, BatchEntry]
  private var active = Option.empty[BatchEntry]
  private var claimed = Option.empty[BatchEntry]
  private var taking = false
  private var nextToken = 1L

  def publish(
    batchId: String,
    rows: Vector[String],
    cursorValue: String,
    commitHandle: CdcBatchCommitHandle
  ): Unit = {
    require(batchId != null, "batchId must not be null")
    require(rows != null, "rows must not be null")
    require(cursorValue != null, "cursorValue must not be null")
    require(commitHandle != null, "commitHandle must not be null")

    var entry: BatchEntry = null
    try {
      monitor.synchronized {
        ensureOpen()
        while (queue.remainingCapacity() == 0) {
          monitor.wait()
          ensureOpen()
        }
        entry = new BatchEntry(nextDeliveryToken(), batchId, rows, cursorValue, commitHandle, new CompletableFuture[Unit]())
        pending += entry.deliveryToken -> entry
        if (!queue.offer(Some(entry))) {
          pending -= entry.deliveryToken
          throw new IllegalStateException("Debezium queue capacity was lost during admission")
        }
      }
    } catch {
      case error: InterruptedException =>
        if (entry != null) cancel(entry, error)
        Thread.currentThread().interrupt()
        throw new IllegalStateException("interrupted while enqueueing Debezium batch", error)
    }

    awaitCompletion(entry)
  }

  def take(): BridgedCdcBatch = {
    try {
      monitor.synchronized {
        ensureOpen()
        while (active.nonEmpty || claimed.nonEmpty || taking) {
          monitor.wait()
          ensureOpen()
        }
        taking = true
      }

      var result = Option.empty[BridgedCdcBatch]
      while (result.isEmpty) {
        val item = queue.take()
        monitor.synchronized {
          item match {
            case Some(entry) if pending.get(entry.deliveryToken).exists(_ eq entry) =>
              taking = false
              ensureOpen()
              active = Some(entry)
              monitor.notifyAll()
              result = Some(BridgedCdcBatch(entry.deliveryToken, entry.batchId, entry.rows, entry.cursorValue))
            case Some(_) => ()
            case None =>
              taking = false
              throw closedFailure()
          }
        }
      }
      result.get
    } catch {
      case error: InterruptedException =>
        monitor.synchronized {
          taking = false
          monitor.notifyAll()
        }
        Thread.currentThread().interrupt()
        throw new IllegalStateException("interrupted while taking Debezium batch", error)
    }
  }

  def acknowledge(deliveryToken: Long, batchId: String): Unit = {
    val entry = monitor.synchronized {
      ensureOpen()
      active match {
        case Some(entry) if entry.deliveryToken == deliveryToken && entry.batchId == batchId =>
          active = None
          claimed = Some(entry)
          pending -= deliveryToken
          entry
        case _ => return
      }
    }

    try {
      entry.commitHandle.markProcessedAndFinished()
      monitor.synchronized {
        if (claimed.exists(_ eq entry)) {
          claimed = None
          entry.completion.complete(())
          monitor.notifyAll()
        }
      }
    } catch {
      case error: Throwable =>
        monitor.synchronized {
          if (claimed.exists(_ eq entry)) {
            claimed = None
            entry.completion.completeExceptionally(error)
            monitor.notifyAll()
          }
        }
        throw error
      }
  }

  def fail(deliveryToken: Long, batchId: String, cause: Throwable): Unit = {
    require(cause != null, "cause must not be null")
    monitor.synchronized {
      ensureOpen()
      active match {
        case Some(entry) if entry.deliveryToken == deliveryToken && entry.batchId == batchId =>
          cancel(entry, cause)
        case _ => ()
      }
    }
  }

  def close(): Unit = {
    monitor.synchronized {
      if (!closed) {
        closed = true
        val cause = closedFailure()
        pending.values.foreach(_.completion.completeExceptionally(cause))
        pending = Map.empty
        active = None
        queue.clear()
        queue.offer(None)
        monitor.notifyAll()
      }
    }
  }

  private def awaitCompletion(entry: BatchEntry): Unit =
    try entry.completion.get()
    catch {
      case error: InterruptedException =>
        cancel(entry, error)
        Thread.currentThread().interrupt()
        throw new IllegalStateException("interrupted while waiting for Debezium batch acknowledgement", error)
      case error: ExecutionException => rethrow(error.getCause)
    }

  private def cancel(entry: BatchEntry, cause: Throwable): Unit =
    monitor.synchronized {
      if (pending.get(entry.deliveryToken).exists(_ eq entry)) {
        pending -= entry.deliveryToken
        if (active.exists(_ eq entry)) active = None
        queue.remove(Some(entry))
        entry.completion.completeExceptionally(cause)
        monitor.notifyAll()
      }
    }

  private def ensureOpen(): Unit =
    if (closed) throw closedFailure()

  private def nextDeliveryToken(): Long = {
    if (nextToken == Long.MaxValue) {
      throw new IllegalStateException("Debezium delivery token space exhausted")
    }
    val token = nextToken
    nextToken += 1L
    token
  }

  private def closedFailure(): IllegalStateException =
    new IllegalStateException("Debezium batch bridge is closed")

  private def rethrow(cause: Throwable): Nothing = cause match {
    case error: RuntimeException => throw error
    case error: Error => throw error
    case error => throw new RuntimeException(error)
  }
}
