package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Isolated source-side version probe; this does not install a production protocol. */
class SourceVersionProbeSpec extends AnyWordSpec with Matchers {
  "source control version" should {
    "detect a changed snapshot and demonstrate late commit after a version check" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SOURCE_VERSION_PROBE").contains("1"))cancel("private MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("marker-probe"))
      val url=sys.env("CAPTURE_TEST_URL");val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      require(url.contains("pekko_test_shared_wire") && !url.contains(":3306/"))
      def connection():Connection=DriverManager.getConnection(url,user,password)
      def execute(c:Connection,sql:String):Unit={val q=c.createStatement();try {q.execute(sql);()}finally q.close()}
      def scalar(c:Connection,sql:String):String={val q=c.createStatement();try {val r=q.executeQuery(sql);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val id=UUID.randomUUID().toString.replace("-","").take(10);val controls="proof_controls_"+id;val versions="proof_versions_"+id
      val admin=connection();val scan=connection();val writer=connection();val check=connection()
      try {
        execute(admin,s"CREATE TABLE $versions(id INT PRIMARY KEY,version BIGINT NOT NULL) ENGINE=InnoDB")
        execute(admin,s"INSERT INTO $versions VALUES(1,0)")
        execute(admin,s"CREATE TABLE $controls(operation_id VARCHAR(16) PRIMARY KEY) ENGINE=InnoDB")
        execute(admin,s"CREATE TRIGGER ins_$id AFTER INSERT ON $controls FOR EACH ROW UPDATE $versions SET version=version+1 WHERE id=1")
        execute(admin,s"INSERT INTO $controls VALUES('b'),('d')")
        scan.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);scan.setAutoCommit(false)
        val initial=scalar(scan,s"SELECT version FROM $versions WHERE id=1")
        val first=scalar(scan,s"SELECT operation_id FROM $controls ORDER BY operation_id LIMIT 1")
        first shouldBe "b"
        execute(writer,s"INSERT INTO $controls VALUES('a')")
        val current=scalar(check,s"SELECT version FROM $versions WHERE id=1")
        val sameSnapshot=scalar(scan,s"SELECT version FROM $versions WHERE id=1")
        val resumed=scalar(check,s"SELECT operation_id FROM $controls WHERE operation_id>'$first' ORDER BY operation_id LIMIT 1")
        current.toLong should be > initial.toLong
        sameSnapshot shouldBe initial
        resumed shouldBe "d"
        scan.rollback()
        writer.setAutoCommit(false)
        execute(writer,s"INSERT INTO $controls VALUES('c')")
        val beforeRollback=scalar(check,s"SELECT version FROM $versions WHERE id=1")
        beforeRollback shouldBe current
        writer.rollback()
        val afterRollback=scalar(check,s"SELECT version FROM $versions WHERE id=1")
        afterRollback shouldBe current
        execute(writer,s"INSERT INTO $controls VALUES('e')")
        val beforeLateCommit=scalar(check,s"SELECT version FROM $versions WHERE id=1")
        beforeLateCommit shouldBe current
        writer.commit()
        val afterLateCommit=scalar(check,s"SELECT version FROM $versions WHERE id=1")
        afterLateCommit.toLong should be > beforeLateCommit.toLong
        val out=Path.of(sys.env("SOURCE_VERSION_PROBE_EVIDENCE"));Files.createDirectories(out)
        val result=JsObject("initialVersion"->JsString(initial),"firstPage"->JsString(first),"committedEarlierKey"->JsString("a"),
          "versionInOldSnapshot"->JsString(sameSnapshot),"versionAfterConcurrentCommit"->JsString(current),"naiveResume"->JsString(resumed),
          "versionAfterRollback"->JsString(afterRollback),"versionBeforeLateCommit"->JsString(beforeLateCommit),
          "versionAfterLateCommit"->JsString(afterLateCommit),"sourceUuid"->JsString(scalar(admin,"SELECT @@server_uuid")),
          "mysqlVersion"->JsString(scalar(admin,"SELECT @@version")),"scope"->JsString("source JDBC transactions only; no Reader, Journal, Sink, grant or production protocol"))
        Files.writeString(out.resolve("result.json"),result.prettyPrint)
      } finally {scan.close();writer.close();check.close();admin.close()}
    }
  }
}
