package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.connectors.mysql.sink.MySQLCdcApplySinkNode
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.execution.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import java.nio.file.{Files, Path, StandardOpenOption}
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

/** One independent JVM per attempt; no production readiness/recovery changes. */
object CaptureAdmissionReleaseProbe {
  import CdcJournalWindowSupport._
  def main(args: Array[String]): Unit = {
    require(args.length == 5, "mode schema captureId heartbeatMillis evidenceDirectory required")
    val mode = args(0); val schema = args(1); val captureId = args(2); val heartbeat = args(3); val evidence = args(4)
    validateSchema(schema)
    require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty), "exclusive slot lease required")
    val source = Settings.environment()
    val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
    require(source.port != state.port && !Set(3306, 13365, 13366).contains(source.port) &&
      !Set(3306, 13365, 13366).contains(state.port), "protected ports forbidden")
    val out = Path.of(evidence); Files.createDirectories(out)
    val attempt = UUID.randomUUID().toString
    val events = new ConcurrentLinkedQueue[JsObject]()
    def record(kind: String, value: JsObject = JsObject()): Unit = synchronized {
      val e = JsObject(value.fields ++ Map("kind" -> JsString(kind), "attempt" -> JsString(attempt),
        "nanoTime" -> JsNumber(System.nanoTime()), "epoch_ns" -> JsNumber(System.currentTimeMillis() * 1000000L), "at" -> JsString(java.time.Instant.now().toString)))
      events.add(e)
      Files.writeString(out.resolve("events.jsonl"), e.compactPrint + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
    def sql(s: Settings, q: String): Unit = withConnection(s, schema)(executeSql(_, q))
    // Validate the live physical instances before either init writes or observe starts.
    val lineage = withConnection(source, "")(query(_, "SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
    val stateUuid = withConnection(state, "")(query(_, "SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
    require(lineage == sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid == sys.env("CAPTURE_EXPECT_STATE_UUID") && lineage != stateUuid, "slot identity mismatch")
    record("INSTANCE_IDENTITY", JsObject("sourceUuid" -> JsString(lineage), "stateUuid" -> JsString(stateUuid)))
    if (mode == "init") {
      initialize(source, schema); initialize(state, schema)
      sql(source, "CREATE TABLE a LIKE source_rows")
      sql(source, "INSERT INTO a SELECT * FROM source_rows WHERE id<=4")
      sql(state, "CREATE TABLE target_a LIKE source_rows")
      sql(state, "INSERT INTO target_a SELECT * FROM source_rows WHERE id<=4")
      withConnection(state, schema) { c =>
        val input = getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
        val ddl = try scala.io.Source.fromInputStream(input).mkString finally input.close()
        ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c, _))
      }
      record("INITIALIZED", JsObject("fixture" -> JsString("copied baseline; not online bootstrap")))
      return
    }
    require(mode == "observe" || mode == "observe-sdk", "unknown mode")
    def stateConnection() = DriverManager.getConnection(state.url(schema) + "&connectTimeout=5000&socketTimeout=5000", state.user, state.password)
    val store = new MySQLCaptureStore(() => stateConnection(), captureId, lineage, 64L * 1024 * 1024)
    store.initialize()
    val cfg = MySQLCdcStateConfig(state.url(schema), state.user, state.password, "capture_offsets", "capture_history", 0)
    val offsetTable = MySQLCdcSourceNode.connectorStateTables(cfg, captureId)._1
    def offsets(): JsArray = withConnection(state, schema) { c =>
      val exists = query(c, s"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='$offsetTable'")
      if (exists.elements.isEmpty) JsArray() else query(c, s"SELECT offset_key,offset_val FROM `$offsetTable` ORDER BY offset_key")
    }
    def checkedOffsets(): JsArray = {
      val rows = offsets()
      val expected = JsArray(JsString(captureId), JsObject("server" -> JsString(captureId)))
      require(rows.elements.size <= 1 && rows.elements.forall(r =>
        r.asJsObject.fields("offset_key").convertTo[String].parseJson == expected), "offset partition must be unique and match this capture")
      rows
    }
    val mbeans = ManagementFactory.getPlatformMBeanServer
    def metrics(): JsArray = JsArray(mbeans.queryNames(new ObjectName("debezium.mysql:*"), null).asScala.toVector
      .filter(n => Option(n.getKeyProperty("server")).contains(captureId)).sortBy(_.toString).map { n =>
        val wanted = Set("SnapshotCompleted", "SnapshotRunning", "SnapshotAborted", "SnapshotSkipped", "Connected")
        val attrs = mbeans.getMBeanInfo(n).getAttributes.toVector.filter(a => a.isReadable && wanted(a.getName)).map { a =>
          val v = try mbeans.getAttribute(n, a.getName) match {
            case b: java.lang.Boolean => JsBoolean(b.booleanValue())
            case x => JsString(String.valueOf(x))
          } catch { case e: Exception => JsString("UNKNOWN:" + e.getClass.getSimpleName) }
          a.getName -> v
        }.toMap
        JsObject("objectName" -> JsString(n.toString), "attributes" -> JsObject(attrs))
      })
    require(metrics().elements.isEmpty, "stale capture MBean before engine start")
    record("BEFORE_START", JsObject("pid" -> JsNumber(ProcessHandle.current().pid()), "captureId" -> JsString(captureId),
      "jvmStartedMillis" -> JsNumber(ManagementFactory.getRuntimeMXBean.getStartTime),
      "schema" -> JsString(schema), "sourceUuid" -> JsString(lineage), "stateUuid" -> JsString(stateUuid), "D" -> checkedOffsets()))
    val factory = new CaptureAdmissionProbeFactory(heartbeat.toInt, record,sys.env.get("CAPTURE_REQUIRE_SKIP_REFUSAL").contains("1"))
    val sourceCfg = MySQLCdcSourceConfig(captureId, source.host, source.port, schema, "a", source.user, source.password,
      (captureId.hashCode.toLong & 0x7fffffffL) + 100000L, 128, 100)
    val capture = new SharedMySQLCapture(sourceCfg, cfg, store, factory, record) {
      override protected def sourceConnection(): java.sql.Connection = {
        val c = super.sourceConnection()
        try {
          record("APPLICATION_SOURCE_OPEN", JsObject("identity" -> query(c, "SELECT @@server_uuid AS uuid,CONNECTION_ID() AS connection_id")))
          c
        } catch { case e: Throwable => c.close(); throw e }
      }
    }
    val sdkOnly = mode == "observe-sdk"
    val sdk = if (sdkOnly) factory.create(CaptureAdmissionProbeFactory.sdkProperties(sourceCfg, cfg)) else null
    val completion = if (sdkOnly) sdk.start(new DebeziumBatchConsumer {
      def handleBatch(records: Vector[org.apache.kafka.connect.source.SourceRecord], handle: CdcBatchCommitHandle): Unit = {
        record("SDK_COMPONENT_CALLBACK", JsObject("records" -> JsNumber(records.size)))
        handle.markProcessedAndFinished()
        record("SDK_COMPONENT_ACK_RETURNED")
      }
    }) else capture.start()
    def completionOutcome: String = completion.value match {
      case Some(scala.util.Success(_)) => "success"
      case Some(scala.util.Failure(_)) => "failed"
      case None => "pending"
    }
    record("PROBE_SCOPE", JsObject("sdkOnly" -> JsBoolean(sdkOnly),
      "LAndJournalSinkProof" -> JsBoolean(!sdkOnly)))
    record("QUIET_SOURCE_START", JsObject("status" -> withConnection(source, schema)(query(_, "SHOW BINARY LOG STATUS")),
      "rows" -> withConnection(source, schema)(query(_, "SELECT * FROM a ORDER BY id"))))
    val quietSeconds = sys.env.getOrElse("CAPTURE_PROBE_QUIET_SECONDS", "10").toInt
    require(quietSeconds >= 10 && quietSeconds <= 90)
    val quietEnd = System.nanoTime() + quietSeconds.seconds.toNanos
    var last = ""
    var lastAdmission = ""
    var verified = false
    while (System.nanoTime() < quietEnd && capture.failure.isEmpty) {
      val d = checkedOffsets(); val m = metrics()
      val signature = JsObject("D" -> d, "metrics" -> m).compactPrint
      if (signature != last) { record("QUIET_OBSERVATION", JsObject("D" -> d, "metrics" -> m)); last = signature }
      d.elements.foreach { r =>
        val value = r.asJsObject.fields("offset_val").convertTo[String]
        val f = value.parseJson.asJsObject.fields
        if (f.contains("file") && f.contains("pos") && f.get("snapshot").forall(v => v == JsBoolean(false) || v == JsString("false"))) {
          try {
            if (!sdkOnly) store.verifyOffset(value)
            verified = true; record(if (sdkOnly) "SDK_D_OBSERVED" else "D_VERIFIED", JsObject("D" -> value.parseJson,
              "offset_key" -> r.asJsObject.fields("offset_key").convertTo[String].parseJson))
          }
          catch { case e: Exception => record("D_UNVERIFIED", JsObject("error" -> JsString(e.toString))) }
        }
      }
      if(!sdkOnly) {
        val e=capture.startupEvidence
        val v=JsObject("phase"->JsString(e.phase),"reason"->e.reasonCode.map(JsString(_)).getOrElse(JsNull),
          "attempt"->JsString(e.runtimeAttemptId),"floor"->JsNumber(e.ackFloor),"durableAck"->JsNumber(e.durableAck),"confirmedAck"->JsNumber(e.confirmedAck))
        if(v.compactPrint!=lastAdmission){record("PRODUCTION_ADMISSION",v);lastAdmission=v.compactPrint}
      }
      Thread.sleep(250)
    }
    record("QUIET_END", JsObject("verifiedNonInitialD" -> JsBoolean(verified), "D" -> offsets(), "metrics" -> metrics(),
      "completionOutcome" -> JsString(completionOutcome),
      "sourceStatus" -> withConnection(source, schema)(query(_, "SHOW BINARY LOG STATUS")),
      "sourceRows" -> withConnection(source, schema)(query(_, "SELECT * FROM a ORDER BY id")),
      "captureFailure" -> capture.failure.map(e => JsString(e.toString)).getOrElse(JsNull)))
    var kit: ActorTestKit = null
    try {
      if(!sdkOnly && sys.env.get("CAPTURE_REQUIRE_SKIP_REFUSAL").contains("1")) {
        val e=capture.startupEvidence
        val before=withConnection(source,schema)(query(_,"SELECT * FROM pekko_capture_control ORDER BY operation_id"))
        val request="quiet_probe"
        require(events.iterator().asScala.exists(_.fields.get("kind").contains(JsString("TEST_CALLBACK_HELD"))),
          "first callback gate was not reached")
        require(e.snapshot=="SKIPPED" && e.loadedCheckpoint=="COMPLETE" && e.phase=="STARTING" && e.confirmedAck<=e.ackFloor,
          "quiet skipped restart did not reach the no-current-ACK window")
        val attempted=scala.util.Try(capture.subscribe(request,"a","g2"))
        val after=withConnection(source,schema)(query(_,"SELECT * FROM pekko_capture_control ORDER BY operation_id"))
        val code=attempted.failed.toOption.collect {case x:CaptureAdmissionException=>x.code}
        record("QUIET_SKIP_REFUSAL",JsObject("snapshot"->JsString(e.snapshot),"loadedCheckpoint"->JsString(e.loadedCheckpoint),
          "phase"->JsString(e.phase),"ackFloor"->JsNumber(e.ackFloor),"confirmedAck"->JsNumber(e.confirmedAck),
          "code"->code.map(JsString(_)).getOrElse(JsNull),"beforeControls"->before,"afterControls"->after,
          "subscriptionAbsent"->JsBoolean(store.subscription(request).isEmpty),"D"->checkedOffsets(),"L"->store.evidence))
        require(code.contains("CAPTURE_STARTING") && before==after && store.subscription(request).isEmpty,
          "no-current-ACK subscription was not refused without effects")
      }
      if (!sdkOnly && verified && sys.env.get("CAPTURE_PROBE_WITNESS").contains("1")) {
        capture.subscribe("a", "a", "g1")
        awaitCondition("B bound", 20.seconds)(store.subscription("a").exists(_.b.nonEmpty))
        val actorConfig = ConfigFactory.parseMap(Map[String, AnyRef](
          "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
          "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
          "pekko-persistence-jdbc.shared-databases.slick.db.url" -> state.url(schema),
          "pekko-persistence-jdbc.shared-databases.slick.db.user" -> state.user,
          "pekko-persistence-jdbc.shared-databases.slick.db.password" -> state.password,
          "pekko.loglevel" -> "WARNING", "pekko.stdout-loglevel" -> "WARNING").asJava).withFallback(ConfigFactory.load("application-test"))
        kit = ActorTestKit("admission-" + captureId, actorConfig)
        implicit val ec: ExecutionContext = kit.system.executionContext
        val log = new CapturedLogSource(store, schema)
        val registry = NodeRegistry.builtins().withSource(log).withSink(new MySQLCdcApplySinkNode)
        val engine = new WorkflowExecutionEngine(nodeRegistry = registry)(kit.system, ec)
        val sourceNode = Node("source", "source", "mysql.cdc.log", "log", Position(0, 0),
          JsObject("subscriptionId" -> JsString("a"), "initializedTarget" -> JsBoolean(true), "batchSize" -> JsNumber(128)))
        val sinkNode = Node("target", "sink", "mysql.cdc.apply", "target", Position(1, 0), JsObject(
          "host" -> JsString(state.host), "port" -> JsNumber(state.port), "database" -> JsString(schema), "table" -> JsString("target_a"),
          "username" -> JsString(state.user), "passwordEnv" -> JsString("CDC_WINDOW_MYSQL_PASSWORD")))
        val w = Workflow("probe-" + captureId, "probe", "known baseline", "1", "test", Nil, List(sourceNode, sinkNode),
          List(Edge("edge", "source", "target")), WorkflowMetadata("test", "test"))
        val actor = kit.spawn(EventSourcedWorkflowActor(w.id, engine)(ec))
        val reply = kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
        actor ! EventSourcedWorkflowActor.DefineWorkflow(w, 0, reply.ref)
        require(reply.receiveMessage(20.seconds).isInstanceOf[EventSourcedWorkflowActor.Defined])
        actor ! EventSourcedWorkflowActor.ExecuteManual("start", reply.ref)
        require(reply.receiveMessage(20.seconds).isInstanceOf[EventSourcedWorkflowActor.ExecutionAccepted])
        sql(source, "UPDATE a SET label='probe-witness',revision=revision+1,amount=amount+0.1250,note='',updated_at='2026-10-26 01:02:03.123456' WHERE id=1")
        def rows(s: Settings, t: String) = withConnection(s, schema)(query(_, s"SELECT * FROM $t ORDER BY id"))
        awaitCondition("full-field witness", 30.seconds)(rows(source, "a") == rows(state, "target_a"))
        val summary = kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        awaitCondition("Actor checkpoint", 20.seconds) {
          actor ! EventSourcedWorkflowActor.GetSummary(summary.ref)
          summary.receiveMessage(5.seconds).currentExecution.exists(_.checkpoints.nonEmpty)
        }
        record("WITNESS", JsObject("source" -> rows(source, "a"), "target" -> rows(state, "target_a"),
          "T" -> withConnection(state, schema)(query(_, "SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
          "J" -> withConnection(state, schema)(query(_, "SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering")),
          "L" -> store.evidence, "D" -> offsets()))
        // Stop the table by its original K boundary before terminating its test ActorSystem.
        capture.unsubscribe("a")
        awaitCondition("K bound", 20.seconds)(store.subscription("a").exists(_.k.nonEmpty))
        if(sys.env.get("CAPTURE_REQUIRE_K_DURABLE").contains("1")){
          val k=store.barrierSequence("a",true).get
          def dChainReferencesK(value:String):Boolean={
            val head=store.verifiedOffsetSequence(value)
            val frames=store.evidence.fields("log").asInstanceOf[JsArray].elements.map(_.asJsObject.fields)
              .map(f=>f("seq").convertTo[String].toLong->f).toMap
            var cursor=Option(head);var found=false;var visited=Set.empty[Long]
            while(cursor.nonEmpty && !found){
              val seq=cursor.get;require(!visited(seq),"ACK cycle");visited+=seq
              val payload=frames(seq)("payload").convertTo[String].parseJson.asJsObject.fields
              found=payload("references").asInstanceOf[JsArray].elements.exists(_.asJsObject.fields("sequence")==JsNumber(k))
              cursor=payload("parent") match {case JsNull=>None;case x:JsObject=>Some(x.fields("sequence").asInstanceOf[JsNumber].value.toLongExact)}
            }
            found
          }
          awaitCondition("actual D covers K",90.seconds){
            val d=checkedOffsets().elements
            d.size==1 && scala.util.Try(dChainReferencesK(d.head.asJsObject.fields("offset_val").convertTo[String])).getOrElse(false)
          }
          record("K_DURABLE_D",JsObject("KSequence"->JsNumber(k),"D"->checkedOffsets(),"L"->store.evidence))
        }
      }
    } finally {
      factory.releaseGate()
      if (kit != null) kit.shutdownTestKit()
      val closing = Executors.newSingleThreadExecutor((r: Runnable) => { val t = new Thread(r, "probe-close"); t.setDaemon(true); t })
      val f = closing.submit(new Runnable { def run(): Unit = {
        record("CLOSE_BEGIN")
        try { if (sdkOnly) sdk.close() else capture.close(); record("CLOSE_RETURNED") }
        catch { case e: Throwable => record("CLOSE_FAILED", JsObject("error" -> JsString(e.toString))); throw e }
      }})
      closing.shutdown()
      try f.get(10, TimeUnit.SECONDS) catch { case e: Exception => record("CLOSE_WAIT_UNKNOWN", JsObject("error" -> JsString(e.toString))) }
      record("LOCAL_END", JsObject("completionCompleted" -> JsBoolean(completion.isCompleted),
        "completionOutcome" -> JsString(completionOutcome),
        "threads" -> JsArray(Thread.getAllStackTraces.keySet().asScala.toVector.filter(t => t.isAlive &&
          (t.getName.contains("debezium") || t.getName.contains("binlog") || t.getName == "probe-close")).map(t => JsString(t.getName))),
        "metrics" -> metrics()))
    }
  }
}
