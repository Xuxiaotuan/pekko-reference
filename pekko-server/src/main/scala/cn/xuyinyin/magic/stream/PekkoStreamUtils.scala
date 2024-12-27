package cn.xuyinyin.magic.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.FanInShape.{Init, Name}
import org.apache.pekko.stream.{ClosedShape, FanInShape, Graph, Inlet, Materializer, Outlet, Shape}
import org.apache.pekko.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, MergePreferred, RunnableGraph, Sink, Source}

import scala.collection.immutable

/**
 * https://pekko.apache.org/docs/pekko/current/stream/stream-graphs.html#predefined-shapes
 *
 * @author : XuJiaWei
 * @since : 2024-09-23 23:31
 */
object PekkoStreamUtils {
  def apply[In, Out](
      worker: Flow[In, Out, Any],
      workerCount: Int): Graph[PriorityWorkerPoolShape[In, Out], NotUsed] = {
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val priorityMerge = b.add(MergePreferred[In](1))
      val balance       = b.add(Balance[In](workerCount))
      val resultsMerge  = b.add(Merge[Out](workerCount))

      // After merging priority and ordinary jobs, we feed them to the balancer
      priorityMerge ~> balance

      // Wire up each of the outputs of the balancer to a worker flow
      // then merge them back
      for (i <- 0 until workerCount) {
        balance.out(i) ~> worker ~> resultsMerge.in(i)
      }

      // We now expose the input ports of the priorityMerge and the output
      // of the resultsMerge as our PriorityWorkerPool ports
      // -- all neatly wrapped in our domain specific Shape
      PriorityWorkerPoolShape(jobsIn = priorityMerge.in(0), priorityJobsIn = priorityMerge.preferred, resultsOut = resultsMerge.out)
    }
  }

  def main(args: Array[String]): Unit = {
    val worker1                      = Flow[String].map("step 1 " + _)
    val worker2                      = Flow[String].map("step 2 " + _)
    implicit val system: ActorSystem = ActorSystem("pekko-cluster-system")
    implicit val mat: Materializer   = Materializer(system)

    RunnableGraph
      .fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val priorityPool1 = b.add(PekkoStreamUtils(worker1, workerCount = 4))
        val priorityPool2 = b.add(PekkoStreamUtils(worker2, workerCount = 2))

        Source(1 to 100).map("job: " + _) ~> priorityPool1.jobsIn
        Source(1 to 100).map("priority job: " + _) ~> priorityPool1.priorityJobsIn

        priorityPool1.resultsOut ~> priorityPool2.jobsIn
        Source(1 to 100).map("one-step, priority " + _) ~> priorityPool2.priorityJobsIn

        priorityPool2.resultsOut ~> Sink.foreach(println)
        ClosedShape
      })
      .run()
  }
}

// A shape represents the input and output ports of a reusable
// processing module
case class PriorityWorkerPoolShape[In, Out](jobsIn: Inlet[In], priorityJobsIn: Inlet[In], resultsOut: Outlet[Out]) extends Shape {

  // It is important to provide the list of all input and output
  // ports with a stable order. Duplicates are not allowed.
  override val inlets: immutable.Seq[Inlet[_]] =
    jobsIn :: priorityJobsIn :: Nil
  override val outlets: immutable.Seq[Outlet[_]] =
    resultsOut :: Nil

  // A Shape must be able to create a copy of itself. Basically
  // it means a new instance with copies of the ports
  override def deepCopy(): PriorityWorkerPoolShape[In, Out] =
    PriorityWorkerPoolShape(jobsIn.carbonCopy(), priorityJobsIn.carbonCopy(), resultsOut.carbonCopy())

}
