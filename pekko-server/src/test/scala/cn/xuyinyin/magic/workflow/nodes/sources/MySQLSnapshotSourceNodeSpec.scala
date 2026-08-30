package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, SnapshotBoundary, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.{KillSwitches, Materializer}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import spray.json._

import java.lang.reflect.{InvocationTargetException, Proxy}
import java.sql.{Connection, DriverManager, PreparedStatement}
import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

class MySQLSnapshotSourceNodeSpec extends STSpec with Eventually {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(2, Seconds), interval = Span(25, Millis))
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty[Nothing],
    "mysql-snapshot-source-node-spec",
    ConfigFactory.parseString(
      """pekko.actor.provider = local
        |pekko.remote.artery.enabled = false
        |pekko.coordinated-shutdown.exit-jvm = off""".stripMargin
    )
  )
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val materializer: Materializer = Materializer(system)

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
    super.afterAll()
  }

  "MySQLSnapshotSourceConfig" should {
    "reject missing required snapshot fields" in {
      intercept[IllegalArgumentException] {
        MySQLSnapshotSourceConfig.parse(snapshotNode())
      }.getMessage should include("table")

      intercept[IllegalArgumentException] {
        MySQLSnapshotSourceConfig.parse(snapshotNode("table" -> JsString("source_rows"), "columns" -> JsArray()))
      }.getMessage should include("columns")

      intercept[IllegalArgumentException] {
        MySQLSnapshotSourceConfig.parse(snapshotNode(
          "table" -> JsString("source_rows"),
          "columns" -> JsArray(JsString("id"))
        ))
      }.getMessage should include("primaryKey")
    }

    "reject non-positive chunk sizes" in {
      intercept[IllegalArgumentException] {
        MySQLSnapshotSourceConfig.parse(validSnapshotNode("chunkSize" -> JsNumber(0)))
      }.getMessage should include("chunkSize")
    }

    "reject unsafe identifiers and empty column lists" in {
      val unsafeIdentifiers = Vector("source rows", "source`rows", "source;rows", "id-name")

      unsafeIdentifiers.foreach { identifier =>
        intercept[IllegalArgumentException] {
          MySQLSnapshotSourceConfig.parse(validSnapshotNode("table" -> JsString(identifier)))
        }.getMessage should include("identifier")
      }

      intercept[IllegalArgumentException] {
        MySQLSnapshotSourceConfig.parse(validSnapshotNode("columns" -> JsArray()))
      }.getMessage should include("columns")
    }

    "parse a valid configuration" in {
      MySQLSnapshotSourceConfig.parse(validSnapshotNode()) shouldBe MySQLSnapshotSourceConfig(
        host = "localhost",
        port = 3306,
        database = "source_db",
        username = "reader",
        password = "secret",
        table = "source_rows",
        columns = Vector("id", "payload"),
        primaryKey = "id",
        chunkSize = 2
      )
    }
  }

  "MySQLSnapshotSourceNode" should {
    "read numeric primary keys in ordered keyset batches and resume at the next sequence" in {
      withFixture() { fixture =>
        val boundary = Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds)
        val batches = Await.result(
          fixture.node.createBatches(fixture.snapshotNode(), "execution-1", boundary, None, _ => ()).runWith(Sink.seq),
          5.seconds
        )

        batches.map(_.cursor.value) shouldBe Seq("2", "9", "12")
        batches.map(_.batchSequence) shouldBe Seq(0L, 1L, 2L)
        batches.map(_.batchId).distinct.size shouldBe 3
        batches.map(_.batchId) shouldBe Seq(
          "3f7d863bd46aa12572359d733ed273b43a1daf9cb4121a0fe015a76913f7a4d9",
          "a1dfb56ba1d614ab2d7f4ce8ec222164b0d1bc29faa71f9e909d3bb5a6c37433",
          "b6a7a47ba2ecef5e140236786396655582ad288ab7c7458a993390c2acb216af"
        )
        batches.map(_.rows.map(idValue)) shouldBe Seq(
          Vector(JsString("1"), JsString("2")),
          Vector(JsString("5"), JsString("9")),
          Vector(JsString("12"))
        )

        val resumed = Await.result(
          fixture.node.createBatches(
            fixture.snapshotNode(),
            "execution-1",
            boundary,
            Some(checkpoint(sequence = 2, cursor = "9", upperBound = "12")),
            _ => ()
          ).runWith(Sink.seq),
          5.seconds
        )

        resumed.map(_.batchSequence) shouldBe Seq(3L)
        resumed.flatMap(_.rows).map(idValue) shouldBe Seq(JsString("12"))
      }
    }

    "freeze a discovered upper bound and leave empty tables without a bound" in {
      withFixture() { fixture =>
        val boundary = Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds)
        boundary shouldBe SnapshotBoundary("source-1", "pk-range-0", Some("12"))
        fixture.insert(13, "row-13")

        val rows = Await.result(
          fixture.node.createBatches(fixture.snapshotNode(), "execution-1", boundary, None, _ => ()).runWith(Sink.seq),
          5.seconds
        ).flatMap(_.rows)
        rows.map(idValue) should not contain JsString("13")
      }

      withFixture(initialRows = Vector.empty) { fixture =>
        Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds) shouldBe
          SnapshotBoundary("source-1", "pk-range-0", None)
      }
    }

    "preserve unsigned-range numeric keys as canonical decimal strings" in {
      withFixture(
        initialRows = Vector.empty,
        tableSql = "CREATE TABLE source_rows (id DECIMAL(20, 0) PRIMARY KEY, payload VARCHAR(255) NOT NULL)"
      ) { fixture =>
        fixture.insertDecimal("18446744073709551615", "unsigned-bigint")
        val boundary = Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds)
        boundary.upperBound shouldBe Some("18446744073709551615")

        val batches = Await.result(
          fixture.node.createBatches(fixture.snapshotNode(), "execution-unsigned", boundary, None, _ => ()).runWith(Sink.seq),
          5.seconds
        )
        idValue(batches.flatMap(_.rows).head) shouldBe JsString("18446744073709551615")
      }
    }

    "close its datasource when cancelled or a batch query fails" in {
      withFixture() { fixture =>
        val boundary = Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds)
        val batchDataSource = fixture.nextDataSourceCreated()
        val (killSwitch, completed) = fixture.node
          .createBatches(fixture.snapshotNode(), "execution-1", boundary, None, _ => ())
          .concat(Source.never)
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(Sink.ignore)(Keep.both)
          .run()

        Await.result(batchDataSource, 5.seconds)
        killSwitch.abort(new RuntimeException("abort"))
        intercept[RuntimeException](Await.result(completed, 5.seconds)).getMessage shouldBe "abort"
        eventually {
          fixture.lastDataSource.isClosed shouldBe true
          fixture.activeConnections shouldBe 0
        }
      }

      withFixture() { fixture =>
        val failure = intercept[Exception] {
          Await.result(
            fixture.node.createBatches(fixture.snapshotNode("table" -> JsString("missing_rows")), "execution-1", SnapshotBoundary("source-1", "pk-range-0", Some("12")), None, _ => ()).runWith(Sink.seq),
            5.seconds
          )
        }
        failure.getMessage should not be empty
        fixture.lastDataSource.isClosed shouldBe true
        fixture.activeConnections shouldBe 0
      }
    }

    "reject invalid primary-key metadata" in {
      withFixture(tableSql = "CREATE TABLE source_rows (id INT, payload VARCHAR(255) NOT NULL, PRIMARY KEY (id, payload))") { fixture =>
        intercept[IllegalArgumentException] {
          Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds)
        }.getMessage should include("exactly one")
      }

      withFixture(
        initialRows = Vector.empty,
        tableSql = "CREATE TABLE source_rows (id INT NOT NULL, other_id INT NOT NULL PRIMARY KEY, payload VARCHAR(255) NOT NULL)"
      ) { fixture =>
        intercept[IllegalArgumentException] {
          Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds)
        }.getMessage should include("primary key")
      }

      withFixture(tableSql = "CREATE TABLE source_rows (id VARCHAR(255) PRIMARY KEY, payload VARCHAR(255) NOT NULL)") { fixture =>
        intercept[IllegalArgumentException] {
          Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds)
        }.getMessage should include("numeric")
      }
    }

    "resolve metadata column names without requiring matching case" in {
      withFixture(
        initialRows = Vector.empty,
        tableSql = "CREATE TABLE source_rows (\"Id\" INT PRIMARY KEY, \"Payload\" VARCHAR(255) NOT NULL)",
        h2Options = ";DATABASE_TO_UPPER=FALSE"
      ) { fixture =>
        fixture.insertMixedCase(1, "row-1")
        fixture.insertMixedCase(2, "row-2")

        val boundary = Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds)
        boundary shouldBe SnapshotBoundary("source-1", "pk-range-0", Some("2"))

        val batches = Await.result(
          fixture.node.createBatches(fixture.snapshotNode(), "execution-case", boundary, None, _ => ()).runWith(Sink.seq),
          5.seconds
        )
        batches.map(_.cursor.value) shouldBe Seq("2")
        batches.flatMap(_.rows).map(_.parseJson.asJsObject.fields.keySet) should contain only Set("Id", "Payload")
      }
    }

    "treat metadata table names containing underscores as literal identifiers" in {
      withFixture() { fixture =>
        fixture.createLookalikeTable()

        Await.result(fixture.node.discoverBoundary(fixture.snapshotNode(), _ => ()), 5.seconds) shouldBe
          SnapshotBoundary("source-1", "pk-range-0", Some("12"))
      }
    }

    "run batch JDBC setup and reads on the supplied blocking dispatcher" in {
      withFixture() { fixture =>
        val executor = Executors.newSingleThreadExecutor { runnable =>
          val thread = new Thread(runnable)
          thread.setName("snapshot-blocking-dispatcher")
          thread
        }
        val blockingEc: ExecutionContext = ExecutionContext.fromExecutorService(executor)
        val createThread = new AtomicReference[String]()
        val source = new MySQLSnapshotSourceNode {
          override protected[sources] def createDataSource(
            host: String,
            port: Int,
            database: String,
            username: String,
            password: String
          ): HikariDataSource = {
            createThread.set(Thread.currentThread.getName)
            fixture.newDataSource()
          }
        }
        try {
          Await.result(
            source.createBatches(
              fixture.snapshotNode(),
              "execution-1",
              SnapshotBoundary("source-1", "pk-range-0", Some("12")),
              None,
              _ => ()
            )(blockingEc).runWith(Sink.seq),
            5.seconds
          )
          createThread.get() should include("snapshot-blocking-dispatcher")
        } finally executor.shutdown()
      }
    }

    "keep its datasource open while a controlled read is in flight and close it after cancellation" in {
      withFixture() { fixture =>
        val queryStarted = new CountDownLatch(1)
        val allowQuery = new CountDownLatch(1)
        val source = new MySQLSnapshotSourceNode {
          override protected[sources] def createDataSource(
            host: String,
            port: Int,
            database: String,
            username: String,
            password: String
          ): HikariDataSource = fixture.newDataSourceBlockingQuery(queryStarted, allowQuery)
        }
        val (killSwitch, completed) = source
          .createBatches(
            fixture.snapshotNode(),
            "execution-1",
            SnapshotBoundary("source-1", "pk-range-0", Some("12")),
            None,
            _ => ()
          )
          .concat(Source.never)
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(Sink.ignore)(Keep.both)
          .run()

        queryStarted.await(5, TimeUnit.SECONDS) shouldBe true
        fixture.lastDataSource.isClosed shouldBe false
        killSwitch.abort(new RuntimeException("abort"))
        allowQuery.countDown()
        intercept[RuntimeException](Await.result(completed, 5.seconds)).getMessage shouldBe "abort"
        eventually {
          fixture.lastDataSource.isClosed shouldBe true
          fixture.activeConnections shouldBe 0
        }
      }
    }
  }

  private def validSnapshotNode(overrides: (String, JsValue)*): WorkflowDSL.Node =
    snapshotNode((Vector(
      "host" -> JsString("localhost"),
      "port" -> JsNumber(3306),
      "database" -> JsString("source_db"),
      "username" -> JsString("reader"),
      "password" -> JsString("secret"),
      "table" -> JsString("source_rows"),
      "columns" -> JsArray(JsString("id"), JsString("payload")),
      "primaryKey" -> JsString("id"),
      "chunkSize" -> JsNumber(2)
    ) ++ overrides): _*)

  private def snapshotNode(fields: (String, JsValue)*): WorkflowDSL.Node =
    WorkflowDSL.Node(
      id = "source-1",
      `type` = "source",
      nodeType = "mysql.snapshot",
      label = "MySQL snapshot",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject(fields.toMap)
    )

  private def checkpoint(sequence: Long, cursor: String, upperBound: String): BatchCheckpoint =
    BatchCheckpoint(
      sourceNodeId = "source-1",
      partitionId = "pk-range-0",
      batchSequence = sequence,
      batchId = "prior-batch",
      cursor = SourceCursor("mysql.numeric-pk", cursor, upperBound),
      sourceRowsScanned = 2,
      targetRowsWritten = 2
    )

  private def idValue(row: String): JsValue =
    row.parseJson.asJsObject.fields.collectFirst { case (name, value) if name.equalsIgnoreCase("id") => value }.get

  private def withFixture(
    initialRows: Vector[(Int, String)] = Vector(1 -> "row-1", 2 -> "row-2", 5 -> "row-5", 9 -> "row-9", 12 -> "row-12"),
    tableSql: String = "CREATE TABLE source_rows (id INT PRIMARY KEY, payload VARCHAR(255) NOT NULL)",
    h2Options: String = ""
  )(test: H2Fixture => Any): Unit = {
    val fixture = new H2Fixture(initialRows, tableSql, h2Options)
    try test(fixture)
    finally fixture.close()
  }

  private final class H2Fixture(initialRows: Vector[(Int, String)], tableSql: String, h2Options: String) {
    private val jdbcUrl = s"jdbc:h2:mem:mysql_snapshot_${UUID.randomUUID().toString.replace('-', '_')};MODE=MySQL;DB_CLOSE_DELAY=0$h2Options"
    private val inspectionConnection: Connection = DriverManager.getConnection(jdbcUrl, "sa", "")
    private var dataSources = Vector.empty[HikariDataSource]
    private var nextCreation: Option[Promise[HikariDataSource]] = None

    val node = new MySQLSnapshotSourceNode {
      override protected[sources] def createDataSource(
        host: String,
        port: Int,
        database: String,
        username: String,
        password: String
      ): HikariDataSource = {
        newDataSource()
      }
    }

    initialize()

    def snapshotNode(overrides: (String, JsValue)*): WorkflowDSL.Node =
      validSnapshotNode(overrides: _*)

    def nextDataSourceCreated(): Future[HikariDataSource] = {
      val created = Promise[HikariDataSource]()
      nextCreation = Some(created)
      created.future
    }
    def lastDataSource: HikariDataSource = dataSources.last
    def activeConnections: Int = lastDataSource.getHikariPoolMXBean.getActiveConnections

    def newDataSource(): HikariDataSource = {
      val config = new HikariConfig()
      config.setJdbcUrl(jdbcUrl)
      config.setDriverClassName("org.h2.Driver")
      config.setUsername("sa")
      config.setPassword("")
      config.setMaximumPoolSize(1)
      config.setMinimumIdle(0)
      val dataSource = new HikariDataSource(config)
      dataSources :+= dataSource
      nextCreation.foreach(_.trySuccess(dataSource))
      nextCreation = None
      dataSource
    }

    def newDataSourceBlockingQuery(queryStarted: CountDownLatch, allowQuery: CountDownLatch): HikariDataSource = {
      val config = new HikariConfig()
      config.setJdbcUrl(jdbcUrl)
      config.setDriverClassName("org.h2.Driver")
      config.setUsername("sa")
      config.setPassword("")
      config.setMaximumPoolSize(1)
      config.setMinimumIdle(0)
      val dataSource = new HikariDataSource(config) {
        override def getConnection: Connection =
          proxyConnection(super.getConnection, queryStarted, allowQuery)
      }
      dataSources :+= dataSource
      nextCreation.foreach(_.trySuccess(dataSource))
      nextCreation = None
      dataSource
    }

    def createLookalikeTable(): Unit = {
      val statement = inspectionConnection.createStatement()
      try statement.executeUpdate("CREATE TABLE sourceXrows (id INT PRIMARY KEY, payload VARCHAR(255) NOT NULL)")
      finally statement.close()
    }

    def insert(id: Int, payload: String): Unit = {
      val statement = inspectionConnection.prepareStatement("INSERT INTO source_rows (id, payload) VALUES (?, ?)")
      try {
        statement.setInt(1, id)
        statement.setString(2, payload)
        statement.executeUpdate()
      } finally statement.close()
    }

    def insertDecimal(id: String, payload: String): Unit = {
      val statement = inspectionConnection.prepareStatement("INSERT INTO source_rows (id, payload) VALUES (?, ?)")
      try {
        statement.setBigDecimal(1, new java.math.BigDecimal(id))
        statement.setString(2, payload)
        statement.executeUpdate()
      } finally statement.close()
    }

    def insertMixedCase(id: Int, payload: String): Unit = {
      val statement = inspectionConnection.prepareStatement("INSERT INTO source_rows (\"Id\", \"Payload\") VALUES (?, ?)")
      try {
        statement.setInt(1, id)
        statement.setString(2, payload)
        statement.executeUpdate()
      } finally statement.close()
    }

    def close(): Unit = inspectionConnection.close()

    private def initialize(): Unit = {
      val statement = inspectionConnection.createStatement()
      try statement.executeUpdate(tableSql)
      finally statement.close()
      initialRows.foreach { case (id, payload) => insert(id, payload) }
    }

    private def proxyConnection(connection: Connection, queryStarted: CountDownLatch, allowQuery: CountDownLatch): Connection =
      Proxy.newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[Connection]),
        (_, method, arguments) => {
          if (method.getName == "prepareStatement") {
            val statement = invoke(connection, method, arguments).asInstanceOf[PreparedStatement]
            proxyStatement(statement, queryStarted, allowQuery)
          } else invoke(connection, method, arguments)
        }
      ).asInstanceOf[Connection]

    private def proxyStatement(statement: PreparedStatement, queryStarted: CountDownLatch, allowQuery: CountDownLatch): PreparedStatement =
      Proxy.newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[PreparedStatement]),
        (_, method, arguments) => {
          if (method.getName == "executeQuery") {
            queryStarted.countDown()
            allowQuery.await(5, TimeUnit.SECONDS)
          }
          invoke(statement, method, arguments)
        }
      ).asInstanceOf[PreparedStatement]

    private def invoke(target: AnyRef, method: java.lang.reflect.Method, arguments: Array[AnyRef]): AnyRef =
      try method.invoke(target, Option(arguments).getOrElse(Array.empty[AnyRef]): _*)
      catch { case error: InvocationTargetException => throw error.getCause }
  }
}
