package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, SnapshotBoundary, SourceCursor}
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.engine.{ExecutionResult, ReliableRunContext, WorkflowExecutionEngine}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Edge, Node, Position, Workflow, WorkflowMetadata}
import cn.xuyinyin.magic.workflow.nodes.sources.{MySQLCdcSourceNode, MySQLCdcStateConfig}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import spray.json._

import java.sql.{Connection, DriverManager}
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise, blocking}
import scala.io.StdIn
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Separate JVM used by [[RealMySQLCdcRecoverySpec]]. */
object RealMySQLCdcProcess {
  private val SourceNodeId = "source-1"

  private final case class Arguments(
    mode: String,
    connectorId: String,
    executionId: String,
    idStart: Long,
    idEnd: Long
  )

  private final case class Settings(
    host: String,
    port: Int,
    database: String,
    writerUser: String,
    writerPassword: String,
    readerUser: String,
    readerPassword: String,
    serverId: Long,
    offsetFlushIntervalMillis: Int
  ) {
    val jdbcUrl: String =
      s"jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true" +
        "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
  }

  def main(rawArguments: Array[String]): Unit = {
    var system: ActorSystem[Nothing] = null
    var engine: WorkflowExecutionEngine = null
    var executionId: String = "unavailable"
    var connectorId: String = "unavailable"
    var exitCode = 0
    try {
      val arguments = parseArguments(rawArguments)
      executionId = arguments.executionId
      connectorId = arguments.connectorId
      val settings = settingsFromEnvironment()
      val config = ConfigFactory.parseMap(Map[String, AnyRef](
        "pekko.loglevel" -> "WARN",
        "pekko.stdout-loglevel" -> "WARN"
      ).asJava).withFallback(ConfigFactory.load("application-test"))
      system = ActorSystem[Nothing](Behaviors.empty, s"mysql-cdc-${arguments.connectorId}", config)
      implicit val executionContext: ExecutionContext = system.executionContext

      val source = new MySQLCdcSourceNode(
        loadStateConfig = () => MySQLCdcStateConfig(
          settings.jdbcUrl,
          settings.writerUser,
          settings.writerPassword,
          "debezium_offset_storage",
          "debezium_database_history",
          settings.offsetFlushIntervalMillis
        )
      )
      NodeRegistry.registerSource(source)
      engine = new WorkflowExecutionEngine()(system, executionContext)
      val checkpoint = arguments.mode match {
        case "snapshot-and-stream" => None
        case _ => Some(loadCheckpoint(settings, arguments))
      }
      val boundary = checkpoint.map(value => SnapshotBoundary(
        value.sourceNodeId,
        value.partitionId,
        Some(value.cursor.upperBound)
      ))
      val context = ReliableRunContext(
        executionId = arguments.executionId,
        workflowRevision = 1L,
        boundary = boundary,
        checkpoints = checkpoint.toVector,
        initializeBoundary = _ => Future.successful(Done),
        checkpointCommitted = value => {
          printStatus(arguments, value.batchSequence, value.targetRowsWritten, value.cursor)
          if (arguments.mode == "resume-stream-crash-after-commit" &&
            checkpoint.exists(previous => value.batchSequence > previous.batchSequence)) {
            System.out.flush()
            Runtime.getRuntime.halt(86)
          }
          Future.successful(Done)
        }
      )

      printStatus(arguments, -1L, 0L, SourceCursor("mysql.binlog.v1", "", ""))
      val running = engine.execute(workflow(arguments, settings), context, message => {
        println(s"CDC_LOG ${singleLine(message)}")
        System.out.flush()
      })
      val stop = Promise[Done]()
      val commandExecutor = Executors.newSingleThreadExecutor { runnable =>
        val thread = new Thread(runnable, s"mysql-cdc-command-${arguments.connectorId}")
        thread.setDaemon(true)
        thread
      }
      val commandEc: ExecutionContext = ExecutionContext.fromExecutor(commandExecutor)
      Future(blocking(StdIn.readLine()))(commandEc).foreach {
        case "STOP" | null => stop.trySuccess(Done)
        case _ => stop.tryFailure(new IllegalArgumentException("unsupported child command"))
      }(ExecutionContext.parasitic)

      try {
        val outcomes: Vector[Future[Either[ExecutionResult, Done]]] = Vector(
          running.map(result => Left(result): Either[ExecutionResult, Done])(ExecutionContext.parasitic),
          stop.future.map(_ => Right(Done): Either[ExecutionResult, Done])(ExecutionContext.parasitic)
        )
        Await.result(Future.firstCompletedOf(outcomes)(executionContext), Duration.Inf) match {
          case Left(result) if !result.success =>
            val details = result.nodeResults.flatMap(_.message).mkString("; ")
            throw new IllegalStateException(singleLine(s"${result.message}; $details"))
          case Left(_) => throw new IllegalStateException("CDC workflow terminated before STOP")
          case Right(_) =>
            Await.result(engine.cancel(arguments.executionId), 30.seconds)
            val result = Await.result(running, 30.seconds)
            if (!result.success) throw new IllegalStateException("CDC workflow failed while stopping")
        }
      } finally commandExecutor.shutdownNow()
    } catch {
      case NonFatal(error) =>
        exitCode = 1
        System.err.println(
          s"CDC_FAILED connectorId=$connectorId executionId=$executionId " +
            s"errorClass=${error.getClass.getSimpleName} " +
            s"message=${singleLine(Option(error.getMessage).getOrElse("unavailable"))}"
        )
        System.err.flush()
    } finally {
      if (engine != null && executionId != "unavailable") {
        try Await.result(engine.cancel(executionId), 30.seconds)
        catch { case NonFatal(_) => exitCode = 1 }
      }
      if (system != null) {
        try {
          system.terminate()
          Await.result(system.whenTerminated, 30.seconds)
        }
        catch { case NonFatal(_) => exitCode = 1 }
      }
    }
    if (exitCode != 0) System.exit(exitCode)
  }

  private def parseArguments(values: Array[String]): Arguments = {
    if (values.length != 5 ||
      !Set("snapshot-and-stream", "resume-stream", "resume-stream-crash-after-commit").contains(values(0))) {
      throw new IllegalArgumentException(
        "expected: snapshot-and-stream|resume-stream|resume-stream-crash-after-commit " +
          "<connectorId> <executionId> <idStart> <idEnd>"
      )
    }
    val connectorId = values(1)
    if (!connectorId.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
      throw new IllegalArgumentException("invalid connectorId")
    }
    val executionId = values(2)
    if (executionId.isEmpty || executionId.length > 255) {
      throw new IllegalArgumentException("invalid executionId")
    }
    val idStart = values(3).toLong
    val idEnd = values(4).toLong
    if (idStart < 0L || idEnd < idStart || idEnd - idStart > 1000L) {
      throw new IllegalArgumentException("invalid acceptance ID range")
    }
    Arguments(values(0), connectorId, executionId, idStart, idEnd)
  }

  private def settingsFromEnvironment(): Settings = {
    def required(name: String): String = sys.env.get(name).filter(_.nonEmpty)
      .getOrElse(throw new IllegalArgumentException(s"missing environment variable: $name"))
    def long(name: String, minimum: Long, maximum: Long): Long = {
      val parsed = required(name).toLong
      if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException(s"invalid environment variable: $name")
      parsed
    }
    val database = required("MYSQL_CDC_TEST_DATABASE")
    if (!database.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("invalid environment variable: MYSQL_CDC_TEST_DATABASE")
    }
    Settings(
      host = required("MYSQL_CDC_TEST_HOST"),
      port = long("MYSQL_CDC_TEST_PORT", 1L, 65535L).toInt,
      database = database,
      writerUser = required("MYSQL_CDC_TEST_WRITER_USER"),
      writerPassword = required("MYSQL_CDC_TEST_WRITER_PASSWORD"),
      readerUser = required("MYSQL_CDC_TEST_READER_USER"),
      readerPassword = required("MYSQL_CDC_TEST_READER_PASSWORD"),
      serverId = long("MYSQL_CDC_TEST_SERVER_ID", 1L, 4294967295L),
      offsetFlushIntervalMillis = long("MYSQL_CDC_TEST_OFFSET_FLUSH_INTERVAL_MS", 0L, Int.MaxValue.toLong).toInt
    )
  }

  private def workflow(arguments: Arguments, settings: Settings): Workflow = {
    val source = Node(
      SourceNodeId,
      "source",
      "mysql.cdc",
      "real MySQL CDC source",
      Position(0, 0),
      JsObject(
        "connectorId" -> JsString(arguments.connectorId),
        "host" -> JsString(settings.host),
        "port" -> JsNumber(settings.port),
        "database" -> JsString(settings.database),
        "table" -> JsString("pekko_cdc_source_acceptance"),
        "username" -> JsString(settings.readerUser),
        "passwordEnv" -> JsString("MYSQL_CDC_TEST_READER_PASSWORD"),
        "serverId" -> JsNumber(settings.serverId),
        "maxBatchSize" -> JsNumber(100),
        "pollIntervalMillis" -> JsNumber(100)
      )
    )
    val sink = Node(
      "sink-1",
      "sink",
      "mysql.cdc.apply",
      "real MySQL CDC apply sink",
      Position(1, 0),
      JsObject(
        "host" -> JsString(settings.host),
        "port" -> JsNumber(settings.port),
        "database" -> JsString(settings.database),
        "table" -> JsString("pekko_cdc_target_acceptance"),
        "username" -> JsString(settings.writerUser),
        "passwordEnv" -> JsString("MYSQL_CDC_TEST_WRITER_PASSWORD")
      )
    )
    Workflow(
      id = s"workflow-${arguments.connectorId}",
      name = "real MySQL CDC acceptance",
      description = "isolated external snapshot, binlog, replay, and recovery acceptance",
      version = "1",
      author = "test",
      tags = Nil,
      nodes = List(source, sink),
      edges = List(Edge("source-to-sink", source.id, sink.id)),
      metadata = WorkflowMetadata("2026-08-30", "2026-08-30")
    )
  }

  private def loadCheckpoint(settings: Settings, arguments: Arguments): BatchCheckpoint = withConnection(settings) { connection =>
    val statement = connection.prepareStatement(
      "SELECT source_node_id, partition_id, batch_sequence, batch_id, cursor_kind, cursor_value, upper_bound, " +
        "source_rows, target_rows FROM pekko_sync_batch_ledger " +
        "WHERE execution_id = ? AND source_node_id = ? AND partition_id = ? ORDER BY batch_sequence DESC LIMIT 1"
    )
    try {
      statement.setString(1, arguments.executionId)
      statement.setString(2, SourceNodeId)
      statement.setString(3, s"mysql-cdc:${arguments.connectorId}")
      val rows = statement.executeQuery()
      try {
        if (!rows.next()) throw new IllegalStateException("resume checkpoint is absent")
        BatchCheckpoint(
          sourceNodeId = rows.getString("source_node_id"),
          partitionId = rows.getString("partition_id"),
          batchSequence = rows.getLong("batch_sequence"),
          batchId = rows.getString("batch_id"),
          cursor = SourceCursor(
            rows.getString("cursor_kind"),
            rows.getString("cursor_value"),
            rows.getString("upper_bound")
          ),
          sourceRowsScanned = rows.getLong("source_rows"),
          targetRowsWritten = rows.getLong("target_rows")
        )
      } finally rows.close()
    } finally statement.close()
  }

  private def printStatus(arguments: Arguments, sequence: Long, rows: Long, cursor: SourceCursor): Unit = {
    val (file, position) = cursorPosition(cursor.value)
    println(
      s"CDC_STATUS connectorId=${arguments.connectorId} executionId=${arguments.executionId} " +
        s"batchSequence=$sequence rowCount=$rows cursorFile=$file cursorPosition=$position"
    )
    System.out.flush()
  }

  private def singleLine(value: String): String =
    Option(value).getOrElse("").replaceAll("[\\r\\n]+", " ").take(2000)

  private def cursorPosition(value: String): (String, Long) = {
    val parsed = try value.parseJson.asJsObject.fields.get("offset").collect { case objectValue: JsObject => objectValue }
    catch { case NonFatal(_) => None }
    val file = parsed.flatMap(_.fields.get("file")).collect {
      case JsString(candidate) if candidate.matches("[A-Za-z0-9._-]+") => candidate
    }.getOrElse("unavailable")
    val position = parsed.flatMap(_.fields.get("pos")).collect {
      case JsNumber(candidate) if candidate.isValidLong => candidate.toLong
    }.getOrElse(-1L)
    file -> position
  }

  private def withConnection[A](settings: Settings)(operation: Connection => A): A = {
    Class.forName("com.mysql.cj.jdbc.Driver")
    val connection = DriverManager.getConnection(settings.jdbcUrl, settings.writerUser, settings.writerPassword)
    try operation(connection)
    finally connection.close()
  }
}
