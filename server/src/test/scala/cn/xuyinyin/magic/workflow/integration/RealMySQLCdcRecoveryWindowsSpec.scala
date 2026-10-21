package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._
import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration._

/** Seven historical windows; original C2 failure evidence stays immutable in docs/experiments.
  * Disposable MySQL only. Raw records/ACKs, target ledger/audit, real serialized Actor Journal,
  * durable D and independent six-field table comparisons are retained per scenario.
  */
class RealMySQLCdcRecoveryWindowsSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._

  "the remaining real complete-group recovery windows" should {
    for ((scenario, originalSize, recoveredSize) <- Vector(
      ("N0",16,16), ("J1",16,1), ("J2",1,128), ("C1",16,1),
      ("C0",16,1), ("K1",1,1), ("K2",1,128))) {
      s"retain independent T/J/D and all fields for $scenario" taggedAs ExternalIntegration in {
        if (!sys.env.get("RUN_MYSQL_CDC_RECOVERY_WINDOWS").contains("1"))
          cancel("external_blocked: isolated loopback MySQL required")
        val settings = Settings.environment()
        val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
        val schema = s"pekko_test_cdc_window_$suffix"
        val connector = s"groups_$suffix"
        val dir = Path.of("target", "cdc-recovery-windows", s"$scenario-$suffix").toAbsolutePath
        Files.createDirectories(dir)
        initialize(settings, schema)
        withConnection(settings, schema) { c =>
          val input = getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          val sql = try scala.io.Source.fromInputStream(input).mkString finally input.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c, _))
        }
        val lineage = withConnection(settings, schema)(query(_, "SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        write(dir, "environment.json", JsObject("schema" -> JsString(schema), "connector" -> JsString(connector),
          "lineage" -> JsString(lineage), "scenario" -> JsString(scenario), "originalBatchSize" -> JsNumber(originalSize),
          "recoveredBatchSize" -> JsNumber(recoveredSize), "timezone" -> JsString(java.util.TimeZone.getDefault.getID),
          "mysql" -> withConnection(settings, schema)(query(_, "SELECT VERSION() AS version, @@binlog_format AS format, @@binlog_row_image AS row_image, @@gtid_mode AS gtid_mode"))))
        var bootstrap: Child = null; var original: Child = null; var recovered: Child = null
        var offsetBlocker: java.sql.Connection = null
        def start(mode: String, size: Int, fault: String, log: String): Child = {
          val loaders = Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l => Option(l.getParent)))
            .takeWhile(_.nonEmpty).flatten.collect { case l: URLClassLoader => l.getURLs.iterator }.flatten
            .filter(_.getProtocol == "file").map(u => Path.of(u.toURI).toString)
          val cp = (loaders ++ System.getProperty("java.class.path", "").split(File.pathSeparator).iterator)
            .filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString,
            "-Xmx512m", "-Duser.timezone=UTC", "-cp", cp, RealMySQLCdcGroupRecoveryProcess.getClass.getName.stripSuffix("$"),
            mode, schema, connector, size.toString, fault, lineage)
          builder.redirectError(dir.resolve(log + ".stderr").toFile)
          builder.environment().put("CDC_WINDOW_MYSQL_HOST", settings.host)
          builder.environment().put("CDC_WINDOW_MYSQL_PORT", settings.port.toString)
          builder.environment().put("CDC_WINDOW_MYSQL_USER", settings.user)
          builder.environment().put("CDC_WINDOW_MYSQL_PASSWORD", settings.password)
          new Child(builder.start(), dir.resolve(log))
        }
        def offsetMatches(j: JsObject): Boolean = offsetState(settings, schema, connector).elements.exists(r =>
          r.asJsObject.fields("offset_val").convertTo[String].parseJson == journalCheckpoint(j).fields("cursor").asJsObject.fields("offset"))
        def groupEvidence(child: Child): JsObject = JsObject("T" -> withConnection(settings, schema)(query(_, "SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
          "J" -> child.state(), "D" -> offsetState(settings, schema, connector), "comparison" -> comparison(settings, schema),
          "audit" -> withConnection(settings, schema)(query(_, "SELECT * FROM target_audit ORDER BY audit_id")))
        try {
          // Establish S0 and a durable streaming D independently of the new group generation.
          bootstrap = start("bootstrap", 16, "none", "bootstrap.log")
          bootstrap.awaitEvent("PROCESS_READY")
          awaitCondition("trusted initial target equality", 180.seconds) { comparison(settings, schema).fields("equal") == JsBoolean(true) }
          withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET label='trusted-streaming-baseline' WHERE id=64"))
          awaitCondition("bootstrap ACK and durable streaming D") {
            comparison(settings, schema).fields("equal") == JsBoolean(true) && offsetMatches(bootstrap.state()) &&
              journalCheckpoint(bootstrap.state()).fields("cursor").asJsObject.fields("offset").asJsObject.fields.get("snapshot")
                .forall(v => v == JsBoolean(false) || v == JsString("false"))
          }
          write(dir, "trusted-baseline.json", evidence(settings, schema, connector, bootstrap.state()))
          bootstrap.send("CRASH"); bootstrap.exit() shouldBe 86
          original = start("fresh", originalSize, scenario, "original.log")
          original.awaitEvent("PROCESS_READY")
          original.awaitEvent("ENGINE_CONFIG")
          // Start v2 with one ordinary streaming group, and wait for actual D (ACK return alone is insufficient).
          withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET note='v2-baseline' WHERE id=64"))
          awaitCondition("v2 baseline T/J/D agree") {
            val state = original.state()
            state.fields("journal").asInstanceOf[JsArray].elements.exists(_.asJsObject.fields("checkpoint") != JsNull) &&
              comparison(settings, schema).fields("equal") == JsBoolean(true) && offsetMatches(state)
          }
          val baseline = groupEvidence(original); write(dir, "baseline.json", baseline)
          val jBefore = journalCheckpoint(baseline.fields("J").asJsObject)
          val pkOnly = scenario.startsWith("K")
          if (Set("J2", "K2").contains(scenario)) {
            offsetBlocker = java.sql.DriverManager.getConnection(settings.url(schema), settings.user, settings.password)
            offsetBlocker.setAutoCommit(false)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(connector.getBytes("UTF-8"))
              .map(b => f"${b & 0xff}%02x").mkString.take(32)
            query(offsetBlocker, s"SELECT offset_key,offset_val FROM debezium_offset_storage_$digest FOR UPDATE").elements.nonEmpty shouldBe true
            write(dir, "offset-blocker.json", JsObject("connection" -> query(offsetBlocker, "SELECT CONNECTION_ID() AS id"),
              "lockedOffset" -> baseline.fields("D")))
          }
          original.send("ARM"); original.awaitEvent("ARMED")
          val workload = if (pkOnly) Vector("UPDATE source_rows SET id=150,label='pk-window-🙂' WHERE id=50")
            else Vector("UPDATE source_rows SET revision=1,label=CONCAT('window-',id),amount=amount+0.0100," +
              "note=IF(MOD(id,2)=0,NULL,'窗口-🙂'),updated_at='2026-10-19 12:34:56.123456'",
              "UPDATE source_rows SET label='repeat-a' WHERE id=1", "UPDATE source_rows SET label='repeat-b' WHERE id=1",
              "UPDATE source_rows SET label='repeat-a' WHERE id=1", "UPDATE source_rows SET label='repeat-b' WHERE id=1",
              "UPDATE source_rows SET id=150 WHERE id=50")
          Files.writeString(dir.resolve("workload.sql"), workload.mkString("BEGIN;\n", ";\n", ";\nCOMMIT;\n"))
          withConnection(settings, schema) { c => c.setAutoCommit(false); workload.foreach(executeSql(c, _)); c.commit() }
          val cut = if (scenario == "N0") {
            awaitCondition("normal workload converges", 180.seconds) {
              comparison(settings,schema).fields("equal") == JsBoolean(true) && offsetMatches(original.state())
            }
            JsObject("kind" -> JsString("NO_FAULT_CONTROL"))
          } else original.awaitEvent("CUTPOINT")
          write(dir,"cut-event.json",cut)
          if (scenario == "C0") {
            val id = cut.fields("connectionId").convertTo[Long]
            awaitCondition("unforwarded COMMIT transaction has rolled back") {
              withConnection(settings,schema)(query(_,s"SELECT trx_id FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=$id")).elements.isEmpty
            }
          }
          val atCut = groupEvidence(original); write(dir,"cutpoint.json",atCut)
          if (Set("J2", "K2").contains(scenario)) {
            val waits = withConnection(settings,schema)(query(_,s"SELECT p.ID,p.STATE,p.INFO,t.trx_id,t.trx_state FROM information_schema.PROCESSLIST p JOIN information_schema.INNODB_TRX t ON t.trx_mysql_thread_id=p.ID WHERE p.DB='$schema' AND p.ID <> CONNECTION_ID() AND p.INFO LIKE '%debezium_offset_storage%'"))
            write(dir,"offset-write-waits.json",JsObject("waitingTransactions" -> waits))
            waits.elements.exists(_.asJsObject.fields("trx_state") == JsString("LOCK WAIT")) shouldBe true
          }
          val jAtCut = journalCheckpoint(atCut.fields("J").asJsObject)
          val oldRaw = original.events.filter(_.fields("kind") == JsString("RAW_ACK_RETURNED"))
          val cutAudit = atCut.fields("audit").asInstanceOf[JsArray].elements
          val baseAudit = baseline.fields("audit").asInstanceOf[JsArray].elements
          if (Set("C0","C1").contains(scenario)) jAtCut shouldBe jBefore
          if (Set("J1","J2","K1","K2").contains(scenario)) jAtCut.fields("sequence").convertTo[Long] should be > jBefore.fields("sequence").convertTo[Long]
          if (scenario == "C0") { atCut.fields("T") shouldBe baseline.fields("T"); cutAudit shouldBe baseAudit }
          else atCut.fields("T").asInstanceOf[JsArray].elements.size should be > baseline.fields("T").asInstanceOf[JsArray].elements.size
          if (scenario != "N0" && scenario != "K1") atCut.fields("D") shouldBe baseline.fields("D")
          if (scenario == "K1") { atCut.fields("D") should not be baseline.fields("D"); offsetMatches(atCut.fields("J").asJsObject) shouldBe true }
          if (Set("K1","K2").contains(scenario)) {
            val raw = original.events.filter(_.fields("kind") == JsString("RAW_CALLBACK"))
            val deletion = raw.find(e => e.fields("records").asInstanceOf[JsArray].elements.exists(r =>
              r.compactPrint.contains("__debezium.newkey"))).get
            val a = deletion.fields("callback")
            val b = JsNumber(a.convertTo[Long]+1)
            raw.find(_.fields("callback") == b).get.fields("records").asInstanceOf[JsArray].elements.size shouldBe 1
            oldRaw.exists(_.fields("callback") == a) shouldBe true
            original.events.exists(e => e.fields("kind") == JsString("RAW_ACK_BEGIN") && e.fields("callback") == b) shouldBe false
            cutAudit.size shouldBe 68
          }
          if (scenario == "J1") original.events.filter(_.fields("kind") == JsString("ACK_RETURNED"))
            .exists(_.fields("batchId") == cut.fields("batch").asJsObject.fields("batchId")) shouldBe false
          original.send("CRASH"); original.exit() shouldBe 86
          if (offsetBlocker != null) {
            // Do not release the lock while an old asynchronous JDBC offset writer could still commit.
            awaitCondition("old offset writer connections disappear") {
              withConnection(settings,schema)(query(_,s"SELECT ID FROM information_schema.PROCESSLIST WHERE DB='$schema' AND INFO LIKE '%debezium_offset_storage%' AND INFO NOT LIKE '%PROCESSLIST%'")).elements.isEmpty
            }
            offsetBlocker.rollback(); offsetBlocker.close(); offsetBlocker = null
          }
          offsetState(settings,schema,connector) shouldBe atCut.fields("D")
          tailMutations(settings,schema)
          recovered = start("recover",recoveredSize,"none","recovered.log")
          recovered.awaitEvent("PROCESS_READY")
          var firstReplay: JsValue = JsNull
          recovered.awaitEvent("READER_RESUME").fields("checkpoint") shouldBe jAtCut
          firstReplay = recovered.awaitEvent("SOURCE_BATCH").fields("batch")
          firstReplay.asJsObject.fields("sequence").convertTo[Long] shouldBe jAtCut.fields("sequence").convertTo[Long]+1
          awaitCondition("all fields and durable D converge",180.seconds) {
            comparison(settings,schema).fields("equal") == JsBoolean(true) && offsetMatches(recovered.state())
          }
          val afterReplay = groupEvidence(recovered); write(dir,"after-replay.json",afterReplay)
          val audit = afterReplay.fields("audit").asInstanceOf[JsArray].elements
          audit.take(cutAudit.size) shouldBe cutAudit
          // Independently derive the total DML count from the workload SQL, not Source/Sink counters.
          audit.size shouldBe (if (pkOnly) 71 else 139)
          if (Set("K1","K2").contains(scenario)) {
            val added = audit.drop(cutAudit.size)
            added.exists(_.asJsObject.fields("row_id") == JsString("150")) shouldBe false
          }
          withConnection(settings,schema)(executeSql(_,"UPDATE source_rows SET revision=revision+1,note='recovery-followup-🙂' WHERE id=150"))
          awaitCondition("fresh data after recovery") { comparison(settings,schema).fields("equal") == JsBoolean(true) && offsetMatches(recovered.state()) }
          val result = groupEvidence(recovered)
          result.fields("comparison").asJsObject.fields("sourceCount") shouldBe JsNumber(64)
          result.fields("audit").asInstanceOf[JsArray].elements.size shouldBe (if (pkOnly) 72 else 140)
          write(dir,"final.json",result)
          write(dir,"outcome.json",JsObject("scenario" -> JsString(scenario),"injectionEstablished" -> JsBoolean(scenario != "N0"),
            "automaticRecovery" -> JsString("PASS"),"fullFieldConvergence" -> JsBoolean(true)))
          write(dir,"replay.json",JsObject("firstReplayBatch" -> firstReplay,"checkpointBefore" -> jBefore,
            "checkpointAtCut" -> jAtCut,"originalPid" -> JsNumber(original.pid),"recoveredPid" -> JsNumber(recovered.pid)))
          println(s"CDC_RECOVERY_WINDOW_OBSERVED scenario=$scenario evidence=$dir")
        } catch { case e: Throwable =>
          write(dir,"harness-failure.json",JsObject("error" -> JsString(e.toString))); throw e
        } finally {
          Vector(bootstrap,original,recovered).filter(_ != null).foreach(_.kill())
          if (offsetBlocker != null) { offsetBlocker.rollback(); offsetBlocker.close() }
        }
      }
    }
  }
}
