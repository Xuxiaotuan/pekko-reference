package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection,DriverManager,SQLException}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await,Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** Isolated, fresh namespace only; no application or production account is altered. */
class CaptureLedgerGuardMySqlSpec extends AnyWordSpec with Matchers {
  "the dedicated ledger guard" should {
    "reject inherited roles and mutable metadata while holding the actual MySQL lock" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_LEDGER_GUARD_PROBE").contains("1"))cancel("dedicated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-ledger-guard"))
      Class.forName("com.mysql.cj.jdbc.Driver")
      val url=sys.env("CAPTURE_STATE_URL");require(url.contains(":25832/") && url.contains("pekko_test_shared_state"))
      val adminUser=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val schema="pekko_gc_"+suffix;val user="lg_"+suffix;val role="lgr_"+suffix
      val admin=DriverManager.getConnection(url,adminUser,password)
      def exec(c:Connection,s:String):Unit={val q=c.createStatement();try q.execute(s)finally q.close()}
      def scalar(c:Connection,s:String):String={val q=c.createStatement();try {val r=q.executeQuery(s);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      try {
        exec(admin,s"CREATE DATABASE `$schema`")
        exec(admin,s"CREATE TABLE `$schema`.pekko_sync_cdc_group_ledger(group_id CHAR(64) PRIMARY KEY,evidence LONGTEXT NOT NULL) ENGINE=InnoDB")
        exec(admin,s"CREATE TRIGGER `$schema`.pekko_ledger_no_update BEFORE UPDATE ON `$schema`.pekko_sync_cdc_group_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger immutable'")
        exec(admin,s"CREATE TRIGGER `$schema`.pekko_ledger_no_delete BEFORE DELETE ON `$schema`.pekko_sync_cdc_group_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger immutable'")
        val q=admin.prepareStatement(s"CREATE USER '$user'@'127.0.0.1' IDENTIFIED BY ?")
        try {q.setString(1,password);q.execute()}finally q.close()
        exec(admin,s"GRANT SELECT,INSERT,UPDATE,DELETE ON `$schema`.pekko_sync_cdc_group_ledger TO '$user'@'127.0.0.1'")
        val db=url.replace("pekko_test_shared_state",schema)
        def observer():Connection=DriverManager.getConnection(db,adminUser,password)
        def writer():Connection=DriverManager.getConnection(db,user,password)
        val o=observer();val w=writer()
        try {
          val uuid=scalar(o,"SELECT @@server_uuid");val definer=scalar(o,"SELECT CURRENT_USER()");val account=scalar(w,"SELECT CURRENT_USER()")
          scalar(o,"SELECT @@performance_schema") shouldBe "1"
          exec(w,"INSERT INTO pekko_sync_cdc_group_ledger VALUES (REPEAT('a',64),'evidence')")
          w.setAutoCommit(false)
          CaptureLedgerGuard.withVerified(o,w,uuid,schema,account,definer) {
            scalar(w,"SELECT evidence FROM pekko_sync_cdc_group_ledger WHERE group_id=REPEAT('a',64) FOR UPDATE") shouldBe "evidence"
            intercept[SQLException](exec(w,"UPDATE pekko_sync_cdc_group_ledger SET evidence='evidence' WHERE group_id=REPEAT('a',64)"))
            intercept[SQLException](exec(w,"DELETE FROM pekko_sync_cdc_group_ledger WHERE group_id=REPEAT('a',64)"))
            val ddl=Future {val x=observer();try {exec(x,"SET SESSION lock_wait_timeout=1");exec(x,"DROP TRIGGER pekko_ledger_no_update");false}
              catch {case e:SQLException=>Option(e.getMessage).exists(_.toLowerCase.contains("lock wait timeout"))}finally x.close()}
            Await.result(ddl,10.seconds) shouldBe true
          }
          w.rollback()
          intercept[IllegalStateException] {CaptureLedgerGuard.withVerified(o,w,uuid,schema,account,definer) {
            exec(w,"INSERT INTO pekko_sync_cdc_group_ledger VALUES (REPEAT('b',64),'rolled-back')")
            throw new IllegalStateException("injected callback failure")
          }}
          w.rollback()
          scalar(w,"SELECT COUNT(*) FROM pekko_sync_cdc_group_ledger WHERE group_id=REPEAT('b',64)") shouldBe "0"
          w.rollback()
          intercept[IllegalArgumentException] {CaptureLedgerGuard.withVerified(o,w,uuid,schema,account,definer){w.commit()}}
          // A new transaction can reacquire the same metadata lock on the same connection.
          // This must not make the old guard scope valid again.
          intercept[IllegalArgumentException] {CaptureLedgerGuard.withVerified(o,w,uuid,schema,account,definer){
            w.commit()
            scalar(w,"SELECT COUNT(*) FROM pekko_sync_cdc_group_ledger")
          }}
          w.rollback()
          // InnoDB foreign-key actions do not run row triggers on the child table.
          // SET NULL can therefore modify a ledger even with the two guards installed.
          exec(admin,s"CREATE TABLE `$schema`.fk_parent(id INT PRIMARY KEY) ENGINE=InnoDB")
          exec(admin,s"ALTER TABLE `$schema`.pekko_sync_cdc_group_ledger ADD COLUMN parent_id INT NULL, ADD CONSTRAINT ledger_parent_fk FOREIGN KEY(parent_id) REFERENCES `$schema`.fk_parent(id) ON DELETE SET NULL")
          exec(admin,s"INSERT INTO `$schema`.fk_parent VALUES(1)")
          exec(admin,s"INSERT INTO `$schema`.pekko_sync_cdc_group_ledger(group_id,evidence,parent_id) VALUES(REPEAT('c',64),'fk-evidence',1)")
          exec(admin,s"DELETE FROM `$schema`.fk_parent WHERE id=1")
          scalar(admin,s"SELECT COUNT(*) FROM `$schema`.pekko_sync_cdc_group_ledger WHERE group_id=REPEAT('c',64) AND parent_id IS NULL") shouldBe "1"
          intercept[IllegalArgumentException](CaptureLedgerGuard.withVerified(o,w,uuid,schema,account,definer){()})
          w.rollback()
          exec(admin,s"ALTER TABLE `$schema`.pekko_sync_cdc_group_ledger DROP FOREIGN KEY ledger_parent_fk")
          exec(admin,s"ALTER TABLE `$schema`.pekko_sync_cdc_group_ledger ADD CONSTRAINT ledger_parent_fk FOREIGN KEY(parent_id) REFERENCES `$schema`.fk_parent(id) ON UPDATE SET NULL")
          exec(admin,s"INSERT INTO `$schema`.fk_parent VALUES(2)")
          exec(admin,s"INSERT INTO `$schema`.pekko_sync_cdc_group_ledger(group_id,evidence,parent_id) VALUES(REPEAT('d',64),'fk-evidence',2)")
          exec(admin,s"UPDATE `$schema`.fk_parent SET id=3 WHERE id=2")
          scalar(admin,s"SELECT COUNT(*) FROM `$schema`.pekko_sync_cdc_group_ledger WHERE group_id=REPEAT('d',64) AND parent_id IS NULL") shouldBe "1"
          intercept[IllegalArgumentException](CaptureLedgerGuard.withVerified(o,w,uuid,schema,account,definer){()})
          w.rollback()
          exec(admin,s"ALTER TABLE `$schema`.pekko_sync_cdc_group_ledger DROP FOREIGN KEY ledger_parent_fk, DROP COLUMN parent_id")
          exec(admin,s"CREATE ROLE '$role'")
          exec(admin,s"GRANT SELECT ON `$schema`.pekko_sync_cdc_group_ledger TO '$role'")
          exec(admin,s"GRANT '$role' TO '$user'@'127.0.0.1'")
          exec(admin,s"SET DEFAULT ROLE NONE TO '$user'@'127.0.0.1'")
          val withRole=writer();withRole.setAutoCommit(false)
          try {scalar(withRole,"SELECT CURRENT_ROLE()") shouldBe "NONE"
            intercept[IllegalArgumentException](CaptureLedgerGuard.withVerified(o,withRole,uuid,schema,account,definer){()})
          }finally withRole.close()
          println(s"LEDGER_GUARD schema=$schema positive=true triggerRejected=true mdlBlocked=true callbackRolledBack=true commitEscapeRejected=true fkDeleteSetNullRejected=true fkUpdateSetNullRejected=true inactiveRoleRejected=true")
        }finally {w.close();o.close()}
        scalar(admin,"SELECT COUNT(*) FROM information_schema.innodb_trx") shouldBe "0"
        scalar(admin,"SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() AND USER NOT IN ('event_scheduler','system user')") shouldBe "0"
      }finally admin.close()
    }
  }
}
