package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.sql.{Connection,DriverManager}
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue,CountDownLatch,Executors,TimeUnit}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try
import spray.json._

/** Real lost COMMIT reply on a source controller primitive; not a guardian or Journal test. */
class SourceFenceCommitUnknownSpec extends AnyWordSpec with Matchers {
  "a controller source COMMIT with lost reply" should {
    "read back the exact source state using a fresh connection" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SOURCE_FENCE_UNKNOWN").contains("1"))cancel("private MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("production-source-fence"))
      val base=sys.env("CAPTURE_TEST_URL");require(base.contains("pekko_test_shared_wire"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD");val port=sys.env("CDC_WINDOW_MYSQL_PORT").toInt
      val output=Path.of(sys.env("SOURCE_FENCE_UNKNOWN_EVIDENCE"));Files.createDirectories(output)
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val schema="pekko_test_unk_"+suffix
      val writer="fw_"+suffix;val controller="fc_"+suffix;val pw=UUID.randomUUID().toString.replace("-","")
      def url(p:Int)=base.replace("pekko_test_shared_wire",schema).replace(":"+port+"/",":"+p+"/")+"&connectTimeout=5000&socketTimeout=5000"
      def open(p:Int,u:String=user,pass:String=password)=DriverManager.getConnection(url(p),u,pass)
      def exec(c:Connection,s:String):Unit={val q=c.createStatement();try {q.execute(s);()}finally q.close()}
      def scalar(c:Connection,s:String):String={val q=c.createStatement();try {val r=q.executeQuery(s);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val admin=DriverManager.getConnection(base,user,password);exec(admin,s"CREATE DATABASE $schema")
      val source=open(port);val uuid=try {
        exec(source,"CREATE TABLE pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(source,"CREATE TABLE pekko_capture_control_fence_v1(domain_id VARCHAR(16) PRIMARY KEY,source_uuid CHAR(36) NOT NULL,database_name VARCHAR(64) NOT NULL,epoch BIGINT NOT NULL,owner_token CHAR(64) NOT NULL,phase VARCHAR(16) NOT NULL,version BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(source,CaptureControlFence.ReleaseTableDdl)
        val id=scalar(source,"SELECT @@server_uuid");val q=source.prepareStatement("INSERT INTO pekko_capture_control_fence_v1 VALUES('default',?,?,0,'','OPEN',0,'','','','')")
        try {q.setString(1,id);q.setString(2,schema);q.executeUpdate()}finally q.close()
        CaptureControlFence.TriggerDdl.foreach(exec(source,_));exec(source,CaptureControlFence.OpenProcedureDdl)
        exec(admin,s"CREATE USER '$writer'@'127.0.0.1' IDENTIFIED BY '$pw'");exec(admin,s"CREATE USER '$controller'@'127.0.0.1' IDENTIFIED BY '$pw'")
        exec(admin,s"GRANT SELECT ON `$schema`.* TO '$writer'@'127.0.0.1'");exec(admin,s"GRANT EXECUTE ON PROCEDURE `$schema`.pekko_control_write_open_v1 TO '$writer'@'127.0.0.1'")
        exec(admin,s"GRANT SELECT ON `$schema`.* TO '$controller'@'127.0.0.1'");exec(admin,s"GRANT SELECT,INSERT,UPDATE ON `$schema`.pekko_capture_control TO '$controller'@'127.0.0.1'")
        exec(admin,s"GRANT SELECT,UPDATE ON `$schema`.pekko_capture_control_fence_v1 TO '$controller'@'127.0.0.1'")
        exec(admin,s"GRANT SELECT,INSERT ON `$schema`.pekko_capture_control_release_v1 TO '$controller'@'127.0.0.1'");id
      }finally {source.close();admin.close()}
      val events=new ConcurrentLinkedQueue[JsObject]();val held=new CountDownLatch(1);val releaseHeld=new CountDownLatch(1)
      def emit(k:String,v:JsObject):Unit=events.add(JsObject(v.fields+("kind"->JsString(k))))
      val proxy=new MySQLCommitResponseProxy("127.0.0.1",port,(k,v)=>{emit(k,v);if(k=="PROXY_COMMIT_OK_HELD")held.countDown()},advertisePlaintextOnly=true)
      val releaseProxy=new MySQLCommitResponseProxy("127.0.0.1",port,(k,v)=>{emit(k,v);if(k=="PROXY_COMMIT_OK_HELD")releaseHeld.countDown()},advertisePlaintextOnly=true)
      val executor=Executors.newSingleThreadExecutor();implicit val ec:ExecutionContext=ExecutionContext.fromExecutorService(executor)
      def tx(c:Connection):Unit={c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false)}
      val binding=CaptureFenceBinding(uuid,schema,1L,"b"*64,0L,"a"*64,"SUBSCRIBE","orders","g1")
      val observer=open(port);val controllerConn=open(proxy.port,controller,pw);val writerConn=open(port,writer,pw);tx(observer);tx(controllerConn)
      try {
        val p=CaptureControlFence.protectController(observer,controllerConn,uuid,schema,writerConn)
        p.acquire(binding);controllerConn.commit();observer.commit()
        val receipt=cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(p,binding)
        proxy.arm("C2",JsObject("stage"->JsString("SOURCE_CONTROLLER_COMMIT")))
        val commit=Future(Try(controllerConn.commit()))
        held.await(20,TimeUnit.SECONDS) shouldBe true
        proxy.dropHeldResponse();val outcome=Await.result(commit,20.seconds);outcome.isFailure shouldBe true
        try controllerConn.close()catch {case _:Throwable=>()};observer.rollback();observer.close()
        val leaked=open(port,controller,pw);val leakedId=scalar(leaked,"SELECT CONNECTION_ID()").toLong
        val failingClose=Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
          def invoke(target:Any,method:Method,args:Array[AnyRef]):AnyRef={
            if(method.getName=="close")throw new java.sql.SQLException("injected local close failure")
            try method.invoke(leaked,Option(args).getOrElse(Array.empty[AnyRef]):_*)catch {case e:InvocationTargetException=>throw e.getCause}
          }
        }).asInstanceOf[Connection]
        failingClose.setAutoCommit(false);scalar(failingClose,"SELECT 1") shouldBe "1";failingClose.commit()
        intercept[java.sql.SQLException](failingClose.close())
        val releaseObserver=open(port)
        try {
          scalar(releaseObserver,s"SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=$leakedId") shouldBe "1"
          scalar(releaseObserver,s"SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id=$leakedId") shouldBe "0"
          Files.writeString(output.resolve("close-failure.json"),JsObject("connectionId"->JsNumber(leakedId),"commit"->JsString("returned"),
            "close"->JsString("injected-exception-before-physical-close"),"serverSessionCount"->JsNumber(1),"serverTransactionCount"->JsNumber(0)).prettyPrint)
        }finally {releaseObserver.close();leaked.close()}
        val freshObserver=open(port);val freshController=open(releaseProxy.port,controller,pw);tx(freshObserver);tx(freshController)
        try {
          val fresh=CaptureControlFence.protectController(freshObserver,freshController,uuid,schema,writerConn)
          val observation=fresh.observe();fresh.matches(receipt) shouldBe true
          var escaped:CaptureControlFence.VerifiedSourceReceipt=null
          fresh.withVerifiedReceipt(receipt){p=>p.requireLive();escaped=p};intercept[IllegalArgumentException](escaped.requireLive())
          cn.xuyinyin.magic.SourceFencePrimitiveProbe.release(fresh,receipt,"permit-1","d"*64)
          releaseProxy.arm("C2",JsObject("stage"->JsString("SOURCE_RELEASE_COMMIT")))
          val releaseCommit=Future(Try(freshController.commit()));releaseHeld.await(20,TimeUnit.SECONDS) shouldBe true
          releaseProxy.dropHeldResponse();Await.result(releaseCommit,20.seconds).isFailure shouldBe true
          freshObserver.rollback();freshObserver.close();freshController.close()
          writerConn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);writerConn.setAutoCommit(false)
          val q=writerConn.prepareCall("CALL "+CaptureControlFence.OpenProcedure+"(?,?,?,?)")
          try {q.setString(1,"c"*64);q.setString(2,"SUBSCRIBE");q.setString(3,"other");q.setString(4,"g2");q.execute()}finally q.close();writerConn.commit()
          val againObserver=open(port);val againController=open(port,controller,pw);tx(againObserver);tx(againController)
          try {
            val again=CaptureControlFence.protectController(againObserver,againController,uuid,schema,writerConn)
            again.matchesReleased(receipt,"permit-1","d"*64) shouldBe true
            again.matchesReleased(receipt,"other-permit","d"*64) shouldBe false
            var expired:CaptureControlFence.VerifiedSourceRelease=null
            again.withVerifiedRelease(receipt,"permit-1","d"*64){proof=>proof.requireLive();expired=proof}
            intercept[IllegalArgumentException](expired.requireLive())
            val after=again.observe()
            val result=JsObject("commitCall"->JsString("failed-after-server-ok"),"phase"->JsString(observation.phase),"version"->JsNumber(observation.version),
              "markerCreated"->JsBoolean(receipt.markerCreated),"exactMarkerAndBinding"->JsBoolean(true),"controllerAccount"->JsString(again.account),
              "releaseCommit"->JsString("failed-after-server-ok"),"releaseReceiptMatched"->JsBoolean(true),"versionAfterLaterWrite"->JsNumber(after.version))
            Files.writeString(output.resolve("result.json"),result.prettyPrint);againController.rollback();againObserver.rollback()
          }finally {againController.close();againObserver.close()}
        }finally {freshController.close();freshObserver.close()}
      }finally {
        Files.writeString(output.resolve("events.jsonl"),events.iterator().asScala.toVector.map(_.compactPrint).mkString("","\n","\n"))
        try observer.close()catch{case _:Throwable=>()};try controllerConn.close()catch{case _:Throwable=>()};writerConn.close();proxy.close();releaseProxy.close();executor.shutdownNow()
      }
    }
  }
}
