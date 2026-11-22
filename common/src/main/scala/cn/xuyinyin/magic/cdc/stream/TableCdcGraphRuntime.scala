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
final class TableCdcGraphRuntime private (private val runner: Option[BootstrapRunner], private val graph: Option[TableCdcGraph]) {
  private val materialized = new AtomicReference[Option[TableGraphMaterialized]](None)
  def bootstrapRunner: BootstrapRunner =
    runner.getOrElse(throw new IllegalStateException("bootstrap runner is unavailable for a materialized table graph"))
  def state: BootstrapProgress = bootstrapRunner.state
  def step(stop: () => Boolean = () => false, continuation: Option[BootstrapProgress] = None): BootstrapStepResult = {
    implicit val graphEc: ExecutionContext = ExecutionContext.parasitic
    val activeRunner                       = bootstrapRunner
    // Snapshot phases remain owned by BootstrapRunner. Once Journal has
    // durably entered STREAMING, the materialized graph owns the tail source,
    // target commit, Journal update and L projection.
    if (graph.nonEmpty && activeRunner.state.phase == "STREAMING" && !activeRunner.gcCheckpointRequested) {
      if (stop()) stopMaterialized()
      else if (materialized.get().isEmpty) {
        val running = Await.result(graph.get.materializeRecovered(), 40.seconds)
        require(materialized.compareAndSet(None, Some(running)), "table graph materialization raced")
      }
      BootstrapStepResult(activeRunner.state, false, true, 250, validationComplete = true)
    } else {
      if (activeRunner.gcCheckpointRequested) stopMaterialized()
      if (stop()) stopMaterialized()
      activeRunner.step(stop, continuation)
    }
  }
  def run(stop: () => Boolean = () => false): Unit = bootstrapRunner.run(stop)
  def requestGcCheckpoint(): Unit                  = bootstrapRunner.requestGcCheckpoint()
  def gcCheckpointRequested: Boolean               = bootstrapRunner.gcCheckpointRequested

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
    new TableCdcGraphRuntime(Some(runner), None)
  }
  def fromBootstrap(runner: BootstrapRunner, graph: TableCdcGraph): TableCdcGraphRuntime = {
    require(runner != null && graph != null, "table CDC graph runtime requires bootstrap and streaming graph")
    new TableCdcGraphRuntime(Some(runner), Some(graph))
  }
  def fromGraph(graph: TableCdcGraph): TableCdcGraphRuntime = {
    require(graph != null, "table CDC graph runtime requires a graph")
    new TableCdcGraphRuntime(None, Some(graph))
  }
}
