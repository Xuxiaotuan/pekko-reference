package cn.xuyinyin.magic.stream

import cn.xuyinyin.magic.testkit.STPekkoSpec
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.{CompletionStrategy, Materializer, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

/**
 * @author : Xuxiaotuan
 * @since : 2023-03-20 23:34
 */
class PekkoStreamSpec extends ScalaTestWithActorTestKit with STPekkoSpec {
  "akka" should {

    implicit val system: ActorSystem  = ActorSystem("demo")
    implicit val mat: Materializer    = Materializer(system)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))

    "Cannot open a new substream as there are too many substreams open" in {
      val maxSubstreams = 1000
      Source(1 to 10_000_000)
        .groupBy(maxSubstreams, _ % maxSubstreams)
        .mapAsync(1) { n =>
          org.apache.pekko.pattern.after(100.milliseconds, system.scheduler)(scala.concurrent.Future.successful(n))(ec)
        }
        .mergeSubstreams
        .recover { case ex: Exception =>
          println(s"Error: ${ex.getMessage}")
        }
        .runWith(Sink.foreach(println))
    }

    "akka stream" in {
      Source(1 to 1000000)
        .map(_.toString)
        .grouped(1000)
        .map(group => group.mkString(","))
        .runForeach(println)
    }

    "ExecutionContext" in {
      Source(1 to 100)
        .mapAsyncUnordered(8)(i => Future(i * i)(ec))
        .runForeach(println)
    }

    "test stream execution" in {
      val (ref, publisher) = Source
        .actorRef[String](10_000, OverflowStrategy.fail)
        .toMat(Sink.asPublisher(false))(Keep.both)
        .run()
      val binlogCdcEventMq: Source[String, NotUsed] = Source.fromPublisher(publisher)

      ref ! "hello"
      ref ! "hello"
    }

    "stream source.actorRef" in {
      val source: Source[String, ActorRef] = Source.actorRef(
        completionMatcher = { case Done =>
          // complete stream immediately if we send it Done
          CompletionStrategy.immediately
        },
        // never fail the stream because of a message
        failureMatcher = PartialFunction.empty,
        bufferSize = 100,
        overflowStrategy = OverflowStrategy.dropHead)
      val actorRef: ActorRef = source.to(Sink.foreach(println)).run()

      actorRef ! "hello"
      actorRef ! "world"

      // The stream completes successfully with the following message
      actorRef ! Done
    }

  }
}
