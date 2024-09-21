package cn.xuyinyin.magic

import cn.xuyinyin.magic.cluster.PekkoGuardian
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.ExecutionContextExecutor


object PekkoServer extends App {

  private val config = ConfigFactory.load()

  // init Apache Pekko system
  implicit val system: ActorSystem[PekkoGuardian.Command] = ActorSystem(PekkoGuardian(), "pekko-cluster-system", config)
  implicit val ec: ExecutionContextExecutor               = system.executionContext
  // allowing all actors to finish their tasks and clean up resources before shutting down completely.
  sys.addShutdownHook(system.terminate())

}
