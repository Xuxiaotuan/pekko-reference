package cn.xuyinyin.magic.cdc.stream

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapProgress,BootstrapRunner,BootstrapStepResult}
import scala.concurrent.{ExecutionContext,Future}

/**
  * Canonical per-table execution seam.
  *
  * The current implementation delegates the bounded bootstrap state machine
  * to BootstrapRunner. Keeping that implementation behind this runtime seam
  * lets the materialized TableCdcGraph become the next implementation without
  * changing CdcPlatformRuntime's task, Journal or L-floor contract.
  */
final class TableCdcGraphRuntime private (private val runner:Option[BootstrapRunner],private val graph:Option[TableCdcGraph]) {
  def bootstrapRunner:BootstrapRunner=runner.getOrElse(throw new IllegalStateException("bootstrap runner is unavailable for a materialized table graph"))
  def state:BootstrapProgress=bootstrapRunner.state
  def step(stop:()=>Boolean=()=>false,continuation:Option[BootstrapProgress]=None):BootstrapStepResult=
    bootstrapRunner.step(stop,continuation)
  def run(stop:()=>Boolean=()=>false):Unit=bootstrapRunner.run(stop)
  def requestGcCheckpoint():Unit=bootstrapRunner.requestGcCheckpoint()
  def gcCheckpointRequested:Boolean=bootstrapRunner.gcCheckpointRequested
  def materialize():TableGraphMaterialized=graph.getOrElse(throw new IllegalStateException("materialized table graph is unavailable for bootstrap runtime")).materialize()
  def materializeRecovered()(implicit ec:ExecutionContext):Future[TableGraphMaterialized]=
    graph.getOrElse(throw new IllegalStateException("materialized table graph is unavailable for bootstrap runtime")).materializeRecovered()
}

object TableCdcGraphRuntime {
  def fromBootstrap(runner:BootstrapRunner):TableCdcGraphRuntime={
    require(runner!=null,"table CDC graph runtime requires a bootstrap runner")
    new TableCdcGraphRuntime(Some(runner),None)
  }
  def fromGraph(graph:TableCdcGraph):TableCdcGraphRuntime={
    require(graph!=null,"table CDC graph runtime requires a graph")
    new TableCdcGraphRuntime(None,Some(graph))
  }
}
