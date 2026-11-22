package cn.xuyinyin.magic.cdc.worker

import cn.xuyinyin.magic.cdc.runtime.CdcRuntime

/** Application module for bounded table-graph execution. */
object WorkerMain {
  def main(args: Array[String]): Unit =
    CdcRuntime.main(args)
}
