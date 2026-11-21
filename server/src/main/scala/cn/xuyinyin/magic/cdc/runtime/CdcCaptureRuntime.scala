package cn.xuyinyin.magic.cdc.runtime

import cn.xuyinyin.magic.cdc.platform.{CdcCaptureSettings,CdcPlatformSettings}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{MySQLCaptureStore,SharedMySQLCapture}
import cn.xuyinyin.magic.connectors.mysql.source.DebeziumEngineFactory
import java.sql.DriverManager
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContext,Future}

/** Capture-only runtime. It owns source readers and L, never task grants. */
final class CdcCaptureRuntime(settings: CdcPlatformSettings, leases: CdcRuntimeLeaseStore)(implicit ec: ExecutionContext)
    extends AutoCloseable {
  private final case class Entry(config: CdcCaptureSettings, lease: CaptureLease, store: MySQLCaptureStore, reader: SharedMySQLCapture)
  private val entries = mutable.Map.empty[String,Entry]
  private val incarnation = UUID.randomUUID().toString

  private def openState(config: CdcCaptureSettings) =
    DriverManager.getConnection(config.sdkState.jdbcUrl, config.sdkState.username, config.sdkState.password)

  def start(): Unit = synchronized {
    require(entries.isEmpty, "capture runtime already started")
    settings.captures.foreach { config =>
      val lease = leases.acquireCapture(config.id, config.sourceLineage, incarnation)
        .getOrElse(throw new IllegalStateException("capture owner is not available: " + config.id))
      val store = new MySQLCaptureStore(() => openState(config), config.id, config.sourceLineage, config.maxLogBytes)
      try {
        store.initialize()
        val reader = new SharedMySQLCapture(config.source, config.sdkState, store, DebeziumEngineFactory.real)
        reader.start()
        require(leases.markCaptureActive(lease), "capture owner activation was fenced")
        entries += config.id -> Entry(config, lease, store, reader)
      } catch {
        case error: Throwable =>
          leases.releaseCapture(lease, "UNKNOWN")
          throw error
      }
    }
  }

  def status: Vector[(String,String)] = synchronized {
    entries.toVector.map { case (id, entry) =>
      id -> entry.reader.cachedStartupEvidence.phase
    }
  }

  def stop(): Future[Boolean] = synchronized {
    val closing = entries.values.toVector.map { entry =>
      entry.reader.requestClose().flatMap { evidence =>
        val state = if (evidence.reusable) "STOPPED" else "UNKNOWN"
        Future.successful(leases.releaseCapture(entry.lease, state) && evidence.reusable)
      }
    }
    entries.clear()
    val results: Future[Seq[Boolean]] = Future.sequence(closing)
    results.map(_.forall(identity))
  }

  override def close(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration.Duration
    Await.result(stop(), Duration.Inf)
    ()
  }
}
