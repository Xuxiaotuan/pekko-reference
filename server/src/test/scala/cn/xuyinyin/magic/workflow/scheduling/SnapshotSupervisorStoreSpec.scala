package cn.xuyinyin.magic.workflow.scheduling

import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class SnapshotSupervisorStoreSpec extends AnyWordSpec with Matchers {
  private def fixture(f:TableSchedulingStore=>Unit):Unit={
    val config=new HikariConfig();config.setJdbcUrl("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")
    config.setUsername("sa");config.setMaximumPoolSize(4)
    val ds=new HikariDataSource(config)
    try {
      val c=ds.getConnection
      try Vector("db/h2/pekko-table-scheduling-schema.sql","db/h2/pekko-snapshot-ownership-schema.sql","db/h2/pekko-snapshot-supervisor-schema.sql").foreach { name=>
        val in=getClass.getClassLoader.getResourceAsStream(name)
        val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { s=>val q=c.createStatement();try q.execute(s) finally q.close() }
      } finally c.close()
      f(new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true))
    }finally ds.close()
  }
  "supervisor control transactions" should {
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
