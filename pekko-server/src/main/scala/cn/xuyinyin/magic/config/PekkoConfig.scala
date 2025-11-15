package cn.xuyinyin.magic.config

import cn.xuyinyin.magic.core.cluster.NodeRole
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger

import scala.jdk.CollectionConverters._

/**
 * Global configuration
 *
 * @author : Xuxiaotuan
 * @since : 2024-09-21 23:16
 */
object PekkoConfig {

  private val logger = Logger(getClass)

  /**
   * Pekko root config.
   */
  val root: Config = {
    logger.info("Loading Pekko-reference config...")
    var config: Config = ConfigFactory.load()
    config = resolveClusterNodeRoles(config)
    logger.info("Loading Pekko-reference config done.")
    config
  }

  val projectVersion: String = root.getString("pekko.project-version")
  val pekkoSysName: String   = root.getString("pekko.pekko-sys")

  /**
   * Node roles
   */
  val roles: Set[String] = root.getStringList("pekko.cluster.roles").asScala.toSet

  /**
   * resolve cluster node roles
   */
  private def resolveClusterNodeRoles(conf: Config): Config = {
    val roleConf: Config = {
      val oriRoles = conf.getStringList("pekko.cluster.roles")
      val roles    = NodeRole.flattenRoles(oriRoles.asScala.toSeq)
      logger.info(s"Pekko cluster node roles: ${roles.mkString(", ")}")
      ConfigFactory.parseString(s"pekko.cluster.roles = [${roles.map(e => s"$e").mkString(",")}]")
    }
    roleConf.withFallback(conf)
  }
}
