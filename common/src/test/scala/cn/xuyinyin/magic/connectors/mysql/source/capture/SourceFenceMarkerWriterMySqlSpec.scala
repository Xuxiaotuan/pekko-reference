package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Isolated DB test of the candidate transaction primitive; not an end-to-end recovery test. */
class SourceFenceMarkerWriterMySqlSpec extends AnyWordSpec with Matchers {
  "source fence marker transaction" should {
    "reject stale proofs and write the marker on the locked transaction" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SOURCE_FENCE_WRITER").contains("1"))cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("source-fence-writer"))
      val url=sys.env("CAPTURE_TEST_URL");require(url.contains("pekko_test_shared_wire") && !url.contains(":3306/"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      def open():Connection=DriverManager.getConnection(url,user,password)
      def exec(c:Connection,sql:String):Unit={val q=c.createStatement();try {q.execute(sql);()}finally q.close()}
      def get(c:Connection,sql:String):String={val q=c.createStatement();try {val r=q.executeQuery(sql);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val className="cn.xuyinyin.magic.connectors.mysql.source.capture.SourceFenceMarkerWriter$"
      val singleton=try Some(Class.forName(className).getField("MODULE$").get(null))catch {case _:ClassNotFoundException=>None}
      withClue("guarded source marker writer must exist"){singleton should not be empty}
      val method=singleton.get.getClass.getMethod("write",classOf[Connection],classOf[String],classOf[String],classOf[String],java.lang.Long.TYPE,
        classOf[String],java.lang.Long.TYPE,classOf[String],classOf[String],classOf[String],classOf[String])
      def write(c:Connection,id:String,uuid:String,epoch:Long,token:String,version:Long,op:String,table:String,db:String="pekko_test_shared_wire"):Unit=
        try {method.invoke(singleton.get,c,id,uuid,db,Long.box(epoch),token,Long.box(version),op,"SUBSCRIBE",table,"g1");()}
        catch {case e:InvocationTargetException=>throw e.getCause}
      val c=open();val check=open();val stale=open();val id="guard_"+UUID.randomUUID().toString.replace("-","").take(10)
      try {
        val uuid=get(c,"SELECT @@server_uuid")
        exec(c,"CREATE TABLE IF NOT EXISTS pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(c,"CREATE TABLE IF NOT EXISTS pekko_capture_source_fence_v1(capture_id VARCHAR(64) PRIMARY KEY,source_uuid VARCHAR(64) NOT NULL,epoch BIGINT NOT NULL,owner_token VARCHAR(128) NOT NULL,version BIGINT NOT NULL,state VARCHAR(16) NOT NULL) ENGINE=InnoDB")
        val q=c.prepareStatement("INSERT INTO pekko_capture_source_fence_v1 VALUES(?,?,7,'secret-test-token',13,'FENCED')")
        try {q.setString(1,id);q.setString(2,uuid);q.executeUpdate()}finally q.close()
        intercept[IllegalArgumentException](write(c,id,uuid,7,"secret-test-token",13,"auto-"+id,"orders"))
        c.setAutoCommit(false)
        intercept[IllegalArgumentException](write(c,"missing-"+id,uuid,7,"secret-test-token",13,"missing-"+id,"orders"));c.rollback()
        intercept[IllegalArgumentException](write(c,id,uuid,7,"secret-test-token",12,"stale-"+id,"orders"));c.rollback()
        intercept[IllegalArgumentException](write(c,id,uuid,8,"secret-test-token",13,"epoch-"+id,"orders"));c.rollback()
        intercept[IllegalArgumentException](write(c,id,uuid,7,"wrong-token",13,"token-"+id,"orders"));c.rollback()
        intercept[IllegalArgumentException](write(c,id,"wrong-uuid",7,"secret-test-token",13,"uuid-"+id,"orders"));c.rollback()
        intercept[IllegalArgumentException](write(c,id,uuid,7,"secret-test-token",13,"db-"+id,"orders","wrong_database"));c.rollback()
        get(check,s"SELECT COUNT(*) FROM pekko_capture_control WHERE operation_id LIKE '%$id'") shouldBe "0"
        stale.setAutoCommit(false)
        get(stale,s"SELECT COUNT(*) FROM pekko_capture_control WHERE operation_id='ok-$id'") shouldBe "0"
        write(c,id,uuid,7,"secret-test-token",13,"ok-"+id,"orders")
        get(check,s"SELECT COUNT(*) FROM pekko_capture_control WHERE operation_id='ok-$id'") shouldBe "0"
        exec(check,"SET SESSION innodb_lock_wait_timeout=1")
        val locked=intercept[java.sql.SQLException](exec(check,s"UPDATE pekko_capture_source_fence_v1 SET version=14 WHERE capture_id='$id'"))
        locked.getErrorCode shouldBe 1205
        c.commit()
        get(check,s"SELECT COUNT(*) FROM pekko_capture_control WHERE operation_id='ok-$id'") shouldBe "1"
        // The earlier REPEATABLE READ snapshot cannot see the new marker using an ordinary read.
        get(stale,s"SELECT COUNT(*) FROM pekko_capture_control WHERE operation_id='ok-$id'") shouldBe "0"
        write(stale,id,uuid,7,"secret-test-token",13,"ok-"+id,"orders")
        stale.rollback()
        intercept[IllegalArgumentException](write(c,id,uuid,7,"secret-test-token",13,"ok-"+id,"other"));c.rollback()
        get(check,s"SELECT table_name FROM pekko_capture_control WHERE operation_id='ok-$id'") shouldBe "orders"
        intercept[IllegalArgumentException](write(c,id,uuid,7,"secret-test-token",13,("ok-"+id).toUpperCase,"orders"))
        c.rollback()
        write(c,id,uuid,7,"secret-test-token",13,"rollback-"+id,"orders")
        intercept[IllegalArgumentException](write(c,id,uuid,7,"wrong-token",13,"bad-"+id,"orders"))
        c.commit()
        get(check,s"SELECT COUNT(*) FROM pekko_capture_control WHERE operation_id='rollback-$id'") shouldBe "0"
        val broken=open();broken.setAutoCommit(false)
        val wrapper=Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
          def invoke(o:Any,m:Method,a:Array[AnyRef]):AnyRef={
            if(m.getName=="rollback")throw new java.sql.SQLException("injected rollback failure")
            try m.invoke(broken,Option(a).getOrElse(Array.empty[AnyRef]):_*)catch{case e:InvocationTargetException=>throw e.getCause}
          }
        }).asInstanceOf[Connection]
        val uncertain=intercept[IllegalArgumentException](write(wrapper,id,uuid,7,"wrong-token",13,"close-"+id,"orders"))
        uncertain.getSuppressed.exists(_.getMessage=="injected rollback failure") shouldBe true
        broken.isClosed shouldBe true
      }finally {try c.rollback()finally {c.close();check.close();stale.close()}}
    }
  }
}
