package cn.xuyinyin.magic.workflow.scheduling

import java.sql.{Connection,DriverManager,SQLException}
import java.util.UUID
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import java.net.URLClassLoader
import java.nio.file.{Files,Path}
import java.io.File
import java.util.concurrent.{CountDownLatch,Executors,TimeUnit}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Dedicated loopback database only: shows what account locking does and does not prove. */
class CutoverAccountMySqlSpec extends AnyWordSpec with Matchers {
  "a legacy account lock" should {
    "deny new connections without treating an existing transaction as drained" in {
      val url=sys.env.getOrElse("CDC_CONTROL_TEST_URL",cancel("dedicated MySQL test schema not configured"))
      require(url.startsWith("jdbc:mysql://127.0.0.1:"),"loopback-only fixture")
      val admin=DriverManager.getConnection(url,sys.env("CDC_CONTROL_TEST_USER"),sys.env("CDC_CONTROL_TEST_PASSWORD"))
      val user="cutover_"+UUID.randomUUID().toString.replace("-","").take(12)
      val limited="viewer_"+UUID.randomUUID().toString.replace("-","").take(12)
      val mandatory="mand_"+UUID.randomUUID().toString.replace("-","").take(12)
      val anonymousHost="probe-"+UUID.randomUUID().toString.replace("-","").take(12)+".invalid"
      val password=UUID.randomUUID().toString.replace("-","")
      val database=admin.getCatalog
      require(database.matches("pekko_control_[a-f0-9]+"),"dedicated schema required")
      def execute(c:Connection,sql:String):Unit={val s=c.createStatement();try s.execute(sql) finally s.close()}
      def value(c:Connection,sql:String):String={val s=c.createStatement();try {val r=s.executeQuery(sql);try {require(r.next());r.getString(1)} finally r.close()} finally s.close()}
      var old:Connection=null
      var ds:HikariDataSource=null
      try {
        Vector("db/mysql/pekko-table-scheduling-schema.sql","db/mysql/pekko-snapshot-ownership-schema.sql","db/mysql/pekko-snapshot-supervisor-schema.sql","db/mysql/pekko-legacy-lock-intent-schema.sql","db/mysql/pekko-snapshot-cutover-schema.sql").foreach { name=>
          val in=getClass.getClassLoader.getResourceAsStream(name);val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(execute(admin,_))
        }
        val cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(sys.env("CDC_CONTROL_TEST_USER"));cfg.setPassword(sys.env("CDC_CONTROL_TEST_PASSWORD"));cfg.setMaximumPoolSize(3)
        ds=new HikariDataSource(cfg)
        val store=new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
        val metadata=new SnapshotSupervisorStore(store,GuardianBinding("d","h","g","key"),1);metadata.enroll()
        var first=metadata.claimController("first")
        val executor=Executors.newFixedThreadPool(2)
        val entered=new CountDownLatch(1);val release=new CountDownLatch(1);val trying=new CountDownLatch(1);val claimed=new CountDownLatch(1)
        try {
          val inFlight=executor.submit(new Runnable {def run():Unit=metadata.fenced(first){entered.countDown();require(release.await(5,TimeUnit.SECONDS))}})
          entered.await(5,TimeUnit.SECONDS) shouldBe true
          val replacement=executor.submit(new Runnable {def run():Unit={trying.countDown();metadata.claimController("competing");claimed.countDown()}})
          trying.await(5,TimeUnit.SECONDS) shouldBe true
          claimed.await(250,TimeUnit.MILLISECONDS) shouldBe false
          release.countDown();inFlight.get(5,TimeUnit.SECONDS);replacement.get(5,TimeUnit.SECONDS)
          intercept[IllegalArgumentException](metadata.fenced(first){()})
          first=metadata.claimController("first")
        } finally {release.countDown();executor.shutdownNow()}
        execute(admin,"CREATE TABLE account_probe(id INT PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB")
        execute(admin,"INSERT INTO account_probe VALUES (1,0)")
        execute(admin,s"CREATE USER '$user'@'127.0.0.1' IDENTIFIED BY '$password'")
        execute(admin,s"GRANT SELECT,UPDATE ON `$database`.account_probe TO '$user'@'127.0.0.1'")
        old=DriverManager.getConnection(url,user,password);old.setAutoCommit(false)
        val connectionId=value(old,"SELECT CONNECTION_ID()")
        execute(old,"UPDATE account_probe SET value=1 WHERE id=1")
        value(admin,s"SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=$connectionId") shouldBe "1"
        val uuid=value(admin,"SELECT @@server_uuid")
        val account=LegacyMySqlAccountLock.Account(user,"127.0.0.1")
        admin.setAutoCommit(false)
        execute(admin,"INSERT INTO account_probe VALUES (2,2)")
        intercept[IllegalArgumentException](LegacyMySqlAccountLock.Account(user,"%"))
        val adminUser=sys.env("CDC_CONTROL_TEST_USER");val adminPassword=sys.env("CDC_CONTROL_TEST_PASSWORD")
        intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url+"&connect%54imeout=0",adminUser,adminPassword,uuid,account,Set(adminUser)))
        val admin2=DriverManager.getConnection(url,adminUser,adminPassword)
        try execute(admin2,s"CREATE USER '$user'@'localhost' IDENTIFIED BY '$password'") finally admin2.close()
        intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser)))
        val admin3=DriverManager.getConnection(url,adminUser,adminPassword)
        val rootProxyCount=try {
          execute(admin3,s"DROP USER '$user'@'localhost'")
          value(admin3,"SELECT COUNT(*) FROM mysql.proxies_priv WHERE User='root' AND Host='localhost' AND Proxied_user=''")
        } finally admin3.close()
        val rootProxy=LegacyMySqlAccountLock.Account("root","localhost")
        rootProxyCount shouldBe "1"
        intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser)))
        val grantAdmin=DriverManager.getConnection(url,adminUser,adminPassword)
        try {
          value(grantAdmin,"SELECT @@GLOBAL.mandatory_roles") shouldBe ""
          execute(grantAdmin,s"CREATE ROLE '$mandatory'@'%'")
          try {
            execute(grantAdmin,s"SET GLOBAL mandatory_roles='$mandatory@%'")
            intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser),Set(rootProxy)))
          } finally {execute(grantAdmin,"SET GLOBAL mandatory_roles=''");execute(grantAdmin,s"DROP ROLE '$mandatory'@'%'")}
          execute(grantAdmin,s"CREATE USER ''@'$anonymousHost'")
          try intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser),Set(rootProxy)))
          finally execute(grantAdmin,s"DROP USER ''@'$anonymousHost'")
          execute(grantAdmin,s"GRANT CREATE USER ON *.* TO '$user'@'127.0.0.1'")
          try intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser),Set(rootProxy)))
          finally execute(grantAdmin,s"REVOKE CREATE USER ON *.* FROM '$user'@'127.0.0.1'")
          execute(grantAdmin,s"GRANT UPDATE ON *.* TO '$user'@'127.0.0.1'")
          try intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser),Set(rootProxy)))
          finally execute(grantAdmin,s"REVOKE UPDATE ON *.* FROM '$user'@'127.0.0.1'")
          execute(grantAdmin,s"GRANT UPDATE ON `m%`.* TO '$user'@'127.0.0.1'")
          try intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser),Set(rootProxy)))
          finally execute(grantAdmin,s"REVOKE UPDATE ON `m%`.* FROM '$user'@'127.0.0.1'")
          execute(grantAdmin,s"GRANT EXECUTE ON `$database`.* TO '$user'@'127.0.0.1'")
          try intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser),Set(rootProxy)))
          finally execute(grantAdmin,s"REVOKE EXECUTE ON `$database`.* FROM '$user'@'127.0.0.1'")
        } finally grantAdmin.close()
        val viewAdmin=DriverManager.getConnection(url,adminUser,adminPassword)
        try {
          execute(viewAdmin,"CREATE TABLE definer_probe_table(id INT PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB")
          val objects=Vector(
            (s"CREATE DEFINER='$user'@'127.0.0.1' VIEW account_definer_probe AS SELECT id,value FROM definer_probe_table","DROP VIEW IF EXISTS account_definer_probe"),
            (s"CREATE DEFINER='$user'@'127.0.0.1' PROCEDURE account_definer_proc() SELECT 1","DROP PROCEDURE IF EXISTS account_definer_proc"),
            (s"CREATE DEFINER='$user'@'127.0.0.1' TRIGGER account_definer_trigger BEFORE INSERT ON definer_probe_table FOR EACH ROW SET NEW.value=NEW.value","DROP TRIGGER IF EXISTS account_definer_trigger"),
            (s"CREATE DEFINER='$user'@'127.0.0.1' EVENT account_definer_event ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 1 DAY DO SET @probe=1","DROP EVENT IF EXISTS account_definer_event"))
          objects.foreach {case(create,drop)=>
            execute(viewAdmin,create)
            try intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(adminUser),Set(rootProxy)))
            finally execute(viewAdmin,drop)
          }
          execute(viewAdmin,s"CREATE USER '$limited'@'127.0.0.1' IDENTIFIED BY '$password'")
          execute(viewAdmin,s"GRANT SELECT ON `$database`.* TO '$limited'@'127.0.0.1'")
          execute(viewAdmin,s"GRANT SELECT ON mysql.user TO '$limited'@'127.0.0.1'")
          execute(viewAdmin,s"GRANT SELECT ON mysql.proxies_priv TO '$limited'@'127.0.0.1'")
          execute(viewAdmin,s"GRANT CREATE USER ON *.* TO '$limited'@'127.0.0.1'")
          val missing=intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,limited,password,uuid,account,Set(adminUser,limited),Set(rootProxy)))
          missing.getMessage should include ("complete DEFINER inventory")
          val missingProcess=intercept[IllegalArgumentException](LegacyMySqlAccountLock.verifyDrain(url,limited,password,uuid,account,Set(rootProxy)))
          missingProcess.getMessage should include ("PROCESS privilege")
          value(viewAdmin,s"SELECT account_locked FROM mysql.user WHERE User='$user' AND Host='127.0.0.1'") shouldBe "N"
        } finally {try {execute(viewAdmin,"DROP VIEW IF EXISTS account_definer_probe");execute(viewAdmin,"DROP USER IF EXISTS '"+limited+"'@'127.0.0.1'")} finally viewAdmin.close()}
        intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,"wrong-server",account,Set(adminUser)))
        intercept[IllegalArgumentException](LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,uuid,account,Set(user)))
        val plan=LegacyAccountLockIntent.Plan("op-test",uuid,account,Set(adminUser),Set(rootProxy))
        val intents=new LegacyAccountLockIntent(metadata)
        intents.prepare(first,plan).phase shouldBe "INTENT"
        val urls=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
          .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
        val cp=(urls++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
        val dir=Path.of("target","legacy-intent-probe");Files.createDirectories(dir)
        val output=dir.resolve("crash-"+UUID.randomUUID().toString+".log")
        val child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx256m","-cp",cp,
          LegacyIntentCrashProbe.getClass.getName.stripSuffix("$"),uuid,user).redirectErrorStream(true).redirectOutput(output.toFile).start()
        try {
          withClue(s"fixed-window child did not exit; log=$output: "){child.waitFor(40,TimeUnit.SECONDS) shouldBe true}
          withClue(s"fixed-window child did not reach the DDL cutpoint; log=$output: "){child.exitValue() shouldBe 37}
        } finally if(child.isAlive){child.destroyForcibly();child.waitFor()}
        val check=DriverManager.getConnection(url,adminUser,adminPassword)
        try value(check,"SELECT phase FROM pekko_legacy_account_lock_intent WHERE operation_id='op-test'") shouldBe "INTENT" finally check.close()
        println("LEGACY_INTENT_EVIDENCE child_exit=37 phase_before_recovery=INTENT")
        val replacement=metadata.claimController("replacement")
        intercept[IllegalArgumentException](intents.recordAccountLockObservation(first,plan,url,adminUser,adminPassword))
        new LegacyAccountLockIntent(metadata).recordAccountLockObservation(replacement,plan,url,adminUser,adminPassword).phase shouldBe "ACCOUNT_LOCK_OBSERVED"
        val admin4=DriverManager.getConnection(url,adminUser,adminPassword)
        try execute(admin4,s"ALTER USER '$user'@'127.0.0.1' ACCOUNT UNLOCK") finally admin4.close()
        val unlocked=DriverManager.getConnection(url,user,password);unlocked.close()
        // A persisted observation is historical even though a fresh login now succeeds.
        new LegacyAccountLockIntent(metadata).prepare(replacement,plan).phase shouldBe "ACCOUNT_LOCK_OBSERVED"
        new LegacyAccountLockIntent(metadata).recordAccountLockObservation(replacement,plan,url,adminUser,adminPassword).phase shouldBe "ACCOUNT_LOCK_OBSERVED"
        intercept[SQLException](DriverManager.getConnection(url,user,password))
        intercept[IllegalStateException](LegacyMySqlAccountLock.verifyDrain(url,adminUser,adminPassword,uuid,account,Set(rootProxy)))
        intercept[IllegalStateException](intents.verifyDrain(replacement,plan,url,adminUser,adminPassword))
        value(admin,s"SELECT COUNT(*) FROM information_schema.processlist WHERE ID=$connectionId") shouldBe "1"
        value(admin,s"SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=$connectionId") shouldBe "1"
        old.commit()
        value(admin,"SELECT value FROM account_probe WHERE id=1") shouldBe "1"
        admin.rollback();admin.setAutoCommit(true)
        value(admin,"SELECT COUNT(*) FROM account_probe WHERE id=2") shouldBe "0"
        old.close();old=null
        intercept[SQLException](DriverManager.getConnection(url,user,password))
        val diagnostic=DriverManager.getConnection(url,adminUser,adminPassword)
        try {
          val stmt=diagnostic.createStatement()
          try {val rows=stmt.executeQuery("SELECT t.trx_mysql_thread_id, p.ID, p.USER, t.trx_state FROM information_schema.innodb_trx t LEFT JOIN information_schema.processlist p ON p.ID=t.trx_mysql_thread_id")
            try while(rows.next())println(s"DRAIN_DIAGNOSTIC thread=${rows.getString(1)} process=${rows.getString(2)} user=${rows.getString(3)} state=${rows.getString(4)}") finally rows.close()
          }finally stmt.close()
        }finally diagnostic.close()
        val start=System.nanoTime();val deadline=start+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        var observed=false;var lastFailure:IllegalStateException=null
        while(!observed && System.nanoTime()<deadline) {
          try {LegacyMySqlAccountLock.verifyDrain(url,adminUser,adminPassword,uuid,account,Set(rootProxy));observed=true}
          catch {case e:IllegalStateException=>lastFailure=e;Thread.sleep(100)}
        }
        if(!observed)throw lastFailure
        println("DRAIN_DIAGNOSTIC cleared_after_ms="+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start))
        intercept[IllegalArgumentException](intents.verifyDrain(first,plan,url,adminUser,adminPassword))
        intents.verifyDrain(replacement,plan,url,adminUser,adminPassword)
        intercept[IllegalStateException](LegacyMySqlAccountLock.verifyDrain(url,adminUser,adminPassword,uuid,account,
          Set(rootProxy,LegacyMySqlAccountLock.Account(adminUser,"127.0.0.1"))))
        // This is a primitive test, not authorization: the test explicitly switches the policy.
        val cutover=CdcManagedControlStore.CutoverIdentity("d","h","op-test")
        store.ownership.get.registerOwner(SnapshotOwnerIdentity("old","d",1,"test-start"))
        store.transaction { c=>
          store.update(c,"INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,queue_order,not_before,grant_id,owner_id) VALUES ('w',1,'{}','s','t',1,1,'unknown',1,0,'g','old')")
          store.update(c,"UPDATE pekko_snapshot_owners SET phase='db_drained' WHERE owner_id='old'")
        }
        metadata.fenced(replacement) {
          store.read(c=>store.update(c,"UPDATE pekko_table_admission_lock SET policy_hash=? WHERE id=1",cutover.policyHash(store.limits)))
        }
        intercept[IllegalStateException](store.transaction(_=>()))
        val migrated=new CdcManagedControlStore(ds,store.limits,Some(cutover))
        migrated.transaction(c=>migrated.query(c,"SELECT grant_id FROM pekko_table_tasks WHERE workflow_id='w'")(_.getString(1))) shouldBe Vector("g")
        val reopened=new CdcManagedControlStore(ds,store.limits,Some(cutover))
        reopened.transaction(_=>())
        reopened.ownership.releaseDrained("old",8) shouldBe 1
        reopened.ownership.hasOutstandingQuota("old") shouldBe false
        reopened.transaction { c=>
          reopened.update(c,"INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,execution_id,queue_order,not_before,grant_id,owner_id) VALUES ('workflow-2',1,'{}','s','t',1,1,'running','execution-2',2,0,'permit-2','next')")
          reopened.update(c,"INSERT INTO pekko_snapshot_writer_permissions (workflow_id,owner_id,generation,permit_id,grant_id,execution_id,revision,definition_hash,phase,predecessor_owner) VALUES ('workflow-2','next',1,'permit-2','permit-2','execution-2',1,?, 'active',NULL)","e"*64)
        }
        val authKey=CutoverAuthorizationKey("d","h","op-test","old",1,cutover.policyHash(store.limits),"b"*64,"c"*64)
        reopened.cutoverStore.recordAuthorization(authKey)
        val binding=CredentialBinding("workflow-2","execution-2","source","source","permit-2","next","op-test","127.0.0.1:3306",database,"old_user","new_user","CDC_NEW_PASSWORD","e"*64,"ACTIVE")
        reopened.cutoverStore.recordBinding(binding,authKey)
        reopened.cutoverStore.binding("workflow-2","execution-2","source","source") shouldBe Some(binding)
        println("MIGRATED_POLICY_EVIDENCE legacy_rejected=true quota_preserved=true reopened=true fixture_quota_released=true")
        println("LEGACY_INTENT_EVIDENCE resumed=ACCOUNT_LOCK_OBSERVED unlocked_then_relocked=true old_transaction_committed=true")
      } finally {
        if(old!=null){try old.rollback() finally old.close()}
        if(ds!=null)ds.close()
        try {execute(admin,s"DROP USER IF EXISTS '$user'@'localhost'");execute(admin,s"DROP USER IF EXISTS '$user'@'127.0.0.1'")} finally admin.close()
      }
    }
  }
}
