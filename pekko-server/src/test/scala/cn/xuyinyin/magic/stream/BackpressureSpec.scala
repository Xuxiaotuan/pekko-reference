package cn.xuyinyin.magic.stream

import cn.xuyinyin.magic.PekkoServer.ec
import cn.xuyinyin.magic.testkit.STPekkoSpec
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.{Materializer, OverflowStrategy}

import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class BackpressureSpec extends ScalaTestWithActorTestKit with STPekkoSpec {
  "pekko BackpressureSpec" should {
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

    /**
     * 背压的工作机制
     * 默认策略：下游通过需求信号（demand）控制上游速率。
     * 示例：慢速 Sink 导致 Source 暂停生产。
     */
    "Backpressure demo 1" in {
      val consoleSink = Sink.foreach[Int](x => println(s"Processed: $x"))

      Source(1 to 100000)
        // 每秒1个元素
        .throttle(1, 1.second)
        // 下游处理慢，触发背压
        .runWith(consoleSink)
        .onComplete { _ =>
          // 关闭 ActorSystem
          system.terminate()
        }
    }

    /**
     * 显式缓冲控制
     * 使用 buffer 操作符缓解短期负载：
     */
    "Backpressure demo 2" in {
      Source(1 to 100)
        // 缓冲区大小10，溢出时丢弃头部
        .buffer(1, OverflowStrategy.dropHead)
        .runWith(Sink.foreach(println))
        .onComplete { _ =>
          // 关闭 ActorSystem
          system.terminate()
        }
    }

    /**
     * 动态资源分配
     * 结合 mapAsync 控制并行度：
     */
    "Backpressure demo 3" in {
      // 阻塞操作实现
      def blockingOperation(n: Int): String = {
        Thread.sleep(1000) // 模拟耗时操作
        s"Processed $n"
      }

      // 流定义
      Source(1 to 100)
        .mapAsync(4) { n =>
          Future {
            blockingOperation(n)
          }(ec)
        }
        .runWith(Sink.foreach(println))
        .onComplete { _ =>
          // 关闭 ActorSystem
          system.terminate()
        }(ec)
      Thread.sleep(5000L)
    }

  }

}
