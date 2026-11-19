package cn.xuyinyin.magic.cdc.runtime

/**
 * CDC-only production entry point.
 *
 * The implementation is delegated to the existing standalone bootstrap
 * during the package migration. This makes the runtime boundary explicit
 * without changing Journal, checkpoint, or recovery serialization. The old
 * `CdcStandalone` symbol remains as a compatibility entry for existing launch
 * scripts and tests.
 */
object CdcRuntimeMain {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "configure CDC runtime through the application configuration")
    CdcRuntime.main(args)
  }

  def start(config: com.typesafe.config.Config) = CdcRuntime.start(config)
}
