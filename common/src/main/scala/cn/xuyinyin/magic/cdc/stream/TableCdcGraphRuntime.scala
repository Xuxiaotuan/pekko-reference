package cn.xuyinyin.magic.cdc.stream

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapProgress,BootstrapRunner,BootstrapStepResult}

/**
  * Canonical per-table execution seam.
  *
  * The current implementation delegates the bounded bootstrap state machine
  * to BootstrapRunner. Keeping that implementation behind this runtime seam
  * lets the materialized TableCdcGraph become the next implementation without
  * changing CdcPlatformRuntime's task, Journal or L-floor contract.
  */
final class TableCdcGraphRuntime private (private val runner:BootstrapRunner) {
  def bootstrapRunner:BootstrapRunner=runner
  def state:BootstrapProgress=runner.state
  def step(stop:()=>Boolean=()=>false,continuation:Option[BootstrapProgress]=None):BootstrapStepResult=
    runner.step(stop,continuation)
  def run(stop:()=>Boolean=()=>false):Unit=runner.run(stop)
  def requestGcCheckpoint():Unit=runner.requestGcCheckpoint()
  def gcCheckpointRequested:Boolean=runner.gcCheckpointRequested
}

object TableCdcGraphRuntime {
  def fromBootstrap(runner:BootstrapRunner):TableCdcGraphRuntime={
    require(runner!=null,"table CDC graph runtime requires a bootstrap runner")
    new TableCdcGraphRuntime(runner)
  }
}
