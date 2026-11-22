package cn.xuyinyin.magic.cdc.worker

/** Application module for bounded table-graph execution. */
object WorkerMain {
  def main(args: Array[String]): Unit =
    // Keep the deployable worker boundary explicit. CdcRuntimeMain remains a
    // compatibility launcher for older scripts, while this module always
    // starts the worker runtime directly.
    cn.xuyinyin.magic.cdc.runtime.CdcRuntime.main(args)
}
