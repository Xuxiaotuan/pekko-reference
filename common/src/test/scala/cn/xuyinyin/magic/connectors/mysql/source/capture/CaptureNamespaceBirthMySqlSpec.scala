package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.cdc._
import spray.json._
import java.sql.{Connection,DriverManager,SQLException}
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await,Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class CaptureNamespaceBirthMySqlSpec extends AnyWordSpec with Matchers {
  "the new capture namespace witness" should {
    "bind exact accounts, reject grants and roles, and lock the birth row" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_NAMESPACE_BIRTH_PROBE").contains("1"))cancel("fresh isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-namespace-birth"))
      Class.forName("com.mysql.cj.jdbc.Driver")
      val url=sys.env("CAPTURE_STATE_URL");require((url.contains(":25842/") || url.contains(":25852/") || url.contains(":25862/")) && url.contains("pekko_test_shared_state"))
      val adminUser=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val schema="pekko_gc_"+suffix;val user="nw_"+suffix
      val health="nh_"+suffix;val role="nr_"+suffix;val capture="capture_"+suffix;val nonce="a"*64
      val admin=DriverManager.getConnection(url,adminUser,password)
      def exec(c:Connection,s:String):Unit={val q=c.createStatement();try q.execute(s)finally q.close()}
      def query(c:Connection,s:String):Vector[Vector[String]]={val q=c.createStatement();try {val r=q.executeQuery(s);try {
        val b=Vector.newBuilder[Vector[String]];while(r.next())b+=(1 to r.getMetaData.getColumnCount).map(r.getString).toVector;b.result()
      }finally r.close()}finally q.close()}
      def createUser(name:String,host:String):Unit={val q=admin.prepareStatement(s"CREATE USER '$name'@'$host' IDENTIFIED BY ?")
        try {q.setString(1,password);q.execute()}finally q.close()}
      try {
        val before=query(admin,"SELECT USER,HOST FROM mysql.user WHERE NOT (HOST='localhost' AND USER IN ('mysql.infoschema','mysql.session','mysql.sys') AND account_locked='Y')").map(r=>r(0)+"@"+r(1)).toSet
        exec(admin,s"CREATE DATABASE `$schema`")
        exec(admin,s"CREATE TABLE `$schema`.pekko_sync_cdc_group_ledger(group_id CHAR(64) PRIMARY KEY,evidence LONGTEXT NOT NULL) ENGINE=InnoDB")
        exec(admin,s"CREATE TABLE `$schema`.pekko_capture_gc_birth_v1(namespace_version INT,server_uuid VARCHAR(64),database_name VARCHAR(64),capture_id VARCHAR(64) PRIMARY KEY,source_lineage VARCHAR(255),generation VARCHAR(128),writer_account VARCHAR(128),journal_database VARCHAR(64),birth_nonce CHAR(64),status VARCHAR(16),journal_initially_empty BOOLEAN,writer_issued_after_birth BOOLEAN) ENGINE=InnoDB")
        exec(admin,s"CREATE TRIGGER `$schema`.pekko_ledger_no_update BEFORE UPDATE ON `$schema`.pekko_sync_cdc_group_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger immutable'")
        exec(admin,s"CREATE TRIGGER `$schema`.pekko_ledger_no_delete BEFORE DELETE ON `$schema`.pekko_sync_cdc_group_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger immutable'")
        createUser(user,"127.0.0.1");createUser(health,"127.0.0.1")
        exec(admin,s"GRANT SELECT,INSERT,UPDATE,DELETE ON `$schema`.pekko_sync_cdc_group_ledger TO '$user'@'127.0.0.1'")
        exec(admin,s"GRANT SELECT ON `$schema`.pekko_capture_gc_birth_v1 TO '$user'@'127.0.0.1'")
        exec(admin,s"GRANT PROCESS ON *.* TO '$health'@'127.0.0.1'")
        val db=url.replace("pekko_test_shared_state",schema)
        def observer():Connection=DriverManager.getConnection(db,adminUser,password)
        def writer():Connection=DriverManager.getConnection(db,user,password)
        val o=observer();val w=writer()
        try {
          val uuid=query(o,"SELECT @@server_uuid").head.head;val definer=query(o,"SELECT CURRENT_USER()").head.head
          val writerAccount=query(w,"SELECT CURRENT_USER()").head.head;val healthAccount=health+"@127.0.0.1"
          val insert=o.prepareStatement("INSERT INTO pekko_capture_gc_birth_v1 VALUES(1,?,?,?,?,?,?,?,?, 'READY',TRUE,TRUE)")
          try {Vector(uuid,schema,capture,"source-lineage","generation",writerAccount,schema,nonce).zipWithIndex.foreach {case(v,i)=>insert.setString(i+1,v)};insert.executeUpdate() shouldBe 1}finally insert.close()
          val ddl=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-persistence-schema.sql")
          val ddlText=try scala.io.Source.fromInputStream(ddl).mkString finally ddl.close()
          ddlText.split(";").map(_.trim).filter(_.nonEmpty).foreach(exec(o,_))
          new MySQLCaptureStore(()=>observer(),capture,"source-lineage",10000000).initialize()
          Vector("event_journal","snapshot","pekko_capture_readers","pekko_capture_gc_state_v1","pekko_capture_consumers_v1",
            "pekko_capture_log","pekko_capture_subscriptions","pekko_capture_fences","pekko_capture_schemas").foreach {table=>
            exec(admin,s"GRANT SELECT,INSERT,UPDATE,DELETE ON `$schema`.`$table` TO '$user'@'127.0.0.1'")
          }
          val baseExpected=CaptureNamespaceBirth.Expected(uuid,schema,capture,"source-lineage","generation",writerAccount,definer,nonce,before,Set(healthAccount))
          w.setAutoCommit(false)
          CaptureNamespaceBirth.withVerified(o,w,baseExpected){()};w.rollback()
          // ACCOUNT LOCK prevents new logins; it does not close an existing privileged session.
          val staleUser="nl_"+suffix;createUser(staleUser,"127.0.0.1")
          exec(admin,s"GRANT UPDATE ON `$schema`.pekko_capture_gc_birth_v1 TO '$staleUser'@'127.0.0.1'")
          val stale=DriverManager.getConnection(db,staleUser,password)
          try {
            exec(admin,s"ALTER USER '$staleUser'@'127.0.0.1' ACCOUNT LOCK")
            query(stale,"SELECT CURRENT_USER()").head.head shouldBe staleUser+"@127.0.0.1"
            intercept[IllegalArgumentException](CaptureNamespaceBirth.withVerified(o,w,baseExpected){()})
          }finally {stale.close();w.rollback()}
          exec(admin,s"REVOKE UPDATE ON `$schema`.pekko_capture_gc_birth_v1 FROM '$staleUser'@'127.0.0.1'")
          exec(admin,s"ALTER USER '$staleUser'@'127.0.0.1' ACCOUNT UNLOCK")
          val expected=baseExpected.copy(auxiliaryAccounts=baseExpected.auxiliaryAccounts+(staleUser+"@127.0.0.1"))
          CaptureNamespaceBirth.withVerified(o,w,expected){
            val concurrent=Future {val c=observer();try {exec(c,"SET SESSION innodb_lock_wait_timeout=1");exec(c,s"UPDATE pekko_capture_gc_birth_v1 SET status='BAD' WHERE capture_id='$capture'");false}
              catch {case e:SQLException=>Option(e.getMessage).exists(_.toLowerCase.contains("lock wait timeout"))}finally c.close()}
            Await.result(concurrent,10.seconds) shouldBe true
          }
          w.rollback()
          CaptureNamespaceBirth.activateFreshOn(o,w,expected)
          def blocked(sql:String):Boolean={
            val attempt=Future {val c=writer();try {c.setAutoCommit(false);exec(c,"SET SESSION innodb_lock_wait_timeout=1");exec(c,sql);c.rollback();false}
              catch {case e:SQLException=>Option(e.getMessage).exists(_.toLowerCase.contains("lock wait timeout"))}finally c.close()}
            Await.result(attempt,10.seconds)
          }
          blocked("INSERT INTO event_journal(persistence_id,sequence_number,writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest) VALUES('new-id',1,'writer',0,'',X'01',1,'')") shouldBe true
          blocked("INSERT INTO snapshot(persistence_id,sequence_number,created,snapshot_ser_id,snapshot_ser_manifest,snapshot_payload) VALUES('new-id',1,0,1,'',X'01')") shouldBe true
          blocked("INSERT INTO pekko_capture_readers VALUES('other-capture','other',1,0,NULL)") shouldBe true
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
            .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx128m","-cp",cp,
            CaptureNamespaceLockWorker.getClass.getName.stripSuffix("$"),db,user).redirectErrorStream(true).start()
          val exited=child.waitFor(20,TimeUnit.SECONDS);if(!exited)child.destroyForcibly()
          val output=scala.io.Source.fromInputStream(child.getInputStream).mkString
          require(exited && child.exitValue()==0 && output.contains("NAMESPACE_JVM_RANGE_LOCK_TIMEOUT"),"independent range-lock JVM failed: "+output)
          println(output.trim)
          w.rollback()
          query(o,s"SELECT discovery_status FROM pekko_capture_gc_state_v1 WHERE capture_id='$capture'").head.head shouldBe "UNKNOWN"
          CaptureNamespaceBirth.activateFreshOn(o,w,expected);w.commit()
          query(o,s"SELECT discovery_status FROM pekko_capture_gc_state_v1 WHERE capture_id='$capture'").head.head shouldBe "COMPLETE"
          val store=new MySQLCaptureStore(()=>writer(),capture,"source-lineage",10000000)
          val schemaDef=store.rememberSchema("orders","""{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5,"typeName":"BIGINT"}]}""")
          store.register("req","orders","generation",schemaDef.id);store.bind("req",CapturePoint("mysql-bin.000001",100,0),"{}",stop=false)
          val protocol=MySQLCdcGroupProtocol(capture,"capture-v1","source-lineage")
          val envelope=MySQLCdcEnvelope(1,CdcOperation.Create,JsObject("id"->JsNumber(1)),None,Some(JsObject("id"->JsNumber(1))),
            MySQLCdcSourcePosition(capture,"db","orders",false,Some("mysql-bin.000001"),Some(110L),Some(0),Some(1)))
          val group=MySQLCdcGroup(MySQLCdcGroup.id(protocol,envelope),Vector("single.c"->envelope));store.append(group).get
          intercept[IllegalArgumentException](store.read("req",0,10))
          val consumer="c"*64
          intercept[IllegalArgumentException](store.readForConsumer("req",0,10,consumer))
          store.registerConsumer(consumer,"WORKFLOW","req","source",0)
          store.readForConsumer("req",0,10,consumer).map(_.group) shouldBe Vector(group)
          val direct=new CapturedLogSource(store,"db")
          intercept[IllegalArgumentException](direct.readBatch("req",0,10,"source","execution",0))
          direct.readBatch("req",0,10,"source","execution",0,Some(consumer)).get.rows.size shouldBe 1
          println("BIRTH_ACTIVATION journalRangeBlocked=true snapshotRangeBlocked=true captureRangeBlocked=true rollbackUnknown=true registeredRead=true bypassRejected=true")
          intercept[IllegalArgumentException](CaptureNamespaceBirth.withVerified(o,w,expected.copy(nonce="b"*64)){()});w.rollback()
          intercept[IllegalArgumentException](CaptureNamespaceBirth.withVerified(o,w,expected.copy(trustedAdmins=before-"cdc_lab@localhost")){()});w.rollback()
          createUser(user,"localhost")
          intercept[IllegalArgumentException](CaptureNamespaceBirth.withVerified(o,w,expected){()});w.rollback()
          exec(admin,s"ALTER USER '$user'@'localhost' ACCOUNT LOCK")
          exec(admin,s"GRANT UPDATE ON `$schema`.pekko_capture_gc_birth_v1 TO '$user'@'127.0.0.1'")
          intercept[IllegalArgumentException](CaptureNamespaceBirth.withVerified(o,w,expected){()});w.rollback()
          exec(admin,s"REVOKE UPDATE ON `$schema`.pekko_capture_gc_birth_v1 FROM '$user'@'127.0.0.1'")
          exec(admin,s"CREATE ROLE '$role'");exec(admin,s"GRANT SELECT ON `$schema`.pekko_capture_gc_birth_v1 TO '$role'")
          exec(admin,s"GRANT '$role' TO '$user'@'127.0.0.1'");exec(admin,s"SET DEFAULT ROLE NONE TO '$user'@'127.0.0.1'")
          val r=writer();r.setAutoCommit(false)
          try intercept[IllegalArgumentException](CaptureNamespaceBirth.withVerified(o,r,expected){()})finally r.close()
          val wildcard="wild_"+suffix;createUser(wildcard,"%")
          exec(admin,s"GRANT SELECT,INSERT,UPDATE,DELETE ON `$schema`.pekko_sync_cdc_group_ledger TO '$wildcard'@'%'")
          val broad=DriverManager.getConnection(db,wildcard,password);broad.setAutoCommit(false)
          try {val account=query(broad,"SELECT CURRENT_USER()").head.head
            account shouldBe wildcard+"@%"
            intercept[IllegalArgumentException](CaptureLedgerGuard.withVerified(o,broad,uuid,schema,account,definer){()})
          }finally broad.close()
          println(s"NAMESPACE_BIRTH schema=$schema exactHosts=true auxiliary=true birthLocked=true mismatchRejected=true roleRejected=true wildcardHostRejected=true")
        }finally {w.close();o.close()}
        query(admin,"SELECT COUNT(*) FROM information_schema.innodb_trx").head.head shouldBe "0"
        query(admin,"SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() AND USER NOT IN ('event_scheduler','system user')").head.head shouldBe "0"
      }finally admin.close()
    }
  }
}

object CaptureNamespaceLockWorker {
  def main(args:Array[String]):Unit={
    require(args.length==2);Class.forName("com.mysql.cj.jdbc.Driver")
    val c=DriverManager.getConnection(args(0),args(1),sys.env("CDC_WINDOW_MYSQL_PASSWORD"));c.setAutoCommit(false)
    try {val q=c.createStatement();try {
      q.execute("SET SESSION innodb_lock_wait_timeout=1")
      try {q.execute("INSERT INTO event_journal(persistence_id,sequence_number,writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest) VALUES('different-jvm-id',1,'writer',0,'',X'01',1,'')");throw new IllegalStateException("range lock did not block")}
      catch {case e:SQLException=>require(Option(e.getMessage).exists(_.toLowerCase.contains("lock wait timeout")));println(s"NAMESPACE_JVM_RANGE_LOCK_TIMEOUT pid=${ProcessHandle.current().pid()}")}
    }finally q.close()}finally c.close()
  }
}
