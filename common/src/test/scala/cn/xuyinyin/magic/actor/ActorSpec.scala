package cn.xuyinyin.magic.actor

import cn.xuyinyin.magic.testkit.STPekkoSpec
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.concurrent.ExecutionContext

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
    "process messages in actor order" in {
      val actor = testKit.spawn(MyActor())
      val probe = testKit.createTestProbe[String]()
      actor ! MyActor.ProcessMessage("first", probe.ref)
      actor ! MyActor.ProcessMessage("second", probe.ref)
      probe.expectMessage("Processed: first (count: 1)")
      probe.expectMessage("Processed: second (count: 2)")
    }
  }
}
