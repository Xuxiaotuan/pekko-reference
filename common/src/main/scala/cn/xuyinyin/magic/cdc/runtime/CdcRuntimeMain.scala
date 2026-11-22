package cn.xuyinyin.magic.cdc.runtime

import com.typesafe.config.ConfigFactory

/**
 * CDC-only production entry point.
 *
 * The implementation is delegated to the standalone bootstrap while keeping
 * Journal, checkpoint, and recovery serialization unchanged.
 */
object CdcRuntimeMain {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "configure CDC runtime through the application configuration")
    val config = ConfigFactory.load()
    if (config.hasPath("pekko.workflow.cdc-platform.runtime-role") &&
      config.getString("pekko.workflow.cdc-platform.runtime-role") == "capture")
      CdcCaptureRuntimeMain.main(args)
    else CdcRuntime.main(args)
  }

  def start(config: com.typesafe.config.Config) = CdcRuntime.start(config)
}
