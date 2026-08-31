package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, ExecutionException, ExecutorService, Executors, Future, TimeUnit}
import scala.jdk.CollectionConverters._

class DebeziumBatchBridgeSpec extends STSpec {
  private val startedThreads = new ConcurrentLinkedQueue[StartedThread]()

  override protected def afterEach(): Unit = {
    try {
      startedThreads.iterator().asScala.foreach { started =>
        if (started.thread.isAlive) started.thread.interrupt()
      }
      startedThreads.iterator().asScala.foreach(_.thread.join(2000))
      startedThreads.iterator().asScala.foreach(_.thread.isAlive shouldBe false)
    } finally {
      startedThreads.clear()
      super.afterEach()
    }
  }

  "DebeziumBatchBridge" should {
    "hold publish after take until acknowledgement and commit exactly once across repeated real-thread runs" in {
      (1 to 20).foreach { attempt =>
        val bridge = new DebeziumBatchBridge
        val handle = new CountingHandle
        val batchId = s"batch-$attempt"
        val publisher = startThread(s"ack-publisher-$attempt") {
          bridge.publish(batchId, Vector(s"row-$attempt"), s"cursor-$attempt", handle)
        }

        val batch = bridge.take()
        batch.batchId shouldBe batchId
        batch.rows shouldBe Vector(s"row-$attempt")
        batch.cursorValue shouldBe s"cursor-$attempt"
        batch.deliveryToken should be > 0L
        batch.productArity shouldBe 4
        awaitWaiting(publisher.thread)

        bridge.acknowledge(batch.deliveryToken, batchId)
        awaitFinished(publisher)
        publisher.failure.get() shouldBe null
        handle.calls.get() shouldBe 1

        bridge.acknowledge(batch.deliveryToken, batchId)
        handle.calls.get() shouldBe 1
      }
    }

    "not let a second batch overtake an unacknowledged first batch across repeated real-thread runs" in {
      (1 to 20).foreach { attempt =>
        val bridge = new DebeziumBatchBridge
        val firstHandle = new CountingHandle
        val secondHandle = new CountingHandle
        val firstPublisher = startThread(s"first-publisher-$attempt") {
          bridge.publish("first", Vector("first-row"), "first-cursor", firstHandle)
        }

        val first = bridge.take()
        first.batchId shouldBe "first"
        awaitWaiting(firstPublisher.thread)

        val secondPublisher = startThread(s"second-publisher-$attempt") {
          bridge.publish("second", Vector("second-row"), "second-cursor", secondHandle)
        }
        awaitWaiting(secondPublisher.thread)
        val secondTaken = new AtomicReference[BridgedCdcBatch]()
        val secondTaker = startThread(s"second-taker-$attempt") {
          secondTaken.set(bridge.take())
        }

        awaitWaiting(secondTaker.thread)
        secondTaken.get() shouldBe null

        bridge.acknowledge(first.deliveryToken, "first")
        awaitFinished(firstPublisher)
        awaitFinished(secondTaker)
        val second = secondTaken.get()
        second.batchId shouldBe "second"
        second.rows shouldBe Vector("second-row")
        bridge.acknowledge(second.deliveryToken, "second")
        awaitFinished(secondPublisher)
        firstHandle.calls.get() shouldBe 1
        secondHandle.calls.get() shouldBe 1
      }
    }

    "release a failed callback without marking its Debezium records processed across repeated real-thread runs" in {
      (1 to 20).foreach { attempt =>
        val bridge = new DebeziumBatchBridge
        val handle = new CountingHandle
        val publisher = startThread(s"failed-publisher-$attempt") {
          bridge.publish(s"failed-$attempt", Vector("row"), "cursor", handle)
        }

        val batch = bridge.take()
        batch.batchId shouldBe s"failed-$attempt"
        awaitWaiting(publisher.thread)
        val cause = new RuntimeException("sink failed")
        bridge.fail(batch.deliveryToken, s"failed-$attempt", cause)

        awaitFinished(publisher)
        publisher.failure.get() shouldBe cause
        handle.calls.get() shouldBe 0
      }
    }

    "claim acknowledgement before invoking a reentrant callback and let the claim win over close" in {
      withExecutor(1) { executor =>
        val bridge = new DebeziumBatchBridge
        val handle = new ReentrantHandle(bridge, closeInstead = false)
        val publisher = submit(executor) {
          bridge.publish("reentrant", Vector("row"), "cursor", handle)
        }

        val batch = bridge.take()
        batch.batchId shouldBe "reentrant"
        handle.batch.set(batch)
        bridge.acknowledge(batch.deliveryToken, "reentrant")

        publisher.get(2, TimeUnit.SECONDS)
        handle.calls.get() shouldBe 1
      }

      val bridge = new DebeziumBatchBridge
      val handle = new ReentrantHandle(bridge, closeInstead = true)
      val publisher = startThread("reentrant-close-publisher") {
        bridge.publish("reentrant-close", Vector("row"), "cursor", handle)
      }
      val batch = bridge.take()
      batch.batchId shouldBe "reentrant-close"
      handle.batch.set(batch)
      awaitWaiting(publisher.thread)
      val queuedPublisher = startThread("reentrant-close-queued-publisher") {
        bridge.publish("reentrant-close-queued", Vector("row"), "cursor", new CountingHandle)
      }
      awaitWaiting(queuedPublisher.thread)

      bridge.acknowledge(batch.deliveryToken, "reentrant-close")

      awaitFinished(publisher)
      awaitFinished(queuedPublisher)
      publisher.failure.get() shouldBe null
      queuedPublisher.failure.get() shouldBe a[IllegalStateException]
      handle.calls.get() shouldBe 1
      intercept[IllegalStateException](bridge.take())
    }

    "release a throwing acknowledgement callback and do not commit it twice" in {
      withExecutor(1) { executor =>
        val bridge = new DebeziumBatchBridge
        val cause = new RuntimeException("commit failed")
        val handle = new ThrowingHandle(cause)
        val publisher = submit(executor) {
          bridge.publish("throwing", Vector("row"), "cursor", handle)
        }

        val batch = bridge.take()
        batch.batchId shouldBe "throwing"
        intercept[RuntimeException](bridge.acknowledge(batch.deliveryToken, "throwing")) shouldBe cause
        failureOf(publisher) shouldBe cause
        bridge.acknowledge(batch.deliveryToken, "throwing")
        handle.calls.get() shouldBe 1
      }
    }

    "remove an interrupted enqueue publisher before it can be delivered" in {
      val bridge = new DebeziumBatchBridge
      val first = startThread("full-queue-publisher") {
        bridge.publish("queued", Vector("queued"), "cursor", new CountingHandle)
      }
      awaitWaiting(first.thread)
      val interrupted = startThread("interrupted-enqueue-publisher") {
        bridge.publish("interrupted-enqueue", Vector("interrupted"), "cursor", new CountingHandle)
      }
      awaitWaiting(interrupted.thread)

      interrupted.thread.interrupt()
      awaitFinished(interrupted)
      interrupted.failure.get() shouldBe a[IllegalStateException]
      interrupted.interrupted.get() shouldBe true
      val batch = bridge.take()
      batch.batchId shouldBe "queued"
      bridge.acknowledge(batch.deliveryToken, "queued")
      awaitFinished(first)
      bridge.close()
    }

    "not allocate a delivery token for an interrupted capacity waiter" in {
      val bridge = new DebeziumBatchBridge
      val firstPublisher = startThread("token-first-publisher") {
        bridge.publish("token-first", Vector("first"), "cursor-1", new CountingHandle)
      }
      val first = bridge.take()
      first.deliveryToken shouldBe 1L
      awaitWaiting(firstPublisher.thread)

      val secondPublisher = startThread("token-second-publisher") {
        bridge.publish("token-second", Vector("second"), "cursor-2", new CountingHandle)
      }
      awaitWaiting(secondPublisher.thread)
      val capacityWaiter = startThread("token-capacity-waiter") {
        bridge.publish("token-interrupted", Vector("interrupted"), "cursor-3", new CountingHandle)
      }
      awaitWaiting(capacityWaiter.thread)

      capacityWaiter.thread.interrupt()
      awaitFinished(capacityWaiter)
      capacityWaiter.failure.get() shouldBe a[IllegalStateException]
      capacityWaiter.interrupted.get() shouldBe true

      bridge.acknowledge(first.deliveryToken, first.batchId)
      awaitFinished(firstPublisher)
      val second = bridge.take()
      second.deliveryToken shouldBe 2L
      bridge.acknowledge(second.deliveryToken, second.batchId)
      awaitFinished(secondPublisher)

      val nextPublisher = startThread("token-next-publisher") {
        bridge.publish("token-next", Vector("next"), "cursor-4", new CountingHandle)
      }
      val next = bridge.take()
      next.deliveryToken shouldBe 3L
      bridge.acknowledge(next.deliveryToken, next.batchId)
      awaitFinished(nextPublisher)
      bridge.close()
    }

    "remove an interrupted acknowledgement waiter before it can later be committed" in {
      val bridge = new DebeziumBatchBridge
      val handle = new CountingHandle
      val publisher = startThread("interrupted-ack-publisher") {
        bridge.publish("interrupted-ack", Vector("row"), "cursor", handle)
      }

      val batch = bridge.take()
      batch.batchId shouldBe "interrupted-ack"
      awaitWaiting(publisher.thread)
      publisher.thread.interrupt()
      awaitFinished(publisher)
      publisher.failure.get() shouldBe a[IllegalStateException]
      publisher.interrupted.get() shouldBe true
      bridge.acknowledge(batch.deliveryToken, "interrupted-ack")
      handle.calls.get() shouldBe 0
      bridge.close()
    }

    "reject delayed old tokens while allowing a later delivery with the same batch ID" in {
      (1 to 20).foreach { attempt =>
      withExecutor(1) { executor =>
        val bridge = new DebeziumBatchBridge
        val firstHandle = new CountingHandle
        val first = submit(executor) {
          bridge.publish("reused", Vector("first"), "cursor-1", firstHandle)
        }

        val firstBatch = bridge.take()
        firstBatch.batchId shouldBe "reused"
        bridge.acknowledge(firstBatch.deliveryToken, "reused")
        first.get(2, TimeUnit.SECONDS)

        val secondHandle = new CountingHandle
        val second = submit(executor) {
          bridge.publish("reused", Vector(s"second-$attempt"), "cursor-2", secondHandle)
        }
        val secondBatch = bridge.take()
        secondBatch.batchId shouldBe "reused"
        secondBatch.deliveryToken should not be firstBatch.deliveryToken
        bridge.acknowledge(firstBatch.deliveryToken, "reused")
        bridge.fail(firstBatch.deliveryToken, "reused", new RuntimeException("old token"))
        secondHandle.calls.get() shouldBe 0
        bridge.acknowledge(secondBatch.deliveryToken, "reused")
        second.get(2, TimeUnit.SECONDS)
        firstHandle.calls.get() shouldBe 1
        secondHandle.calls.get() shouldBe 1
      }
      }
    }

    "hold the next delivery while a claimed callback is blocked and ignore a second acknowledgement" in {
      val bridge = new DebeziumBatchBridge
      val handle = new BlockingHandle
      val firstPublisher = startThread("claimed-first-publisher") {
        bridge.publish("claimed-first", Vector("first"), "cursor-1", handle)
      }
      val first = bridge.take()
      awaitWaiting(firstPublisher.thread)
      val acknowledgement = startThread("claimed-acknowledgement") {
        bridge.acknowledge(first.deliveryToken, first.batchId)
      }
      await(handle.entered)
      val secondHandle = new CountingHandle
      val secondPublisher = startThread("claimed-second-publisher") {
        bridge.publish("claimed-second", Vector("second"), "cursor-2", secondHandle)
      }
      awaitWaiting(secondPublisher.thread)
      val secondTaken = new AtomicReference[BridgedCdcBatch]()
      val secondTaker = startThread("claimed-second-taker") {
        secondTaken.set(bridge.take())
      }
      awaitWaiting(secondTaker.thread)

      bridge.acknowledge(first.deliveryToken, first.batchId)
      handle.calls.get() shouldBe 1
      secondTaken.get() shouldBe null

      handle.release.countDown()
      awaitFinished(acknowledgement)
      awaitFinished(firstPublisher)
      firstPublisher.failure.get() shouldBe null
      awaitFinished(secondTaker)
      val second = secondTaken.get()
      second.batchId shouldBe "claimed-second"
      bridge.acknowledge(second.deliveryToken, second.batchId)
      awaitFinished(secondPublisher)
      secondHandle.calls.get() shouldBe 1
    }

    "close releases established blocked consumers and both enqueue and acknowledgement publishers, then fails later operations fast" in {
      (1 to 20).foreach { attempt =>
          val waitingConsumer = new DebeziumBatchBridge
          val consumer = startThread(s"close-consumer-$attempt") {
            waitingConsumer.take()
          }
          awaitWaiting(consumer.thread)
          waitingConsumer.close()
          awaitFinished(consumer)
          consumer.failure.get() shouldBe a[IllegalStateException]

          val bridge = new DebeziumBatchBridge
          val first = startThread(s"close-ack-$attempt") {
            bridge.publish(s"ack-$attempt", Vector("first"), "cursor-1", new CountingHandle)
          }
          val batch = bridge.take()
          batch.batchId shouldBe s"ack-$attempt"
          awaitWaiting(first.thread)
          val second = startThread(s"close-queued-$attempt") {
            bridge.publish(s"enqueue-$attempt", Vector("second"), "cursor-2", new CountingHandle)
          }
          awaitWaiting(second.thread)
          val third = startThread(s"close-enqueue-$attempt") {
            bridge.publish(s"enqueue-later-$attempt", Vector("third"), "cursor-3", new CountingHandle)
          }
          awaitWaiting(third.thread)

          bridge.close()
          bridge.close()

          awaitFinished(first)
          awaitFinished(second)
          awaitFinished(third)
          first.failure.get() shouldBe a[IllegalStateException]
          second.failure.get() shouldBe a[IllegalStateException]
          third.failure.get() shouldBe a[IllegalStateException]
          intercept[IllegalStateException](bridge.take())
          intercept[IllegalStateException](bridge.publish("later", Vector.empty, "later", new CountingHandle))
          intercept[IllegalStateException](bridge.acknowledge(batch.deliveryToken, s"ack-$attempt"))
          intercept[IllegalStateException](bridge.fail(batch.deliveryToken, s"ack-$attempt", new RuntimeException("later")))
      }
    }
  }

  private def withExecutor[A](threads: Int)(body: ExecutorService => A): A = {
    val executor = Executors.newFixedThreadPool(threads)
    try body(executor)
    finally {
      executor.shutdownNow()
      executor.awaitTermination(2, TimeUnit.SECONDS) shouldBe true
    }
  }

  private def submit[A](executor: ExecutorService)(body: => A): Future[A] =
    executor.submit(new Callable[A] {
      override def call(): A = body
    })

  private def startThread(name: String)(body: => Unit): StartedThread = {
    val failure = new AtomicReference[Throwable]()
    val interrupted = new AtomicBoolean(false)
    val thread = new Thread(
      () => {
        try body
        catch { case error: Throwable => failure.set(error) }
        finally interrupted.set(Thread.currentThread().isInterrupted)
      },
      name
    )
    thread.start()
    val started = new StartedThread(thread, failure, interrupted)
    startedThreads.add(started)
    started
  }

  private def awaitWaiting(thread: Thread): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (thread.getState != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.`yield`()
    }
    thread.getState shouldBe Thread.State.WAITING
  }

  private def awaitFinished(started: StartedThread): Unit = {
    started.thread.join(2000)
    started.thread.isAlive shouldBe false
  }

  private def await(latch: java.util.concurrent.CountDownLatch): Unit =
    latch.await(2, TimeUnit.SECONDS) shouldBe true

  private def failureOf(future: Future[_]): Throwable =
    intercept[ExecutionException] {
      future.get(2, TimeUnit.SECONDS)
    }.getCause

  private final class CountingHandle extends CdcBatchCommitHandle {
    val calls = new AtomicInteger(0)

    override def markProcessedAndFinished(): Unit = {
      calls.incrementAndGet()
      ()
    }
  }

  private final class ReentrantHandle(
    bridge: DebeziumBatchBridge,
    closeInstead: Boolean
  ) extends CdcBatchCommitHandle {
    val calls = new AtomicInteger(0)
    val batch = new AtomicReference[BridgedCdcBatch]()

    override def markProcessedAndFinished(): Unit = {
      if (calls.getAndIncrement() == 0) {
        if (closeInstead) bridge.close()
        else {
          val current = batch.get()
          bridge.acknowledge(current.deliveryToken, current.batchId)
        }
      }
      ()
    }
  }

  private final class ThrowingHandle(cause: RuntimeException) extends CdcBatchCommitHandle {
    val calls = new AtomicInteger(0)

    override def markProcessedAndFinished(): Unit = {
      calls.incrementAndGet()
      throw cause
    }
  }

  private final class BlockingHandle extends CdcBatchCommitHandle {
    val calls = new AtomicInteger(0)
    val entered = new java.util.concurrent.CountDownLatch(1)
    val release = new java.util.concurrent.CountDownLatch(1)

    override def markProcessedAndFinished(): Unit = {
      calls.incrementAndGet()
      entered.countDown()
      release.await(2, TimeUnit.SECONDS) shouldBe true
      ()
    }
  }

  private final class StartedThread(
    val thread: Thread,
    val failure: AtomicReference[Throwable],
    val interrupted: AtomicBoolean
  )
}
