//#full-example
package cn.xuyinyin


import cn.xuyinyin.GreeterMain.SayHello
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

//#greeter-actor
object Greeter {

  final case class Greet(whom: String, replyTo: ActorRef[Greeted])

  final case class Greeted(whom: String, from: ActorRef[Greet])

  def apply(): Behavior[Greet] = Behaviors.receive { (context, message) =>
    context.log.info("Hello {}!", message.whom)
    //#greeter-send-messages
    message.replyTo ! Greeted(message.whom, context.self)
    //#greeter-send-messages
    Behaviors.same
  }
}
//#greeter-actor

//#greeter-bot
object GreeterBot {

  def apply(max: Int): Behavior[Greeter.Greeted] = {
    bot(0, max)
  }

  private def bot(greetingCounter: Int, max: Int): Behavior[Greeter.Greeted] =
    Behaviors.receive { (context, message) =>
      val n = greetingCounter + 1
      context.log.info("Greeting {} for {}", n, message.whom)
      n match {
        case n if n == max => Behaviors.stopped
        case _ =>
          message.from ! Greeter.Greet(message.whom, context.self)
          bot(n, max)
      }
    }
}
//#greeter-bot

//#greeter-main
object GreeterMain {

  final case class SayHello(name: String)

  def apply(): Behavior[SayHello] =
    Behaviors.setup { context =>
      //#create-actors
      val greeter = context.spawn(Greeter(), "greeter")
      //#create-actors

      Behaviors.receiveMessage { message =>
        //#create-actors
        val replyTo = context.spawn(GreeterBot(max = 3), message.name)
        //#create-actors
        greeter ! Greeter.Greet(message.name, replyTo)
        Behaviors.same
      }
    }
}
//#greeter-main

//#main-class
object PekkoQuickstart extends App {
  //#actor-system
  val greeterMain: ActorSystem[GreeterMain.SayHello] = ActorSystem(GreeterMain(), "PekkoQuickstart")
  //#actor-system

  //#main-send-messages
  greeterMain ! SayHello("Charles")
  //#main-send-messages
}
//#main-class
//#full-example
