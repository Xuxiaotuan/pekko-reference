package cn.xuyinyin.magic

import cn.xuyinyin.magic.cluster.PekkoGuardian
import cn.xuyinyin.magic.config.PekkoConfig
import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.ExecutionContextExecutor


object PekkoServer extends App {
  private val logger = Logger(getClass)

  private val config: PekkoConfig.type = PekkoConfig
  private val pekkoSysName: String = config.root.getString("pekko.pekko-sys")

  // init Apache Pekko system
  implicit val system: ActorSystem[PekkoGuardian.Command] = ActorSystem(PekkoGuardian(), pekkoSysName, config.root)
  implicit val ec: ExecutionContextExecutor               = system.executionContext
  // allowing all actors to finish their tasks and clean up resources before shutting down completely.
  sys.addShutdownHook(system.terminate())
  logger.info("Pekko server started successfully!")
}
