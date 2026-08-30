package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{Address, AddressFromURIString}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.ShardRegion.ClusterShardingStats
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.sharding.typed.GetClusterShardingStats
import org.apache.pekko.cluster.typed.{Cluster, Down, Join}

import java.sql.{Connection, DriverManager}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.jdk.CollectionConverters._

/** Child JVM used by [[RealMySQLClusterShardingRecoverySpec]]. */
object RealMySQLClusterShardingProcess {
  private final case class Settings(
    mode: String,
    jdbcUrl: String,
    user: String,
    password: String,
    workflowId: String,
    clusterName: String,
    selfPort: Int,
    seedPort: Int
  ) {
    val persistenceId: String = s"workflow-$workflowId"
    val seedAddress: Address = AddressFromURIString(s"pekko://$clusterName@127.0.0.1:$seedPort")
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1 && Set("primary", "survivor").contains(args.head), "expected mode: primary or survivor")
    val settings = settingsFromEnvironment(args.head)
    val configValues = Map[String, AnyRef](
      "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url" -> settings.jdbcUrl,
      "pekko-persistence-jdbc.shared-databases.slick.db.user" -> settings.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password" -> settings.password,
      "pekko.remote.artery.canonical.hostname" -> "127.0.0.1",
      "pekko.remote.artery.canonical.port" -> Int.box(settings.selfPort),
      "pekko.cluster.downing-provider-class" -> "",
      "pekko.cluster.sharding.remember-entities" -> Boolean.box(false),
      "pekko.cluster.sharding.rebalance-interval" -> "1h",
      "pekko.loglevel" -> "INFO",
      "pekko.stdout-loglevel" -> "INFO",
      "pekko.workflow.event-sourcing.snapshot-every" -> Int.box(10)
    )
    val config = ConfigFactory.parseMap(configValues.asJava).withFallback(ConfigFactory.load("application-multinode-test"))
    val testKit = ActorTestKit(settings.clusterName, config)

    try {
      implicit val executionContext = testKit.system.executionContext
      val cluster = Cluster(testKit.system)
      cluster.manager ! Join(if (settings.mode == "primary") cluster.selfMember.address else settings.seedAddress)
      awaitCondition(30.seconds) {
        cluster.selfMember.status == MemberStatus.Up &&
          (settings.mode == "primary" || cluster.state.members.count(_.status == MemberStatus.Up) == 2)
      }

      val engine = new WorkflowExecutionEngine()(testKit.system, executionContext)
      val region = WorkflowSharding.init(testKit.system, engine, numberOfShards = 10)(executionContext)
      settings.mode match {
        case "primary" => runPrimary(testKit, region, settings)
        case "survivor" => runSurvivor(testKit, region, cluster, settings)
      }
    } finally testKit.shutdownTestKit()
  }

  private def runPrimary(
    testKit: ActorTestKit,
    region: org.apache.pekko.actor.typed.ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]],
    settings: Settings
  ): Unit = {
    val replies = testKit.createTestProbe[EventSourcedWorkflowActor.Reply]()
    val workflow = WorkflowFixtures.linearWorkflow.copy(id = settings.workflowId)
    (0L until 101L).foreach { expectedRevision =>
      region ! ShardingEnvelope(
        settings.workflowId,
        EventSourcedWorkflowActor.DefineWorkflow(workflow, expectedRevision, replies.ref)
      )
      replies.expectMessage(EventSourcedWorkflowActor.Defined(settings.workflowId, expectedRevision + 1L))
    }
    awaitDatabase(settings)(connection =>
      maxSequence(connection, "event_journal", settings.persistenceId) >= 101L &&
        maxSequence(connection, "snapshot", settings.persistenceId) >= 100L
    )
    println(s"SHARDING_PRIMARY_READY pid=${ProcessHandle.current().pid()} port=${settings.selfPort} revision=101")
    System.out.flush()

    StdIn.readLine() match {
      case "CRASH" =>
        println("SHARDING_PRIMARY_CRASHING")
        System.out.flush()
        Runtime.getRuntime.halt(23)
      case command => throw new IllegalArgumentException(s"expected CRASH, got $command")
    }
  }

  private def runSurvivor(
    testKit: ActorTestKit,
    region: org.apache.pekko.actor.typed.ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]],
    cluster: Cluster,
    settings: Settings
  ): Unit = {
    val summaryReply = testKit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
    region ! ShardingEnvelope(settings.workflowId, EventSourcedWorkflowActor.GetSummary(summaryReply.ref))
    require(summaryReply.receiveMessage(30.seconds).revision == 101L, "survivor could not query revision 101 through sharding")

    val defineReply = testKit.createTestProbe[EventSourcedWorkflowActor.Reply]()
    val workflow = WorkflowFixtures.linearWorkflow.copy(id = settings.workflowId)
    region ! ShardingEnvelope(
      settings.workflowId,
      EventSourcedWorkflowActor.DefineWorkflow(workflow, 101L, defineReply.ref)
    )
    defineReply.expectMessage(EventSourcedWorkflowActor.Defined(settings.workflowId, 102L))
    awaitDatabase(settings)(connection => maxSequence(connection, "event_journal", settings.persistenceId) >= 102L)
    val registeredRegions = awaitShardingRegions(
      testKit,
      expected = Set(settings.seedAddress, cluster.selfMember.address),
      timeout = 30.seconds)
    println(
      s"SHARDING_SURVIVOR_READY pid=${ProcessHandle.current().pid()} port=${settings.selfPort} " +
        s"remoteRevision=102 registeredRegions=${registeredRegions.regions.size}")
    System.out.flush()

    require(StdIn.readLine() == "RECOVER", "expected RECOVER")
    awaitCondition(45.seconds) {
      cluster.state.unreachable.exists(_.address == settings.seedAddress)
    }
    cluster.manager ! Down(settings.seedAddress)
    awaitCondition(45.seconds) {
      cluster.state.members.map(_.address) == Set(cluster.selfMember.address) &&
        cluster.selfMember.status == MemberStatus.Up
    }
    awaitShardingRegions(testKit, expected = Set(cluster.selfMember.address), timeout = 45.seconds)
    println(s"SHARDING_SURVIVOR_COORDINATOR_READY address=${cluster.selfMember.address}")
    System.out.flush()

    val recoveredReply = testKit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
    region ! ShardingEnvelope(settings.workflowId, EventSourcedWorkflowActor.GetSummary(recoveredReply.ref))
    require(
      recoveredReply.receiveMessage(90.seconds).revision == 102L,
      s"survivor did not recover revision 102; members=${cluster.state.members.iterator.map(member => member.address -> member.status).mkString(",")}"
    )

    region ! ShardingEnvelope(
      settings.workflowId,
      EventSourcedWorkflowActor.DefineWorkflow(workflow, 102L, defineReply.ref)
    )
    defineReply.expectMessage(EventSourcedWorkflowActor.Defined(settings.workflowId, 103L))
    awaitDatabase(settings)(connection => maxSequence(connection, "event_journal", settings.persistenceId) >= 103L)
    val writerChanged = withConnection(settings) { connection =>
      writerAt(connection, settings.persistenceId, 102L) != writerAt(connection, settings.persistenceId, 103L)
    }
    require(writerChanged, "recovered entity did not use a new persistence writer")
    val snapshotSequence = withConnection(settings)(connection => maxSequence(connection, "snapshot", settings.persistenceId))
    require(snapshotSequence >= 100L, s"recovery lost snapshot state: $snapshotSequence")
    println(s"SHARDING_SURVIVOR_RECOVERED pid=${ProcessHandle.current().pid()} revision=103 snapshot=$snapshotSequence writerChanged=true")
    System.out.flush()

    require(StdIn.readLine() == "SHUTDOWN", "expected SHUTDOWN")
  }

  private def settingsFromEnvironment(mode: String): Settings = {
    def required(name: String): String =
      sys.env.get(name).filter(_.nonEmpty).getOrElse(throw new IllegalArgumentException(s"missing environment variable: $name"))

    Settings(
      mode = mode,
      jdbcUrl = required("PEKKO_TEST_MYSQL_JDBC_URL"),
      user = required("PEKKO_TEST_MYSQL_USER"),
      password = sys.env.getOrElse("PEKKO_TEST_MYSQL_PASSWORD", throw new IllegalArgumentException("missing environment variable: PEKKO_TEST_MYSQL_PASSWORD")),
      workflowId = required("PEKKO_TEST_WORKFLOW_ID"),
      clusterName = required("PEKKO_TEST_CLUSTER_NAME"),
      selfPort = required("PEKKO_TEST_SELF_PORT").toInt,
      seedPort = required("PEKKO_TEST_SEED_PORT").toInt
    )
  }

  private def awaitCondition(timeout: FiniteDuration)(condition: => Boolean): Unit = {
    val deadline = timeout.fromNow
    var satisfied = condition
    while (!satisfied && deadline.hasTimeLeft()) {
      Thread.sleep(100L)
      satisfied = condition
    }
    require(satisfied, s"condition was not satisfied within $timeout")
  }

  private def awaitDatabase(settings: Settings)(condition: Connection => Boolean): Unit =
    awaitCondition(30.seconds)(withConnection(settings)(condition))

  private def awaitShardingRegions(
    testKit: ActorTestKit,
    expected: Set[Address],
    timeout: FiniteDuration
  ): ClusterShardingStats = {
    val deadline = timeout.fromNow
    var observed = Set.empty[Address]
    while (deadline.hasTimeLeft()) {
      val reply = testKit.createTestProbe[ClusterShardingStats]()
      ClusterSharding(testKit.system).shardState !
        GetClusterShardingStats(WorkflowSharding.TypeKey, 2.seconds, reply.ref)
      val stats = reply.receiveMessage(3.seconds)
      observed = stats.regions.keySet
      if (observed == expected) return stats
      Thread.sleep(100L)
    }
    throw new IllegalStateException(s"sharding regions did not become $expected within $timeout; observed=$observed")
  }

  private def maxSequence(connection: Connection, table: String, persistenceId: String): Long = {
    require(table == "event_journal" || table == "snapshot", s"unexpected table: $table")
    val statement = connection.prepareStatement(s"SELECT COALESCE(MAX(sequence_number), 0) FROM `$table` WHERE persistence_id = ?")
    try {
      statement.setString(1, persistenceId)
      val resultSet = statement.executeQuery()
      try {
        resultSet.next()
        resultSet.getLong(1)
      } finally resultSet.close()
    } finally statement.close()
  }

  private def writerAt(connection: Connection, persistenceId: String, sequence: Long): String = {
    val statement = connection.prepareStatement(
      "SELECT writer FROM event_journal WHERE persistence_id = ? AND sequence_number = ?"
    )
    try {
      statement.setString(1, persistenceId)
      statement.setLong(2, sequence)
      val resultSet = statement.executeQuery()
      try {
        require(resultSet.next(), s"missing journal sequence $sequence")
        resultSet.getString(1)
      } finally resultSet.close()
    } finally statement.close()
  }

  private def withConnection[A](settings: Settings)(operation: Connection => A): A = {
    Class.forName("com.mysql.cj.jdbc.Driver")
    val connection = DriverManager.getConnection(settings.jdbcUrl, settings.user, settings.password)
    try operation(connection)
    finally connection.close()
  }
}
