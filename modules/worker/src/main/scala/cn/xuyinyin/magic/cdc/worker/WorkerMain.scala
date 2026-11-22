package cn.xuyinyin.magic.cdc.worker

/** Application module for bounded table-graph execution. */
object WorkerMain {
  def main(args: Array[String]): Unit =
    cn.xuyinyin.magic.cdc.runtime.CdcRuntimeMain.main(args)
}
