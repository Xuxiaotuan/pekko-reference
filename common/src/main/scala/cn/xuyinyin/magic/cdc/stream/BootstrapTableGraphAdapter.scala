package cn.xuyinyin.magic.cdc.stream

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapProgress, BootstrapRunner, BootstrapStepResult}

/** Compatibility adapter that keeps LOW/HIGH persistence semantics outside the Graph runtime. */
final class BootstrapTableGraphAdapter(runner: BootstrapRunner) extends TableGraphLifecycle {
  require(runner != null, "bootstrap graph adapter requires a runner")
  override def state: BootstrapProgress = runner.state
  override def step(stop: () => Boolean, continuation: Option[BootstrapProgress]): BootstrapStepResult = runner.step(stop, continuation)
  override def run(stop: () => Boolean): Unit = runner.run(stop)
  override def requestGcCheckpoint(): Unit = runner.requestGcCheckpoint()
  override def gcCheckpointRequested: Boolean = runner.gcCheckpointRequested
  override def gcPublisher: Option[TableGraphGcPublisher] = Some(new TableGraphGcPublisher {
    override def state: BootstrapProgress = runner.state
    override private[magic] def publishGcCheckpoint(retention: cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureGcRetention): String =
      runner.publishGcCheckpoint(retention)
  })
}
