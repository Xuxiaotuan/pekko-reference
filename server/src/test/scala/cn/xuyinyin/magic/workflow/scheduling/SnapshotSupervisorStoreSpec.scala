package cn.xuyinyin.magic.workflow.scheduling

import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.config.ConfigFactory

class SnapshotSupervisorStoreSpec extends AnyWordSpec with Matchers {
  private def fixture(f:TableSchedulingStore=>Unit):Unit={
    val config=new HikariConfig();config.setJdbcUrl("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")
    config.setUsername("sa");config.setMaximumPoolSize(4)
    val ds=new HikariDataSource(config)
    try {
      val c=ds.getConnection
      try Vector("db/h2/pekko-table-scheduling-schema.sql","db/h2/pekko-snapshot-ownership-schema.sql","db/h2/pekko-snapshot-supervisor-schema.sql","db/h2/pekko-legacy-lock-intent-schema.sql","db/h2/pekko-snapshot-cutover-schema.sql").foreach { name=>
        val in=getClass.getClassLoader.getResourceAsStream(name)
        val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { s=>val q=c.createStatement();try q.execute(s) finally q.close() }
      } finally c.close()
      f(new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true))
    }finally ds.close()
  }
  "supervisor control transactions" should {
    "parse explicit cutover binding plans without secrets" in {
      val config=ConfigFactory.parseString("""
        pekko.workflow.snapshot-ha.credential-bindings=[
          {workflow-id="w",node-id="source",node-role="source",operation-id="op",host-id="h",endpoint="127.0.0.1:3306",database="db",legacy-user="old",runtime-user="new",password-env="NEW_PASSWORD",definition-hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        ]
      """)
      SnapshotOwnerBootstrap.bindingPlans(config).size shouldBe 1
      SnapshotOwnerBootstrap.bindingPlans(config).head.passwordEnv shouldBe "NEW_PASSWORD"
    }
    "refuse cutover before a guardian-signed isolation proof" in fixture { s=>
      val metadata=new SnapshotSupervisorStore(s,GuardianBinding("d","h","g","key"),1);metadata.enroll()
      val control=metadata.claimController("controller")
      val identity=SnapshotOwnerIdentity("old","d",1,"start")
      val launch=SnapshotWorkerLaunch("old","profile","launched",Some(identity),Some("op"),Some("bad"))
      val plan=LegacyAccountLockIntent.Plan("op","uuid",LegacyMySqlAccountLock.Account("old_user","127.0.0.1"),Set("controller"))
      val next=SnapshotWorkerLaunch("next","profile","prepared",None,None,None)
      intercept[Exception](new SnapshotCutoverAuthorizer(metadata).authorize(control,launch,next,"bad",plan,"jdbc:mysql://127.0.0.1:3306/db","controller","secret",Vector.empty))
      metadata.ownerRegistry.owner("old") shouldBe None
    }
    "persist an idempotent cutover authorization and credential binding without a secret" in fixture { s=>
      val key=CutoverAuthorizationKey("d","h","op", "old", 3L, "a"*64, "b"*64, "c"*64)
      s.cutoverStore.recordAuthorization(key)
      s.cutoverStore.recordAuthorization(key)
      val binding=CredentialBinding("workflow","execution","source","source","permit","new-owner","op","127.0.0.1:3306","db","old_user","new_user","CDC_NEW_PASSWORD","d"*64,"ACTIVE")
      s.transaction { c=>
        s.update(c,"INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,execution_id,queue_order,not_before,grant_id,owner_id) VALUES ('workflow',1,'{}','s','t',1,1,'running','execution',1,0,'permit','new-owner')")
        s.update(c,"INSERT INTO pekko_snapshot_writer_permissions (workflow_id,owner_id,generation,permit_id,grant_id,execution_id,revision,definition_hash,phase,predecessor_owner) VALUES ('workflow','new-owner',1,'permit','permit','execution',1,?, 'active',NULL)","d"*64)
      }
      s.cutoverStore.recordBinding(binding,key)
      s.cutoverStore.recordBinding(binding,key)
      s.cutoverStore.authorization(key) shouldBe Some(key)
      s.cutoverStore.binding("workflow","execution","source","source") shouldBe Some(binding)
      intercept[IllegalArgumentException](s.cutoverStore.recordBinding(binding.copy(runtimeUser="other"),key))
    }
    "serialize controller replacement with a fenced cutover side effect" in fixture { s=>
      val metadata=new SnapshotSupervisorStore(s,GuardianBinding("d","h","g","key"),1);metadata.enroll()
      val first=metadata.claimController("first")
      val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
      val entered=new java.util.concurrent.CountDownLatch(1);val release=new java.util.concurrent.CountDownLatch(1)
      val attempting=new java.util.concurrent.CountDownLatch(1);val claimed=new java.util.concurrent.CountDownLatch(1)
      try {
        val sideEffect=pool.submit(new Runnable {def run():Unit=metadata.fenced(first) {
          entered.countDown();require(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
        }})
        entered.await(5,java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        val replacement=pool.submit(new Runnable {def run():Unit={attempting.countDown();metadata.claimController("second");claimed.countDown()}})
        attempting.await(5,java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        claimed.await(250,java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe false
        release.countDown();sideEffect.get(5,java.util.concurrent.TimeUnit.SECONDS);replacement.get(5,java.util.concurrent.TimeUnit.SECONDS)
        intercept[IllegalArgumentException](metadata.fenced(first){()})
      } finally {release.countDown();pool.shutdownNow()}
    }
    "persist an immutable legacy account lock intent across controller replacement" in fixture { s=>
      val binding=GuardianBinding("d","h","g","key")
      val metadata=new SnapshotSupervisorStore(s,binding,1);metadata.enroll()
      val first=metadata.claimController("first")
      val plan=LegacyAccountLockIntent.Plan("op1","uuid1",LegacyMySqlAccountLock.Account("old","127.0.0.1"),Set("control"))
      val intents=new LegacyAccountLockIntent(metadata)
      intents.prepare(first,plan).phase shouldBe "INTENT"
      intents.prepare(first,plan).phase shouldBe "INTENT"
      val replacement=metadata.claimController("second")
      new LegacyAccountLockIntent(metadata).prepare(replacement,plan).phase shouldBe "INTENT"
      intercept[IllegalArgumentException](intents.prepare(first,plan))
      intercept[IllegalArgumentException](intents.prepare(replacement,plan.copy(operationId="op2")))
      intercept[IllegalArgumentException](intents.prepare(replacement,plan.copy(protectedUsers=Set("other"))))
      intercept[IllegalArgumentException](intents.prepare(replacement,plan.copy(trustedProxyPrincipals=Set(LegacyMySqlAccountLock.Account("root","localhost")))))
    }
    "refuse legacy admission after the durable policy changes" in fixture { s=>
      s.transaction(c=>s.update(c,"UPDATE pekko_table_admission_lock SET policy_hash=? WHERE id=1","cutover-blocked"))
      val error=intercept[IllegalStateException] {
        s.transaction(_=>fail("legacy admission executed after the cutover marker"))
      }
      error.getMessage should include ("policy differs")
      s.read(c=>s.query(c,"SELECT policy_hash FROM pekko_table_admission_lock WHERE id=1")(_.getString(1)).head) shouldBe "cutover-blocked"
    }
    "retain legacy quota until the owner is drained when using CDC control storage" in {
      val cfg=new HikariConfig();cfg.setJdbcUrl("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")
      cfg.setUsername("sa");val ds=new HikariDataSource(cfg)
      try {
        val c=ds.getConnection
        try Vector("db/h2/pekko-table-scheduling-schema.sql","db/h2/pekko-snapshot-ownership-schema.sql","db/h2/pekko-snapshot-supervisor-schema.sql").foreach { name=>
          val in=getClass.getClassLoader.getResourceAsStream(name);val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { text=>val q=c.createStatement();try q.execute(text) finally q.close() }
        } finally c.close()
        val limits=SchedulingLimits(2,1,8,1,1048576,8)
        val control=new CdcManagedControlStore(ds,limits)
        val registry=control.ownership
        registry.registerOwner(SnapshotOwnerIdentity("old","d",1,"start"))
        val legacy=new TableSchedulingStore(ds,limits,ownershipEnabled=true)
        legacy.transaction { c=>legacy.update(c,"INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,queue_order,not_before,grant_id,owner_id) VALUES ('w',1,'{}','s','t',1,1,'unknown',1,0,'g','old')") }
        registry.hasOutstandingQuota("old") shouldBe true
        registry.releaseDrained("old",10) shouldBe 0
        legacy.transaction(c=>legacy.update(c,"UPDATE pekko_snapshot_owners SET phase='db_drained' WHERE owner_id='old'"))
        registry.releaseDrained("old",10) shouldBe 1
        registry.hasOutstandingQuota("old") shouldBe false
        legacy.get("w").get.state shouldBe "ready"
      } finally ds.close()
    }
    "serialize distinct control and legacy scheduling transaction instances on the same lock" in {
      val cfg=new HikariConfig();cfg.setJdbcUrl("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")
      cfg.setUsername("sa");cfg.setMaximumPoolSize(3)
      val ds=new HikariDataSource(cfg)
      val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
      val entered=new java.util.concurrent.CountDownLatch(1)
      val release=new java.util.concurrent.CountDownLatch(1)
      val attempting=new java.util.concurrent.CountDownLatch(1)
      val acquired=new java.util.concurrent.CountDownLatch(1)
      try {
        val c=ds.getConnection
        try {
          val in=getClass.getClassLoader.getResourceAsStream("db/h2/pekko-table-scheduling-schema.sql")
          val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { text=>val q=c.createStatement();try q.execute(text) finally q.close() }
        } finally c.close()
        val limits=SchedulingLimits(2,1,8,1,1048576,8)
        val legacy=new TableSchedulingStore(ds,limits)
        val control=new ManagedControlTransactions(ds,cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.sha256(limits.toString)) {}
        val old=executor.submit(new Runnable {def run():Unit=legacy.transaction { _=>
          entered.countDown();require(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
        }})
        entered.await(5,java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        val next=executor.submit(new Runnable {def run():Unit={attempting.countDown();control.transaction { _=>acquired.countDown() }}})
        attempting.await(5,java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        acquired.await(250,java.util.concurrent.TimeUnit.MILLISECONDS) shouldBe false
        release.countDown()
        old.get(5,java.util.concurrent.TimeUnit.SECONDS)
        next.get(5,java.util.concurrent.TimeUnit.SECONDS)
        acquired.getCount shouldBe 0L
      } finally {release.countDown();executor.shutdownNow();ds.close()}
    }
    "open a migrated control policy only after an explicit durable switch" in {
      val cfg=new HikariConfig();cfg.setJdbcUrl("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")
      cfg.setUsername("sa");val ds=new HikariDataSource(cfg)
      try {
        val c=ds.getConnection
        try Vector("db/h2/pekko-table-scheduling-schema.sql","db/h2/pekko-snapshot-ownership-schema.sql").foreach { name=>
          val in=getClass.getClassLoader.getResourceAsStream(name);val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { text=>val q=c.createStatement();try q.execute(text) finally q.close() }
        } finally c.close()
        val limits=SchedulingLimits(2,1,8,1,1048576,8)
        val id=CdcManagedControlStore.CutoverIdentity("deployment","host","operation")
        val migrated=new CdcManagedControlStore(ds,limits,Some(id))
        intercept[IllegalStateException](migrated.transaction(_=>()))
        val old=new TableSchedulingStore(ds,limits,ownershipEnabled=true)
        old.ownership.get.registerOwner(SnapshotOwnerIdentity("old","deployment",1,"test-start"))
        old.transaction { c=>
          old.update(c,"INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,queue_order,not_before,grant_id,owner_id) VALUES ('w',1,'{}','s','t',1,1,'unknown',1,0,'g','old')")
          old.update(c,"UPDATE pekko_snapshot_owners SET phase='db_drained' WHERE owner_id='old'")
          old.update(c,"UPDATE pekko_table_admission_lock SET policy_hash=? WHERE id=1",id.policyHash(limits))
        }
        intercept[IllegalStateException](old.transaction(_=>()))
        migrated.transaction(c=>migrated.query(c,"SELECT grant_id FROM pekko_table_tasks WHERE workflow_id='w'")(_.getString(1))) shouldBe Vector("g")
        val reopened=new CdcManagedControlStore(ds,limits,Some(id))
        reopened.transaction(_=>())
        reopened.ownership.releaseDrained("old",8) shouldBe 1
        reopened.ownership.hasOutstandingQuota("old") shouldBe false
        val wrong=new CdcManagedControlStore(ds,limits,Some(id.copy(operationId="other")))
        intercept[IllegalStateException](wrong.transaction(_=>()))
      }finally ds.close()
    }
    "reject an owner registry from another transaction context" in fixture { s=>
      val other=new ManagedControlTransactions(null,cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.sha256(s.limits.toString)) {}
      val binding=GuardianBinding("d","h","g","key")
      intercept[IllegalArgumentException](new SnapshotSupervisorStore(other,s.ownership.get,binding,1))
      noException should be thrownBy new SnapshotSupervisorStore(s,s.ownership.get,binding,1)
    }
    "attest only the original live worker independently of supervisor sequence" in fixture { s=>
      import spray.json._
      import spray.json.DefaultJsonProtocol._
      import SnapshotGuardianWire._
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val binding=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val metadata=new SnapshotSupervisorStore(s,binding,2);metadata.enroll();val control=metadata.claimController("supervisor")
      val manager=new ManagedJvmIsolation("d",2)
      val service=new SnapshotGuardianService(metadata,manager,_=>new ProcessBuilder("/bin/cat"),keys.getPrivate)
      metadata.prepare(control,"worker","profile")
      val identity=service.handle(control,"LAUNCH",JsObject("boot"->JsString("worker"))).fields("identity").convertTo[SnapshotOwnerIdentity]
      s.ownership.get.registerOwner(identity)
      val socket=java.nio.file.Path.of("/tmp","sa-"+java.util.UUID.randomUUID().toString.take(12));val secret=Array.fill[Byte](32)(8)
      val frames=new java.util.concurrent.ConcurrentLinkedQueue[JsObject]()
      val wrongNonce=new java.util.concurrent.atomic.AtomicBoolean(false)
      val server=new SnapshotGuardianWire.Server(socket,secret,binding,keys.getPrivate,service.handle,audit=f=>{frames.add(f);()},attest=args=>{
        val result=service.attest(args)
        if(wrongNonce.get())JsObject(result.fields.updated("nonce",JsString("stale")))else result
      })
      val thread=new Thread(()=>try server.run()catch{case _:java.nio.channels.AsynchronousCloseException=>()},"worker-attestation-test");thread.setDaemon(true);thread.start()
      try {
        val supervisor=new SnapshotGuardianWire.Client(socket,secret,control)
        supervisor.request("BIND")
        val worker=new SnapshotGuardianWire.WorkerClient(socket,secret,binding)
        worker.attest(identity,"profile")
        worker.attest(identity,"profile")
        import scala.jdk.CollectionConverters._
        val challenges=frames.iterator().asScala.toVector.map(_.fields("request").asJsObject.fields("payload").convertTo[String].parseJson.asJsObject)
          .filter(_.fields("method")==JsString("ATTEST_WORKER"))
        val nonces=challenges.map(_.fields("args").asJsObject.fields("nonce").convertTo[String])
        nonces.size shouldBe 2;nonces.distinct.size shouldBe 2
        wrongNonce.set(true);intercept[IllegalArgumentException](worker.attest(identity,"profile"));wrongNonce.set(false)
        supervisor.request("INVENTORY").fields.contains("worker") shouldBe true
        intercept[IllegalArgumentException](worker.attest(identity.copy(startIdentity="other"),"profile"))
        intercept[IllegalArgumentException](worker.attest(identity,"wrong-profile"))
        val other=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic.getEncoded))
        intercept[IllegalArgumentException](new SnapshotGuardianWire.WorkerClient(socket,secret,other).attest(identity,"profile"))
        intercept[IllegalArgumentException](new SnapshotGuardianWire.WorkerClient(socket,Array.fill[Byte](32)(9),binding).attest(identity,"profile"))
        val operation=metadata.operation(control,"worker")
        intercept[IllegalArgumentException](worker.attest(identity,"profile"))
        service.handle(control,"STOP",JsObject("boot"->JsString("worker"),"operation"->JsString(operation),"identity"->identity.toJson))
        intercept[IllegalArgumentException](worker.attest(identity,"profile"))
        metadata.prepare(control,"worker-dead","profile")
        val dead=service.handle(control,"LAUNCH",JsObject("boot"->JsString("worker-dead"))).fields("identity").convertTo[SnapshotOwnerIdentity]
        s.ownership.get.registerOwner(dead)
        worker.attest(dead,"profile")
        manager.managed.find(_.identity==dead).get.process.destroyForcibly().waitFor()
        metadata.launch("worker-dead").get.operation shouldBe None
        intercept[IllegalArgumentException](worker.attest(dead,"profile"))
      } finally {server.close();thread.join(2000);java.nio.file.Files.deleteIfExists(socket);manager.managed.foreach(_.process.destroyForcibly().waitFor())}
    }
    "isolate a live worker when its managed CDC owner needs recovery" in fixture { s=>
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val g=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val h=new SnapshotSupervisorStore(s,g,1);h.enroll();val control=h.claimController("supervisor")
      val manager=new ManagedJvmIsolation("d",1)
      val service=new SnapshotGuardianService(h,manager,_=>new ProcessBuilder("/bin/cat"),keys.getPrivate)
      val socket=java.nio.file.Path.of("/tmp","sg-"+java.util.UUID.randomUUID().toString.take(12));val secret=Array.fill[Byte](32)(1)
      val server=new SnapshotGuardianWire.Server(socket,secret,g,keys.getPrivate,service.handle)
      val thread=new Thread(()=>try server.run()catch{case _:java.nio.channels.AsynchronousCloseException=>()},"cdc-isolation-test");thread.setDaemon(true);thread.start()
      var retire=false;var observed=1000L
      try {
        val runtime=new SnapshotSupervisorRuntime(h,control,new SnapshotGuardianWire.Client(socket,secret,control),
          (_,i)=>{observed+=1000;SnapshotDatabaseObservation(i._1,true,0,0,0,"{}",observed)},"profile",100,
          needsIsolation=_=>retire)
        runtime.tick();val boot=runtime.workerBoot.get;val id=h.launch(boot).get.identity.get
        s.ownership.get.registerOwner(id);s.ownership.get.registerSession(boot,"mysql","uuid",10,11,"source","user")
        retire=true;runtime.tick();runtime.tick();runtime.tick()
        h.launch(boot).get.phase shouldBe "retired"
        h.launch(boot).get.proof should not be empty
        s.ownership.get.owner(boot).get.phase shouldBe "db_drained"
      } finally {server.close();thread.join(2000);java.nio.file.Files.deleteIfExists(socket);manager.managed.foreach(_.process.destroyForcibly().waitFor())}
    }
    "keep a fork with failed identity lookup unknown instead of cancelling it as never started" in fixture { s=>
      import spray.json._
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val g=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val h=new SnapshotSupervisorStore(s,g,1);h.enroll();val a=h.claimController("a")
      val manager=new ManagedJvmIsolation("d",1)
      val service=new SnapshotGuardianService(h,manager,_=>new ProcessBuilder("/bin/cat"),keys.getPrivate)
      h.prepare(a,"identity-unknown","profile")
      val previous=System.getSecurityManager
      val caller=Thread.currentThread()
      var denied=false
      // Java17 policy denial occurs after the real fork, at Process.info(); no production test hook.
      val policy=new SecurityManager {
        override def checkPermission(permission:java.security.Permission):Unit={
          if(Thread.currentThread()==caller && permission.getName=="manageProcess" &&
            Thread.currentThread().getStackTrace.exists(f=>f.getClassName=="java.lang.Process" && f.getMethodName=="info")) {
            denied=true;throw new SecurityException("test identity lookup denied after fork")
          }
          if(previous!=null)previous.checkPermission(permission)
        }
      }
      try {
        System.setSecurityManager(policy)
        intercept[SecurityException](service.handle(a,"LAUNCH",JsObject("boot"->JsString("identity-unknown"))))
      }finally System.setSecurityManager(previous)
      denied shouldBe true
      val inventory=service.handle(a,"INVENTORY",JsObject.empty)
      inventory.fields("identity-unknown").asJsObject.fields("status") shouldBe JsString("UNKNOWN")
      intercept[IllegalArgumentException](service.handle(a,"CANCEL",JsObject("boot"->JsString("identity-unknown"))))
      h.launch("identity-unknown").get.phase shouldBe "dispatching"
      intercept[IllegalArgumentException](h.prepare(a,"successor","profile"))
      intercept[IllegalStateException](manager.launch("other",new ProcessBuilder("/bin/cat")))
    }
    "prune only proven ended active-owner sessions so a healthy worker can keep opening pages" in fixture { s=>
      import spray.json._
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val g=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val h=new SnapshotSupervisorStore(s,g,1);h.enroll();val a=h.claimController("a")
      val manager=new ManagedJvmIsolation("d",1)
      val service=new SnapshotGuardianService(h,manager,_=>new ProcessBuilder("/bin/cat"),keys.getPrivate)
      val socket=java.nio.file.Path.of("/tmp","sg-"+java.util.UUID.randomUUID().toString.take(12));val secret=Array.fill[Byte](32)(1)
      val server=new SnapshotGuardianWire.Server(socket,secret,g,keys.getPrivate,service.handle)
      val thread=new Thread(()=>try server.run()catch{case _:java.nio.channels.AsynchronousCloseException=>()},"guardian-prune-server");thread.setDaemon(true);thread.start()
      var complete=false
      try {
        val ended=Vector(11L)++(13L to 1035L).toVector
        val runtime=new SnapshotSupervisorRuntime(h,a,new SnapshotGuardianWire.Client(socket,secret,a),(_,_)=>SnapshotDatabaseObservation("mysql",complete,1,0,0,"{}",1000,ended),"profile",100)
        runtime.tick();val id=h.launch(runtime.workerBoot.get).get.identity.get
        val ha=s.ownership.get;ha.registerOwner(id)
        ha.registerSession(id.bootId,"mysql","uuid",10,11,"target","u");ha.registerSession(id.bootId,"mysql","uuid",10,12,"source","u")
        s.transaction { c=>(13L to 4106L).foreach(n=>s.update(c,"INSERT INTO pekko_snapshot_owner_sessions VALUES (?,?,?,?,?)",id.bootId,"mysql",n,"target",0L)) }
        ha.knownSessions(id.bootId,"mysql").size shouldBe 4096
        intercept[IllegalArgumentException](ha.registerSession(id.bootId,"mysql","uuid",10,5000,"target","u"))
        runtime.tick();ha.knownSessions(id.bootId,"mysql").size shouldBe 4096
        complete=true;runtime.tick()
        val remaining=ha.knownSessions(id.bootId,"mysql");remaining.size shouldBe 3072;remaining should contain(12L);remaining should not contain 11L
        ha.registerSession(id.bootId,"mysql","uuid",10,5000,"target","u")
        ha.knownSessions(id.bootId,"mysql") should contain(5000L)
      }finally {server.close();thread.join(2000);java.nio.file.Files.deleteIfExists(socket);manager.managed.foreach(_.process.destroyForcibly().waitFor())}
    }
    "continue drain observation after the wake budget is exhausted" in fixture { s=>
      import spray.json._
      import SnapshotGuardianWire._
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val g=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val h=new SnapshotSupervisorStore(s,g,1);h.enroll();val a=h.claimController("a")
      val manager=new ManagedJvmIsolation("d",1)
      val service=new SnapshotGuardianService(h,manager,_=>new ProcessBuilder("/bin/cat"),keys.getPrivate)
      h.prepare(a,"old","profile")
      val id=service.handle(a,"LAUNCH",JsObject("boot"->JsString("old"))).fields("identity").convertTo[SnapshotOwnerIdentity]
      val ha=s.ownership.get;ha.registerOwner(id);ha.registerInstance("old","mysql","uuid",10)
      val socket=java.nio.file.Path.of("/tmp","sg-"+java.util.UUID.randomUUID().toString.take(12))
      val secret=Array.fill[Byte](32)(1)
      val server=new SnapshotGuardianWire.Server(socket,secret,g,keys.getPrivate,service.handle)
      val thread=new Thread(()=>try server.run()catch{case _:java.nio.channels.AsynchronousCloseException=>()},"guardian-exhausted-wake-test")
      thread.setDaemon(true);thread.start()
      try {
        val b=h.claimController("b");var rounds=0;var wakeCalls=0
        val runtime=new SnapshotSupervisorRuntime(h,b,new SnapshotGuardianWire.Client(socket,secret,b),(_,_)=>{
          rounds+=1
          SnapshotDatabaseObservation("mysql",true,if(rounds==1)1 else 0,0,0,"{}",1000+rounds*200)
        },"profile",100,wake=(_,_)=>{
          wakeCalls+=1
          cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureWake.nextAvailable(_=>true) shouldBe None
        })
        runtime.tick();rounds shouldBe 1;ha.owner("old").get.phase shouldBe "drain_wait"
        runtime.tick();rounds shouldBe 2
        runtime.tick();rounds shouldBe 3;wakeCalls shouldBe 3
        ha.owner("old").get.phase shouldBe "db_drained"
      }finally {server.close();thread.join(2000);java.nio.file.Files.deleteIfExists(socket);manager.managed.foreach(_.process.destroyForcibly().waitFor())}
    }
    "pause before drain transitions when guardian control disappears after receiving its proof" in fixture { s=>
      import spray.json._
      import SnapshotGuardianWire._
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val g=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val h=new SnapshotSupervisorStore(s,g,1);h.enroll();val a=h.claimController("a")
      val manager=new ManagedJvmIsolation("d",1)
      val service=new SnapshotGuardianService(h,manager,_=>new ProcessBuilder("/bin/cat"),keys.getPrivate)
      h.prepare(a,"old","profile")
      val id=service.handle(a,"LAUNCH",JsObject("boot"->JsString("old"))).fields("identity").convertTo[SnapshotOwnerIdentity]
      s.ownership.get.registerOwner(id);s.ownership.get.registerInstance("old","mysql","uuid",10)
      val socket=java.nio.file.Path.of("/tmp","sg-"+java.util.UUID.randomUUID().toString.take(12))
      val secret=Array.fill[Byte](32)(1)
      val server=new SnapshotGuardianWire.Server(socket,secret,g,keys.getPrivate,service.handle)
      val thread=new Thread(()=>try server.run()catch{case _:java.nio.channels.AsynchronousCloseException=>()},"guardian-unit-server")
      thread.setDaemon(true);thread.start()
      try {
        val b=h.claimController("b");val client=new SnapshotGuardianWire.Client(socket,secret,b)
        val runtime=new SnapshotSupervisorRuntime(h,b,client,(_,_)=>SnapshotDatabaseObservation("mysql",true,0,0,0,"{}",1000),"profile",100,
          (kind,_)=>if(kind=="PROOF_RECORDED")server.close())
        runtime.tick()
        s.ownership.get.owner("old").get.phase shouldBe "fenced"
      }finally {server.close();thread.join(2000);java.nio.file.Files.deleteIfExists(socket);manager.managed.foreach(_.process.destroyForcibly().waitFor())}
    }
    "reject replacing the original capability even when a pending fork has no recorded PID" in fixture { s=>
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val g=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val h=new SnapshotSupervisorStore(s,g,1);h.enroll();val a=h.claimController("a")
      new SnapshotGuardianService(h,new ManagedJvmIsolation("d",1),_=>new ProcessBuilder("/bin/cat"),keys.getPrivate)
      h.prepare(a,"pending","profile");h.dispatch(a,"pending")
      intercept[IllegalArgumentException](new SnapshotGuardianService(h,new ManagedJvmIsolation("d",1),_=>new ProcessBuilder("/bin/cat"),keys.getPrivate))
      h.launch("pending").get.phase shouldBe "dispatching"
    }
    "keep nested ownership reads on the admission transaction connection" in fixture { s=>
      s.transaction { outer=>s.read(inner=>inner eq outer) shouldBe true }
    }
    "roll back nested owner changes when the enclosing controller transaction fails" in fixture { s=>
      intercept[IllegalStateException] {
        s.transaction { _=>s.ownership.get.registerOwner(SnapshotOwnerIdentity("rollback","d",101,"start"));throw new IllegalStateException("abort") }
      }
      s.ownership.get.owner("rollback") shouldBe None
    }
    "retain launch quota and reject stale controller writes after restart" in fixture { s=>
      val g=GuardianBinding("d","host","guardian","public-key")
      val h=new SnapshotSupervisorStore(s,g,1);h.enroll()
      val a=h.claimController("a");h.prepare(a,"worker","profile")
      val b=h.claimController("b");b.epoch shouldBe 2
      intercept[IllegalArgumentException](h.fenced(a){s.ownership.get.registerOwner(SnapshotOwnerIdentity("foreign","d",102,"start"))})
      s.ownership.get.owner("foreign") shouldBe None
      intercept[IllegalArgumentException](h.prepare(b,"other","profile"))
      h.launch("worker").get.phase shouldBe "prepared"
      h.claimController("b") shouldBe b
    }
    "bind cancelled tickets permanently and never reuse a boot" in fixture { s=>
      val h=new SnapshotSupervisorStore(s,GuardianBinding("d","h","g","key"),1);h.enroll();val a=h.claimController("a")
      h.prepare(a,"cancel","profile");h.cancel(a,"cancel")
      intercept[IllegalArgumentException](h.dispatch(a,"cancel"))
      intercept[IllegalArgumentException](h.prepare(a,"cancel","profile"))
      h.prepare(a,"next","profile").phase shouldBe "prepared"
    }
    "not rebind a live deployment to a new guardian or change its worker policy" in fixture { s=>
      val g=GuardianBinding("d","h","g","key");val h=new SnapshotSupervisorStore(s,g,1);h.enroll()
      intercept[IllegalArgumentException](new SnapshotSupervisorStore(s,g.copy(bootId="other"),1).enroll())
      intercept[IllegalArgumentException](new SnapshotSupervisorStore(s,g,2).enroll())
    }
    "freeze the original isolation operation and require complete drain before retirement" in fixture { s=>
      val h=new SnapshotSupervisorStore(s,GuardianBinding("d","h","g","key"),1);h.enroll();val a=h.claimController("a")
      h.prepare(a,"w","profile");h.dispatch(a,"w")
      val id=SnapshotOwnerIdentity("w","d",101,"start");h.attach("w",id)
      s.ownership.get.registerOwner(id);s.ownership.get.registerInstance("w","mysql","uuid",10)
      val op=h.operation(a,"w");h.operation(h.claimController("b"),"w") shouldBe op
      val b=h.claimController("b")
      intercept[IllegalArgumentException](h.retire(b,"w"))
      s.ownership.get.beginRevocation("w",op)
      s.ownership.get.confirmIsolation(SnapshotIsolationReceipt(op,id,"managed-child-jvm",1,"{}"))
      s.ownership.get.recordObservation("w",SnapshotDatabaseObservation("mysql",true,0,0,0,"{}",1000),100)
      s.ownership.get.recordObservation("w",SnapshotDatabaseObservation("mysql",true,0,0,0,"{}",1200),100)
      h.recordProof(b,"w","trusted-local-proof"){()}
      h.retire(b,"w");h.launch("w").get.phase shouldBe "retired"
    }
    "recover the guardian process slot when SQL retirement committed but RETIRE RPC never arrived" in fixture { s=>
      import spray.json._
      import SnapshotGuardianWire._
      val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val g=GuardianBinding("d","host","guardian",java.util.Base64.getEncoder.encodeToString(keys.getPublic.getEncoded))
      val h=new SnapshotSupervisorStore(s,g,1);h.enroll();val a=h.claimController("a")
      val manager=new ManagedJvmIsolation("d",1)
      val service=new SnapshotGuardianService(h,manager,_=>new ProcessBuilder("/bin/cat"),keys.getPrivate)
      h.prepare(a,"old","profile")
      val id=service.handle(a,"LAUNCH",JsObject("boot"->JsString("old"))).fields("identity").convertTo[SnapshotOwnerIdentity]
      try {
        s.ownership.get.registerOwner(id);s.ownership.get.registerInstance("old","mysql","uuid",10)
        val op=h.operation(a,"old");s.ownership.get.beginRevocation("old",op)
        val raw=text(service.handle(a,"STOP",JsObject("boot"->JsString("old"),"operation"->JsString(op),"identity"->id.toJson)),"proof")
        h.recordProof(a,"old",raw){s.ownership.get.confirmIsolation(verifyReceipt(raw,g,id,op));()}
        s.ownership.get.recordObservation("old",SnapshotDatabaseObservation("mysql",true,0,0,0,"{}",1000),100)
        s.ownership.get.recordObservation("old",SnapshotDatabaseObservation("mysql",true,0,0,0,"{}",1200),100)
        h.retire(a,"old")
        service.handle(h.claimController("b"),"INVENTORY",JsObject.empty)
        manager.managed shouldBe empty
      }finally manager.managed.foreach(_.process.destroyForcibly().waitFor())
    }
  }
}
