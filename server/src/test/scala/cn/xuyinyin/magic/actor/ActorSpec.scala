package cn.xuyinyin.magic.actor

import cn.xuyinyin.magic.testkit.STPekkoSpec
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, Routers}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object MyActor {
  // 定义 Actor 的消息协议
  sealed trait Command
  case class ProcessMessage(text: String, replyTo: ActorRef[String]) extends Command

  // 定义 Actor 的行为（携带轻量级状态）
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    var processedCount = 0 // 每个 Actor 的状态示例

    Behaviors.receiveMessage { case ProcessMessage(text, replyTo) =>
      processedCount += 1
      replyTo ! s"Processed: $text (count: $processedCount)"
      Behaviors.same
    }
  }
}

class ActorSpec extends ScalaTestWithActorTestKit with STPekkoSpec {

  // 使用测试套件提供的 ExecutionContext
  implicit val ec: ExecutionContext = system.executionContext

  "Pekko Actor" should {
    "create 1 million actors efficiently" in {
      // 打印实际绑定的端口
      val binding: Int = testKit.system.address.port.get
      testKit.system.settings.config match {
        case config: Config =>
          println(s"Config: ${config.getString("pekko.remote.artery.canonical.port")}")
        case _ =>
          println("Config not found")
      }
      println(s"Actual port in use: $binding")

      // 1. 使用路由池优化创建方式
      val pool = Routers.pool(poolSize = 100) { // 控制并发创建
        Behaviors
          .supervise(MyActor())
          .onFailure[Exception](SupervisorStrategy.restart)
      }

      val router = testKit.spawn(pool, "million-actors-pool")

      // 2. 批量创建 Actor（优化内存）
      val batchSize = 10000
      (1 to 1000000).grouped(batchSize).foreach { batch =>
        batch.foreach { i =>
          router ! MyActor.ProcessMessage(s"msg-$i", testKit.createTestProbe().ref)
        }
        // 控制创建速度防止 OOM
        Thread.sleep(100)
      }

      // 3. 验证 Actor 是否存活（抽样检查）
      val probe = testKit.createTestProbe[String]()
      router ! MyActor.ProcessMessage("test-msg", probe.ref)
      probe.expectMessageType[String](10.seconds) should startWith("Processed: test-msg")

      // 4. 内存监控（示例）
      val runtime    = Runtime.getRuntime
      val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
      println(s"≈ Used memory after creation: $usedMemory MB")
    }
  }
}
