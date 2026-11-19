package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection,DriverManager}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Only tests a source transaction primitive, not owner isolation or a versioned permit. */
class CaptureControlFenceMySqlSpec extends AnyWordSpec with Matchers {
  "source control fencing transaction" should {
    "hold one source-domain lock and validate idempotent marker identity" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_CONTROL_FENCE").contains("1"))cancel("dedicated isolated MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("production-source-fence"))
      val url=sys.env("CAPTURE_TEST_URL");require(url.contains("pekko_test_shared_wire") && !url.contains(":3306/"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val pw=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      def open():Connection=DriverManager.getConnection(url,user,pw)
      def exec(c:Connection,s:String):Unit={val q=c.createStatement();try {q.execute(s);()}finally q.close()}
      def scalar(c:Connection,s:String):String={val q=c.createStatement();try {val r=q.executeQuery(s);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val c=open();val observer=open()
      try {
        val uuid=scalar(c,"SELECT @@server_uuid");val db=scalar(c,"SELECT DATABASE()")
        exec(c,"CREATE TABLE pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(c,"CREATE TABLE pekko_capture_control_fence_v1(domain_id VARCHAR(16) PRIMARY KEY,source_uuid CHAR(36) NOT NULL,database_name VARCHAR(64) NOT NULL,epoch BIGINT NOT NULL,owner_token CHAR(64) NOT NULL,phase VARCHAR(16) NOT NULL,version BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        val b=CaptureFenceBinding(uuid,db,1L,"b"*64,0L,"a"*64,"SUBSCRIBE","orders","g1")
        val insert=c.prepareStatement("INSERT INTO pekko_capture_control_fence_v1 VALUES('default',?,?,1,?,'FENCED',0,?,'SUBSCRIBE','orders','g1')")
        try {insert.setString(1,uuid);insert.setString(2,db);insert.setString(3,b.ownerToken);insert.setString(4,b.operation);insert.executeUpdate()}finally insert.close()
        c.setAutoCommit(false)
        intercept[IllegalArgumentException](cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(c,b));c.rollback()
        scalar(observer,"SELECT COUNT(*) FROM pekko_capture_control") shouldBe "0"
        c.setAutoCommit(true)
        exec(c,"CREATE TRIGGER test_fence_version AFTER INSERT ON pekko_capture_control FOR EACH ROW UPDATE pekko_capture_control_fence_v1 SET version=version+1 WHERE domain_id='default'")
        c.setAutoCommit(false)
        intercept[IllegalArgumentException](cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(c,b.copy(version=2)));c.rollback()
        val receipt=cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(c,b)
        receipt.versionAfter shouldBe 1L;receipt.markerCreated shouldBe true
        scalar(observer,"SELECT COUNT(*) FROM pekko_capture_control") shouldBe "0"
        c.commit()
        scalar(observer,"SELECT COUNT(*) FROM pekko_capture_control") shouldBe "1"
        intercept[IllegalArgumentException](cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(c,b));c.rollback()
        val duplicate=cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(c,b.copy(version=1))
        duplicate.markerCreated shouldBe false;duplicate.versionAfter shouldBe 1L;c.rollback()
        intercept[IllegalArgumentException](cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(c,b.copy(version=1,table="other")));c.rollback()
      }finally {try c.rollback()finally {c.close();observer.close()}}
    }
  }
}
