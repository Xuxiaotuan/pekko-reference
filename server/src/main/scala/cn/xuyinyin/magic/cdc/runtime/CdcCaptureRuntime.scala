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
  private val logger=org.slf4j.LoggerFactory.getLogger(getClass)
  private val leaseTtlMillis=5000L
  private val heartbeat:ScheduledExecutorService=Executors.newSingleThreadScheduledExecutor(r=>{
    val thread=new Thread(r,"cdc-capture-heartbeat");thread.setDaemon(true);thread
  })
  private final case class Entry(config: CdcCaptureSettings, lease: CaptureLease, store: MySQLCaptureStore, reader: SharedMySQLCapture)
  private val entries = mutable.Map.empty[String,Entry]
  private val incarnation = UUID.randomUUID().toString

  private def openState(config: CdcCaptureSettings) =
    DriverManager.getConnection(config.sdkState.jdbcUrl, config.sdkState.username, config.sdkState.password)

  private def openControl(config: CdcCaptureSettings) =
    DriverManager.getConnection(config.controlState.jdbcUrl, config.controlState.username, config.controlState.password)

  private def reconcileSubscriptions(entry: Entry): Unit = {
    val connection=openControl(entry.config)
    try {
      val query=connection.prepareStatement(
        "SELECT task_id,table_name,generation,desired_state,phase FROM pekko_cdc_tasks_v1 WHERE capture_id=?")
      try {
        query.setString(1,entry.config.id)
        val rows=query.executeQuery()
        try while(rows.next()) {
          val requestId=rows.getString("task_id")
          val table=rows.getString("table_name")
          val generation=rows.getString("generation")
          val desired=rows.getString("desired_state")
          val phase=rows.getString("phase")
          val current=entry.store.subscription(requestId)
          if(desired=="RUNNING" && !Set("PAUSED","CANCELED").contains(phase) && current.isEmpty) {
            try entry.reader.subscribe(requestId,table,generation)
            catch {case error:Throwable=>logger.warn("CDC capture subscription deferred capture={} request={} table={} generation={} reason={}",entry.config.id,requestId,table,generation,error.toString)}
          } else if(desired=="CANCELED" || phase=="CANCELED") current.foreach { subscription =>
            try {
              subscription.status match {
                case "B_PENDING" => entry.reader.cancelPending(requestId)
                case "CAPTURE_READY" => entry.reader.unsubscribe(requestId)
                case _ => ()
              }
            } catch {case error:Throwable=>logger.warn("CDC capture cancellation deferred capture={} request={} reason={}",entry.config.id,requestId,error.toString)}
          }
        } finally rows.close()
      } finally query.close()
    } finally connection.close()
  }

  private def renewLeases():Unit=synchronized {
    entries.values.foreach { entry =>
      if(!leases.renewCapture(entry.lease,leaseTtlMillis)) {
        entry.reader.requestClose()
        leases.releaseCapture(entry.lease,"UNKNOWN")
      } else reconcileSubscriptions(entry)
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
        val reader = new SharedMySQLCapture(config.source, config.sdkState, store, DebeziumEngineFactory.real,
          ownerLockName=Some(s"pekko_capture_${config.id}_${lease.ownerAttempt}"))
        reader.start()
        require(leases.markCaptureActive(lease), "capture owner activation was fenced")
        entries += config.id -> Entry(config, lease, store, reader)
      } catch {
        case error: Throwable =>
          leases.releaseCapture(lease, "UNKNOWN")
          throw error
      }
    }
    heartbeat.scheduleWithFixedDelay(new Runnable {def run():Unit=try renewLeases() catch {case error:Throwable=>logger.warn("CDC capture heartbeat failed",error)}},1000L,1000L,TimeUnit.MILLISECONDS)
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
