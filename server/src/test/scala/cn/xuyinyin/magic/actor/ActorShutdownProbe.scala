package cn.xuyinyin.magic.actor

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path, StandardOpenOption}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, Routers}
import spray.json._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Standalone diagnostic: original workload, varying reply-probe cardinality only.
  * Does not alter ActorSpec, shutdown configuration, or production code.
  */
object ActorShutdownProbe {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val mode = args(0)
    require(Set("per-message", "shared").contains(mode))
    val messages = args(1).toInt
    require(messages > 0 && messages <= 1000000)
    val output = Path.of(args(2))
    require(!Files.exists(output), "preserve previous diagnostics")
    def emit(value: JsObject): Unit = {
      Files.writeString(output, value.compactPrint + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      println(value.compactPrint)
    }
    def memory: JsObject = {
      val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
      JsObject("heapUsed" -> JsNumber(heap.getUsed), "heapCommitted" -> JsNumber(heap.getCommitted),
        "gc" -> JsArray(ManagementFactory.getGarbageCollectorMXBeans.asScala.map(g =>
          JsObject("name" -> JsString(g.getName), "count" -> JsNumber(g.getCollectionCount),
            "millis" -> JsNumber(g.getCollectionTime))).toVector))
    }
    val kit = ActorTestKit("ActorShutdownProbe")
    val config = kit.system.settings.config
    emit(JsObject("phase" -> JsString("START"), "mode" -> JsString(mode), "messages" -> JsNumber(messages),
      "pid" -> JsNumber(ProcessHandle.current().pid()), "java" -> JsString(System.getProperty("java.version")),
      "maxHeap" -> JsNumber(Runtime.getRuntime.maxMemory()),
      "provider" -> JsString(config.getString("pekko.actor.provider")),
      "shutdownDefault" -> JsString(config.getString("pekko.actor.testkit.typed.system-shutdown-default"))))
    val router = kit.spawn(Routers.pool(100)(Behaviors.supervise(MyActor())
      .onFailure[Exception](SupervisorStrategy.restart)), "million-actors-pool")
    val shared = if (mode == "shared") Some(kit.createTestProbe[String]()) else None
    val started = System.nanoTime()
    (1 to messages).grouped(10000).foreach { batch =>
      batch.foreach(i => router ! MyActor.ProcessMessage(s"msg-$i",
        shared.getOrElse(kit.createTestProbe[String]()).ref))
      Thread.sleep(100) // Same pacing as ActorSpec.
    }
    val sample = kit.createTestProbe[String]()
    router ! MyActor.ProcessMessage("test-msg", sample.ref)
    require(sample.expectMessageType[String](10.seconds).startsWith("Processed: test-msg"))
    emit(JsObject("phase" -> JsString("BEFORE_TERMINATE"),
      "createdReplyProbes" -> JsNumber(if (mode == "per-message") messages else 1),
      "routerPoolSize" -> JsNumber(100), "sampleProbes" -> JsNumber(1),
      "workloadMillis" -> JsNumber((System.nanoTime() - started) / 1000000), "memory" -> memory))
    val terminating = System.nanoTime()
    kit.system.terminate()
    // Observe the existing 10s boundary without ActorTestKit printing a million-node tree.
    val withinTen = Try(Await.result(kit.system.whenTerminated, 10.seconds)).isSuccess
    emit(JsObject("phase" -> JsString("TEN_SECOND_BOUNDARY"), "terminatedWithin10s" -> JsBoolean(withinTen),
      "elapsedMillis" -> JsNumber((System.nanoTime() - terminating) / 1000000), "memory" -> memory))
    if (!withinTen) {
      val stacks = Thread.getAllStackTraces.asScala.toVector.sortBy(_._1.getName).map { case (thread, frames) =>
        JsObject("thread" -> JsString(thread.getName), "state" -> JsString(thread.getState.toString),
          "frames" -> JsArray(frames.take(16).map(f => JsString(f.toString)).toVector))
      }
      emit(JsObject("phase" -> JsString("TIMEOUT_STACKS"), "threads" -> JsArray(stacks)))
    }
    val ended = withinTen || Try(Await.result(kit.system.whenTerminated, 110.seconds)).isSuccess
    emit(JsObject("phase" -> JsString("FINAL"), "terminated" -> JsBoolean(ended),
      "elapsedMillis" -> JsNumber((System.nanoTime() - terminating) / 1000000), "memory" -> memory))
    if (!ended) System.exit(2)
  }
}
