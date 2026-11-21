package cn.xuyinyin.magic.cdc.runtime

import cn.xuyinyin.magic.cdc.platform.{CdcCaptureSettings,CdcPlatformSettings}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{MySQLCaptureStore,SharedMySQLCapture}
import cn.xuyinyin.magic.connectors.mysql.source.DebeziumEngineFactory
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.{Executors,ScheduledExecutorService,TimeUnit}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext,Future}

/** Capture-only runtime. It owns source readers and L, never task grants. */
final class CdcCaptureRuntime(settings: CdcPlatformSettings, leases: CdcRuntimeLeaseStore)(implicit ec: ExecutionContext)
    extends CaptureRuntimeControl with AutoCloseable {
  private val leaseTtlMillis=5000L
  private val heartbeat:ScheduledExecutorService=Executors.newSingleThreadScheduledExecutor(r=>{
    val thread=new Thread(r,"cdc-capture-heartbeat");thread.setDaemon(true);thread
  })
  private final case class Entry(config: CdcCaptureSettings, lease: CaptureLease, store: MySQLCaptureStore, reader: SharedMySQLCapture)
  private val entries = mutable.Map.empty[String,Entry]
  private val incarnation = UUID.randomUUID().toString

  private def openState(config: CdcCaptureSettings) =
    DriverManager.getConnection(config.sdkState.jdbcUrl, config.sdkState.username, config.sdkState.password)

  private def renewLeases():Unit=synchronized {
    entries.values.foreach { entry =>
      if(!leases.renewCapture(entry.lease,leaseTtlMillis)) {
        entry.reader.requestClose()
        leases.releaseCapture(entry.lease,"UNKNOWN")
      }
    }
  }

  private def entry(captureId:String):Entry=entries.getOrElse(captureId,
    throw new IllegalArgumentException("capture runtime is not started: "+captureId))

  override def acquire(captureId:String,sourceLineage:String,requestedIncarnation:String):Future[CaptureLease]=Future {
    synchronized {
      entries.get(captureId).map(_.lease).getOrElse(
        leases.acquireCapture(captureId,sourceLineage,requestedIncarnation).getOrElse(
          throw new IllegalStateException("capture owner is not available: "+captureId)))
    }
  }

  override def renew(lease:CaptureLease):Future[Boolean]=Future(leases.renewCapture(lease,leaseTtlMillis))

  override def ensureSubscription(request:CaptureSubscriptionRequest):Future[CaptureSubscriptionResult]=Future {
    synchronized {
      val e=entry(request.captureId)
      require(leases.renewCapture(e.lease,leaseTtlMillis),"capture lease is stale")
      val subscription=e.reader.subscribe(request.requestId,request.tableName,request.generation)
      CaptureSubscriptionResult(request.captureId,subscription.requestId,subscription.table,subscription.generation,subscription.status)
    }
  }

  override def cancelSubscription(captureId:String,requestId:String,reason:String):Future[CaptureSubscriptionResult]=Future {
    synchronized {
      val e=entry(captureId)
      require(reason.nonEmpty,"cancellation reason is required")
      require(leases.renewCapture(e.lease,leaseTtlMillis),"capture lease is stale")
      val current=e.store.subscription(requestId).getOrElse(throw new IllegalArgumentException("unknown subscription: "+requestId))
      current.status match {
        case "B_PENDING" => e.reader.cancelPending(requestId)
        case "CAPTURE_READY" => e.reader.unsubscribe(requestId)
        case "CAPTURE_STOPPED" | "CANCELED" => ()
        case other => throw new IllegalStateException("subscription cannot be canceled from "+other)
      }
      val result=e.store.subscription(requestId).getOrElse(current)
      CaptureSubscriptionResult(captureId,result.requestId,result.table,result.generation,result.status)
    }
  }

  override def release(lease:CaptureLease):Future[Boolean]=Future(leases.releaseCapture(lease,"STOPPED"))

  def start(): Unit = synchronized {
    require(entries.isEmpty, "capture runtime already started")
    leases.initialize()
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
    heartbeat.scheduleWithFixedDelay(new Runnable {def run():Unit=try renewLeases() catch {case _:Throwable=>()}},1000L,1000L,TimeUnit.MILLISECONDS)
  }

  def status: Vector[(String,String)] = synchronized {
    entries.toVector.map { case (id, entry) =>
      id -> entry.reader.cachedStartupEvidence.phase
    }
  }

  def stop(): Future[Boolean] = synchronized {
    heartbeat.shutdown()
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
    heartbeat.shutdownNow()
    Await.result(stop(), Duration.Inf)
    ()
  }
}
