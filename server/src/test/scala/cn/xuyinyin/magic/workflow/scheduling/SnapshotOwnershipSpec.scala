package cn.xuyinyin.magic.workflow.scheduling

import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class SnapshotOwnershipSpec extends AnyWordSpec with Matchers {
  private def fixture(f: (TableSchedulingStore, SnapshotOwnershipStore) => Unit): Unit = {
    val config=new HikariConfig(); config.setJdbcUrl("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")
    config.setUsername("sa"); config.setMaximumPoolSize(4)
    val ds=new HikariDataSource(config)
    try {
      val c=ds.getConnection
      try Vector("db/h2/pekko-table-scheduling-schema.sql","db/h2/pekko-snapshot-ownership-schema.sql").foreach { path =>
        val in=getClass.getClassLoader.getResourceAsStream(path)
        val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { s=>val q=c.createStatement();try q.execute(s) finally q.close() }
      } finally c.close()
      val store=new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
      f(store,store.ownership.get)
    } finally ds.close()
  }
  private val ownerA=SnapshotOwnerIdentity("a","managed-jvm",101,"start-a")
  private val ownerB=SnapshotOwnerIdentity("b","managed-jvm",102,"start-b")
  private val d=ScheduledTableDefinition("w",1,"{}",TableResourceDemand("mysql","mysql",1,1024))
  private def observation(empty: Boolean=true,complete: Boolean=true,at:Long=1000)=
    SnapshotDatabaseObservation("mysql",complete,if(empty)0 else 1,if(empty)0 else 1,0,"{}",at)
  "managed snapshot ownership" should {
    "retire an unregistered dead startup without reusing its boot or leaking a worker slot" in fixture { (_,h)=>
      val manager=new ManagedJvmIsolation("test-domain",1)
      val coordinator=new SnapshotTakeoverCoordinator(h,manager,(_,_)=>observation(),100)
      val failed=manager.launch("bad-start",new ProcessBuilder("/bin/sh","-c","sleep 0.1; exit 1"))
      failed.process.waitFor();coordinator.tick()
      manager.managed shouldBe empty
      h.owner("bad-start").get.phase shouldBe "startup_failed"
      intercept[IllegalArgumentException](manager.launch("bad-start",new ProcessBuilder("/bin/cat")))
      val next=manager.launch("good-start",new ProcessBuilder("/bin/cat"))
      try next.process.isAlive shouldBe true finally next.process.destroyForcibly().waitFor()
    }
    "deduplicate old Journal watermarks and preserve the original scheduled execution across takeover" in fixture { (s,h)=>
      s.register(d)
      h.submitScheduled("w","cron",1000,"cron-1000",Some(1000),"ignored") shouldBe ("duplicate","cron-1000")
      s.get("w").get.state shouldBe "registered"
      h.submitScheduled("w","cron",2000,"cron-2000",Some(1000),"scheduled-e") shouldBe ("accepted","scheduled-e")
      h.scheduledTrigger("scheduled-e").get.triggerId shouldBe "cron-2000"
      h.submitScheduled("w","cron",2000,"cron-2000",None,"different") shouldBe ("duplicate","cron-2000")
      h.submitScheduled("w","cron",3000,"cron-3000",None,"different") shouldBe ("skipped","scheduled-e")
      h.skippedTriggers("w").map(_.scheduledAt) shouldBe Vector(3000)
      h.submitScheduled("w","cron",2500,"cron-2500",None,"older") shouldBe ("duplicate","cron-2500")
      s.get("w").get.executionId shouldBe Some("scheduled-e")
    }
    "deny a foreign writer and retain quota while the old process is only suspected" in fixture { (s,h)=>
      h.registerOwner(ownerA);h.registerOwner(ownerB);h.registerInstance("a","mysql","uuid",10)
      s.enqueue(d,"e","r");val g=s.claim(1).head
      h.acquire(g,"a").get.generation shouldBe 1
      s.bind(g,"a") shouldBe true
      h.beginRevocation("a","op-a")
      h.acquire(g,"b") shouldBe None
      s.release(g,"ready",true,Some("a")) shouldBe false
      s.get("w").get.owner shouldBe Some("a")
      s.claim(1) shouldBe empty
    }
    "require both isolation and two complete empty observations before returning quota" in fixture { (s,h)=>
      h.registerOwner(ownerA);h.registerOwner(ownerB);h.registerInstance("a","mysql","uuid",10)
      s.enqueue(d,"e","r");val old=s.claim(1).head;h.acquire(old,"a");s.bind(old,"a")
      h.recordObservation("a",observation(),100) shouldBe false
      h.beginRevocation("a","op-a")
      h.confirmIsolation(SnapshotIsolationReceipt("op-a",ownerA,"managed-child-jvm",1234,"{}")) shouldBe true
      h.recordObservation("a",observation(empty=false),100) shouldBe false
      h.recordObservation("a",observation(complete=false,at=1200),100) shouldBe false
      h.releaseDrained("a",8) shouldBe 0
      h.recordObservation("a",observation(at=1400),100) shouldBe false
      h.recordObservation("a",observation(at=1600),100) shouldBe true
      h.releaseDrained("a",8) shouldBe 1
      val next=s.claim(1).head;next.id should not be old.id
      val permit=h.acquire(next,"b").get
      permit.generation shouldBe 2;permit.executionId shouldBe "e"
      s.release(old,"completed",true,Some("a")) shouldBe false
      s.bind(next,"b") shouldBe true
    }
    "reject a receipt for a different boot identity or operation" in fixture { (_,h)=>
      h.registerOwner(ownerA);h.beginRevocation("a","op-a")
      h.confirmIsolation(SnapshotIsolationReceipt("wrong",ownerA,"managed-child-jvm",1,"{}")) shouldBe false
      h.confirmIsolation(SnapshotIsolationReceipt("op-a",ownerA.copy(startIdentity="reused-pid"),"managed-child-jvm",1,"{}")) shouldBe false
      h.owner("a").get.phase shouldBe "revoking"
    }
    "retain writer ownership across normal window quota release" in fixture { (s,h)=>
      h.registerOwner(ownerA);h.registerOwner(ownerB)
      s.enqueue(d,"e","r");val first=s.claim(1).head;h.acquire(first,"a");s.bind(first,"a")
      s.release(first,"ready",true,Some("a")) shouldBe true
      val next=s.claim(1).head
      h.acquire(next,"b") shouldBe None
      h.acquire(next,"a").get.generation shouldBe 1
    }
    "hold the start budget throughout Journal recovery and reuse an idempotent permit" in fixture { (s,h)=>
      h.registerOwner(ownerA);s.enqueue(d,"e","r");s.enqueue(d.copy(workflowId="other"),"e2","r2")
      val g=s.claim(1).head;val first=h.acquire(g,"a").get
      h.acquire(g,"a") shouldBe Some(first)
      s.get("w").get.state shouldBe "recovering"
      s.claim(1) shouldBe empty
    }
    "seal the instance manifest and keep a second unknown instance from permitting recovery" in fixture { (_,h)=>
      h.registerOwner(ownerA);h.registerInstance("a","mysql","uuid",10);h.registerInstance("a","other","uuid2",20)
      h.beginRevocation("a","op-a")
      h.confirmIsolation(SnapshotIsolationReceipt("op-a",ownerA,"managed-child-jvm",1,"{}")) shouldBe true
      intercept[IllegalStateException](h.registerInstance("a","late","uuid3",30))
      h.recordObservation("a",observation(at=1000),100) shouldBe false
      h.recordObservation("a",observation(at=1200),100) shouldBe false
      h.owner("a").get.phase shouldBe "drain_wait"
    }
    "reject a fixed endpoint whose server UUID or boot changed across worker starts" in fixture { (_,h)=>
      h.registerOwner(ownerA);h.registerOwner(ownerB);h.registerInstance("a","mysql","uuid",10000)
      intercept[IllegalArgumentException](h.registerInstance("b","mysql","other-server",10000))
      intercept[IllegalArgumentException](h.registerInstance("b","mysql","uuid",20000))
      h.registerInstance("b","mysql","uuid",10500)
    }
    "not turn repeated fast polling or an incomplete observation into a release proof" in fixture { (_,h)=>
      h.registerOwner(ownerA);h.registerInstance("a","mysql","uuid",10);h.beginRevocation("a","op-a")
      h.confirmIsolation(SnapshotIsolationReceipt("op-a",ownerA,"managed-child-jvm",1,"{}")) shouldBe true
      h.recordObservation("a",observation(at=1000),100) shouldBe false
      h.recordObservation("a",observation(at=1000),100) shouldBe false
      h.recordObservation("a",observation(complete=false,at=1100),100) shouldBe false
      h.recordObservation("a",observation(at=1200),100) shouldBe false
      h.recordObservation("a",observation(at=1300),100) shouldBe true
    }
    "not revive an old boot and not let a later coordinator replace its isolation operation" in fixture { (_,h)=>
      h.registerOwner(ownerA);h.beginRevocation("a","first") shouldBe "first"
      h.beginRevocation("a","second") shouldBe "first"
      intercept[IllegalArgumentException](h.registerOwner(ownerA))
      h.confirmIsolation(SnapshotIsolationReceipt("second",ownerA,"managed-child-jvm",1,"{}")) shouldBe false
    }
    "serialize competing recovery candidates without double charging a reservation" in fixture { (s,h)=>
      import scala.concurrent.{Future,Await,ExecutionContext}
      import scala.concurrent.duration._
      implicit val ec:ExecutionContext=ExecutionContext.global
      h.registerOwner(ownerA);h.registerOwner(ownerB)
      s.enqueue(d,"e","r");val grant=s.claim(1).head
      val results=Await.result(Future.sequence(Vector("a","b").map(id=>Future(h.acquire(grant,id)))),5.seconds)
      results.flatten.size shouldBe 1
      val winner=results.flatten.head
      s.get("w").get.owner shouldBe Some(winner.owner)
      h.acquire(grant,winner.owner) shouldBe Some(winner)
      s.claim(1) shouldBe empty
    }
    "retire proven and drained child handles while SQL prevents boot reuse" in fixture { (_,h)=>
      val manager=new ManagedJvmIsolation("test-domain",1)
      val child=manager.launch("child-one",new ProcessBuilder("/bin/cat"))
      h.registerOwner(child.identity);h.registerInstance("child-one","mysql","uuid",10)
      var clock=1000L
      val coordinator=new SnapshotTakeoverCoordinator(h,manager,(id,instance)=>{clock+=200;observation(at=clock)},100)
      try {
        child.process.destroyForcibly().waitFor()
        coordinator.tick();coordinator.tick()
        h.owner("child-one").get.phase shouldBe "db_drained"
        manager.managed shouldBe empty
        intercept[IllegalArgumentException](manager.launch("child-one",new ProcessBuilder("/bin/cat")))
        val next=manager.launch("child-two",new ProcessBuilder("/bin/cat"));next.process.destroyForcibly().waitFor()
      }finally manager.managed.foreach(c=>{c.process.destroyForcibly().waitFor();()})
    }
  }
}
