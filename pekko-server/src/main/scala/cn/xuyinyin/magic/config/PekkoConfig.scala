package cn.xuyinyin.magic.config

import cn.xuyinyin.magic.common.PekkoBanner
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger

/**
 * Global configuration
 *
 * @author : XuJiaWei
 * @since : 2024-09-21 23:16
 */
object PekkoConfig {

  private val logger = Logger(getClass)

  /**
   * Pekko root config.
   */
  val root: Config = {
    logger.info("Loading Pekko-reference config...")
    val config: Config = ConfigFactory.load()
    val projectVersion: String = config.getString("pekko.project-version")
    logger.info(PekkoBanner.pekko(projectVersion))
    logger.info("Loading Pekko-reference config done.")
    config
  }
}
