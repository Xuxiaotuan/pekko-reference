package cn.xuyinyin.magic.cdc.capture

import cn.xuyinyin.magic.cdc.runtime.CdcCaptureRuntimeMain

/** Application module for the shared MySQL capture role. */
object CaptureMain {
  def main(args: Array[String]): Unit =
    CdcCaptureRuntimeMain.main(args)
}
