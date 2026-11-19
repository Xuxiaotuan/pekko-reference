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

class RealMySQLSnapshotSplitSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  private val scenarios = Vector("BASELINE", "EMPTY", "WINDOWS", "PLAN", "REGISTER_BEFORE", "T_J", "EOF",
    "EOF_J_LOST", "STOP_T", "J_LOST", "COMMIT_LOST", "COMMIT_ABSENT", "REJECT_J", "RETRY_EXHAUSTED", "CHANGED", "PAGE_BYTES", "SNAPSHOT_RECOVERY", "WIDE_PAGE", "TARGET_CLOSE", "QUERY_TIMEOUT", "READ_T", "STOP_READ_T")
  "single-table snapshot splits with a real MySQL Journal and Sink" should {
    scenarios.foreach { scenario =>
      s"preserve fault and full-field evidence for $scenario" taggedAs ExternalIntegration in {
        if (!sys.env.get("RUN_MYSQL_SNAPSHOT_SPLITS").contains("1")) cancel("external_blocked: explicit isolated MySQL required")
        val settings = Settings.environment()
        val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
        val schema = s"pekko_test_cdc_window_$suffix"; val workflowId = s"snapshot-$suffix"
        val dir = Path.of("target", "snapshot-splits", s"$scenario-$suffix").toAbsolutePath
        Files.createDirectories(dir); initialize(settings, schema)
        withConnection(settings, schema) { c =>
          val in = getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-snapshot-batch-evidence-schema.sql")
          val sql = try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c, _))
          executeSql(c, "UPDATE source_rows SET id=id+1000000 ORDER BY id DESC")
          executeSql(c, "UPDATE source_rows SET id=(id-1000000)*5-25 ORDER BY id ASC")
          executeSql(c, "INSERT INTO source_rows VALUES (9007199254740993,'大主键🙂',-123.4500,'','2026-10-21 23:59:59.999999',3)")
          if (scenario == "EMPTY") executeSql(c, "DELETE FROM source_rows")
          if (scenario == "WIDE_PAGE") {
            executeSql(c, "ALTER TABLE source_rows MODIFY note LONGTEXT NULL")
            executeSql(c, "ALTER TABLE target_rows MODIFY note LONGTEXT NULL")
            executeSql(c, "UPDATE source_rows SET note=REPEAT('界',2097152) WHERE id=-20")
          }
        }
        write(dir, "environment.json", JsObject("schema" -> JsString(schema), "workflowId" -> JsString(workflowId),
          "scenario" -> JsString(scenario), "D" -> JsString("N/A: snapshot-only"),
          "mysql" -> withConnection(settings, schema)(query(_, "SELECT VERSION() AS version,@@server_uuid AS server_uuid"))))
        var original: Child = null; var recovered: Child = null
        def start(mode: String, name: String): Child = {
          val loaders = Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l => Option(l.getParent)))
            .takeWhile(_.nonEmpty).flatten.collect { case l: URLClassLoader => l.getURLs.iterator }.flatten
            .filter(_.getProtocol == "file").map(u => Path.of(u.toURI).toString)
          val cp = (loaders ++ System.getProperty("java.class.path", "").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString,
            "-Xmx512m", "-Duser.timezone=UTC", "-cp", cp, RealMySQLSnapshotSplitProcess.getClass.getName.stripSuffix("$"), mode, schema, workflowId, scenario)
          builder.redirectError(dir.resolve(name + ".stderr").toFile)
          builder.environment().put("CDC_WINDOW_MYSQL_HOST", settings.host); builder.environment().put("CDC_WINDOW_MYSQL_PORT", settings.port.toString)
          builder.environment().put("CDC_WINDOW_MYSQL_USER", settings.user); builder.environment().put("CDC_WINDOW_MYSQL_PASSWORD", settings.password)
          new Child(builder.start(), dir.resolve(name + ".log"))
        }
        def capture(child: Child): JsObject = JsObject("T" -> withConnection(settings, schema)(query(_, "SELECT * FROM pekko_sync_batch_ledger ORDER BY partition_id,batch_sequence")),
          "digests" -> withConnection(settings, schema)(query(_, "SELECT * FROM pekko_snapshot_batch_evidence ORDER BY batch_id")),
          "J" -> child.state(), "D" -> JsString("N/A: no binlog reader"), "comparison" -> comparison(settings, schema),
          "audit" -> withConnection(settings, schema)(query(_, "SELECT * FROM target_audit ORDER BY audit_id")))
        try {
          original = start("fresh", "original"); original.awaitEvent("PROCESS_READY")
          val kill = Set("PLAN", "REGISTER_BEFORE", "T_J", "SNAPSHOT_RECOVERY", "EOF", "CHANGED").contains(scenario)
          if (kill || Set("EOF_J_LOST", "STOP_T", "READ_T", "STOP_READ_T", "J_LOST", "COMMIT_LOST", "COMMIT_ABSENT").contains(scenario)) {
            original.awaitEvent("CUTPOINT")
            if (Set("READ_T", "STOP_READ_T").contains(scenario)) {
              original.awaitEvent("READ_FAILURE_DURING_T")
              // Let the stream failure be processed while the actual T result remains held.
              Thread.sleep(500)
              val early = original.events.filter(_.fields("kind") == JsString("J_CONFIRMED"))
              early.exists(e => Set("RetrySnapshotSplit", "BlockSnapshotSplit").contains(e.fields("update").convertTo[String])) shouldBe false
              early.flatMap(_.fields("state").asJsObject.fields("splits").asInstanceOf[JsArray].elements)
                .filter(_.asJsObject.fields("id").convertTo[String].endsWith(":0"))
                .forall(_.asJsObject.fields("sequence") == JsNull) shouldBe true
              write(dir, "before-release.json", capture(original))
            }
            if (Set("T_J", "EOF", "CHANGED").contains(scenario)) {
              awaitCondition("healthy sibling completed while split zero is held", 30.seconds) {
                original.events.filter(_.fields("kind") == JsString("J_CONFIRMED")).exists(_.fields("state").asJsObject.fields("splits")
                  .asInstanceOf[JsArray].elements.exists { p => val f = p.asJsObject.fields
                    !f("id").convertTo[String].endsWith(":0") && f("done") == JsBoolean(true) })
              }
            }
            val cut = capture(original); write(dir, "cut.json", cut)
            if (scenario == "SNAPSHOT_RECOVERY") {
              awaitCondition("durable entity snapshot exists", 10.seconds) {
                withConnection(settings, schema)(query(_, "SELECT sequence_number FROM snapshot")).elements.nonEmpty
              }
              write(dir, "snapshot-store.json", withConnection(settings, schema)(query(_, "SELECT sequence_number,snapshot_ser_id,snapshot_ser_manifest,HEX(snapshot_payload) AS payload_hex FROM snapshot")))
            }
            if (kill) {
              original.kill()
              if (scenario == "CHANGED") withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET note='violated-quiescent-contract' WHERE id=-20"))
              recovered = start("recover", "recovered"); recovered.awaitEvent("PROCESS_READY")
            } else if (Set("STOP_T", "STOP_READ_T").contains(scenario)) {
              if (scenario == "STOP_READ_T") {
                original.send("STOP")
                Thread.sleep(500)
                write(dir, "stopping-before-release.json", capture(original))
              }
              if (scenario != "STOP_READ_T") original.send("STOP")
              original.send("RELEASE"); original.send("WAIT_STOP")
              original.awaitEvent("OWNER_STOPPED").fields("allSourcePoolsClosed") shouldBe JsBoolean(true)
              original.kill()
              recovered = start("recover", "recovered"); recovered.awaitEvent("PROCESS_READY")
            } else if (scenario == "READ_T") original.send("RELEASE")
            else original.send(if (Set("J_LOST", "EOF_J_LOST").contains(scenario)) "DROP_J" else "DROP_RESPONSE")
          }
          val live = Option(recovered).getOrElse(original)
          val negative = Set("REJECT_J", "RETRY_EXHAUSTED", "CHANGED", "PAGE_BYTES", "WIDE_PAGE", "TARGET_CLOSE").contains(scenario)
          awaitCondition(if (negative) "durable safe pause" else "snapshot completion", (if (scenario == "EOF_J_LOST") 30.seconds else 120.seconds)) {
            val status = live.state().fields("status")
            if (!negative && status == JsString("snapshot_blocked")) throw new AssertionError("unexpected durable snapshot_blocked; see failure.json")
            status == JsString(if (negative) "snapshot_blocked" else "completed")
          }
          val finalEvidence = capture(live); write(dir, "final.json", finalEvidence)
          write(dir, "events.json", JsObject("original" -> JsArray(original.events), "recovered" -> JsArray(Option(recovered).toVector.flatMap(_.events))))
          val journal = finalEvidence.fields("J").asJsObject.fields("journal").asInstanceOf[JsArray]
          journal.elements.exists(_.asJsObject.fields("update") == JsString("InitializeSnapshotSplits")) shouldBe true
          if (negative) {
            finalEvidence.fields("J").asJsObject.fields("executionId") should not be JsNull
            val allEvents = original.events ++ Option(recovered).toVector.flatMap(_.events)
            val states = allEvents.filter(_.fields("kind") == JsString("J_CONFIRMED")).map(_.fields("state").asJsObject)
            val paused = states.flatMap(_.fields("splits").asInstanceOf[JsArray].elements).map(_.asJsObject.fields)
              .filter(_("blocked") != JsNull)
            paused should not be empty
            if (scenario == "REJECT_J") paused.exists(p => p("blocked") == JsString("protocol_conflict") && p("attempts") == JsNumber(0)) shouldBe true
            if (scenario == "RETRY_EXHAUSTED") paused.exists(p => p("attempts") == JsNumber(3)) shouldBe true
            if (scenario == "CHANGED") paused.exists(_("blocked") == JsString("protocol_conflict")) shouldBe true
            if (scenario == "TARGET_CLOSE") {
              paused.exists(_("blocked") == JsString("local_release_unconfirmed")) shouldBe true
              original.events.exists(_.fields("kind") == JsString("TARGET_CLOSE_OBSERVATION_FAILED")) shouldBe true
              val failureIndex = original.events.indexWhere(e => e.fields("kind") == JsString("J_CONFIRMED") &&
                e.fields("update") == JsString("BlockSnapshotSplit"))
              failureIndex should be >= 0
              original.events.drop(failureIndex + 1).exists(_.fields("kind") == JsString("READER_OPEN")) shouldBe false
            }
          } else {
            finalEvidence.fields("comparison").asJsObject.fields("equal") shouldBe JsBoolean(true)
            finalEvidence.fields("J").asJsObject.fields("allSourcePoolsClosed") shouldBe JsBoolean(true)
            val count = if (scenario == "EMPTY") 0 else 65
            finalEvidence.fields("audit").asInstanceOf[JsArray].elements.size shouldBe count
            finalEvidence.fields("T").asInstanceOf[JsArray].elements.size shouldBe finalEvidence.fields("digests").asInstanceOf[JsArray].elements.size
            if (scenario == "QUERY_TIMEOUT") original.events.exists(_.fields("kind") == JsString("SLOW_QUERY_INJECTED")) shouldBe true
            if (scenario == "READ_T") {
              val events = original.events
              val firstJ = events.indexWhere(e => e.fields("kind") == JsString("J_CONFIRMED") && e.fields("update") == JsString("CommitSnapshotPage") &&
                e.fields("state").asJsObject.fields("splits").asInstanceOf[JsArray].elements.exists(p =>
                  p.asJsObject.fields("id").convertTo[String].endsWith(":0") && p.asJsObject.fields("sequence") == JsNumber(0)))
              val retry = events.indexWhere(e => e.fields("kind") == JsString("J_CONFIRMED") && e.fields("update") == JsString("RetrySnapshotSplit"))
              firstJ should be >= 0
              retry should be > firstJ
            }
            if (scenario == "T_J") {
              recovered.events.exists(e => e.fields("kind") == JsString("T_CONFIRMED") && e.fields("replay") == JsBoolean(true)) shouldBe true
              val completed = original.events.filter(_.fields("kind") == JsString("J_CONFIRMED")).flatMap(_.fields("state").asJsObject.fields("splits")
                .asInstanceOf[JsArray].elements).map(_.asJsObject.fields).filter(_("done") == JsBoolean(true)).map(_("id")).toSet
              recovered.events.filter(_.fields("kind") == JsString("READER_OPEN")).foreach(e => completed should not contain e.fields("split"))
            }
          }
          write(dir, "result.json", JsObject("passed" -> JsBoolean(true), "classification" -> JsString(if (negative) "safe_pause" else "converged"),
            "sourceSchema" -> JsString(schema), "evidenceDirectory" -> JsString(dir.toString)))
        } catch {
          case scala.util.control.NonFatal(error) =>
            Option(recovered).orElse(Option(original)).filter(_.alive).foreach(c =>
              scala.util.Try(capture(c)).foreach(v => write(dir, "failure.json", v)))
            throw error
        } finally { Option(original).foreach(_.kill()); Option(recovered).foreach(_.kill()) }
      }
    }
  }
}
