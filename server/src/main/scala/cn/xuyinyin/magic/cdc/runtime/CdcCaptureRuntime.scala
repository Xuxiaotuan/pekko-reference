package cn.xuyinyin.magic.cdc.runtime

import cn.xuyinyin.magic.cdc.platform.{CdcCaptureSettings,CdcPlatformSettings}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureSubscription,MySQLCaptureStore,SharedMySQLCapture}
import cn.xuyinyin.magic.connectors.mysql.source.DebeziumEngineFactory
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.{Executors,ScheduledExecutorService,TimeUnit}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext,Future}

object CdcCaptureRuntime {
  private[runtime] def requireIncarnation(current: CaptureLease, requested: String): CaptureLease = {
    require(requested.nonEmpty && requested == current.incarnation, "capture incarnation is stale")
    current
  }
}

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
  private val reconcileBusy = mutable.Set.empty[String]
  private val incarnation = UUID.randomUUID().toString

  private def openState(config: CdcCaptureSettings) =
    DriverManager.getConnection(config.sdkState.jdbcUrl, config.sdkState.username, config.sdkState.password)

  private def openControl(config: CdcCaptureSettings) =
    DriverManager.getConnection(config.controlState.jdbcUrl, config.controlState.username, config.controlState.password)

  /** Publish the Reader fact after its Capture State transaction has returned.
    * WorkerRuntime consumes this Control DB projection and never opens Capture State.
    */
  private def publishSubscription(entry: Entry, subscription: CaptureSubscription): Unit = {
    val connection = openControl(entry.config)
    try {
      val update = connection.prepareStatement(
        "UPDATE pekko_cdc_capture_subscription_v1 SET state=?,capture_readiness=?,owner_attempt=?,capture_owner_epoch=?,capture_lease_id=?,control_revision=control_revision+1,updated_at=? WHERE capture_id=? AND task_id=? AND (state<>? OR capture_readiness<>? OR COALESCE(owner_attempt,'')<>? OR COALESCE(capture_owner_epoch,-1)<>? OR COALESCE(capture_lease_id,'')<>?)")
      try {
        update.setString(1, subscription.status)
        update.setString(2, subscription.status)
        update.setString(3, entry.lease.ownerAttempt)
        update.setLong(4, entry.lease.ownerEpoch)
        entry.lease.leaseId match { case Some(value) => update.setString(5, value); case None => update.setObject(5, null) }
        update.setLong(6, System.currentTimeMillis())
        update.setString(7, entry.config.id)
        update.setString(8, subscription.requestId)
        update.setString(9, subscription.status)
        update.setString(10, subscription.status)
        update.setString(11, entry.lease.ownerAttempt)
        update.setLong(12, entry.lease.ownerEpoch)
        entry.lease.leaseId match { case Some(value) => update.setString(13, value); case None => update.setString(13, "") }
        update.executeUpdate()
      } finally update.close()
    } finally connection.close()
  }

  /** A stale owner is recoverable only after the source proves its old lock is gone. */
  private def sourceLockFree(config:CdcCaptureSettings,ownerAttempt:String):Boolean={
    val connection=DriverManager.getConnection(
      s"jdbc:mysql://${config.source.host}:${config.source.port}/${config.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000",
      config.source.username,config.source.password)
    val statement=connection.prepareStatement("SELECT IS_USED_LOCK(?)")
    try {
      statement.setString(1,s"pekko_capture_${config.id}_${ownerAttempt}")
      val result=statement.executeQuery()
      try result.next() && result.getObject(1)==null
      finally result.close()
    } finally {statement.close();connection.close()}
  }

  private def sourceTransactionsDrained(config:CdcCaptureSettings):Boolean={
    val connection=DriverManager.getConnection(
      s"jdbc:mysql://${config.source.host}:${config.source.port}/${config.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000",
      config.source.username,config.source.password)
    val statement=connection.prepareStatement(
      "SELECT COUNT(*) FROM information_schema.innodb_trx t JOIN information_schema.processlist p ON p.ID=t.trx_mysql_thread_id WHERE p.USER=?")
    try {
      statement.setString(1,config.source.username)
      val result=statement.executeQuery()
      try result.next() && result.getLong(1)==0L
      finally result.close()
    } finally {statement.close();connection.close()}
  }

  private def acquireLease(config:CdcCaptureSettings):Option[CaptureLease]={
    leases.acquireCapture(config.id,config.sourceLineage,incarnation).orElse {
      leases.currentCapture(config.id).flatMap {snapshot =>
        if(Set("ACQUIRING","ACTIVE","UNKNOWN")(snapshot.state) &&
          sourceLockFree(config,snapshot.lease.ownerAttempt) && sourceTransactionsDrained(config)) {
          require(leases.releaseCapture(snapshot.lease,"STOPPED"),"stale capture owner changed during recovery")
          logger.warn("CDC capture owner recovered after source lock release capture={} oldAttempt={}",config.id,snapshot.lease.ownerAttempt)
          leases.acquireCapture(config.id,config.sourceLineage,incarnation)
        } else None
      }
    }
  }

  private def reconcileSubscriptions(entry: Entry): Unit = {
    val connection=openControl(entry.config)
    try {
      val query=connection.prepareStatement(
        "SELECT task_id,table_name,generation,desired_state,state AS phase FROM pekko_cdc_capture_subscription_v1 WHERE capture_id=?")
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
            entry.store.activeSubscription(table) match {
              case Some(active) =>
                pauseConflictingProjection(entry,requestId,table,generation,active)
              case None =>
                try entry.reader.subscribe(requestId,table,generation)
                catch {case error:Throwable=>logger.warn("CDC capture subscription deferred capture={} request={} table={} generation={} reason={}",entry.config.id,requestId,table,generation,error.toString)}
            }
          } else if(desired=="CANCELED" || phase=="CANCELED") current.foreach { subscription =>
            try {
              subscription.status match {
                case "B_PENDING" => entry.reader.cancelPending(requestId)
                case "CAPTURE_READY" => entry.reader.unsubscribe(requestId)
                case _ => ()
              }
              entry.store.subscription(requestId).foreach(publishSubscription(entry, _))
            } catch {case error:Throwable=>logger.warn("CDC capture cancellation deferred capture={} request={} reason={}",entry.config.id,requestId,error.toString)}
          }
          entry.store.subscription(requestId).foreach { subscription =>
            if (subscription.status == "CAPTURE_READY" || subscription.status == "CAPTURE_STOPPED" || subscription.status == "CANCELED")
              try publishSubscription(entry, subscription)
              catch {case error:Throwable=>logger.warn("CDC capture readiness projection deferred capture={} request={} reason={}",entry.config.id,requestId,error.toString)}
          }
        } finally rows.close()
      } finally query.close()
    } finally connection.close()
  }

  /** A second control request must stop retrying when an older generation is
    * still effective. The existing subscription remains untouched. */
  private def pauseConflictingProjection(entry:Entry,requestId:String,table:String,generation:String,active:CaptureSubscription):Unit={
    val connection=openControl(entry.config)
    try {
      val update=connection.prepareStatement(
        "UPDATE pekko_cdc_capture_subscription_v1 SET state='PAUSED',capture_readiness='PAUSED',initialization_evidence=?,control_revision=control_revision+1,updated_at=? WHERE capture_id=? AND task_id=? AND state NOT IN ('PAUSED','CANCELED')")
      try {
        update.setString(1,s"TABLE_ALREADY_SUBSCRIBED activeRequestId=${active.requestId} activeGeneration=${active.generation} requestedRequestId=$requestId requestedGeneration=$generation")
        update.setLong(2,System.currentTimeMillis())
        update.setString(3,entry.config.id)
        update.setString(4,requestId)
        update.executeUpdate()
      } finally update.close()
      logger.warn("CDC capture subscription paused due to active table capture={} request={} table={} generation={} activeRequest={} activeGeneration={}",entry.config.id,requestId,table,generation,active.requestId,active.generation)
    } finally connection.close()
  }

  /** Subscription reconciliation may perform JDBC and source calls. It must not
    * occupy the lease heartbeat thread, otherwise a slow table admission can
    * make a healthy Reader appear expired. One reconciliation remains in flight
    * per capture and SharedMySQLCapture serializes the actual command path.
    */
  private def scheduleReconcile(entry: Entry): Unit = {
    val scheduled = reconcileBusy.synchronized {
      if (reconcileBusy(entry.config.id)) false else { reconcileBusy += entry.config.id; true }
    }
    if (scheduled) Future {
      try reconcileSubscriptions(entry)
      finally reconcileBusy.synchronized(reconcileBusy -= entry.config.id)
    }
  }

  private def renewLeases():Unit=synchronized {
    entries.values.foreach { entry =>
      if(!leases.renewCapture(entry.lease,leaseTtlMillis)) {
        entry.reader.requestClose()
        leases.releaseCapture(entry.lease,"UNKNOWN")
      } else scheduleReconcile(entry)
    }
  }

  private def entry(captureId:String):Entry=entries.getOrElse(captureId,
    throw new IllegalArgumentException("capture runtime is not started: "+captureId))

  override def acquire(captureId:String,sourceLineage:String,requestedIncarnation:String):Future[CaptureLease]=Future {
    synchronized {
      entries.get(captureId).map(entry => CdcCaptureRuntime.requireIncarnation(entry.lease, requestedIncarnation)).getOrElse(
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
      publishSubscription(e, subscription)
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
      publishSubscription(e, result)
      CaptureSubscriptionResult(captureId,result.requestId,result.table,result.generation,result.status)
    }
  }

  override def release(lease:CaptureLease):Future[Boolean]=Future(leases.releaseCapture(lease,"STOPPED"))

  def start(): Unit = synchronized {
    require(entries.isEmpty, "capture runtime already started")
    leases.initialize()
    settings.captures.foreach { config =>
      val lease = acquireLease(config)
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
