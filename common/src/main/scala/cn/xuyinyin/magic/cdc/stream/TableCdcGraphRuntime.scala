package cn.xuyinyin.magic.cdc.stream

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapProgress, BootstrapRunner, BootstrapStepResult}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.Await
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
  def bootstrapRunner: Option[BootstrapRunner]
}

private final class BootstrapRunnerLifecycle(runner: BootstrapRunner) extends TableGraphLifecycle {
  override def state: BootstrapProgress = runner.state
  override def step(stop: () => Boolean, continuation: Option[BootstrapProgress]): BootstrapStepResult = runner.step(stop, continuation)
  override def run(stop: () => Boolean): Unit = runner.run(stop)
  override def requestGcCheckpoint(): Unit = runner.requestGcCheckpoint()
  override def gcCheckpointRequested: Boolean = runner.gcCheckpointRequested
  override def bootstrapRunner: Option[BootstrapRunner] = Some(runner)
}

final class TableCdcGraphRuntime private (private val lifecycle: Option[TableGraphLifecycle], private val graph: Option[TableCdcGraph]) {
  private val materialized = new AtomicReference[Option[TableGraphMaterialized]](None)
  def bootstrapRunner: BootstrapRunner =
    lifecycle.flatMap(_.bootstrapRunner).getOrElse(throw new IllegalStateException("bootstrap runner is unavailable for a materialized table graph"))
  def state: BootstrapProgress = lifecycle.map(_.state).getOrElse(throw new IllegalStateException("table graph state is unavailable"))
  def step(stop: () => Boolean = () => false, continuation: Option[BootstrapProgress] = None): BootstrapStepResult = {
    implicit val graphEc: ExecutionContext = ExecutionContext.parasitic
    val activeLifecycle                   = lifecycle.getOrElse(throw new IllegalStateException("table graph lifecycle is unavailable"))
    // Snapshot phases remain owned by BootstrapRunner. Once Journal has
    // durably entered STREAMING, the materialized graph owns the tail source,
    // target commit, Journal update and L projection.
    if (graph.nonEmpty && activeLifecycle.state.phase == "STREAMING" && !activeLifecycle.gcCheckpointRequested) {
      if (stop()) stopMaterialized()
      else if (materialized.get().exists(_.failure().nonEmpty)) {
        val failure = materialized.getAndSet(None).flatMap(_.failure()).get
        throw failure
      } else if (materialized.get().isEmpty) {
        val running = Await.result(graph.get.materializeRecovered(), 40.seconds)
        require(materialized.compareAndSet(None, Some(running)), "table graph materialization raced")
      }
      BootstrapStepResult(activeLifecycle.state, false, true, 250, validationComplete = true)
    } else {
      if (activeLifecycle.gcCheckpointRequested) stopMaterialized()
      if (stop()) stopMaterialized()
      activeLifecycle.step(stop, continuation)
    }
  }
  def run(stop: () => Boolean = () => false): Unit = lifecycle.getOrElse(throw new IllegalStateException("table graph lifecycle is unavailable")).run(stop)
  def requestGcCheckpoint(): Unit = lifecycle.getOrElse(throw new IllegalStateException("table graph lifecycle is unavailable")).requestGcCheckpoint()
  def gcCheckpointRequested: Boolean = lifecycle.exists(_.gcCheckpointRequested)
  /** True while the streaming graph owns the live tail and Journal handoff. */
  def materializedActive: Boolean = materialized.get().nonEmpty

  /** Stop the tail graph before cancellation, GC sealing, or owner release. */
  def stopMaterialized(): Unit = materialized.getAndSet(None).foreach { running =>
    Await.result(running.stop(), 40.seconds)
  }
  def materialize(): TableGraphMaterialized =
    graph.getOrElse(throw new IllegalStateException("materialized table graph is unavailable for bootstrap runtime")).materialize()
  def materializeRecovered()(implicit ec: ExecutionContext): Future[TableGraphMaterialized] =
    graph.getOrElse(throw new IllegalStateException("materialized table graph is unavailable for bootstrap runtime")).materializeRecovered()
}

object TableCdcGraphRuntime {
  def fromBootstrap(runner: BootstrapRunner): TableCdcGraphRuntime = {
    require(runner != null, "table CDC graph runtime requires a bootstrap runner")
    new TableCdcGraphRuntime(Some(new BootstrapRunnerLifecycle(runner)), None)
  }
  def fromBootstrap(runner: BootstrapRunner, graph: TableCdcGraph): TableCdcGraphRuntime = {
    require(runner != null && graph != null, "table CDC graph runtime requires bootstrap and streaming graph")
    new TableCdcGraphRuntime(Some(new BootstrapRunnerLifecycle(runner)), Some(graph))
  }
  def fromGraph(graph: TableCdcGraph): TableCdcGraphRuntime = {
    require(graph != null, "table CDC graph runtime requires a graph")
    new TableCdcGraphRuntime(None, Some(graph))
  }
}
