package cn.xuyinyin.magic.cdc.capture

/** Application module for the shared MySQL capture role. */
object CaptureMain {
  def main(args: Array[String]): Unit =
    cn.xuyinyin.magic.cdc.runtime.CdcCaptureRuntimeMain.main(args)
}
