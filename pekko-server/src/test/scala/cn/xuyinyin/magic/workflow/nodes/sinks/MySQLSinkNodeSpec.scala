package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.{KillSwitches, Materializer}
import org.apache.pekko.stream.scaladsl.{Keep, Source}
import spray.json._

import java.sql.{Connection, DriverManager}
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

class MySQLSinkNodeSpec extends STSpec {
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty[Nothing],
    "mysql-sink-node-spec",
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

  "MySQLSinkNode" should {
    "write every row across complete and partial batches" in {
      withFixture { fixture =>
        val result = Await.result(runSink(fixture, rows = 250, batchSize = 100), 5.seconds)

        result shouldBe Done
        fixture.selectCount("sink_rows") shouldBe 250
        fixture.lastDataSource.isClosed shouldBe true
        fixture.activeConnections shouldBe 0
      }
    }

    "defer datasource allocation until a materialized stream receives its first element" in {
      withFixture { fixture =>
        val sink = fixture.node.createSink(sinkNode(batchSize = 100), _ => ())

        fixture.dataSourceCount shouldBe 0
        Await.result(Source.empty[String].runWith(sink), 5.seconds) shouldBe Done
        fixture.dataSourceCount shouldBe 0
      }
    }

    "close the datasource when inner sink setup fails after allocation" in {
      withFixture(failInnerSink = true) { fixture =>
        val failure = intercept[IllegalStateException] {
          Await.result(Source.single(row(1)).runWith(fixture.node.createSink(sinkNode(batchSize = 100), _ => ())), 5.seconds)
        }

        failure.getMessage should include("inner sink setup failed")
        fixture.lastDataSource.isClosed shouldBe true
      }
    }

    "roll back a failing batch and fail the materialized Future" in {
      withFixture { fixture =>
        val failure = intercept[IllegalStateException] {
          Await.result(runBatchContainingDuplicateKey(fixture), 5.seconds)
        }

        failure.getMessage should include("batch write failed")
        fixture.selectCount("sink_rows") shouldBe 0
        fixture.lastDataSource.isClosed shouldBe true
      }
    }

    "close its datasource when upstream cancellation completes the sink" in {
      withFixture { fixture =>
        val sink = fixture.node.createSink(sinkNode(batchSize = 100), _ => ())
        val (killSwitch, result) = Source
          .single(row(1))
          .concat(Source.never)
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(sink)(Keep.both)
          .run()

        Await.result(fixture.dataSourceCreated, 5.seconds)
        killSwitch.abort(new RuntimeException("abort"))

        intercept[RuntimeException] {
          Await.result(result, 5.seconds)
        }.getMessage shouldBe "abort"
        fixture.lastDataSource.isClosed shouldBe true
      }
    }

    "wrap malformed JSON as a failed batch write" in {
      withFixture { fixture =>
        val failure = intercept[IllegalStateException] {
          Await.result(Source.single("{not-json").runWith(fixture.node.createSink(sinkNode(batchSize = 100), _ => ())), 5.seconds)
        }

        failure.getMessage should include("batch write failed")
        fixture.selectCount("sink_rows") shouldBe 0
      }
    }
  }

  private def runSink(fixture: H2Fixture, rows: Int, batchSize: Int): Future[Done] =
    Source(1 to rows)
      .map(row)
      .runWith(fixture.node.createSink(sinkNode(batchSize), _ => ()))

  private def runBatchContainingDuplicateKey(fixture: H2Fixture): Future[Done] =
    Source(List(row(1), row(2), row(1)))
      .runWith(fixture.node.createSink(sinkNode(batchSize = 100), _ => ()))

  private def sinkNode(batchSize: Int): WorkflowDSL.Node =
    WorkflowDSL.Node(
      id = "mysql-sink",
      `type` = "sink",
      nodeType = "mysql.write",
      label = "MySQL",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject(
        "host" -> JsString("unused"),
        "port" -> JsNumber(3306),
        "database" -> JsString("unused"),
        "table" -> JsString("sink_rows"),
        "username" -> JsString("sa"),
        "password" -> JsString(""),
        "batchSize" -> JsNumber(batchSize),
        "mode" -> JsString("insert")
      )
    )

  private def row(id: Int): String = s"""{"id":$id,"payload":"row-$id"}"""

  private def withFixture(test: H2Fixture => Any): Unit = withFixture(failInnerSink = false)(test)

  private def withFixture(failInnerSink: Boolean)(test: H2Fixture => Any): Unit = {
    val fixture = new H2Fixture(failInnerSink)
    try test(fixture)
    finally fixture.close()
  }

  private final class H2Fixture(failInnerSink: Boolean) {
    private val jdbcUrl = s"jdbc:h2:mem:mysql_sink_${UUID.randomUUID().toString.replace('-', '_')};MODE=MySQL;DB_CLOSE_DELAY=0"
    private val inspectionConnection: Connection = DriverManager.getConnection(jdbcUrl, "sa", "")
    private var dataSources = Vector.empty[HikariDataSource]
    private val created = Promise[HikariDataSource]()

    val node = new MySQLSinkNode {
      override protected[sinks] def createDataSource(
        host: String,
        port: Int,
        database: String,
        username: String,
        password: String
      ): HikariDataSource = {
        val config = new HikariConfig()
        config.setJdbcUrl(jdbcUrl)
        config.setDriverClassName("org.h2.Driver")
        config.setUsername(username)
        config.setPassword(password)
        config.setMaximumPoolSize(1)
        config.setMinimumIdle(0)
        val dataSource = new HikariDataSource(config)
        dataSources :+= dataSource
        created.success(dataSource)
        dataSource
      }

      override protected[sinks] def createInnerSink(
        dataSource: HikariDataSource,
        table: String,
        batchSize: Int,
        mode: String,
        onLog: String => Unit
      )(implicit executionContext: ExecutionContext) = {
        if (failInnerSink) throw new IllegalStateException("inner sink setup failed")
        super.createInnerSink(dataSource, table, batchSize, mode, onLog)(executionContext)
      }
    }

    initializeTable()

    def dataSourceCount: Int = dataSources.size
    def dataSourceCreated: Future[HikariDataSource] = created.future
    def lastDataSource: HikariDataSource = dataSources.last
    def activeConnections: Int = lastDataSource.getHikariPoolMXBean.getActiveConnections

    def selectCount(table: String): Int = {
      val statement = inspectionConnection.createStatement()
      try {
        val resultSet = statement.executeQuery(s"SELECT COUNT(*) FROM $table")
        try {
          resultSet.next()
          resultSet.getInt(1)
        } finally resultSet.close()
      } finally statement.close()
    }

    def close(): Unit = inspectionConnection.close()

    private def initializeTable(): Unit = {
      val statement = inspectionConnection.createStatement()
      try statement.executeUpdate("CREATE TABLE sink_rows (id INT PRIMARY KEY, payload VARCHAR(255) NOT NULL)")
      finally statement.close()
    }
  }
}
