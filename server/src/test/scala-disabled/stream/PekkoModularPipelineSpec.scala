package cn.xuyinyin.magic.stream

import cn.xuyinyin.magic.testkit.STPekkoSpec
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.language.postfixOps

class PekkoModularPipelineSpec extends ScalaTestWithActorTestKit with STPekkoSpec {
  "pekko stream  flow" should {

    val config = ConfigFactory.parseString("""
                                             |pekko {
                                             |  remote.artery {
                                             |    canonical {
                                             |      port = 0 # 使用随机端口
                                             |    }
                                             |  }
                                             |}
                                             |""".stripMargin)

    implicit val system: ActorSystem  = ActorSystem("demo", config)
    implicit val mat: Materializer    = Materializer(system)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))

    "pekko Streams working with flow sample." in {
      // 定义 Fake 数据作为 Source
      val fakeData = List(1, 2, 3, 4, 5) // 这里使用 List 作为 Fake 数据
      val source = Source(fakeData)

      // 定义 Flow：将数据乘以 2
      val multiplyByTwoFlow = Flow[Int].map(_ * 2)

      // 定义 Sink：打印到控制台
      val consoleSink = Sink.foreach[Int](x => println(s"Processed: $x"))

      // 组装流处理管道
      source
        .via(multiplyByTwoFlow)
        .runWith(consoleSink)
        .onComplete { _ =>
          system.terminate() // 关闭 ActorSystem
        }
    }
  }
}
