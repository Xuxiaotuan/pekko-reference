package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.typed.{Cluster, Down, Join}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.Source
import scala.jdk.CollectionConverters._

private[integration] object MultiNodeTestSupport {
  final case class Database(directory: Path, url: String)

  def newDatabase(prefix: String): Database = {
    val directory = Files.createTempDirectory(prefix)
    val database = Database(
      directory,
      s"jdbc:h2:file:${directory.resolve("persistence").toAbsolutePath};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
    )
    try {
      initializeH2(database.url)
      database
    } catch {
      case error: Throwable =>
        cleanupDatabase(database)
        throw error
    }
  }

  def cleanupDatabase(database: Database): Unit = {
    val directory = database.directory.toAbsolutePath.normalize()
    val tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath.normalize()
    require(directory.getParent == tempRoot, s"refusing to delete directory outside java.io.tmpdir: $directory")
    require(
      directory.getFileName.toString.startsWith("two-node-workflow-") ||
        directory.getFileName.toString.startsWith("scheduler-failover-"),
      s"refusing to delete unexpected test directory: $directory"
    )
    if (Files.exists(directory)) {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(path => Files.delete(path))
      finally paths.close()
    }
  }

  def nodeConfig(databaseUrl: String): Config = {
    require(
      getClass.getClassLoader.getResource("application-multinode-test.conf") != null,
      "application-multinode-test.conf is required"
    )
    ConfigFactory
      .parseString(s"pekko-persistence-jdbc.shared-databases.slick.db.url = \"$databaseUrl\"")
      .withFallback(ConfigFactory.load("application-multinode-test"))
  }

  def joinSelf(testKit: ActorTestKit): Unit = {
    val cluster = Cluster(testKit.system)
    cluster.manager ! Join(cluster.selfMember.address)
  }

  def join(testKit: ActorTestKit, seed: ActorTestKit): Unit =
    Cluster(testKit.system).manager ! Join(Cluster(seed.system).selfMember.address)

  def downFrom(survivor: ActorTestKit, address: org.apache.pekko.actor.Address): Unit =
    Cluster(survivor.system).manager ! Down(address)

  def terminate(testKit: ActorTestKit): Unit = {
    testKit.system.terminate()
    Await.result(testKit.system.whenTerminated, 20.seconds)
  }

  def shutdown(testKit: ActorTestKit): Unit =
    if (!testKit.system.whenTerminated.isCompleted) testKit.shutdownTestKit()

  private def initializeH2(url: String): Unit = {
    Class.forName("org.h2.Driver")
    val connection = DriverManager.getConnection(url)
    try {
      val statement = connection.createStatement()
      try {
        val input = Option(getClass.getClassLoader.getResourceAsStream("schema/h2/h2-create-schema.sql"))
          .getOrElse(throw new IllegalStateException("Pekko Persistence JDBC H2 schema resource is unavailable"))
        val sql = try Source.fromInputStream(input).mkString finally input.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      } finally statement.close()
    } finally connection.close()
  }
}

class TwoNodeWorkflowRecoverySpec extends AnyWordSpec with Matchers with Eventually {
  implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(35, Seconds), interval = Span(250, org.scalatest.time.Millis))

  "application-prod scalar environment substitutions" should {
    "override the built-in network defaults when the resolved config is loaded" in synchronized {
      val overrides = Map(
        "PEKKO_HOSTNAME" -> "node-from-environment",
        "PEKKO_PORT" -> "2651",
        "HTTP_HOST" -> "127.0.0.2",
        "HTTP_PORT" -> "8181",
        "PEKKO_SHARDING_SHARDS" -> "37",
        "PEKKO_WORKFLOW_SNAPSHOT_EVERY" -> "23",
        "PEKKO_WORKFLOW_KEEP_SNAPSHOTS" -> "4",
        "PEKKO_LOG_LEVEL" -> "DEBUG"
      )
      val previous = overrides.keys.map(key => key -> Option(System.getProperty(key))).toMap
      try {
        overrides.foreach { case (key, value) => System.setProperty(key, value) }
        ConfigFactory.invalidateCaches()
        val config = ConfigFactory.load("application-prod")
        config.getString("pekko.remote.artery.canonical.hostname") shouldBe "node-from-environment"
        config.getInt("pekko.remote.artery.canonical.port") shouldBe 2651
        config.getString("http.host") shouldBe "127.0.0.2"
        config.getInt("http.port") shouldBe 8181
        config.getInt("pekko.cluster.sharding.number-of-shards") shouldBe 37
        config.getInt("pekko.workflow.event-sourcing.snapshot-every") shouldBe 23
        config.getInt("pekko.workflow.event-sourcing.keep-n-snapshots") shouldBe 4
        config.getString("pekko.loglevel") shouldBe "DEBUG"
      } finally {
        previous.foreach {
          case (key, Some(value)) => System.setProperty(key, value)
          case (key, None) => System.clearProperty(key)
        }
        ConfigFactory.invalidateCaches()
      }
    }

    "keep library, default, development, and test shutdown non-exiting while production exits" in {
      ConfigFactory.invalidateCaches()
      ConfigFactory.defaultReference().getBoolean("pekko.coordinated-shutdown.exit-jvm") shouldBe false
      ConfigFactory.load().getBoolean("pekko.coordinated-shutdown.exit-jvm") shouldBe false
      ConfigFactory.load("application-dev").getBoolean("pekko.coordinated-shutdown.exit-jvm") shouldBe false
      ConfigFactory.load("application-test").getBoolean("pekko.coordinated-shutdown.exit-jvm") shouldBe false
      ConfigFactory.load("application-prod").getBoolean("pekko.coordinated-shutdown.exit-jvm") shouldBe true
    }
  }

  "workflow sharding with shared JDBC persistence" should {
    "recover the definition and terminal state after its hosting ActorSystem terminates" in {
      val database = MultiNodeTestSupport.newDatabase("two-node-workflow-")
      val clusterName = s"task8-workflow-${java.util.UUID.randomUUID()}"
      var node1: ActorTestKit = null
      var node2: ActorTestKit = null

      try {
        node1 = ActorTestKit(clusterName, MultiNodeTestSupport.nodeConfig(database.url))
        MultiNodeTestSupport.joinSelf(node1)
        eventually {
          Cluster(node1.system).selfMember.status shouldBe MemberStatus.Up
        }

        implicit val node1ExecutionContext = node1.system.executionContext
        val region1 = WorkflowSharding.init(node1.system, new WorkflowExecutionEngine()(node1.system, node1ExecutionContext))(node1ExecutionContext)
        val workflowId = s"recover-${java.util.UUID.randomUUID()}"

        val defineReply = node1.createTestProbe[EventSourcedWorkflowActor.Reply]()
        region1 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, defineReply.ref))
        defineReply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))

        val firstExecutionReply = node1.createTestProbe[EventSourcedWorkflowActor.Reply]()
        region1 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.ExecuteManual("before-node-loss", firstExecutionReply.ref))
        firstExecutionReply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]

        eventually {
          val summaryReply = node1.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
          region1 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(summaryReply.ref))
          val summary = summaryReply.receiveMessage(5.seconds)
          summary.revision shouldBe 1L
          summary.status shouldBe EventSourcedWorkflowActor.Completed
          summary.recentExecutions should have size 1
        }

        node2 = ActorTestKit(clusterName, MultiNodeTestSupport.nodeConfig(database.url))
        MultiNodeTestSupport.join(node2, node1)
        eventually {
          Cluster(node1.system).state.members.count(_.status == MemberStatus.Up) shouldBe 2
          Cluster(node2.system).state.members.count(_.status == MemberStatus.Up) shouldBe 2
        }

        implicit val node2ExecutionContext = node2.system.executionContext
        val region2 = WorkflowSharding.init(node2.system, new WorkflowExecutionEngine()(node2.system, node2ExecutionContext))(node2ExecutionContext)
        val node1Address = Cluster(node1.system).selfMember.address

        Cluster(node1.system).selfMember.roles should contain allElementsOf Seq("worker", "coordinator")
        Cluster(node2.system).selfMember.roles should contain allElementsOf Seq("worker", "coordinator")
        Cluster(node1.system).selfMember.address.host shouldBe Some("127.0.0.1")
        Cluster(node2.system).selfMember.address.host shouldBe Some("127.0.0.1")
        Cluster(node1.system).selfMember.address.port.get should not be Cluster(node2.system).selfMember.address.port.get

        MultiNodeTestSupport.terminate(node1)
        MultiNodeTestSupport.downFrom(node2, node1Address)
        eventually {
          Cluster(node2.system).state.members.map(_.address) shouldBe Set(Cluster(node2.system).selfMember.address)
        }

        eventually {
          val recoveredReply = node2.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
          region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(recoveredReply.ref))
          val recovered = recoveredReply.receiveMessage(5.seconds)
          recovered.revision shouldBe 1L
          recovered.status shouldBe EventSourcedWorkflowActor.Completed
          recovered.recentExecutions should have size 1
        }

        val secondExecutionReply = node2.createTestProbe[EventSourcedWorkflowActor.Reply]()
        region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.ExecuteManual("after-node-loss", secondExecutionReply.ref))
        secondExecutionReply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]

        eventually {
          val finalReply = node2.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
          region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(finalReply.ref))
          val summary = finalReply.receiveMessage(5.seconds)
          summary.revision shouldBe 1L
          summary.status shouldBe EventSourcedWorkflowActor.Completed
          summary.recentExecutions should have size 2
        }
      } finally {
        if (node1 != null) MultiNodeTestSupport.shutdown(node1)
        if (node2 != null) MultiNodeTestSupport.shutdown(node2)
        MultiNodeTestSupport.cleanupDatabase(database)
      }
      Files.exists(database.directory) shouldBe false
    }
  }
}
