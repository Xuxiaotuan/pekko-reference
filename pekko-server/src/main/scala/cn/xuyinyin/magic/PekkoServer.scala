package cn.xuyinyin.magic

import cn.xuyinyin.magic.cluster.PekkoGuardian
import cn.xuyinyin.magic.common.PekkoBanner
import cn.xuyinyin.magic.config.PekkoConfig
import cn.xuyinyin.magic.config.PekkoConfig.pekkoSysName
import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.ExecutionContextExecutor

/**
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object PekkoServer extends App {
  private val logger = Logger(getClass)
  private val config: PekkoConfig.type = PekkoConfig
  logger.info(PekkoBanner.pekkoServer)

  // init Apache Pekko system
  implicit val system: ActorSystem[PekkoGuardian.Command] = ActorSystem(PekkoGuardian(), pekkoSysName, config.root)
  implicit val ec: ExecutionContextExecutor               = system.executionContext
  // allowing all actors to finish their tasks and clean up resources before shutting down completely.
  sys.addShutdownHook(system.terminate())
  logger.info("Pekko server started successfully!")
}
