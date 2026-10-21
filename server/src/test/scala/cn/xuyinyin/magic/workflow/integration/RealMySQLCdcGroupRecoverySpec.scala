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

/** Disposable MySQL only. Raw records/ACKs, target ledger/audit, real serialized Actor Journal,
  * durable D and independent six-field table comparisons are retained per scenario.
  */
class RealMySQLCdcGroupRecoverySpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._

  "the production complete-group recovery protocol" should {
    "reject nontransactional target storage before applying group data" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_MYSQL_CDC_GROUP_RECOVERY").contains("1")) cancel("external_blocked: disposable MySQL required")
      val settings = Settings.environment()
      val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
      val schema = s"pekko_test_cdc_window_$suffix"
      initialize(settings, schema)
      val lineage = withConnection(settings, schema)(query(_, "SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
      withConnection(settings, schema) { c =>
        executeSql(c, "CREATE TABLE pekko_sync_cdc_group_ledger(group_id VARCHAR(64) PRIMARY KEY,evidence LONGTEXT,committed_at TIMESTAMP) ENGINE=InnoDB")
        executeSql(c, "ALTER TABLE target_rows ENGINE=MyISAM")
      }
      val connection = Map[String, JsValue]("host" -> JsString(settings.host), "port" -> JsNumber(settings.port),
        "database" -> JsString(schema), "username" -> JsString(settings.user), "passwordEnv" -> JsString("CDC_WINDOW_MYSQL_PASSWORD"))
      val source = cn.xuyinyin.magic.workflow.model.WorkflowDSL.Node("source", "source", "mysql.cdc", "source",
        cn.xuyinyin.magic.workflow.model.WorkflowDSL.Position(0,0), JsObject(connection ++ Map("table" -> JsString("source_rows"),
          "connectorId" -> JsString("groups_" + suffix), "serverId" -> JsNumber(54321), "maxBatchSize" -> JsNumber(16),
          "pollIntervalMillis" -> JsNumber(1000), "groupProtocol" -> JsObject("replicationId" -> JsString("groups_" + suffix),
            "generation" -> JsString("g1"), "sourceLineage" -> JsString(lineage), "initializedTarget" -> JsBoolean(true)))))
      val sink = source.copy(id = "sink", `type` = "sink", nodeType = "mysql.cdc.apply",
        config = JsObject(connection + ("table" -> JsString("target_rows"))))
      implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
      val boundary = scala.concurrent.Await.result(new cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcSourceNode()
        .discoverBoundary(source, None, _ => ()), 10.seconds)
      intercept[IllegalStateException](scala.concurrent.Await.result(new cn.xuyinyin.magic.connectors.mysql.sink.MySQLCdcApplySinkNode()
        .validateSourceBoundary(sink, boundary, _ => ()), 10.seconds)).getMessage should include("InnoDB")
    }

    for ((scenario, originalSize, recoveredSize) <- Vector(("split-gap", 16, 1), ("merge-gap", 1, 128), ("pk-gap", 1, 1))) {
      s"reconcile every field without replay side effects for $scenario" taggedAs ExternalIntegration in {
        if (!sys.env.get("RUN_MYSQL_CDC_GROUP_RECOVERY").contains("1"))
          cancel("external_blocked: set RUN_MYSQL_CDC_GROUP_RECOVERY=1 against disposable loopback MySQL")
        val settings = Settings.environment()
        val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
        val schema = s"pekko_test_cdc_window_$suffix"
        val connector = s"groups_$suffix"
        val dir = Path.of("target", "cdc-group-recovery", s"$scenario-$suffix").toAbsolutePath
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
          original = start("fresh", originalSize, "after-T", "original.log")
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
          original.send("ARM"); original.awaitEvent("ARMED")
          val workload = if (scenario == "pk-gap") Vector("UPDATE source_rows SET id=150,label='pk-window-🙂' WHERE id=50")
            else Vector("UPDATE source_rows SET revision=1,label=CONCAT('window-',id),amount=amount+0.0100," +
              "note=IF(MOD(id,2)=0,NULL,'窗口-🙂'),updated_at='2026-10-19 12:34:56.123456'", "UPDATE source_rows SET label='repeat-a' WHERE id=1", "UPDATE source_rows SET label='repeat-b' WHERE id=1",
              "UPDATE source_rows SET label='repeat-a' WHERE id=1", "UPDATE source_rows SET label='repeat-b' WHERE id=1",
              "UPDATE source_rows SET id=150 WHERE id=50")
          Files.writeString(dir.resolve("workload.sql"), workload.mkString("BEGIN;\n", ";\n", ";\nCOMMIT;\n"))
          withConnection(settings, schema) { c => c.setAutoCommit(false); workload.foreach(executeSql(c, _)); c.commit() }
          val cut = original.awaitEvent("CUTPOINT").fields("batch").asJsObject
          val atCut = groupEvidence(original); write(dir, "T-committed-J-missing.json", atCut)
          journalCheckpoint(atCut.fields("J").asJsObject) shouldBe jBefore
          atCut.fields("D") shouldBe baseline.fields("D")
          atCut.fields("T").asInstanceOf[JsArray].elements.size should be > baseline.fields("T").asInstanceOf[JsArray].elements.size
          original.events.filter(_.fields("kind") == JsString("ACK_RETURNED")).exists(_.fields("batchId") == cut.fields("batchId")) shouldBe false
          val auditAtCut = atCut.fields("audit").asInstanceOf[JsArray].elements
          original.send("CRASH"); original.exit() shouldBe 86
          offsetState(settings, schema, connector) shouldBe atCut.fields("D")
          if (scenario != "pk-gap") tailMutations(settings, schema)
          recovered = start("recover", recoveredSize, "none", "recovered.log")
          recovered.awaitEvent("PROCESS_READY")
          recovered.awaitEvent("READER_RESUME").fields("checkpoint") shouldBe jBefore
          val firstReplay = recovered.awaitEvent("SOURCE_BATCH").fields("batch").asJsObject
          firstReplay.fields("batchId") shouldBe cut.fields("batchId")
          if (scenario == "split-gap") firstReplay.fields("rows").asInstanceOf[JsArray].elements.size shouldBe 1
          if (scenario == "merge-gap") firstReplay.fields("rows").asInstanceOf[JsArray].elements.size should be > 1
          awaitCondition("full six-field convergence and durable D after recovery", 180.seconds) {
            comparison(settings, schema).fields("equal") == JsBoolean(true) && offsetMatches(recovered.state())
          }
          val afterReplay = groupEvidence(recovered); write(dir, "after-replay.json", afterReplay)
          // All DML effects already covered at the cut remain exactly once, including trigger side effects.
          val finalAudit = afterReplay.fields("audit").asInstanceOf[JsArray].elements
          finalAudit.take(auditAtCut.size) shouldBe auditAtCut
          val added = finalAudit.drop(auditAtCut.size)
          val cutEvents = cut.fields("rows").asInstanceOf[JsArray].elements.flatMap(_.asJsObject.fields("members").asInstanceOf[JsArray].elements)
          val cutEnvelopes = cutEvents.map(_.asJsObject.fields("envelope").asJsObject)
          cutEnvelopes.foreach { e =>
            val op = e.fields("op").convertTo[String]
            val image = e.fields(if (op == "d") "before" else "after").asJsObject
            val id = image.fields("id").compactPrint
            val label = image.fields("label")
            added.exists { a =>
              val f = a.asJsObject.fields
              f("row_id") == JsString(id) && f(if (op == "d") "old_label" else "new_label") == label
            } shouldBe false
          }
          if (scenario == "pk-gap") finalAudit shouldBe auditAtCut
          // Once recovered, a new write must still flow through T/J and durable D.
          withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET revision=revision+1,note='recovery-followup-🙂' WHERE id=150"))
          awaitCondition("new data after recovered ACK") {
            comparison(settings, schema).fields("equal") == JsBoolean(true) && offsetMatches(recovered.state())
          }
          val result = groupEvidence(recovered)
          result.fields("comparison").asJsObject.fields("sourceCount") shouldBe JsNumber(64)
          result.fields("audit").asInstanceOf[JsArray].elements.size shouldBe (if (scenario == "pk-gap") 69 else 140)
          write(dir, "final.json", result)
          write(dir, "replay.json", JsObject("cutBatch" -> cut, "firstReplayBatch" -> firstReplay,
            "originalPid" -> JsNumber(original.pid), "recoveredPid" -> JsNumber(recovered.pid),
            "cutMemberCount" -> JsNumber(cutEvents.size), "replayedEffects" -> JsNumber(0), "outcome" -> JsString("PASS")))
          println(s"CDC_GROUP_RECOVERY_PASS scenario=$scenario evidence=$dir")
        } catch { case e: Throwable =>
          write(dir, "harness-failure.json", JsObject("error" -> JsString(e.toString)))
          throw e
        } finally { Vector(bootstrap, original, recovered).filter(_ != null).foreach(_.kill()) }
      }
    }
  }
}
