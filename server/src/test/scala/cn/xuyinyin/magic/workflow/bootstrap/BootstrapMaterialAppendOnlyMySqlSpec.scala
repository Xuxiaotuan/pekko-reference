package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection, DriverManager, SQLException}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Isolated protocol probe, not a production immutability attestation. */
class BootstrapMaterialAppendOnlyMySqlSpec extends AnyWordSpec with Matchers {
  "sealed bootstrap material in an isolated namespace" should {
    "reject mutations by a limited writer while leaving privileged DDL an explicit boundary" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CAPTURE_ARCHIVE_PROBE").contains("1")) cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      val url = sys.env("CAPTURE_STATE_URL")
      require(url.contains(":25822/") && url.contains("pekko_test_shared_state"))
      val adminUser = sys.env("CDC_WINDOW_MYSQL_USER")
      val adminPassword = sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix = UUID.randomUUID().toString.replace("-", "").take(10)
      val schema = "pekko_test_material_" + suffix
      val writer = "spool_" + suffix
      val writerPassword = UUID.randomUUID().toString.replace("-", "")
      val admin = DriverManager.getConnection(url, adminUser, adminPassword)
      def exec(c: Connection, sql: String): Unit = { val q = c.createStatement(); try { q.execute(sql); () } finally q.close() }
      try {
        exec(admin, s"CREATE DATABASE `$schema`")
        for (host <- Vector("localhost", "127.0.0.1")) {
          exec(admin, s"CREATE USER '$writer'@'$host' IDENTIFIED BY '$writerPassword'")
          exec(admin, s"GRANT SELECT,INSERT,UPDATE,DELETE ON `$schema`.* TO '$writer'@'$host'")
        }
      } finally admin.close()
      val isolated = url.replace("pekko_test_shared_state", schema)
      def adminOpen(): Connection = DriverManager.getConnection(isolated, adminUser, adminPassword)
      def writerOpen(): Connection = DriverManager.getConnection(isolated, writer, writerPassword)
      val setup = new BootstrapStore(() => adminOpen(), "boot", 1024 * 1024)
      setup.initialize("{}")
      val triggerStatements = Vector(
        "CREATE TRIGGER pages_no_update BEFORE UPDATE ON pekko_bootstrap_pages FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable page'",
        "CREATE TRIGGER pages_no_delete BEFORE DELETE ON pekko_bootstrap_pages FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable page'",
        "CREATE TRIGGER done_no_update BEFORE UPDATE ON pekko_bootstrap_done FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable completion'",
        "CREATE TRIGGER done_no_delete BEFORE DELETE ON pekko_bootstrap_done FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable completion'",
        "CREATE TRIGGER windows_no_delete BEFORE DELETE ON pekko_bootstrap_windows FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable window'",
        """CREATE TRIGGER windows_once BEFORE UPDATE ON pekko_bootstrap_windows FOR EACH ROW BEGIN
           IF OLD.manifest_json IS NOT NULL OR NOT (OLD.bootstrap_id <=> NEW.bootstrap_id) OR NOT (OLD.window_id <=> NEW.window_id)
             OR NOT (OLD.spec_json <=> NEW.spec_json) OR NOT (OLD.spec_hash <=> NEW.spec_hash)
             OR NEW.manifest_json IS NULL OR NEW.manifest_hash IS NULL THEN
             SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable sealed window'; END IF; END""",
        """CREATE TRIGGER pages_before_insert BEFORE INSERT ON pekko_bootstrap_pages FOR EACH ROW BEGIN
           IF (SELECT manifest_json FROM pekko_bootstrap_windows WHERE bootstrap_id=NEW.bootstrap_id AND window_id=NEW.window_id) IS NOT NULL THEN
             SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sealed window'; END IF; END"""
      )
      val triggerAdmin = adminOpen()
      try triggerStatements.foreach(exec(triggerAdmin, _)) finally triggerAdmin.close()
      val store = new BootstrapStore(() => writerOpen(), "boot", 1024 * 1024)
      store.initialize("{}")
      store.register("w", "{}")
      val rows = Vector(JsObject("id" -> JsNumber(1)), JsObject("id" -> JsNumber(2)))
      store.rawPage("w", 0, rows)
      val manifest = store.seal("w", "{}", rows, 1)
      val root = store.complete(0, "w", "")
      store.verifyFinal("w", 0, manifest) shouldBe Vector(rows(0))

      // Fresh connections model a worker restart; the guards live in MySQL metadata.
      val mutations = Vector(
        "UPDATE pekko_bootstrap_pages SET payload='[]',digest=SHA2('[]',256) WHERE bootstrap_id='boot' AND window_id='w' AND page_kind='RAW'",
        "DELETE FROM pekko_bootstrap_pages WHERE bootstrap_id='boot' AND window_id='w'",
        "UPDATE pekko_bootstrap_done SET root=REPEAT('a',64) WHERE bootstrap_id='boot'",
        "DELETE FROM pekko_bootstrap_done WHERE bootstrap_id='boot'",
        "UPDATE pekko_bootstrap_windows SET manifest_json='{}',manifest_hash=SHA2('{}',256) WHERE bootstrap_id='boot'",
        "DELETE FROM pekko_bootstrap_windows WHERE bootstrap_id='boot'",
        "INSERT INTO pekko_bootstrap_pages VALUES('boot','w','FINAL',9,'[]',SHA2('[]',256))",
        "DROP TRIGGER pages_no_update"
      )
      mutations.foreach { sql => val c = writerOpen(); try intercept[SQLException](exec(c, sql)) finally c.close() }
      store.completed(-1, 1).head._4 shouldBe root
      store.verifyFinal("w", 1, manifest) shouldBe Vector(rows(1))
      val verify = writerOpen()
      try { val q = verify.createStatement(); try { val r = q.executeQuery("SELECT CURRENT_USER()")
        try { r.next() shouldBe true; r.getString(1).startsWith(writer + "@") shouldBe true } finally r.close()
      } finally q.close() } finally verify.close()
      // A DBA can drop the trigger: the experiment deliberately does not treat it as a hostile-DBA defence.
      val privileged = adminOpen()
      try exec(privileged, "DROP TRIGGER pages_no_update") finally privileged.close()
      val afterDdl = writerOpen()
      try exec(afterDdl, "UPDATE pekko_bootstrap_pages SET payload='[]',digest=SHA2('[]',256) WHERE bootstrap_id='boot' AND window_id='w' AND page_kind='FINAL' AND page_index=0") finally afterDdl.close()
      intercept[IllegalArgumentException](store.verifyFinal("w", 0, manifest))
      val malformed = adminOpen()
      try exec(malformed, "ALTER TABLE pekko_bootstrap_pages ADD COLUMN unexpected_extra INT NULL") finally malformed.close()
      val rejected = intercept[IllegalArgumentException](new BootstrapStore(() => writerOpen(), "other", 1024 * 1024).initialize("{}"))
      rejected.getMessage should include ("bootstrap material schema conflict")
      val registry = writerOpen()
      try { val q = registry.createStatement(); try { val r = q.executeQuery("SELECT COUNT(*) FROM pekko_bootstrap_storage WHERE bootstrap_id='other'")
        try { r.next() shouldBe true; r.getLong(1) shouldBe 0L } finally r.close()
      } finally q.close() } finally registry.close()
      def ddl(sql:String):Unit={val c=adminOpen();try exec(c,sql)finally c.close()}
      def rejectSchema():Unit=intercept[IllegalArgumentException](new BootstrapStore(() => writerOpen(), "other", 1024 * 1024).initialize("{}"))
      ddl("ALTER TABLE pekko_bootstrap_pages DROP COLUMN unexpected_extra")
      ddl("ALTER TABLE pekko_bootstrap_pages MODIFY COLUMN page_kind VARCHAR(32) NOT NULL")
      rejectSchema()
      ddl("ALTER TABLE pekko_bootstrap_pages MODIFY COLUMN page_kind VARCHAR(16) NOT NULL")
      ddl("ALTER TABLE pekko_bootstrap_windows MODIFY COLUMN manifest_hash CHAR(64) NOT NULL")
      rejectSchema()
      ddl("ALTER TABLE pekko_bootstrap_windows MODIFY COLUMN manifest_hash CHAR(64) NULL")
      ddl("ALTER TABLE pekko_bootstrap_pages MODIFY COLUMN payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL")
      rejectSchema()
      ddl("ALTER TABLE pekko_bootstrap_pages MODIFY COLUMN payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL")
      ddl("ALTER TABLE pekko_bootstrap_pages DROP PRIMARY KEY, ADD PRIMARY KEY(bootstrap_id,window_id,page_index,page_kind)")
      rejectSchema()
      info(s"isolated_schema=$schema; limited_writer_mutations_rejected=${mutations.size}; fresh_sessions=true; privileged_ddl_bypass=true; corrupted_page_rejected=true; malformed_schema_rejected_before_registry=true; wrong_length_nullability_collation_key_rejected=true")
    }
  }
}
