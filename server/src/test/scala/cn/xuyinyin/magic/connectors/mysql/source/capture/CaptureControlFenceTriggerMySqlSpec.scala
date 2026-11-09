package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection,DriverManager,SQLException}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Real source trigger enforcement; excludes guardian, Journal, D and target. */
class CaptureControlFenceTriggerMySqlSpec extends AnyWordSpec with Matchers {
  "operator-provisioned source protection" should {
    "serialize writes, preserve duplicates, and reject unapproved fenced changes" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_CONTROL_FENCE").contains("1"))cancel("dedicated isolated MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("production-source-fence"))
      val base=sys.env("CAPTURE_TEST_URL");require(base.contains("pekko_test_shared_wire") && !base.contains(":3306/"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val pw=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      def open(url:String):Connection=DriverManager.getConnection(url,user,pw)
      def exec(c:Connection,s:String):Unit={val q=c.createStatement();try {q.execute(s);()}finally q.close()}
      def scalar(c:Connection,s:String):String={val q=c.createStatement();try {val r=q.executeQuery(s);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val admin=open(base);val suffix=UUID.randomUUID().toString.replace("-","").take(10);val schema="pekko_test_fence_"+suffix
      exec(admin,s"CREATE DATABASE $schema")
      val c=open(base.replace("pekko_test_shared_wire",schema));val other=open(base.replace("pekko_test_shared_wire",schema))
      try {
        val uuid=scalar(c,"SELECT @@server_uuid")
        exec(c,"CREATE TABLE pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(c,"CREATE TABLE pekko_capture_control_fence_v1(domain_id VARCHAR(16) PRIMARY KEY,source_uuid CHAR(36) NOT NULL,database_name VARCHAR(64) NOT NULL,epoch BIGINT NOT NULL,owner_token CHAR(64) NOT NULL,phase VARCHAR(16) NOT NULL,version BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(c,CaptureControlFence.ReleaseTableDdl)
        val token="b"*64;val op="a"*64
        val q=c.prepareStatement("INSERT INTO pekko_capture_control_fence_v1 VALUES('default',?,?,0,'','OPEN',0,'','','','')")
        try {q.setString(1,uuid);q.setString(2,schema);q.executeUpdate()}finally q.close()
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false)
        intercept[IllegalArgumentException](CaptureControlFence.verifyProtection(c,uuid,schema));c.rollback();c.setAutoCommit(true)
        CaptureControlFence.TriggerDdl.foreach(exec(c,_))
        exec(c,CaptureControlFence.OpenProcedureDdl)
        c.setAutoCommit(false);CaptureControlFence.verifyProtection(c,uuid,schema)
        exec(other,"SET SESSION lock_wait_timeout=1")
        val ddlBlocked=intercept[SQLException](exec(other,"DROP TRIGGER pekko_control_fence_bu"))
        ddlBlocked.getErrorCode shouldBe 1205
        c.commit()
        val restricted="fence_"+suffix
        exec(admin,s"CREATE USER '$restricted'@'127.0.0.1' IDENTIFIED BY 'isolated-fixture-only-password'")
        exec(admin,s"GRANT SELECT ON `$schema`.* TO '$restricted'@'127.0.0.1'")
        exec(admin,s"GRANT EXECUTE ON PROCEDURE `$schema`.pekko_control_write_open_v1 TO '$restricted'@'127.0.0.1'")
        val controller="fctrl_"+suffix
        exec(admin,s"CREATE USER '$controller'@'127.0.0.1' IDENTIFIED BY 'isolated-fixture-controller-password'")
        exec(admin,s"GRANT SELECT ON `$schema`.* TO '$controller'@'127.0.0.1'")
        exec(admin,s"GRANT SELECT,INSERT,UPDATE ON `$schema`.pekko_capture_control TO '$controller'@'127.0.0.1'")
        exec(admin,s"GRANT SELECT,UPDATE ON `$schema`.pekko_capture_control_fence_v1 TO '$controller'@'127.0.0.1'")
        exec(admin,s"GRANT SELECT,INSERT ON `$schema`.pekko_capture_control_release_v1 TO '$controller'@'127.0.0.1'")
        val wildcard="fwide_"+suffix;val roleUser="frole_"+suffix;val role="fcrole_"+suffix
        exec(admin,s"CREATE USER '$wildcard'@'%' IDENTIFIED BY 'isolated-fixture-only-password'")
        exec(admin,s"GRANT SELECT ON `$schema`.* TO '$wildcard'@'%'")
        exec(admin,s"GRANT EXECUTE ON PROCEDURE `$schema`.pekko_control_write_open_v1 TO '$wildcard'@'%'")
        exec(admin,s"CREATE USER '$roleUser'@'127.0.0.1' IDENTIFIED BY 'isolated-fixture-only-password'")
        exec(admin,s"GRANT SELECT ON `$schema`.* TO '$roleUser'@'127.0.0.1'")
        exec(admin,s"GRANT EXECUTE ON PROCEDURE `$schema`.pekko_control_write_open_v1 TO '$roleUser'@'127.0.0.1'")
        exec(admin,s"CREATE ROLE '$role'");exec(admin,s"GRANT '$role' TO '$roleUser'@'127.0.0.1'")
        Vector(wildcard,roleUser).foreach {name=>
          val rejected=DriverManager.getConnection(base.replace("pekko_test_shared_wire",schema),name,"isolated-fixture-only-password")
          try {rejected.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);rejected.setAutoCommit(false)
            intercept[IllegalArgumentException](CaptureControlFence.protect(c,rejected,uuid,schema));c.rollback();rejected.rollback()
          }finally rejected.close()
        }
        val writer=DriverManager.getConnection(base.replace("pekko_test_shared_wire",schema),restricted,"isolated-fixture-only-password")
        try {
          writer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);writer.setAutoCommit(false)
          val protectedDomain=CaptureControlFence.protect(c,writer,uuid,schema)
          protectedDomain.writeOpen("c"*64,"SUBSCRIBE","existing","g1");writer.commit();c.commit()
          intercept[SQLException](exec(writer,"INSERT IGNORE INTO pekko_capture_control VALUES('"+("C"*64)+"','STOP','existing','g1')"));writer.rollback()
          intercept[SQLException](exec(writer,"UPDATE pekko_capture_control_fence_v1 SET version=version+1"));writer.rollback()
          intercept[SQLException](exec(writer,"DROP TRIGGER pekko_control_fence_bu"));writer.rollback()
        }finally writer.close()
        val untrusted=DriverManager.getConnection(base.replace("pekko_test_shared_wire",schema),user,pw)
        try {untrusted.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);untrusted.setAutoCommit(false)
          intercept[IllegalArgumentException](CaptureControlFence.protect(c,untrusted,uuid,schema));c.rollback();untrusted.rollback()
        }finally untrusted.close()
        scalar(other,"SELECT version FROM pekko_capture_control_fence_v1 WHERE domain_id='default'") shouldBe "1"
        CaptureControlFence.writeOpenMarker(c,uuid,schema,"c"*64,"SUBSCRIBE","existing","g1");c.commit()
        scalar(other,"SELECT version FROM pekko_capture_control_fence_v1 WHERE domain_id='default'") shouldBe "1"
        intercept[IllegalArgumentException](CaptureControlFence.writeOpenMarker(c,uuid,schema,"c"*64,"STOP","existing","g1"));c.rollback()
        val binding=CaptureFenceBinding(uuid,schema,1L,token,1L,op,"SUBSCRIBE","orders","g1")
        val before=CaptureControlFence.observe(c,uuid,schema);before.phase shouldBe "OPEN";before.epoch shouldBe 0L;before.version shouldBe 1L;c.commit()
        CaptureControlFence.acquire(c,binding) shouldBe binding;c.commit()
        CaptureControlFence.acquire(c,binding) shouldBe binding;c.commit()
        val fencedWriter=DriverManager.getConnection(base.replace("pekko_test_shared_wire",schema),restricted,"isolated-fixture-only-password")
        try {
          fencedWriter.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);fencedWriter.setAutoCommit(false)
          val protectedDomain=CaptureControlFence.protect(c,fencedWriter,uuid,schema)
          intercept[SQLException](protectedDomain.writeOpen(op,"SUBSCRIBE","orders","g1"));fencedWriter.rollback();c.rollback()
        }finally fencedWriter.close()
        intercept[IllegalArgumentException](CaptureControlFence.writeOpenMarker(c,uuid,schema,"d"*64,"SUBSCRIBE","other","g1"));c.rollback()
        other.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);other.setAutoCommit(false)
        intercept[SQLException](exec(other,"INSERT INTO pekko_capture_control VALUES('"+("d"*64)+"','SUBSCRIBE','other','g1')"));other.rollback()
        intercept[SQLException](exec(other,"DELETE FROM pekko_capture_control WHERE operation_id='"+("c"*64)+"'"));other.rollback()
        val controllerConnection=DriverManager.getConnection(base.replace("pekko_test_shared_wire",schema),controller,"isolated-fixture-controller-password")
        val writerProof=DriverManager.getConnection(base.replace("pekko_test_shared_wire",schema),restricted,"isolated-fixture-only-password")
        controllerConnection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);controllerConnection.setAutoCommit(false)
        val protectedController=CaptureControlFence.protectController(c,controllerConnection,uuid,schema,writerProof)
        val receipt=try {
          intercept[IllegalArgumentException](protectedController.withVerifiedFence(binding,true)(_.requireLive()))
          var beforeProof:CaptureControlFence.VerifiedSourceFence=null
          val provisional=protectedController.withVerifiedFence(binding,false){proof=>
            proof.requireLive();beforeProof=proof
            cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(protectedController,binding)
          }
          intercept[IllegalArgumentException](beforeProof.requireLive())
          intercept[IllegalArgumentException](protectedController.withVerifiedReceipt(provisional)(_.requireLive()))
          controllerConnection.rollback()
          val result=cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(protectedController,binding);controllerConnection.commit();c.commit();result
        }finally controllerConnection.close()
        receipt.versionAfter shouldBe 2L;receipt.markerCreated shouldBe true
        val duplicate=cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(c,binding.copy(version=2));duplicate.markerCreated shouldBe false;duplicate.versionAfter shouldBe 2L;c.commit()
        scalar(other,"SELECT version FROM pekko_capture_control_fence_v1 WHERE domain_id='default'") shouldBe "2"
        val retryController=DriverManager.getConnection(base.replace("pekko_test_shared_wire",schema),controller,"isolated-fixture-controller-password")
        try {
          retryController.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);retryController.setAutoCommit(false)
          val recovered=CaptureControlFence.protectController(c,retryController,uuid,schema,writerProof)
          recovered.observe().version shouldBe 2L
          recovered.matches(receipt) shouldBe true
          var escaped:CaptureControlFence.VerifiedSourceReceipt=null
          recovered.withVerifiedReceipt(receipt){proof=>proof.requireLive();escaped=proof}
          intercept[IllegalArgumentException](escaped.requireLive())
          cn.xuyinyin.magic.SourceFencePrimitiveProbe.release(recovered,receipt,"permit-1","d"*64)
          intercept[IllegalArgumentException](recovered.withVerifiedRelease(receipt,"permit-1","d"*64)(_.requireLive()))
          retryController.rollback()
          cn.xuyinyin.magic.SourceFencePrimitiveProbe.release(recovered,receipt,"permit-1","d"*64);retryController.commit();c.commit()
          recovered.matchesReleased(receipt,"permit-1","d"*64) shouldBe true
          recovered.matchesReleased(receipt,"permit-2","d"*64) shouldBe false
          retryController.rollback();c.rollback()
        }finally {retryController.close();writerProof.close()}
        scalar(other,"SELECT phase FROM pekko_capture_control_fence_v1 WHERE domain_id='default'") shouldBe "OPEN"
        c.setAutoCommit(true);exec(c,"DROP TRIGGER pekko_control_fence_bu")
        c.setAutoCommit(false);intercept[IllegalArgumentException](CaptureControlFence.verifyProtection(c,uuid,schema));c.rollback()
      }finally {try c.rollback()catch{case _:SQLException=>()};try other.rollback()catch{case _:SQLException=>()};c.close();other.close();admin.close()}
    }
  }
}
