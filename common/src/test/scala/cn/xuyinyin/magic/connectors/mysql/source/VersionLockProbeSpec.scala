package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager,SQLException}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Tests InnoDB lock behavior only; no guardian or production recovery permit is fabricated. */
class VersionLockProbeSpec extends AnyWordSpec with Matchers {
  "a source version row lock" should {
    "wait for an old writer and protect a state write until the source lock is released" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_VERSION_LOCK_PROBE").contains("1"))cancel("private dual MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("marker-probe"))
      val sourceUrl=sys.env("CAPTURE_TEST_URL");val stateUrl=sys.env("CAPTURE_STATE_URL")
      require(sourceUrl.contains("pekko_test_shared_wire") && stateUrl.contains("pekko_test_shared_state"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      def open(url:String)=DriverManager.getConnection(url,user,password)
      def exec(c:Connection,sql:String):Unit={val q=c.createStatement();try {q.execute(sql);()}finally q.close()}
      def scalar(c:Connection,sql:String):String={val q=c.createStatement();try {val r=q.executeQuery(sql);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val versions="version_lock_"+suffix;val controls="controls_lock_"+suffix;val permits="permit_lock_"+suffix
      val admin=open(sourceUrl);val old=open(sourceUrl);val guard=open(sourceUrl);val other=open(sourceUrl);val state=open(stateUrl)
      try {
        executeSetup(admin,versions,controls,suffix)
        exec(state,s"CREATE TABLE $permits(id INT PRIMARY KEY,version BIGINT NOT NULL) ENGINE=InnoDB")
        old.setAutoCommit(false);exec(old,s"INSERT INTO $controls VALUES('old')")
        guard.setAutoCommit(false);exec(guard,"SET SESSION innodb_lock_wait_timeout=1")
        val oldVersion=scalar(admin,s"SELECT version FROM $versions WHERE id=1")
        val blockedOld=intercept[SQLException](scalar(guard,s"SELECT version FROM $versions WHERE id=1 FOR UPDATE"))
        blockedOld.getErrorCode shouldBe 1205
        old.commit()
        val locked=scalar(guard,s"SELECT version FROM $versions WHERE id=1 FOR UPDATE")
        locked.toLong should be > oldVersion.toLong
        exec(other,"SET SESSION innodb_lock_wait_timeout=1")
        val blockedNew=intercept[SQLException](exec(other,s"INSERT INTO $controls VALUES('new')"))
        blockedNew.getErrorCode shouldBe 1205
        exec(state,s"INSERT INTO $permits VALUES(1,$locked)")
        val persisted=scalar(state,s"SELECT version FROM $permits WHERE id=1")
        persisted shouldBe locked
        guard.commit()
        exec(other,s"INSERT INTO $controls VALUES('new')")
        val afterRelease=scalar(admin,s"SELECT version FROM $versions WHERE id=1")
        afterRelease.toLong should be > persisted.toLong
        val output=Path.of(sys.env("VERSION_LOCK_PROBE_EVIDENCE"));Files.createDirectories(output)
        val evidence=JsObject("sourceUuid"->JsString(scalar(admin,"SELECT @@server_uuid")),"stateUuid"->JsString(scalar(state,"SELECT @@server_uuid")),
          "beforeOldCommit"->JsString(oldVersion),"oldWriterLockTimeout"->JsNumber(blockedOld.getErrorCode),
          "lockedAfterOldCommit"->JsString(locked),"newWriterLockTimeout"->JsNumber(blockedNew.getErrorCode),
          "testStateRowVersion"->JsString(persisted),"versionAfterLockRelease"->JsString(afterRelease),
          "scope"->JsString("MySQL source row lock and test-only state row; no guardian, Journal, actual D, production permit, Sink or restart"))
        Files.writeString(output.resolve("result.json"),evidence.prettyPrint)
      }finally {old.close();guard.close();other.close();state.close();admin.close()}
    }
  }
  private def executeSetup(c:Connection,versions:String,controls:String,suffix:String):Unit={
    def exec(sql:String):Unit={val q=c.createStatement();try {q.execute(sql);()}finally q.close()}
    exec(s"CREATE TABLE $versions(id INT PRIMARY KEY,version BIGINT NOT NULL) ENGINE=InnoDB")
    exec(s"INSERT INTO $versions VALUES(1,0)")
    exec(s"CREATE TABLE $controls(operation_id VARCHAR(16) PRIMARY KEY) ENGINE=InnoDB")
    exec(s"CREATE TRIGGER lock_ins_$suffix AFTER INSERT ON $controls FOR EACH ROW UPDATE $versions SET version=version+1 WHERE id=1")
  }
}
