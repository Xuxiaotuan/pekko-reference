package cn.xuyinyin.magic.stream

import cn.xuyinyin.magic.testkit.STPekkoSpec
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.{ActorAttributes, Materializer, Supervision}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}

class MaterializationSpec extends ScalaTestWithActorTestKit with STPekkoSpec {
  "pekko stream  flow" should {
    val config = ConfigFactory.parseString("""
                                             |pekko {
                                             |  actor.provider = "local"
                                             |  remote.artery.enabled = false
                                             |}
                                             |""".stripMargin)

    implicit val system: ActorSystem  = ActorSystem("pekko-cluster-system", config)
    implicit val mat: Materializer    = Materializer(system)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))

    "Materialization demo 1" in {
      // 定义 Source 和 Sink，保留物化值（Future[Int]）
      val counterSink                   = Sink.fold[Int, Int](0)((count, _) => count + 1)
      val (_, futureCount: Future[Int]) = Source(1 to 50).toMat(counterSink)(Keep.both).run()

      futureCount.onComplete { result =>
        println(s"Total elements: ${result.get}")
      }
    }

    "Supervision demo 1" in {
      val decider: Supervision.Decider = {
        case _: ArithmeticException => Supervision.Resume  // 忽略错误，继续运行
        case _ => Supervision.Stop
      }

      val flow = Flow[Int].map(n => 100 / (n - 5))  // 可能在 n=5 时抛出异常
        .withAttributes(ActorAttributes.supervisionStrategy(decider))

      Source(1 to 10)
        .via(flow)
        .runWith(Sink.foreach(println))  // 遇到 n=5 时会跳过
    }


  }

}
