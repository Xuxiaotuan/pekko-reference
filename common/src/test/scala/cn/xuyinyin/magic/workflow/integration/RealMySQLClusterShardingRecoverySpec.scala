package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{BufferedWriter, File, OutputStreamWriter}
import java.net.ServerSocket
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.{Connection, DriverManager}
import java.util.UUID
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._
import scala.io.{Source => IoSource}

class RealMySQLClusterShardingRecoverySpec extends AnyWordSpec with Matchers {
  import RealMySQLClusterShardingRecoverySpec._

  "Cluster Sharding with real MySQL persistence" should {
    "recover the same entity in a second JVM after its host is hard-killed" taggedAs ExternalIntegration in {
      val settings = Settings.fromSystemProperties().getOrElse {
        cancel("set pekko.test.mysql.{host,port,user,password} system properties")
      }
      val schema = s"pekko_test_sharding_${UUID.randomUUID().toString.replace("-", "").take(12)}"
      val workflowId = s"sharded-${UUID.randomUUID()}"
      val clusterName = s"sharding-recovery-${UUID.randomUUID()}"
      val primaryPort = freePort()
      val survivorPort = freePort(excluding = Set(primaryPort))
      var primary: ChildProcess = null
      var survivor: ChildProcess = null

      createSchema(settings, schema)
      try {
        initializePersistence(settings, schema)
        primary = startChild("primary", settings, schema, workflowId, clusterName, primaryPort, primaryPort)
        primary.awaitLine("SHARDING_PRIMARY_READY", 90.seconds) should include("revision=101")

        survivor = startChild("survivor", settings, schema, workflowId, clusterName, survivorPort, primaryPort)
        survivor.awaitLine("SHARDING_SURVIVOR_READY", 90.seconds) should include("remoteRevision=102")

        primary.send("CRASH")
        primary.awaitLine("SHARDING_PRIMARY_CRASHING", 10.seconds)
        primary.awaitExit(30.seconds) shouldBe 23

        survivor.send("RECOVER")
        val recovered = survivor.awaitLine("SHARDING_SURVIVOR_RECOVERED", 120.seconds)
        recovered should include("revision=103")
        recovered should include("writerChanged=true")

        val persistenceId = s"workflow-$workflowId"
        withConnection(settings.schemaUrl(schema), settings) { connection =>
          maxSequence(connection, "event_journal", persistenceId) shouldBe 103L
          maxSequence(connection, "snapshot", persistenceId) should be >= 100L
          writerAt(connection, persistenceId, 102L) should not be writerAt(connection, persistenceId, 103L)
        }

        survivor.send("SHUTDOWN")
        survivor.awaitExit(30.seconds) shouldBe 0
      } finally {
        if (primary != null) primary.destroyIfAlive()
        if (survivor != null) survivor.destroyIfAlive()
        dropSchema(settings, schema)
      }
    }
  }
}

private object RealMySQLClusterShardingRecoverySpec {
  private final case class Settings(host: String, port: Int, user: String, password: String) {
    val adminUrl: String =
      s"jdbc:mysql://$host:$port/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    def schemaUrl(schema: String): String =
      s"jdbc:mysql://$host:$port/$schema?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
  }

  private object Settings {
    def fromSystemProperties(): Option[Settings] = for {
      host <- sys.props.get("pekko.test.mysql.host").filter(_.nonEmpty)
      port <- sys.props.get("pekko.test.mysql.port").flatMap(_.toIntOption)
      user <- sys.props.get("pekko.test.mysql.user").filter(_.nonEmpty)
      password <- sys.props.get("pekko.test.mysql.password")
    } yield Settings(host, port, user, password)
  }

  private final class ChildProcess(process: Process) {
    private val output = new LinkedBlockingQueue[String]()
    private val tail = new java.util.concurrent.ConcurrentLinkedDeque[String]()
    private val input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream))
    private val drain = new Thread(() => {
      val source = IoSource.fromInputStream(process.getInputStream)
      try source.getLines().foreach { line =>
        output.put(line)
        tail.addLast(line)
        while (tail.size() > 200) tail.removeFirst()
      }
      finally source.close()
    })
    drain.setDaemon(true)
    drain.start()

    def send(command: String): Unit = input.synchronized {
      input.write(command)
      input.newLine()
      input.flush()
    }

    def awaitLine(prefix: String, timeout: FiniteDuration): String = {
      val deadline = timeout.fromNow
      while (deadline.hasTimeLeft()) {
        val line = output.poll(math.max(1L, math.min(100L, deadline.timeLeft.toMillis)), TimeUnit.MILLISECONDS)
        if (line != null && line.startsWith(prefix)) return line
        if (line == null && !process.isAlive && !drain.isAlive && output.isEmpty)
          throw new AssertionError(s"child exited before $prefix; exit=${exitValue}; tail=${tailText}")
      }
      throw new AssertionError(s"child output did not contain $prefix; exit=${exitValue}; tail=${tailText}")
    }

    def awaitExit(timeout: FiniteDuration): Int = {
      if (!process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS))
        throw new AssertionError(s"child did not exit within $timeout; tail=${tailText}")
      process.exitValue()
    }

    def destroyIfAlive(): Unit = {
      if (process.isAlive) {
        process.destroyForcibly()
        process.waitFor(20L, TimeUnit.SECONDS)
      }
    }

    private def exitValue: String = if (process.isAlive) "running" else process.exitValue().toString
    private def tailText: String = tail.toArray.mkString(" | ")
  }

  private def startChild(
    mode: String,
    settings: Settings,
    schema: String,
    workflowId: String,
    clusterName: String,
    selfPort: Int,
    seedPort: Int
  ): ChildProcess = {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val classpath = runtimeClasspath
    val builder = new ProcessBuilder(
      java,
      "-cp",
      classpath,
      "cn.xuyinyin.magic.workflow.integration.RealMySQLClusterShardingProcess",
      mode
    )
    builder.redirectErrorStream(true)
    val environment = builder.environment()
    environment.put("PEKKO_TEST_MYSQL_JDBC_URL", settings.schemaUrl(schema))
    environment.put("PEKKO_TEST_MYSQL_USER", settings.user)
    environment.put("PEKKO_TEST_MYSQL_PASSWORD", settings.password)
    environment.put("PEKKO_TEST_WORKFLOW_ID", workflowId)
    environment.put("PEKKO_TEST_CLUSTER_NAME", clusterName)
    environment.put("PEKKO_TEST_SELF_PORT", selfPort.toString)
    environment.put("PEKKO_TEST_SEED_PORT", seedPort.toString)
    new ChildProcess(builder.start())
  }

  private def runtimeClasspath: String = {
    val loaderEntries = Iterator
      .iterate(Option(getClass.getClassLoader))(_.flatMap(loader => Option(loader.getParent)))
      .takeWhile(_.nonEmpty)
      .flatten
      .collect { case loader: URLClassLoader => loader.getURLs.iterator }
      .flatten
      .filter(_.getProtocol == "file")
      .map(url => Path.of(url.toURI).toString)

    val propertyEntries = System
      .getProperty("java.class.path", "")
      .split(File.pathSeparator)
      .iterator
      .filter(_.nonEmpty)

    val testClasses = Option(getClass.getProtectionDomain.getCodeSource)
      .map(_.getLocation)
      .filter(_.getProtocol == "file")
      .iterator
      .map(url => Path.of(url.toURI).toString)

    (testClasses ++ loaderEntries ++ propertyEntries).toSeq.distinct.mkString(File.pathSeparator)
  }

  private def freePort(excluding: Set[Int] = Set.empty): Int = {
    var port = -1
    while (port < 0 || excluding.contains(port)) {
      val socket = new ServerSocket(0)
      try port = socket.getLocalPort
      finally socket.close()
    }
    port
  }

  private def createSchema(settings: Settings, schema: String): Unit = {
    require(schema.matches("pekko_test_sharding_[a-f0-9]{12}"), s"unsafe test schema: $schema")
    withConnection(settings.adminUrl, settings) { connection =>
      val statement = connection.createStatement()
      try statement.executeUpdate(s"CREATE DATABASE `$schema`")
      finally statement.close()
    }
  }

  private def initializePersistence(settings: Settings, schema: String): Unit =
    withConnection(settings.schemaUrl(schema), settings) { connection =>
      val input = Option(getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-persistence-schema.sql"))
        .getOrElse(throw new IllegalStateException("missing MySQL persistence schema resource"))
      val sql = try IoSource.fromInputStream(input).mkString finally input.close()
      val statement = connection.createStatement()
      try sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      finally statement.close()
    }

  private def dropSchema(settings: Settings, schema: String): Unit = {
    require(schema.matches("pekko_test_sharding_[a-f0-9]{12}"), s"unsafe test schema: $schema")
    withConnection(settings.adminUrl, settings) { connection =>
      val statement = connection.createStatement()
      try statement.executeUpdate(s"DROP DATABASE IF EXISTS `$schema`")
      finally statement.close()
    }
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

  private def withConnection[A](url: String, settings: Settings)(operation: Connection => A): A = {
    Class.forName("com.mysql.cj.jdbc.Driver")
    val connection = DriverManager.getConnection(url, settings.user, settings.password)
    try operation(connection)
    finally connection.close()
  }
}
