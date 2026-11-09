package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection, DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Isolated counterexample: page checks use independent connections, not a single read view. */
class BootstrapAuditTamperMySqlSpec extends AnyWordSpec with Matchers {
  "a multi-step bootstrap page audit" should {
    "detect changed sealed content when it is reread, but not retroactively during a later page check" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CAPTURE_ARCHIVE_PROBE").contains("1")) cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      val url = sys.env("CAPTURE_STATE_URL")
      require(url.contains("pekko_test_shared_state") && !url.contains(":3306/"))
      val user = sys.env("CDC_WINDOW_MYSQL_USER")
      val password = sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val schema = "pekko_test_audit_tamper_" + UUID.randomUUID().toString.replace("-", "").take(10)
      val admin = DriverManager.getConnection(url, user, password)
      try { val q = admin.createStatement(); try q.execute("CREATE DATABASE `" + schema + "`") finally q.close() } finally admin.close()
      val isolated = url.replace("pekko_test_shared_state", schema)
      def open(): Connection = DriverManager.getConnection(isolated, user, password)
      val store = new BootstrapStore(() => open(), "tamper", 1024 * 1024)
      store.initialize("{}")
      store.register("w", "{}")
      val rows = Vector(JsObject("id" -> JsNumber(1)), JsObject("id" -> JsNumber(2)))
      val manifest = store.seal("w", "{}", rows, 1)
      store.complete(0, "w", "")
      store.verifyFinal("w", 0, manifest) shouldBe Vector(rows(0))

      val changed = MySQLCdcGroup.canonical(JsArray(JsObject("id" -> JsNumber(99))))
      val attacker = open()
      try {
        val q = attacker.prepareStatement("UPDATE pekko_bootstrap_pages SET payload=?,digest=? WHERE bootstrap_id=? AND window_id=? AND page_kind='FINAL' AND page_index=0")
        try { q.setString(1, changed); q.setString(2, MySQLCdcGroup.hash(changed)); q.setString(3, "tamper"); q.setString(4, "w"); q.executeUpdate() shouldBe 1 } finally q.close()
      } finally attacker.close()

      store.verifyFinal("w", 1, manifest) shouldBe Vector(rows(1))
      store.manifest("w") shouldBe Some(manifest)
      val ex = intercept[IllegalArgumentException](store.verifyFinal("w", 0, manifest))
      ex.getMessage should include ("bootstrap final page not sealed")
      info(s"isolated_schema=$schema; page0_before=accepted; page0_changed=committed; page1_after=accepted; manifest=unchanged; page0_reread=rejected")
    }
  }
}
