package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration._

/** Real Source/Actor Journal/Sink; only the return after successful T is held. */
class RealMySQLCdcSessionReleaseSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "managed CDC sessions" should {
    for (fault <- Vector("none", "cancel-T", "stop-T")) {
      s"confirm physical release with $fault and retain full field evidence" taggedAs ExternalIntegration in {
        if (!sys.env.get("RUN_MYSQL_CDC_RECOVERY_WINDOWS").contains("1")) cancel("external_blocked: isolated MySQL required")
        val settings = Settings.environment()
        val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
        val schema = s"pekko_test_cdc_window_$suffix"
        val connector = s"release_$suffix"
        val dir = Path.of("target", "cdc-session-release", s"$fault-$suffix").toAbsolutePath
        Files.createDirectories(dir)
        initialize(settings, schema)
        withConnection(settings, schema) { c =>
          val text = scala.io.Source.fromResource("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          try text.mkString.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c, _)) finally text.close()
        }
        val lineage = withConnection(settings, schema)(query(_, "SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        def start(mode: String, log: String): Child = {
          val cp = System.getProperty("java.class.path")
          val b = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString,
            "-Xmx512m", "-Duser.timezone=UTC", "-cp", cp,
            RealMySQLCdcGroupRecoveryProcess.getClass.getName.stripSuffix("$"), mode, schema, connector, "16", fault, lineage)
          b.redirectError(dir.resolve(log + ".stderr").toFile)
          b.environment().put("CDC_WINDOW_MYSQL_HOST", settings.host)
          b.environment().put("CDC_WINDOW_MYSQL_PORT", settings.port.toString)
          b.environment().put("CDC_WINDOW_MYSQL_USER", settings.user)
          b.environment().put("CDC_WINDOW_MYSQL_PASSWORD", settings.password)
          new Child(b.start(), dir.resolve(log))
        }
        var bootstrap: Child = null
        var child: Child = null
        def capture(c: Child): JsObject = JsObject("T" -> withConnection(settings, schema)(query(_, "SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
          "J" -> c.state(), "D" -> offsetState(settings, schema, connector), "comparison" -> comparison(settings, schema))
        try {
          bootstrap = start("bootstrap", "bootstrap.log")
          bootstrap.awaitEvent("PROCESS_READY")
          awaitCondition("bootstrap equality", 180.seconds) { comparison(settings, schema).fields("equal") == JsBoolean(true) }
          withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET label='trusted-streaming-baseline' WHERE id=64"))
          awaitCondition("durable bootstrap streaming D") {
            val state = bootstrap.state()
            comparison(settings, schema).fields("equal") == JsBoolean(true) &&
              offsetState(settings, schema, connector).elements.exists(r =>
                r.asJsObject.fields("offset_val").convertTo[String].parseJson == journalCheckpoint(state).fields("cursor").asJsObject.fields("offset")) &&
              journalCheckpoint(state).fields("cursor").asJsObject.fields("offset").asJsObject.fields.get("snapshot")
                .forall(v => v == JsBoolean(false) || v == JsString("false"))
          }
          bootstrap.send("CRASH"); bootstrap.exit() shouldBe 86
          child = start("fresh", "session.log")
          child.awaitEvent("PROCESS_READY")
          child.awaitEvent("ENGINE_CONFIG")
          withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET note='v2-baseline' WHERE id=64"))
          awaitCondition("group baseline T/J/D aligned") {
            val state = child.state()
            state.fields("actorCheckpoints").asInstanceOf[JsArray].elements.nonEmpty &&
              comparison(settings, schema).fields("equal") == JsBoolean(true) &&
              offsetState(settings, schema, connector).elements.exists(r =>
                r.asJsObject.fields("offset_val").convertTo[String].parseJson == journalCheckpoint(state).fields("cursor").asJsObject.fields("offset"))
          }
          val baseline = capture(child); write(dir, "baseline.json", baseline)
          if (Set("cancel-T","stop-T").contains(fault)) {
            child.send("ARM"); child.awaitEvent("ARMED")
            withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET label='cancel-committed',revision=revision+1 WHERE id=1"))
            val cut = child.awaitEvent("CUTPOINT"); write(dir, "cut-event.json", cut)
            val atCut = capture(child); write(dir, "cutpoint.json", atCut)
            atCut.fields("T") should not be baseline.fields("T")
            journalCheckpoint(atCut.fields("J").asJsObject) shouldBe journalCheckpoint(baseline.fields("J").asJsObject)
            if (fault == "stop-T") {
              child.send("STOP_OWNER"); child.awaitEvent("OWNER_STOP_REQUESTED")
              awaitCondition("owner remains alive while T result is held") {child.state().fields("status")==JsString("stopping")}
              write(dir,"stop-pending.json",capture(child))
              child.events.exists(_.fields("kind")==JsString("OWNER_STOPPED")) shouldBe false
            } else {
              child.send("CANCEL")
              val pending = child.awaitEvent("CANCEL_RESULT"); write(dir, "cancel-pending.json", pending)
              pending.fields("status") shouldBe JsString("TimedOut")
            }
            child.events.exists(_.fields("kind") == JsString("ENGINE_CLOSED")) shouldBe false
            child.send("RELEASE_T"); child.awaitEvent("T_RELEASED")
          }
          if (fault == "stop-T") {
            val stopped=child.awaitEvent("OWNER_STOPPED");write(dir,"owner-stopped.json",stopped)
            stopped.fields("liveRunners") shouldBe JsNumber(0)
            val events=child.events
            def at(kind:String)=events.indexWhere(_.fields("kind")==JsString(kind))
            val confirmed=events.indexWhere(_.fields("kind")==JsString("JOURNAL_CONFIRMED"),at("CUTPOINT"))
            val acked=events.indexWhere(_.fields("kind")==JsString("ACK_RETURNED"),confirmed)
            confirmed should be > at("CUTPOINT")
            acked should be > confirmed
            at("ENGINE_CLOSED") should be > acked
            at("ENGINE_CLOSED") should be < at("OWNER_STOPPED")
            events.count(_.fields("kind")==JsString("ENGINE_CLOSED")) shouldBe 1
            child.send("CRASH");child.exit() shouldBe 86
            child=start("recover","recovered.log");child.awaitEvent("PROCESS_READY")
            val oldId=baseline.fields("J").asJsObject.fields("executionId")
            awaitCondition("replacement recovers the same durable execution") {
              val state=child.state()
              state.fields("executionId")==oldId && state.fields("journal").asInstanceOf[JsArray].elements.forall {e =>
                val kind=e.asJsObject.fields("eventType");kind!=JsString("ExecutionCompleted") && kind!=JsString("ExecutionFailed")
              } && journalCheckpoint(state).fields("sequence").convertTo[Long]>journalCheckpoint(baseline.fields("J").asJsObject).fields("sequence").convertTo[Long]
            }
            write(dir,"after-owner-restart.json",capture(child))
            withConnection(settings,schema)(executeSql(_,"UPDATE source_rows SET note='after-owner-stop-🙂',revision=revision+1 WHERE id=1"))
            awaitCondition("replacement full fields and durable D converge",180.seconds) {
              val state=child.state()
              comparison(settings,schema).fields("equal")==JsBoolean(true) &&
                offsetState(settings,schema,connector).elements.exists(r=>r.asJsObject.fields("offset_val").convertTo[String].parseJson==journalCheckpoint(state).fields("cursor").asJsObject.fields("offset"))
            }
          }
          child.send("AWAIT_RELEASE")
          val released = child.awaitEvent("CANCEL_RESULT"); write(dir, "released.json", released)
          released.fields("status") shouldBe JsString("Released")
          released.fields("liveRunners") shouldBe JsNumber(0)
          child.events.count(_.fields("kind") == JsString("ENGINE_CLOSED")) shouldBe 1
          val end = capture(child); write(dir, "final.json", end)
          end.fields("comparison").asJsObject.fields("equal") shouldBe JsBoolean(true)
          if (Set("cancel-T","stop-T").contains(fault)) journalCheckpoint(end.fields("J").asJsObject).fields("sequence").convertTo[Long] should
            be > journalCheckpoint(baseline.fields("J").asJsObject).fields("sequence").convertTo[Long]
          write(dir, "outcome.json", JsObject("physicalRelease" -> JsBoolean(true), "allFieldsEqual" -> JsBoolean(true),
            "schema" -> JsString(schema), "fault" -> JsString(fault), "events" -> JsArray(child.events)))
          println(s"CDC_SESSION_RELEASE_OBSERVED fault=$fault evidence=$dir")
        } finally { if (bootstrap != null) bootstrap.kill(); if (child != null) child.kill() }
      }
    }
  }
}
