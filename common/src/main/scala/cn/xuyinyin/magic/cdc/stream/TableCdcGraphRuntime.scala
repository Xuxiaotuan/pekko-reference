package cn.xuyinyin.magic.cdc.stream

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapProgress, BootstrapStepResult}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Canonical per-table execution seam.
 *
 * BootstrapRunner remains the bounded LOW/HIGH/F state machine. After the
 * Journal enters STREAMING, the materialized TableCdcGraph owns the tail
 * source, target commit, Journal update and L projection behind this seam.
 */
trait TableGraphLifecycle {
  def state: BootstrapProgress
  def step(stop: () => Boolean, continuation: Option[BootstrapProgress]): BootstrapStepResult
  def run(stop: () => Boolean): Unit
  def requestGcCheckpoint(): Unit
  def gcCheckpointRequested: Boolean
  def gcPublisher: Option[TableGraphGcPublisher]
}

/** Narrow capability needed by GC. The runtime no longer exposes BootstrapRunner. */
trait TableGraphGcPublisher {
  def state: BootstrapProgress
  private[magic] def publishGcCheckpoint(retention: cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureGcRetention): String
}

/** Owns the lifecycle decision and the optional materialized stream. */
private final class TableGraphController(
    lifecycle: Option[TableGraphLifecycle],
    graph: Option[TableCdcGraph]) {

  private val materialized = new AtomicReference[Option[TableGraphMaterialized]](None)

  def gcPublisher: TableGraphGcPublisher =
    lifecycle.flatMap(_.gcPublisher).getOrElse(throw new IllegalStateException("GC publisher is unavailable for a materialized table graph"))

  def state: BootstrapProgress =
    lifecycle.map(_.state).getOrElse(throw new IllegalStateException("table graph state is unavailable"))

  def step(stop: () => Boolean, continuation: Option[BootstrapProgress]): BootstrapStepResult = {
    implicit val graphEc: ExecutionContext = ExecutionContext.parasitic
    val activeLifecycle                   = lifecycle.getOrElse(throw new IllegalStateException("table graph lifecycle is unavailable"))

    if (streamingGraphShouldRun(activeLifecycle)) runStreamingStep(activeLifecycle, stop)
    else {
      stopForLifecycleBoundary(activeLifecycle, stop)
      activeLifecycle.step(stop, continuation)
    }
  }

  def run(stop: () => Boolean): Unit =
    lifecycle.getOrElse(throw new IllegalStateException("table graph lifecycle is unavailable")).run(stop)

  def requestGcCheckpoint(): Unit =
    lifecycle.getOrElse(throw new IllegalStateException("table graph lifecycle is unavailable")).requestGcCheckpoint()

  def gcCheckpointRequested: Boolean = lifecycle.exists(_.gcCheckpointRequested)

  def materializedActive: Boolean = materialized.get().nonEmpty

  def stopMaterialized(): Unit = materialized.getAndSet(None).foreach { running =>
    Await.result(running.stop(), 40.seconds)
  }

  def materialize(): TableGraphMaterialized =
    graph.getOrElse(throw new IllegalStateException("materialized table graph is unavailable for bootstrap runtime")).materialize()

  def materializeRecovered()(implicit ec: ExecutionContext): Future[TableGraphMaterialized] =
    graph.getOrElse(throw new IllegalStateException("materialized table graph is unavailable for bootstrap runtime")).materializeRecovered()

  private def streamingGraphShouldRun(activeLifecycle: TableGraphLifecycle): Boolean =
    graph.nonEmpty && activeLifecycle.state.phase == "STREAMING" && !activeLifecycle.gcCheckpointRequested

  private def runStreamingStep(activeLifecycle: TableGraphLifecycle, stop: () => Boolean)(implicit ec: ExecutionContext): BootstrapStepResult = {
    if (stop()) stopMaterialized()
    else if (materialized.get().exists(_.failure().nonEmpty)) {
      val failure = materialized.getAndSet(None).flatMap(_.failure()).get
      throw failure
    } else if (materialized.get().isEmpty) {
      val running = Await.result(graph.get.materializeRecovered(), 40.seconds)
      require(materialized.compareAndSet(None, Some(running)), "table graph materialization raced")
    }
    BootstrapStepResult(activeLifecycle.state, didWork = false, releaseConfirmed = true, 250, validationComplete = true)
  }

  private def stopForLifecycleBoundary(activeLifecycle: TableGraphLifecycle, stop: () => Boolean): Unit = {
    if (activeLifecycle.gcCheckpointRequested || stop()) stopMaterialized()
  }
}

/** Stable public facade used by the platform and tests. */
final class TableCdcGraphRuntime private (controller: TableGraphController) {
  def gcPublisher: TableGraphGcPublisher = controller.gcPublisher
  def state: BootstrapProgress = controller.state
  def step(stop: () => Boolean = () => false, continuation: Option[BootstrapProgress] = None): BootstrapStepResult =
    controller.step(stop, continuation)
  def run(stop: () => Boolean = () => false): Unit = controller.run(stop)
  def requestGcCheckpoint(): Unit = controller.requestGcCheckpoint()
  def gcCheckpointRequested: Boolean = controller.gcCheckpointRequested
  /** True while the streaming graph owns the live tail and Journal handoff. */
  def materializedActive: Boolean = controller.materializedActive
  /** Stop the tail graph before cancellation, GC sealing, or owner release. */
  def stopMaterialized(): Unit = controller.stopMaterialized()
  def materialize(): TableGraphMaterialized = controller.materialize()
  def materializeRecovered()(implicit ec: ExecutionContext): Future[TableGraphMaterialized] = controller.materializeRecovered()
}

object TableCdcGraphRuntime {
  def fromSnapshot(lifecycle: TableGraphLifecycle, graph: Option[TableCdcGraph]): TableCdcGraphRuntime = {
    require(lifecycle != null, "table CDC graph runtime requires a table lifecycle")
    new TableCdcGraphRuntime(new TableGraphController(Some(lifecycle), graph))
  }

  def fromGraph(graph: TableCdcGraph): TableCdcGraphRuntime = {
    require(graph != null, "table CDC graph runtime requires a graph")
    new TableCdcGraphRuntime(new TableGraphController(None, Some(graph)))
  }
}
