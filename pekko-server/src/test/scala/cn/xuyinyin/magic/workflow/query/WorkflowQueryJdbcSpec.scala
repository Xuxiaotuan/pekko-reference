package cn.xuyinyin.magic.workflow.query

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._

/** Real JDBC currentPersistenceIds + persisted entities; not a MySQL or sharding scale test. */
class WorkflowQueryJdbcSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private val url = s"jdbc:h2:mem:query_${UUID.randomUUID().toString.replace('-', '_')};MODE=MySQL"
  private val keeper = DriverManager.getConnection(url)
  private val schema = getClass.getClassLoader.getResourceAsStream("schema/h2/h2-create-schema.sql")
  private val sql = try scala.io.Source.fromInputStream(schema).mkString finally schema.close()
  private val statement = keeper.createStatement()
  try sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute) finally statement.close()
  private val kit = ActorTestKit("jdbc-workflow-directory", ConfigFactory.parseString(s"""
    pekko.actor.provider = local
    pekko-persistence-jdbc.shared-databases.slick.db.url = "$url"
  """).withFallback(ConfigFactory.load("application-test")))
  private implicit val system: org.apache.pekko.actor.typed.ActorSystem[_] = kit.system
  private implicit val ec: scala.concurrent.ExecutionContext = kit.system.executionContext
  override def afterAll(): Unit = {
    try kit.shutdownTestKit() finally { keeper.close(); super.afterAll() }
  }

  "the bounded directory query" should {
    "read actual JDBC persistence IDs and return persisted workflow revisions" in {
      val engine = new WorkflowExecutionEngine()
      val entities = Vector("alpha", "beta", "gamma").map { id =>
        val entity = kit.spawn(EventSourcedWorkflowActor(id, engine))
        val reply = kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
        entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow.copy(id = id), 0L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined(id, 1L))
        id -> entity
      }.toMap
      val supervisor = kit.spawn(Behaviors.receiveMessage[WorkflowSupervisor.Command] {
        case WorkflowSupervisor.GetWorkflowSummary(id, replyTo) =>
          entities(id) ! EventSourcedWorkflowActor.GetSummary(replyTo)
          Behaviors.same
        case _ => Behaviors.unhandled
      })
      val service = new WorkflowQueryService(kit.system, supervisor)
      val page = Await.result(service.list(0, 10), 8.seconds)
      page.items.map(_.workflowId).toSet shouldBe entities.keySet
      page.items.map(_.revision).toSet shouldBe Set(1L)
      val farPage = Await.result(service.list(Int.MaxValue, Int.MaxValue), 8.seconds)
      farPage.items shouldBe empty
    }
  }
}
