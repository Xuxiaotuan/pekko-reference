package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager,SQLException}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Actual separate JDBC sessions; this does not prove guardian fencing or owner-epoch correctness. */
class SourceFenceLockOrderMySqlSpec extends AnyWordSpec with Matchers {
  "source and capture startup locks" should {
    "block nested independent connections and succeed after the first lock is released" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_FENCE_LOCK_ORDER").contains("1"))cancel("private MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("production-source-fence"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val pw=sys.env("CDC_WINDOW_MYSQL_PASSWORD");val base=sys.env("CAPTURE_TEST_URL");val dst=sys.env("CAPTURE_STATE_URL")
      require(base.contains(":25521/") && dst.contains(":25522/"))
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val db="pekko_test_lock_"+suffix;val sourceUrl=base.replace("pekko_test_shared_wire",db)
      def open(url:String):Connection=DriverManager.getConnection(url,user,pw)
      def exec(c:Connection,s:String):Unit={val q=c.createStatement();try {q.execute(s);()}finally q.close()}
      def scalar(c:Connection,s:String):String={val q=c.createStatement();try {val r=q.executeQuery(s);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val admin=open(base);try exec(admin,s"CREATE DATABASE $db")finally admin.close()
      val a=open(sourceUrl);val b=open(sourceUrl);val state=open(dst)
      try {
        val uuid=scalar(a,"SELECT @@server_uuid")
        exec(a,"CREATE TABLE pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(a,"CREATE TABLE pekko_capture_control_fence_v1(domain_id VARCHAR(16) PRIMARY KEY,source_uuid CHAR(36) NOT NULL,database_name VARCHAR(64) NOT NULL,epoch BIGINT NOT NULL,owner_token CHAR(64) NOT NULL,phase VARCHAR(16) NOT NULL,version BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(a,CaptureControlFence.ReleaseTableDdl)
        val init=a.prepareStatement("INSERT INTO pekko_capture_control_fence_v1 VALUES('default',?,?,0,'','OPEN',0,'','','','')")
        try {init.setString(1,uuid);init.setString(2,db);init.executeUpdate()}finally init.close()
        CaptureControlFence.TriggerDdl.foreach(exec(a,_));exec(a,CaptureControlFence.OpenProcedureDdl)
        Vector(a,b,state).foreach {c=>c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false)}
        exec(b,"SET SESSION innodb_lock_wait_timeout=1")
        CaptureControlFence.observe(a,uuid,db).phase shouldBe "OPEN"
        val sourceTimeout=intercept[SQLException](CaptureControlFence.observe(b,uuid,db));sourceTimeout.getErrorCode shouldBe 1205
        b.rollback();a.rollback();CaptureControlFence.observe(b,uuid,db).phase shouldBe "OPEN";b.rollback()
        val capture="lock_"+suffix
        def stateOpen():Connection={val c=open(dst);exec(c,"SET SESSION innodb_lock_wait_timeout=1");c}
        val store=new MySQLCaptureStore(()=>stateOpen(),capture,uuid,10000000);store.initialize()
        val lock=state.prepareStatement("SELECT capture_id FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE")
        try {lock.setString(1,capture);val r=lock.executeQuery();try require(r.next())finally r.close()}finally lock.close()
        val headTimeout=intercept[SQLException](store.rememberProfile("{\"version\":1}"));headTimeout.getErrorCode shouldBe 1205
        state.rollback();store.rememberProfile("{\"version\":1}") should be > 0L
        val ownerKey="pekko_capture_"+capture;val get=state.prepareStatement("SELECT GET_LOCK(?,0)")
        try {get.setString(1,ownerKey);val r=get.executeQuery();try {r.next();r.getInt(1) shouldBe 1}finally r.close()}finally get.close()
        val contender=open(dst);try {
          val q=contender.prepareStatement("SELECT GET_LOCK(?,0)");try {q.setString(1,ownerKey);val r=q.executeQuery();try {r.next();r.getInt(1) shouldBe 0}finally r.close()}finally q.close()
        }finally contender.close()
        Files.createDirectories(Path.of(sys.env("FENCE_LOCK_EVIDENCE")))
        Files.writeString(Path.of(sys.env("FENCE_LOCK_EVIDENCE")).resolve("result.json"),JsObject("sourceSecondLockError"->JsNumber(sourceTimeout.getErrorCode),
          "stateSecondLockError"->JsNumber(headTimeout.getErrorCode),"afterRelease"->JsString("both succeeded"),"competitorGetLock"->JsNumber(0)).prettyPrint)
      }finally {try a.rollback()catch{case _:Throwable=>()};try b.rollback()catch{case _:Throwable=>()};try state.rollback()catch{case _:Throwable=>()};a.close();b.close();state.close()}
    }
  }
}
