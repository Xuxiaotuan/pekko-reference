package cn.xuyinyin.magic.stream

import cn.xuyinyin.magic.testkit.STPekkoSpec
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.{ClosedShape, FlowShape, Inlet, Materializer, Outlet, SourceShape, UniformFanInShape, UniformFanOutShape}
import org.apache.pekko.stream.connectors.slick.scaladsl.{Slick, SlickSession}
import org.apache.pekko.stream.scaladsl.GraphDSL.Implicits.{FanInOps, SourceArrow, fanOut2flow, port2flow}
import org.apache.pekko.stream.scaladsl.{Balance, Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import slick.jdbc.GetResult

import java.util.concurrent.Executors
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class ModularitySpec extends ScalaTestWithActorTestKit with STPekkoSpec {
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

    "pekko Streams Plain SQL queries" in {
      val nestedSource =
        Source
          .single(0)             // An atomic source
          .map(_ + 1)            // an atomic processing stage
          .named("nestedSource") // wraps up the current Source and gives it a name

      val nestedFlow =
        Flow[Int]
          .filter(_ != 0)      // an atomic processing stage
          .map(_ - 2)          // another atomic processing stage
          .named("nestedFlow") // wraps up the Flow, and gives it a name

      val nestedSink =
        nestedFlow
          .to(
            Sink
              .fold(0)(_ + _)
          )                    // wire an atomic sink to the nestedFlow
          .named("nestedSink") // wrap it up

      // Create a RunnableGraph
      val runnableGraph = nestedSource.to(nestedSink)
      runnableGraph.run()
    }

    "Composing complex systems" in {
      val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
        val A: Outlet[Int]                  = builder.add(Source.single(0)).out
        val B: UniformFanOutShape[Int, Int] = builder.add(Broadcast[Int](2))
        val C: UniformFanInShape[Int, Int]  = builder.add(Merge[Int](2))
        val D: FlowShape[Int, Int]          = builder.add(Flow[Int].map(_ + 1))
        val E: UniformFanOutShape[Int, Int] = builder.add(Balance[Int](2))
        val F: UniformFanInShape[Int, Int]  = builder.add(Merge[Int](2))
        val G: Inlet[Any]                   = builder.add(Sink.foreach(println)).in

                      C     <~      F
        A  ~>  B  ~>  C     ~>      F
               B  ~>  D  ~>  E  ~>  F
                             E  ~>  G

        ClosedShape
      })
      graph.run()
    }

    "Composing complex systems 2" in {
      val source: Source[Int, NotUsed] = Source.fromGraph(GraphDSL.create() { implicit builder =>
        val A: Source[Int, NotUsed] = Source.single(0)
        val B: Source[Int, NotUsed] = Source(List(2, 3, 4))
        val C: UniformFanInShape[Int, Int] = builder.add(Merge[Int](2))

        A ~> C
        B ~> C

        SourceShape(C.out)
      }).named("sourceShape")

      val flow: Flow[Int, Int, NotUsed] = Flow.fromGraph(GraphDSL.create() { implicit builder =>
        val B = builder.add(Broadcast[Int](2))
        val C = builder.add(Merge[Int](2))
        val E = builder.add(Balance[Int](2))
        val F = builder.add(Merge[Int](2))

        C  <~  F
        B  ~>                            C  ~>  F
        B  ~>  Flow[Int].map(_ + 1)  ~>  E  ~>  F
        FlowShape(B.in, E.out(1))
      }).named("flowShape")


      val sink: Sink[Int, NotUsed] = {
        val A = Flow[Int].map(_ * 2).drop(10).named("nestedFlow")
        A.to(Sink.head)
      }.named("sinkShape")

      val graphDSL: RunnableGraph[NotUsed] = source.via(flow.filter(_ > 1)).to(sink)

      graphDSL.run()
    }


    "embed one closed graph in another" in {
      val closed1 = Source.single(0).to(Sink.foreach(println))
      val closed2 = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
        val embeddedClosed: ClosedShape = builder.add(closed1)
        embeddedClosed
      })
    }
  }

}
