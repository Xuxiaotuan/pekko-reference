package cn.xuyinyin.magic.stream

import cn.xuyinyin.magic.testkit.STPekkoSpec
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.GraphDSL.Implicits.{SourceArrow, fanOut2flow}
import org.apache.pekko.stream.{ClosedShape, CompletionStrategy, Materializer, OverflowStrategy, UniformFanInShape}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, RunnableGraph, Sink, Source}

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

/**
 *  https://doc.pekko.io/docs/pekko/current/stream/stream-flows-and-basics.html
 *
 * @author : Xuxiaotuan
 * @since : 2023-03-20 23:34
 */
class PekkoStreamFlowSpec extends ScalaTestWithActorTestKit with STPekkoSpec {
  "pekko stream  flow" should {

    val config = ConfigFactory.parseString(
      """
        |pekko {
        |  remote.artery {
        |    canonical {
        |      port = 0 # 使用随机端口
        |    }
        |  }
        |}
        |""".stripMargin)

    implicit val system: ActorSystem = ActorSystem("demo", config)
    implicit val mat: Materializer    = Materializer(system)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))

    "pekko Streams working with flow sample." in {
      val in  = Source(1 to 10)
      val out = Sink.foreach[Int](x => println(s"out: Number: $x"))

      val processing1 = Flow[Int].map(_ * 2)
      val processing2 = Flow[Int].filter(_ % 2 == 0)

      val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>


        val broadcast                          = builder.add(Broadcast[Int](2))
        val merge: UniformFanInShape[Int, Int] = builder.add(Merge[Int](2))

        /**
         * * {{{
         *        +-----------+                 +-----------+
         *        |           |~> processing1   |           |
         *   in ~>| broadcast |                 |   merge   |  ~> out1
         *        |           |~> processing2   |           |
         *        +-----------+                 +-----------+
         * }}}
         */
        in ~> broadcast ~> processing1 ~> merge ~> out
        broadcast ~> processing2 ~> merge

        ClosedShape
      })

      graph.run()
    }

    "pekko Stream RunnableGraph" in {
      Source(1 to 10)
        // connect the source to the sink , obtaining a RunnableGraph
        .toMat(Sink.fold[Int, Int](0)(_ + _))(Keep.right)
        // materialize the flow and get the value of the sink
        .run()
        .onComplete {
          case Success(res) => println(res)
          case Failure(e)   => println(s"something wrong ${e.getCause}")
        }
    }

    "pekko Stream runWith, toMat()(Keep.right).run() A simplified version of " in {
      Source(1 to 10)
        // materialize the flow, getting the Sink's materialized value
        .runWith(Sink.fold[Int, Int](0)(_ + _))
        .onComplete {
          case Success(res) => println(res)
          case Failure(e)   => println(s"something wrong ${e.getCause}")
        }
    }

    "pekko operators are immutable" in {
      val source = Source(1 to 10)
      // has no effect on source, since it`s immutable
      source.map(_ => 0)
      // 55
      source
        .runWith(Sink.fold(0)(_ + _))
        .onComplete {
          case Success(res) => println(s"has no effect on source, since it`s immutable $res")
          case Failure(e)   => println(s"something wrong ${e.getCause}")
        }
      // returns new Source[Int], with `map()` appended
      val zeroes = source.map(_ => 0)
      // 0
      zeroes
        .runWith(Sink.fold(0)(_ + _))
        .onComplete {
          case Success(res) => println(s"returns new Source[Int], with `map()` appended $res")
          case Failure(e)   => println(s"something wrong ${e.getCause}")
        }
    }

    "RunnableGraph 1" in {
      // connect the Source to the Sinl, obtaining a RunnableGraph
      val source: Source[Int, NotUsed]              = Source(1 to 10)
      val sink: Sink[Int, Future[Int]]              = Sink.fold[Int, Int](0)(_ + _)
      val runnableGraph: RunnableGraph[Future[Int]] = source.toMat(sink)(Keep.right)

      // get the materialized value of the sink
      val sum1: Future[Int] = runnableGraph.run()
      val sum2: Future[Int] = runnableGraph.run()

      // sum1 and sum2 are different Futures!
      sum1.onComplete {
        case Success(value)     => println(s"sum1: $value")
        case Failure(exception) => println(s"sum1: ${exception.getMessage}")
      }

      sum2.onComplete {
        case Success(value)     => println(s"sum2: $value")
        case Failure(exception) => println(s"sum2: ${exception.getMessage}")
      }
    }

    "Source pre-materialization" in {
      val completeWithDone: PartialFunction[Any, CompletionStrategy] = { case Done => CompletionStrategy.immediately }
      val matValuePowerdSource: Source[String, ActorRef] = Source.actorRef[String](
        completionMatcher = completeWithDone,
        failureMatcher = PartialFunction.empty,
        bufferSize = 100,
        overflowStrategy = OverflowStrategy.fail)
      val (actoRef: ActorRef, source: Source[String, NotUsed]) = matValuePowerdSource.preMaterialize()

      actoRef ! "Hello world!"

      // pass source around for materialization
      source.runWith(Sink.foreach(println))
    }
  }
}
