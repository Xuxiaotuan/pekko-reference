package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.workflow.bootstrap._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.tags.ExternalIntegration
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.nio.file.{Files, Path}
import java.sql.{DriverManager, SQLException}
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

/** A real RR view is lost after a durable RAW page. Cache reconstruction is explicit test setup. */
class BootstrapStepViewRecoverySpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "same-owner bounded bootstrap reconstruction" should {
    "discard an unsealed real read view without spending another recovery attempt" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CDC_PLATFORM_MATRIX").contains("1")) cancel("exclusive dual-MySQL platform fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val source = Settings.environment(); val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
      require(!Set(3306, 13365, 13366).contains(source.port) && !Set(3306, 13365, 13366).contains(state.port))
      def identity(s: Settings) = withConnection(s, "")(query(_, "SELECT @@server_uuid AS uuid,@@port AS port,@@datadir AS datadir"))
      val sourceIdentity = identity(source); val stateIdentity = identity(state)
      val lineage = sourceIdentity.elements.head.asJsObject.fields("uuid").convertTo[String]
      val stateLineage = stateIdentity.elements.head.asJsObject.fields("uuid").convertTo[String]
      require(lineage == sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateLineage == sys.env("CAPTURE_EXPECT_STATE_UUID") && lineage != stateLineage)
      val suffix = UUID.randomUUID().toString.replace("-", "").take(12); val schema = "pekko_test_cdc_window_" + suffix; val captureId = "step_" + suffix
      val dir = Path.of(sys.env.getOrElse("CDC_PLATFORM_EVIDENCE", "target/cdc-platform-matrix"), "P09-view-recovery-" + suffix).toAbsolutePath
      Files.createDirectories(dir)
      write(dir, "environment.json", JsObject("source" -> sourceIdentity, "state" -> stateIdentity,
        "sourceManifestSha256" -> JsString(sys.env.getOrElse("BOOTSTRAP_SOURCE_MANIFEST_SHA256", "unfrozen")),
        "injection" -> JsString("SQLException 08006 from observer after actual RAW page save; not a network disconnect")))
      initialize(source, schema); initialize(state, schema)
      def sql(s: Settings, text: String): Unit = withConnection(s, schema)(executeSql(_, text))
      sql(source, "CREATE TABLE a LIKE source_rows"); sql(source, "INSERT INTO a SELECT * FROM source_rows"); sql(state, "CREATE TABLE target_a LIKE source_rows")
      withConnection(state, schema) { c =>
        val input = getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
        val ddl = try scala.io.Source.fromInputStream(input).mkString finally input.close()
        ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c, _))
      }
      val sourceUser = "cv_" + suffix; val stateUser = "dv_" + suffix
      def account(s: Settings, username: String, sourceRole: Boolean): Unit = withConnection(s, "") { c =>
        val password = s.password.replace("\\", "\\\\").replace("'", "\\'")
        executeSql(c, s"CREATE USER '$username'@'%' IDENTIFIED BY '$password'")
        executeSql(c, s"GRANT ALL PRIVILEGES ON `$schema`.* TO '$username'@'%'")
        if (sourceRole) executeSql(c, s"GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '$username'@'%'")
      }
      account(source, sourceUser, sourceRole = true); account(state, stateUser, sourceRole = false)
      def stateOpen() = DriverManager.getConnection(state.url(schema), state.user, state.password)
      def sourceOpen() = DriverManager.getConnection(source.url(schema), sourceUser, source.password)
      val config = ConfigFactory.parseMap(Map[String, AnyRef]("pekko.actor.provider" -> "local",
        "pekko.remote.artery.canonical.port" -> Int.box(0), "pekko.cluster.seed-nodes" -> java.util.Collections.emptyList[String](),
        "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
        "pekko-persistence-jdbc.shared-databases.slick.db.url" -> state.url(schema),
        "pekko-persistence-jdbc.shared-databases.slick.db.user" -> state.user,
        "pekko-persistence-jdbc.shared-databases.slick.db.password" -> state.password).asJava).withFallback(ConfigFactory.load("application-test"))
      val kit = ActorTestKit("view-" + suffix, config)
      implicit val ec: scala.concurrent.ExecutionContext = kit.system.executionContext
      implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = kit.system.scheduler
      val events = new ConcurrentLinkedQueue[JsObject](); val failOnce = new AtomicBoolean(true)
      def event(kind: String, value: JsObject): Unit = {
        events.add(JsObject(value.fields + ("kind" -> JsString(kind)))); emit(kind, value.fields.toSeq: _*)
        if (kind == "RAW_PAGE" && failOnce.compareAndSet(true, false)) throw new SQLException("injected after actual RAW page", "08006")
      }
      val sdkState = MySQLCdcStateConfig(state.url(schema), stateUser, state.password, "capture_offsets", "capture_history", 0)
      val log = new MySQLCaptureStore(() => stateOpen(), captureId, lineage, 128L * 1024 * 1024)
      val observer = CaptureResourceObserver.exclusiveAccounts(
        () => DriverManager.getConnection(source.url(schema), source.user, source.password), () => stateOpen(), sourceUser, stateUser, lineage, stateLineage)
      val capture = new SharedMySQLCapture(MySQLCdcSourceConfig(captureId, source.host, source.port, schema, "a", sourceUser, source.password,
        (captureId.hashCode.toLong & 0x7fffffffL) + 100000L, 128, 100), sdkState, log, new CaptureObservedFactory(event), event, resourceObserver = Some(observer))
      var firstJournal: org.apache.pekko.actor.typed.ActorRef[BootstrapJournal.Command] = null
      var secondJournal: org.apache.pekko.actor.typed.ActorRef[BootstrapJournal.Command] = null
      val id = "view_recovery"
      val spool = new BootstrapStore(() => stateOpen(), id, 64L * 1024 * 1024)
      val admission = new BootstrapAdmission(1, 1, 2)
      def progress(p: BootstrapProgress): JsValue = JsObject("phase" -> JsString(p.phase), "revision" -> JsNumber(p.revision),
        "executionId" -> JsString(p.executionId), "recoveryAttempts" -> JsNumber(p.recoveryAttempts), "attempt" -> JsNumber(p.attempt),
        "window" -> p.window.map(JsString(_)).getOrElse(JsNull))
      def snapshot(p: BootstrapProgress): JsObject = JsObject("progress" -> progress(p), "L" -> log.evidence,
        "D" -> withConnection(state, schema)(query(_, s"SELECT offset_key,offset_val FROM `${MySQLCdcSourceNode.connectorStateTables(sdkState, captureId)._1}`")),
        "J" -> withConnection(state, schema)(query(_, "SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering")),
        "T" -> withConnection(state, schema)(query(_, "SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
        "baselineT" -> withConnection(state, schema)(query(_, "SELECT * FROM pekko_bootstrap_receipts ORDER BY bootstrap_id,operation_id")),
        "spool" -> spool.evidence, "source" -> withConnection(source, schema)(query(_, "SELECT * FROM a ORDER BY id")),
        "target" -> withConnection(state, schema)(query(_, "SELECT * FROM target_a ORDER BY id")), "events" -> JsArray(events.iterator().asScala.toVector))
      try {
        log.initialize(); capture.start()
        awaitCondition("actual capture admission", 90.seconds)(capture.startupEvidence.phase == "ADMISSION_READY")
        val codec = new BootstrapRowCodec(log.schema("a").get.definition)
        val target = new BootstrapTarget(() => stateOpen(), "target_a", codec, id)
        val node = Node("target", "sink", "mysql.cdc.apply", "real target", Position(0, 0), JsObject("host" -> JsString(state.host),
          "port" -> JsNumber(state.port), "database" -> JsString(schema), "table" -> JsString("target_a"), "username" -> JsString(state.user), "passwordEnv" -> JsString("CDC_WINDOW_MYSQL_PASSWORD")))
        def runner(access: BootstrapJournalAccess) = new BootstrapRunner(id, "a", "g1", "same-owner-view-recovery", capture, log, spool, target,
          access, new BootstrapSnapshot(() => sourceOpen(), "a", codec, 10000, 8L * 1024 * 1024, event),
          new BootstrapReplaySink(_ => stateOpen()), node, schema, () => stateOpen(), admission, BootstrapLimits(chunkRows = 16, pageRows = 4), event)(ExecutionContext.parasitic)
        firstJournal = kit.spawn(BootstrapJournal(captureId + "-" + id), "first")
        val first = runner(new BootstrapJournalAccess(firstJournal)); var cut: BootstrapStepResult = null
        awaitCondition("actual RAW page failure returned a clean step", 90.seconds) { cut = first.step(); cut.progress.recoveryAttempts == 1 }
        cut.releaseConfirmed shouldBe true; cut.progress.phase shouldBe "SCANNING"; cut.progress.attempt shouldBe 0
        val oldWindow = cut.progress.window.get; spool.manifest(oldWindow) shouldBe None; spool.pageCount(oldWindow, "RAW") shouldBe 1
        write(dir, "after-raw-failure.json", snapshot(cut.progress))
        val readId = events.iterator().asScala.find(_.fields.get("kind").contains(JsString("RAW_PAGE"))).get.fields("connectionId").convertTo[String]
        withConnection(source, schema)(query(_, s"SELECT trx_id FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id=$readId")).elements shouldBe empty
        sql(source, "UPDATE a SET label='after-eviction',amount=amount+0.1250,revision=revision+1,note='',updated_at='2026-10-27 04:05:06.123456' WHERE id=1")
        sql(source, "DELETE FROM a WHERE id=16")
        kit.stop(firstJournal); firstJournal = null
        secondJournal = kit.spawn(BootstrapJournal(captureId + "-" + id), "second")
        val second = runner(new BootstrapJournalAccess(secondJournal)); var hint: Option[BootstrapProgress] = Some(cut.progress); var last: BootstrapStepResult = null
        awaitCondition("reconstructed same-owner Runner reaches durable STREAMING", 180.seconds) {
          last = second.step(continuation = hint); hint = None
          last.progress.recoveryAttempts shouldBe 1
          last.progress.phase should not be "PAUSED"
          last.progress.phase == "STREAMING"
        }
        last.progress.executionId shouldBe cut.progress.executionId
        val replacement = id + "_0_1"
        spool.spec(replacement) shouldBe spool.spec(oldWindow)
        spool.pageCount(replacement, "RAW") shouldBe 4
        spool.page(replacement, "RAW", 0) should not be spool.page(oldWindow, "RAW", 0)
        events.iterator().asScala.find(e => e.fields.get("kind").contains(JsString("READ_OPEN")) && e.fields.get("window").contains(JsString(replacement)))
          .get.fields("connectionId") should not be JsString(readId)
        events.iterator().asScala.exists(e => e.fields.get("kind").contains(JsString("ABANDONED")) && e.fields.get("window").contains(JsString(oldWindow))) shouldBe true
        awaitCondition("all source/target fields equal", 90.seconds) {
          withConnection(source, schema)(query(_, "SELECT * FROM a ORDER BY id")) == withConnection(state, schema)(query(_, "SELECT * FROM target_a ORDER BY id"))
        }
        write(dir, "final.json", snapshot(last.progress))
      } catch { case e: Throwable =>
        write(dir, "failure.json", JsObject("errorClass" -> JsString(e.getClass.getSimpleName), "events" -> JsArray(events.iterator().asScala.toVector),
          "message" -> JsString(Option(e.getMessage).getOrElse("").replace(source.password, "[REDACTED]"))))
        throw e
      } finally {
        try {
          if (firstJournal != null) kit.stop(firstJournal)
          if (secondJournal != null) kit.stop(secondJournal)
          Await.result(capture.requestClose(), 15.seconds)
          awaitCondition("capture actually reusable after natural server release", 180.seconds) {
            if (!capture.releaseEvidence.reusable) Await.result(capture.refreshRelease(), 15.seconds)
            capture.releaseEvidence.reusable
          }
          val closed = capture.releaseEvidence
          write(dir, "release.json", JsObject("localTermination" -> JsString(closed.localTermination), "serverRelease" -> JsString(closed.serverRelease),
            "ownerReleased" -> JsBoolean(closed.ownerReleased), "reusable" -> JsBoolean(closed.reusable), "operationId" -> JsString(closed.operationId)))
        } finally kit.shutdownTestKit()
      }
    }
  }
}
