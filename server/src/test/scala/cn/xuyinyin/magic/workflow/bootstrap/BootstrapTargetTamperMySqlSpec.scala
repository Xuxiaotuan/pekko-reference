package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection, DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Counterexample only: receipt checks cannot establish target-row immutability. */
class BootstrapTargetTamperMySqlSpec extends AnyWordSpec with Matchers {
  "a bootstrap target receipt" should {
    "remain valid when a privileged connection changes a previously applied target row" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CAPTURE_ARCHIVE_PROBE").contains("1")) cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      val url = sys.env("CAPTURE_STATE_URL")
      require(url.contains("pekko_test_shared_state") && !url.contains(":3306/"))
      val user = sys.env("CDC_WINDOW_MYSQL_USER")
      val password = sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val schema = "pekko_test_target_tamper_" + UUID.randomUUID().toString.replace("-", "").take(10)
      val admin = DriverManager.getConnection(url, user, password)
      try { val q = admin.createStatement(); try q.execute("CREATE DATABASE `" + schema + "`") finally q.close() } finally admin.close()
      val isolated = url.replace("pekko_test_shared_state", schema)
      def open(): Connection = DriverManager.getConnection(isolated, user, password)
      val c = open()
      try { val q = c.createStatement(); try q.execute("CREATE TABLE target_rows(id BIGINT PRIMARY KEY,label VARCHAR(64)) ENGINE=InnoDB") finally q.close() } finally c.close()
      val definition = """{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5,"typeName":"BIGINT"},{"name":"label","jdbcType":12,"typeName":"VARCHAR"}]}"""
      val target = new BootstrapTarget(() => open(), "target_rows", new BootstrapRowCodec(definition), "tamper")
      val rows = Vector(JsObject("id" -> JsNumber(1), "label" -> JsString("expected")))
      target.claim()
      target.applyPage("w", 0, "manifest", rows) shouldBe true
      target.verifyPage("w", 0, "manifest", rows)

      val attacker = open()
      try { val q = attacker.createStatement(); try q.executeUpdate("UPDATE target_rows SET label='changed' WHERE id=1") shouldBe 1 finally q.close() } finally attacker.close()
      target.verifyPage("w", 0, "manifest", rows)
      val check = open()
      try { val q = check.createStatement(); try { val r = q.executeQuery("SELECT label FROM target_rows WHERE id=1")
        try { r.next() shouldBe true; r.getString(1) shouldBe "changed" } finally r.close()
      } finally q.close() } finally check.close()
      info(s"isolated_schema=$schema; receipt=accepted; target_row=changed; privileged_mutation=true")
    }
  }
}
