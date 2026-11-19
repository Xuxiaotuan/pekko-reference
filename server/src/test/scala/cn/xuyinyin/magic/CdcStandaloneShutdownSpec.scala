package cn.xuyinyin.magic

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class CdcStandaloneShutdownSpec extends AnyWordSpec with Matchers {
  "standalone CDC shutdown" should {
    "wait for a later confirmed STOPPED without starting another close operation" in {
      implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "standalone-close-wait",
        ConfigFactory.parseString("pekko.actor.provider = local").withFallback(ConfigFactory.load()))
      implicit val ec: ExecutionContext = system.executionContext
      try {
        val state = new AtomicReference("UNKNOWN")
        val calls = new AtomicInteger()
        val result = CdcStandalone.awaitStopped(() => { calls.incrementAndGet(); () }, () => state.get(), 2.seconds)
        result.isCompleted shouldBe false
        system.scheduler.scheduleOnce(200.millis, new Runnable { def run(): Unit = state.set("STOPPED") })
        Await.result(result, 3.seconds) shouldBe org.apache.pekko.Done
        calls.get() shouldBe 1
      } finally { system.terminate(); Await.result(system.whenTerminated, 10.seconds) }
    }

    "time out instead of treating UNKNOWN as released" in {
      implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "standalone-close-unknown",
        ConfigFactory.parseString("pekko.actor.provider = local").withFallback(ConfigFactory.load()))
      implicit val ec: ExecutionContext = system.executionContext
      try {
        val calls = new AtomicInteger()
        val result = CdcStandalone.awaitStopped(() => { calls.incrementAndGet(); () }, () => "UNKNOWN", 250.millis)
        intercept[TimeoutException](Await.result(result, 3.seconds))
        calls.get() shouldBe 1
      } finally { system.terminate(); Await.result(system.whenTerminated, 10.seconds) }
    }

    "keep its wait inside the configured coordinated shutdown phase" in {
      val default = ConfigFactory.parseString("pekko.coordinated-shutdown.default-phase-timeout = 5s")
      CdcStandalone.shutdownWait(default) shouldBe 4750.millis
      val overrideConfig = ConfigFactory.parseString(
        "pekko.coordinated-shutdown.phases.before-actor-system-terminate.timeout = 2s").withFallback(default)
      CdcStandalone.shutdownWait(overrideConfig) shouldBe 1750.millis
    }

    "use a standalone phase budget while respecting an explicit phase override" in {
      val default = ConfigFactory.parseString("pekko.coordinated-shutdown.default-phase-timeout = 5s")
      CdcStandalone.shutdownWait(CdcStandalone.standaloneConfig(default)) shouldBe 15.seconds
      val configured = ConfigFactory.parseString(
        "pekko.coordinated-shutdown.phases.before-actor-system-terminate.timeout = 2s").withFallback(default)
      CdcStandalone.shutdownWait(CdcStandalone.standaloneConfig(configured)) shouldBe 1750.millis
      CdcStandalone.standaloneConfig(configured).getString("pekko.actor.provider") shouldBe "local"
    }

    "wait for the confirmed status when invoked by coordinated shutdown" in {
      implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "standalone-close-hook",
        ConfigFactory.parseString("pekko.actor.provider = local").withFallback(ConfigFactory.load()))
      implicit val ec: ExecutionContext = system.executionContext
      val state = new AtomicReference("UNKNOWN")
      val calls = new AtomicInteger()
      CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "test-cdc-release") { () =>
        CdcStandalone.awaitStopped(() => { calls.incrementAndGet(); () }, () => state.get(), 2.seconds)
      }
      val shutdown = CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason)
      system.scheduler.scheduleOnce(200.millis, new Runnable { def run(): Unit = state.set("STOPPED") })
      Await.result(shutdown, 5.seconds) shouldBe org.apache.pekko.Done
      calls.get() shouldBe 1
      Await.result(system.whenTerminated, 10.seconds)
    }
  }
}
