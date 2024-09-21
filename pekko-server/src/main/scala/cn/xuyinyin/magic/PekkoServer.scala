package cn.xuyinyin.magic

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import com.typesafe.config.ConfigFactory

/**
 * @author : XuJiaWei
 * @since : 2024-09-21 09:11
 */

object PekkoServer extends App {

  private val ports =
    if (args.isEmpty)
      Seq(17356, 17357, 17358)
    else
      args.toSeq.map(_.toInt)
  ports.foreach(startup)

  private object RootBehavior {
    def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { context =>
      // Create an actor that handles cluster domain events
      context.spawn(ClusterListener(), "ClusterListener")

      Behaviors.empty
    }
  }

  private def startup(port: Int): Unit = {
    // Override the configuration of the port
    val config = ConfigFactory
      .parseString(s"""
      pekko.remote.artery.canonical.port=$port
      """)
      .withFallback(ConfigFactory.load())

    // Create an Apache Pekko system
    ActorSystem[Nothing](RootBehavior(), "ClusterSystem", config)
  }

}
