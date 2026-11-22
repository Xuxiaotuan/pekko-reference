package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager}
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

/** Reader event/ACK probe; this is not a recovery-permit test. */
class ControlFenceProcedureReaderProbeSpec extends AnyWordSpec with Matchers {
  "a CALL marker with a filtered fence update" should {
    "record Debezium metadata, Reader binding, ACK order and JDBC offset" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_FENCE_PROCEDURE_READER").contains("1"))cancel("private MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("production-source-fence"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val base=sys.env("CAPTURE_TEST_URL");val stateUrl=sys.env("CAPTURE_STATE_URL");val port=sys.env("CDC_WINDOW_MYSQL_PORT").toInt
      require(port!=3306 && base.contains("pekko_test_shared_wire"))
      val dir=Path.of(sys.env("FENCE_PROCEDURE_READER_EVIDENCE"));Files.createDirectories(dir)
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val database="pekko_test_freader_"+suffix
      val readerUser="fr_"+suffix;val stateUser="fs_"+suffix;val fixturePassword="isolated-fixture-observer-password"
      val sourceUrl=base.replace("pekko_test_shared_wire",database)
      def open(url:String)=DriverManager.getConnection(url,user,password)
      def exec(c:Connection,sql:String):Unit={val s=c.createStatement();try {s.execute(sql);()}finally s.close()}
      def scalar(c:Connection,sql:String):String={val s=c.createStatement();try {val r=s.executeQuery(sql);try {require(r.next());r.getString(1)}finally r.close()}finally s.close()}
      val admin=open(base);try {
        exec(admin,s"CREATE DATABASE $database");exec(admin,s"CREATE USER '$readerUser'@'127.0.0.1' IDENTIFIED BY '$fixturePassword'")
        exec(admin,s"GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '$readerUser'@'127.0.0.1'")
      }finally admin.close()
      val stateAdmin=open(stateUrl);val stateUuid=try {
        val id=scalar(stateAdmin,"SELECT @@server_uuid");val db=scalar(stateAdmin,"SELECT DATABASE()")
        require(db.matches("pekko_test_[A-Za-z0-9_]+"));exec(stateAdmin,s"CREATE USER '$stateUser'@'%' IDENTIFIED BY '$fixturePassword'")
        exec(stateAdmin,s"GRANT ALL PRIVILEGES ON `$db`.* TO '$stateUser'@'%'");id
      }finally stateAdmin.close()
      val setup=open(sourceUrl);val lineage=try {
        exec(setup,"CREATE TABLE orders(id BIGINT PRIMARY KEY,label VARCHAR(64)) ENGINE=InnoDB")
        exec(setup,"CREATE TABLE pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(setup,"CREATE TABLE pekko_capture_control_fence_v1(domain_id VARCHAR(16) PRIMARY KEY,source_uuid CHAR(36) NOT NULL,database_name VARCHAR(64) NOT NULL,epoch BIGINT NOT NULL,owner_token CHAR(64) NOT NULL,phase VARCHAR(16) NOT NULL,version BIGINT NOT NULL,operation_id VARCHAR(128) NOT NULL,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")
        exec(setup,CaptureControlFence.ReleaseTableDdl)
        val uuid=scalar(setup,"SELECT @@server_uuid")
        val q=setup.prepareStatement("INSERT INTO pekko_capture_control_fence_v1 VALUES('default',?,?,0,'','OPEN',0,'','','','')")
        try {q.setString(1,uuid);q.setString(2,database);q.executeUpdate()}finally q.close()
        CaptureControlFence.TriggerDdl.foreach(exec(setup,_));exec(setup,CaptureControlFence.OpenProcedureDdl)
        exec(setup,s"GRANT EXECUTE ON PROCEDURE `$database`.pekko_control_write_open_v1 TO '$readerUser'@'127.0.0.1'");uuid
      }finally setup.close()
      val events=new ConcurrentLinkedQueue[JsObject]()
      def emit(kind:String,v:JsObject):Unit=events.add(JsObject(v.fields+("kind"->JsString(kind))))
      val id="freader_"+suffix;val store=new MySQLCaptureStore(()=>DriverManager.getConnection(stateUrl,stateUser,fixturePassword),id,lineage,10000000);store.initialize()
      val source=MySQLCdcSourceConfig(id,"127.0.0.1",port,database,"orders",readerUser,fixturePassword,310003,1,100)
      val state=MySQLCdcStateConfig(stateUrl,stateUser,fixturePassword,"capture_offsets","capture_history",0)
      val observed=new CaptureObservedFactory(emit)
      val observer=CaptureResourceObserver.exclusiveAccounts(()=>open(sourceUrl),()=>open(stateUrl),readerUser,stateUser,lineage,stateUuid)
      val reader=new SharedMySQLCapture(source,state,store,observed,emit,resourceObserver=Some(observer),closeBudget=CaptureCloseBudget(5000,1000),
        sourceFenceRequired=true,sourceFenceObserver=Some(()=>open(sourceUrl)),sourceFenceUuid=Some(lineage))
      def await(label:String)(done: => Boolean):Unit={val deadline=System.nanoTime()+45.seconds.toNanos
        while(!done && reader.failure.isEmpty && System.nanoTime()<deadline)Thread.sleep(20)
        withClue(label){done shouldBe true}}
      def checkpoint():String={val c=open(stateUrl);try {val name=MySQLCdcSourceNode.connectorStateTables(state,id)._1
        scalar(c,s"SELECT COALESCE(MAX(offset_val),'') FROM `$name`")}finally c.close()}
      def version():String={val c=open(sourceUrl);try scalar(c,"SELECT version FROM pekko_capture_control_fence_v1 WHERE domain_id='default'")finally c.close()}
      def callMarker():Unit={val c=open(sourceUrl);try {c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false)
        val q=c.prepareCall("CALL "+CaptureControlFence.OpenProcedure+"(?,?,?,?)")
        try {q.setString(1,store.markerId("manual_intent",false));q.setString(2,"SUBSCRIBE");q.setString(3,"orders");q.setString(4,"g1");q.execute()}finally q.close();c.commit()
      }finally c.close()}
      try {
        reader.start();await("schema and Reader admission"){store.schema("orders").nonEmpty && reader.startupEvidence.phase=="ADMISSION_READY"}
        val before=checkpoint();val priorAcks=events.iterator().asScala.count(_.fields.get("kind").contains(JsString("SDK_MARK_BATCH_FINISHED_RETURNED")))
        reader.subscribe("manual_intent","orders","g1")
        await("CALL marker bind"){store.subscription("manual_intent").exists(_.status=="CAPTURE_READY")}
        await("raw ACK"){events.iterator().asScala.count(_.fields.get("kind").contains(JsString("SDK_MARK_BATCH_FINISHED_RETURNED")))>priorAcks}
        await("durable SDK offset advances"){checkpoint()!=before}
        val firstVersion=version();callMarker();val duplicateVersion=version();duplicateVersion shouldBe firstVersion
        val guard=open(sourceUrl);try {
          guard.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);guard.setAutoCommit(false)
          val binding=CaptureFenceBinding(lineage,database,1L,"b"*64,firstVersion.toLong,store.markerId("manual_intent",false),"SUBSCRIBE","orders","g1")
          CaptureControlFence.acquire(guard,binding);guard.commit()
          val blockedId="blocked_"+suffix
          val blockedStore=new MySQLCaptureStore(()=>DriverManager.getConnection(stateUrl,stateUser,fixturePassword),blockedId,lineage,10000000)
          blockedStore.initialize()
          val blocked=new SharedMySQLCapture(source.copy(connectorId=blockedId),state,blockedStore,
            sourceFenceRequired=true,sourceFenceObserver=Some(()=>open(sourceUrl)),sourceFenceUuid=Some(lineage))
          intercept[CaptureAdmissionException](blocked.start()).code shouldBe "SOURCE_FENCE_ACTIVE"
          val receipt=cn.xuyinyin.magic.SourceFencePrimitiveProbe.write(guard,binding);guard.commit()
          cn.xuyinyin.magic.SourceFencePrimitiveProbe.release(guard,receipt,"permit-1","d"*64);guard.commit()
          exec(guard,"INSERT INTO orders VALUES(1,'after-release')");guard.commit()
        }finally guard.close()
        await("business change after release"){store.read("manual_intent",0,100).nonEmpty}
        val after=checkpoint();val all=events.iterator().asScala.toVector
        val hidden=all.filter(e=>e.fields.get("kind").contains(JsString("ORIGINAL_RECORD")) && e.fields.get("schema").exists(!_.toString.contains("SchemaChangeValue")) && e.fields.get("data").exists {
          case data:JsObject=>data.fields.get("source").exists {case src:JsObject=>src.fields.get("table").exists {
            case JsString(t)=>Set("pekko_capture_control_fence_v1","pekko_capture_control_release_v1")(t)
            case _=>false};case _=>false};case _=>false})
        val ends=all.filter(e=>e.fields.get("kind").contains(JsString("ORIGINAL_RECORD")) && e.fields.get("schema").exists(_.toString.contains("TransactionMetadataValue")) && e.fields.get("data").exists(_.toString.contains("END")))
        Files.writeString(dir.resolve("events.jsonl"),all.map(_.compactPrint).mkString("","\n","\n"))
        val internalSchemaEvents=all.count(e=>e.fields.get("schema").exists(_.toString.contains("SchemaChangeValue")) &&
          e.fields.get("data").exists(x=>x.toString.contains("pekko_capture_control_fence_v1") || x.toString.contains("pekko_capture_control_release_v1")))
        store.schema(CaptureControlFence.FenceTable) shouldBe None
        store.schema(CaptureControlFence.ReleaseTable) shouldBe None
        Files.writeString(dir.resolve("result.json"),JsObject("status"->JsString(store.subscription("manual_intent").get.status),"D_before"->JsString(before),"D_after"->JsString(after),"version_first"->JsString(firstVersion),"version_duplicate"->JsString(duplicateVersion),"internalSchemaEvents"->JsNumber(internalSchemaEvents),"internalRowEvents"->JsNumber(hidden.size),"end"->JsArray(ends),"readerFailure"->reader.failure.map(x=>JsString(x.toString)).getOrElse(JsNull)).prettyPrint)
        val markerEnd=ends.filter(_.fields("data").asJsObject.fields("data_collections").toString.contains("pekko_capture_control")).last
        markerEnd.fields("data").asJsObject.fields("event_count") shouldBe JsNumber(1)
        hidden shouldBe empty
      }finally {
        Files.writeString(dir.resolve("events-final.jsonl"),events.iterator().asScala.toVector.map(_.compactPrint).mkString("","\n","\n"))
        var c=open(sourceUrl);var stoppedSource=false
        def lab(action:String,crash:Boolean):Unit={
            require(port==25521 && sys.env.get("FENCE_SOURCE_CRASH_ALLOWED").contains("1"),"source crash is not authorized for this fixture")
            val args=Vector("python3","scripts/experiments/cdc-lab-pool/pool.py","--config",sys.env("CDC_LAB_CONFIG"),action,
              "--token-file",sys.env("CDC_LAB_TOKEN"),"--role","source")++(if(crash)Vector("--crash")else Vector.empty)++Vector("a")
            val p=new ProcessBuilder(args:_*).redirectErrorStream(true).start();val out=scala.io.Source.fromInputStream(p.getInputStream)
            val text=try out.mkString finally out.close();val exit=p.waitFor();Files.writeString(dir.resolve("lab-"+action+".txt"),s"exit=$exit\n$text")
            require(exit==0,"isolated source operation failed")
        }
        try {
          val mode=sys.env.getOrElse("FENCE_CLOSE_MODE","write");require(Set("idle","write","disconnect","socket","crash","cleanup")(mode))
          def sockets():String={val p=new ProcessBuilder("lsof","-nP","-iTCP:"+port).redirectErrorStream(true).start()
            val out=scala.io.Source.fromInputStream(p.getInputStream);try out.mkString finally {out.close();p.waitFor()}}
          def threads():String=Thread.getAllStackTraces.keySet().asScala.toVector.filter(t=>{
            val n=t.getName.toLowerCase(java.util.Locale.ROOT);n.contains("binlog") || n.contains("debezium") || n.contains("capture-close")
          }).map(t=>s"${t.getId}\t${t.getName}\t${t.getState}").sorted.mkString("\n")
          val idQuery=c.prepareStatement("SELECT ID FROM information_schema.PROCESSLIST WHERE USER=? AND COMMAND LIKE 'Binlog Dump%'")
          val before=try {idQuery.setString(1,readerUser);val r=idQuery.executeQuery();try {val ids=Vector.newBuilder[Long];while(r.next())ids+=r.getLong(1);ids.result()}finally r.close()}finally idQuery.close()
          require(before.size<=1,"ambiguous fixture binlog sessions")
          val processBefore=before.map(cid=>s"$cid\t"+scalar(c,s"SELECT CONCAT(USER,'|',HOST,'|',COMMAND) FROM information_schema.PROCESSLIST WHERE ID=$cid"))
          val serverStart=scalar(c,"SELECT CAST(UNIX_TIMESTAMP()-VARIABLE_VALUE AS SIGNED) FROM performance_schema.global_status WHERE VARIABLE_NAME='Uptime'").toLong
          Files.writeString(dir.resolve("close-sockets-before.txt"),sockets());Files.writeString(dir.resolve("close-threads-before.txt"),threads())
          if(mode=="disconnect")observer.close()
          if(mode=="socket"){require(before.size==1,"fixture binlog connection missing");exec(c,s"KILL CONNECTION ${before.head}")}
          if(mode=="crash"){
            require(scalar(c,"SELECT @@server_uuid")==sys.env("CAPTURE_EXPECT_SOURCE_UUID"),"source instance changed")
            stoppedSource=true;lab("stop",crash=true)
          }
          val started=System.nanoTime();val localClose=scala.util.Try(reader.close());val first=Await.result(reader.requestClose(),10.seconds)
          val elapsed=(System.nanoTime()-started)/1000000L
          if(stoppedSource){lab("start",crash=false);stoppedSource=false;try c.close()catch{case _:Throwable=>()};c=open(sourceUrl)}
          val serverStartAfter=scalar(c,"SELECT CAST(UNIX_TIMESTAMP()-VARIABLE_VALUE AS SIGNED) FROM performance_schema.global_status WHERE VARIABLE_NAME='Uptime'").toLong
          val remains=before.filter(id=>scalar(c,s"SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=$id")=="1")
          Files.writeString(dir.resolve("close-sockets-after.txt"),sockets());Files.writeString(dir.resolve("close-threads-after.txt"),threads())
          val ownerCheck=open(stateUrl);val available=try {val q=ownerCheck.prepareStatement("SELECT GET_LOCK(?,0)")
            try {q.setString(1,"pekko_capture_"+id);val r=q.executeQuery();try {r.next();r.getInt(1)==1}finally r.close()}finally q.close()
          }finally ownerCheck.close()
          if(!first.reusable)available shouldBe false
          var killed=false;var unrelatedSurvived=false
          if(mode=="cleanup" && remains.nonEmpty){
            require(first.localTermination=="CONFIRMED" && !first.reusable && !available && remains.size==1,"fixture release not safely isolated")
            require(scalar(c,"SELECT @@server_uuid")==lineage,"fixture server changed")
            val nowStart=scalar(c,"SELECT CAST(UNIX_TIMESTAMP()-VARIABLE_VALUE AS SIGNED) FROM performance_schema.global_status WHERE VARIABLE_NAME='Uptime'").toLong
            require(math.abs(nowStart-serverStart)<=2,"fixture server restarted")
            val id=remains.head;val current=s"$id\t"+scalar(c,s"SELECT CONCAT(USER,'|',HOST,'|',COMMAND) FROM information_schema.PROCESSLIST WHERE ID=$id")
            require(processBefore.contains(current),"fixture session identity changed")
            val unrelated=DriverManager.getConnection(sourceUrl,readerUser,fixturePassword)
            try {
              val otherId=scalar(unrelated,"SELECT CONNECTION_ID()").toLong;require(otherId!=id,"fixture id collision")
              val wrong=s"$otherId\t"+scalar(c,s"SELECT CONCAT(USER,'|',HOST,'|',COMMAND) FROM information_schema.PROCESSLIST WHERE ID=$otherId")
              intercept[IllegalArgumentException](require(processBefore.contains(wrong),"wrong session rejected"))
              exec(c,s"KILL CONNECTION $id");killed=true;unrelatedSurvived=scalar(unrelated,"SELECT 1")=="1"
              require(unrelatedSurvived,"unrelated fixture session was interrupted")
            }finally unrelated.close()
          }
          var finalRelease=first;val observations=Vector.newBuilder[JsValue];val deadline=System.nanoTime()+(if(Set("disconnect","crash")(mode))3.seconds else 15.seconds).toNanos
          var writeId=2
          while(!finalRelease.reusable && System.nanoTime()<deadline){
            if(mode=="write"){exec(c,s"INSERT INTO orders VALUES($writeId,'close-wakeup')");writeId+=1}
            finalRelease=Await.result(reader.refreshRelease(),10.seconds)
            observations+=JsObject("elapsedMillis"->JsNumber((System.nanoTime()-started)/1000000L),"server"->JsString(finalRelease.serverRelease),"reusable"->JsBoolean(finalRelease.reusable))
            if(!finalRelease.reusable)Thread.sleep(100)
          }
          Files.writeString(dir.resolve("close-result.json"),JsObject("mode"->JsString(mode),"localClose"->JsString(localClose.fold(_.getClass.getSimpleName,_=>"RETURNED")),"readerUser"->JsString(readerUser),"processBefore"->JsArray(processBefore.map(JsString(_))),"binlogIds"->JsArray(before.map(JsNumber(_))),"remainedAfterClose"->JsArray(remains.map(JsNumber(_))),
            "serverStartBefore"->JsNumber(serverStart),"serverStartAfter"->JsNumber(serverStartAfter),
            "elapsedMillis"->JsNumber(elapsed),"initialLocal"->JsString(first.localTermination),"initialServer"->JsString(first.serverRelease),
            "initialReusable"->JsBoolean(first.reusable),"competingOwnerAcquired"->JsBoolean(available),"lastServer"->JsString(finalRelease.serverRelease),"lastReusable"->JsBoolean(finalRelease.reusable),
            "verdict"->JsString(if(finalRelease.reusable)"RELEASE_CONFIRMED"else "SAFE_PENDING"),"writes"->JsNumber(writeId-2),"fixtureKilledBoundSession"->JsBoolean(killed),"unrelatedSessionSurvived"->JsBoolean(unrelatedSurvived),
            "observations"->JsArray(observations.result())).prettyPrint)
          if(Set("disconnect","crash")(mode))finalRelease.reusable shouldBe false
        }finally try {if(stoppedSource)lab("start",crash=false)} finally {
          try reader.close()catch {case _:Throwable=>()};c.close()
        }
      }
    }
  }
}
