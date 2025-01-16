package cn.xuyinyin.magic.stream

import cn.xuyinyin.magic.testkit.STPekkoSpec
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.connectors.slick.scaladsl.{Slick, SlickSession}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import slick.jdbc.GetResult

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class MysqlToConsoleSpec extends ScalaTestWithActorTestKit with STPekkoSpec {
  "pekko stream  flow" should {
    val config = ConfigFactory.parseString("""
                                             |pekko {
                                             |  actor.provider = "local"
                                             |  remote.artery.enabled = false
                                             |}
                                             |""".stripMargin)

    implicit val system: ActorSystem  = ActorSystem("pekko-cluster-system", config)
    implicit val mat: Materializer    = Materializer(system)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))

    "pekko Streams Plain SQL queries" in {
      implicit val session: SlickSession = SlickSession.forConfig("slick-h2")
      system.registerOnTermination(session.close())

      case class User(id: Int, name: String)

      implicit val getUserResult = GetResult(r => User(r.nextInt(), r.nextString()))


      import session.profile.api._

      Slick
        .source(sql"SELECT ID, NAME FROM PEKKO_CONNECTORS_SLICK_SCALADSL_TEST_USERS".as[User])
        .log("user")
        .runWith(Sink.ignore)
    }

    "pekko Streams Typed Queries." in {
      implicit val session: SlickSession = SlickSession.forConfig("slick-h2")

      // This import brings everything you need into scope
      import session.profile.api._

      // The example domain
      class Users(tag: Tag) extends Table[(Int, String)](tag, "PEKKO_CONNECTORS_SLICK_SCALADSL_TEST_USERS") {
        def id = column[Int]("ID")
        def name = column[String]("NAME")
        def * = (id, name)
      }

      Slick.source(TableQuery[Users].result)
        .log("nr-of-updated-rows")
        .runWith(Sink.ignore)
          .onComplete { _ =>
            system.registerOnTermination(() => session.close())
          }
    }

    "Using a Slick Flow or Sink¶" in {
      implicit val session = SlickSession.forConfig("slick-h2")
      system.registerOnTermination(session.close())

      // The example domain
      case class User(id: Int, name: String)
      val users = (1 to 42).map(i => User(i, s"Name$i"))

      // This import enables the use of the Slick sql"...",
      // sqlu"...", and sqlt"..." String interpolators.
      // See "http://slick.lightbend.com/doc/3.2.1/sql.html#string-interpolation"
      import session.profile.api._

      // Stream the users into the database as insert statements
      val done: Future[Done] =
        Source(users)
          .runWith(
            // add an optional first argument to specify the parallelism factor (Int)
            Slick.sink(user =>
              sqlu"INSERT INTO PEKKO_CONNECTORS_SLICK_SCALADSL_TEST_USERS VALUES(${user.id}, ${user.name})"))
    }

    "Flow" in {
      implicit val session = SlickSession.forConfig("slick-h2")
      system.registerOnTermination(session.close())

      // The example domain
      case class User(id: Int, name: String)
      val users = (1 to 42).map(i => User(i, s"Name$i"))

      // This import enables the use of the Slick sql"...",
      // sqlu"...", and sqlt"..." String interpolators.
      // See "http://slick.lightbend.com/doc/3.2.1/sql.html#string-interpolation"
      import session.profile.api._

      // Stream the users into the database as insert statements
      val done: Future[Done] =
        Source(users)
          .via(
            // add an optional first argument to specify the parallelism factor (Int)
            Slick.flow(user =>
              sqlu"INSERT INTO PEKKO_CONNECTORS_SLICK_SCALADSL_TEST_USERS VALUES(${user.id}, ${user.name})"))
          .log("nr-of-updated-rows")
          .runWith(Sink.ignore)
    }
  }


}
