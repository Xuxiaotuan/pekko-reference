package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.api.http.routes.CdcTaskRoutes
import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore
import cn.xuyinyin.magic.workflow.bootstrap.{BootstrapLimits, BootstrapStore}
import cn.xuyinyin.magic.workflow.cdc._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.http.scaladsl.Http
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.jdk.CollectionConverters._
import spray.json._

/** Real ingress/runtime/kernel; gates hold production observers, never replace records or ACKs. */
object CdcPlatformMatrixWorker {
  import CdcJournalWindowSupport._
  def main(args: Array[String]): Unit = {
    val Array(schema, captureId, sourceUser, stateUser, sourceLineage, stateLineage, holdStart) = args
    validateSchema(schema)
    val source = Settings.environment(); val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
    def stateOpen() = DriverManager.getConnection(state.url(schema) + "&connectTimeout=5000&socketTimeout=5000", state.user, state.password)
    val gate = new AtomicReference[String](""); val latch = new AtomicReference(new CountDownLatch(1))
    def event(kind: String, value: JsObject): Unit = {
      emit(kind, value.fields.toSeq: _*)
      if (CdcProbeGate.claim(gate,kind)) {
        emit("CUTPOINT", ("window" -> JsString(kind)) +: value.fields.toSeq: _*)
        latch.get().await()
      }
    }
    val factory = new CaptureStartupRecoveryFactory(event, holdStart.toBoolean)
    val engineCreations = new AtomicInteger()
    val counted = new DebeziumEngineFactory {
      def create(properties: java.util.Properties): DebeziumEngineAccess = { engineCreations.incrementAndGet(); factory.create(properties) }
    }
    val cfg = ConfigFactory.parseMap(Map[String, AnyRef](
      "pekko.actor.provider" -> "local", "pekko.remote.artery.canonical.port" -> Int.box(0),
      "pekko.cluster.seed-nodes" -> java.util.Collections.emptyList[String](),
      "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url" -> state.url(schema),
      "pekko-persistence-jdbc.shared-databases.slick.db.user" -> state.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password" -> state.password,
      "pekko.loglevel" -> "WARNING", "pekko.stdout-loglevel" -> "WARNING").asJava).withFallback(ConfigFactory.load("application-test"))
    val kit = ActorTestKit("platform-" + captureId, cfg)
    implicit val ec: ExecutionContext = kit.system.executionContext
    implicit val classic: org.apache.pekko.actor.ActorSystem = kit.system.classicSystem
    val sourceCfg = MySQLCdcSourceConfig(captureId, source.host, source.port, schema, "pekko_capture_control", sourceUser, source.password,
      (captureId.hashCode.toLong & 0x7fffffffL) + 100000L, 128, 100)
    val controlCfg = MySQLCdcStateConfig(state.url(schema), state.user, state.password, "capture_offsets", "capture_history", 0)
    val sdkCfg = controlCfg.copy(username = stateUser)
    val limits = CdcPlatformLimits(snapshotSteps = 2, tailSteps = 2, candidatePage = 32, tailDelayMillis = 100L)
    val settings = CdcPlatformSettings(Vector(CdcCaptureSettings(captureId, sourceCfg, sdkCfg, controlCfg,
      sourceLineage, stateLineage, bootstrapLimits = BootstrapLimits(chunkRows = 16, pageRows = 4, groupBatch = 128),
      sourceObserverUsername = Some(source.user), sourceObserverPassword = Some(source.password))), limits, 30L)
    val kernel = new CdcBootstrapKernel(settings, kit.system, event, counted)
    val taskStore = new CdcTaskStore(() => stateOpen(), limits)
    val runtime = new CdcPlatformRuntime(taskStore, kernel, limits, pollIntervalMillis = 30L)
    val log = new MySQLCaptureStore(() => stateOpen(), captureId, sourceLineage, 128L * 1024 * 1024)
    var binding: Http.ServerBinding = null
    def statusJson: JsObject = {
      val status = runtime.status
      JsObject("ownerState" -> JsString(status.ownerState), "reason" -> status.reason.map(JsString(_)).getOrElse(JsNull),
        "activeSteps" -> JsNumber(status.activeSteps), "maxActiveSteps" -> JsNumber(status.maxActiveSteps),
        "runtimeAttemptId" -> JsString(runtime.runtimeAttemptId))
    }
    def releaseJson: JsObject = JsObject(kernel.captureReleaseEvidence.map {case(id,e)=>
      id->JsObject("attempt"->JsString(e.runtimeAttemptId),"operation"->JsString(e.operationId),
        "local"->JsString(e.localTermination),"server"->JsString(e.serverRelease),"ownerReleased"->JsBoolean(e.ownerReleased),
        "reusable"->JsBoolean(e.reusable),"reason"->e.reasonCode.map(JsString(_)).getOrElse(JsNull))
    }.toMap)
    def tableRows(table: String, order: String, columns: String = "*"): JsValue = withConnection(state, schema) { c =>
      val q = c.prepareStatement("SELECT 1 FROM information_schema.tables WHERE table_schema=? AND table_name=?")
      q.setString(1, schema); q.setString(2, table)
      val exists = try { val r = q.executeQuery(); try r.next() finally r.close() } finally q.close()
      if (exists) query(c, s"SELECT $columns FROM `$table` ORDER BY $order") else JsNull
    }
    def snapshot(): JsObject = {
      val offsets = MySQLCdcSourceNode.connectorStateTables(sdkCfg, captureId)._1
      val tasks = tableRows("pekko_cdc_tasks_v1", "task_id")
      val spoolExists = tableRows("pekko_bootstrap_storage", "bootstrap_id") != JsNull
      val spools = if (!spoolExists || tasks == JsNull) JsObject.empty else JsObject(tasks.asInstanceOf[JsArray].elements.map { value =>
        val id = value.asJsObject.fields("task_id").convertTo[String]
        id -> new BootstrapStore(() => stateOpen(), id, 64L * 1024 * 1024).evidence
      }.toMap)
      JsObject("platform" -> statusJson, "engineCreations" -> JsNumber(engineCreations.get()), "kernelResources" -> kernel.resourceEvidence,
        "owner" -> tableRows("pekko_cdc_owner_v1", "platform_id"), "tasks" -> tasks,
        "grants" -> tableRows("pekko_cdc_grants_v1", "created_at,grant_id"),
        "dispatch" -> tableRows("pekko_cdc_dispatch_v1", "capture_id"),
        "L" -> (if (tableRows("pekko_capture_readers", "capture_id") == JsNull) JsNull else log.evidence),
        "D" -> tableRows(offsets, "id"), "J" -> tableRows("event_journal", "ordering", "persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex"),
        "T" -> tableRows("pekko_sync_cdc_group_ledger", "group_id"), "baselineT" -> tableRows("pekko_bootstrap_receipts", "bootstrap_id,operation_id"),
        "spools" -> spools,
        "sourceConnections" -> withConnection(source, schema)(query(_, "SELECT ID,USER,HOST,DB,COMMAND,STATE FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()")),
        "sourceTransactions" -> withConnection(source, schema)(query(_, "SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.INNODB_TRX")),
        "stateConnections" -> withConnection(state, schema)(query(_, "SELECT ID,USER,HOST,DB,COMMAND,STATE FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()")),
        "stateTransactions" -> withConnection(state, schema)(query(_, "SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.INNODB_TRX")))
    }
    var clean = false
    try {
      Await.result(runtime.captures(), 30.seconds)
      binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(new CdcTaskRoutes(Some(runtime)).routes), 20.seconds)
      emit("PROCESS_READY", "pid" -> JsNumber(ProcessHandle.current().pid()), "httpPort" -> JsNumber(binding.localAddress.getPort), "owner" -> statusJson)
      var done = false
      while (!done) {
        val line = StdIn.readLine()
        if (line == null) throw new IllegalStateException("controller input lost")
        val command = line.split(" ")
        command(0) match {
          case "STATE" => emit("STATE", snapshot().fields.toSeq: _*)
          case "ARM" => latch.set(new CountDownLatch(1)); gate.set(command(1)); emit("ARMED")
          case "RELEASE" => latch.get().countDown(); emit("GATE_RELEASED")
          case "RELEASE_START" => factory.release(); emit("START_RELEASED")
          case "CRASH" => System.out.flush(); Runtime.getRuntime.halt(86)
          case "STOP" =>
            gate.set(""); latch.get().countDown(); factory.release()
            val wasUnproven = runtime.status.ownerState == "UNKNOWN" && engineCreations.get() == 0
            val first = Await.result(runtime.stopRuntime(), 25.seconds)
            val deadline = 160.seconds.fromNow
            while (!first && runtime.status.ownerState != "STOPPED" && !wasUnproven && deadline.hasTimeLeft()) Thread.sleep(250)
            clean = runtime.status.ownerState == "STOPPED" || wasUnproven
            emit(if (wasUnproven) "SAFE_PAUSED_LOCAL_EXIT" else "RUNTIME_STOPPED", "confirmed" -> JsBoolean(clean && !wasUnproven),
              "localExitReady"->JsBoolean(clean),"owner" -> statusJson,"captureRelease"->releaseJson)
            if (!clean) throw new IllegalStateException("runtime physical release remains unknown")
            done = true
          case _ => throw new IllegalArgumentException("unknown controller command")
        }
      }
    } finally {
      gate.set(""); latch.get().countDown(); factory.release()
      if (!clean) try Await.ready(runtime.stopRuntime(), 25.seconds) catch { case _: Throwable => () }
      if (binding != null) Await.ready(binding.unbind(), 10.seconds)
      kit.shutdownTestKit()
    }
  }
}
