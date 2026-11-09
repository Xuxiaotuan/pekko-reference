package cn.xuyinyin.magic.workflow.scheduling

import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.{CountDownLatch,Executors,TimeUnit}

/** Runs only against an explicitly provisioned, dedicated empty test schema. */
class CdcControlStoreMySqlSpec extends AnyWordSpec with Matchers {
  "CDC and legacy control stores" should {
    "serialize on MySQL and preserve old quota until drain" in {
      val url=sys.env.getOrElse("CDC_CONTROL_TEST_URL",cancel("dedicated MySQL test schema not configured"))
      val cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(sys.env("CDC_CONTROL_TEST_USER"));cfg.setPassword(sys.env("CDC_CONTROL_TEST_PASSWORD"));cfg.setMaximumPoolSize(4)
      val ds=new HikariDataSource(cfg);val pool=Executors.newFixedThreadPool(2)
      val entered=new CountDownLatch(1);val attempting=new CountDownLatch(1);val acquired=new CountDownLatch(1);val release=new CountDownLatch(1)
      try {
        val c=ds.getConnection
        try Vector("db/mysql/pekko-table-scheduling-schema.sql","db/mysql/pekko-snapshot-ownership-schema.sql","db/mysql/pekko-snapshot-supervisor-schema.sql").foreach { name=>
          val in=getClass.getClassLoader.getResourceAsStream(name);val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { text=>val q=c.createStatement();try q.execute(text) finally q.close() }
        } finally c.close()
        val limits=SchedulingLimits(2,1,8,1,1048576,8)
        val legacy=new TableSchedulingStore(ds,limits,ownershipEnabled=true);val cdc=new CdcManagedControlStore(ds,limits)
        val first=pool.submit(new Runnable {def run():Unit=legacy.transaction { _=>entered.countDown();require(release.await(10,TimeUnit.SECONDS)) }})
        entered.await(5,TimeUnit.SECONDS) shouldBe true
        val second=pool.submit(new Runnable {def run():Unit={attempting.countDown();cdc.transaction { _=>acquired.countDown() }}})
        attempting.await(5,TimeUnit.SECONDS) shouldBe true
        acquired.await(500,TimeUnit.MILLISECONDS) shouldBe false
        release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS)
        acquired.getCount shouldBe 0L
        cdc.ownership.registerOwner(SnapshotOwnerIdentity("old","d",1,"start"))
        legacy.transaction(c=>legacy.update(c,"INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,queue_order,not_before,grant_id,owner_id) VALUES ('w',1,'{}','s','t',1,1,'unknown',1,0,'g','old')"))
        cdc.ownership.releaseDrained("old",10) shouldBe 0
        cdc.ownership.hasOutstandingQuota("old") shouldBe true
        legacy.transaction(c=>legacy.update(c,"UPDATE pekko_snapshot_owners SET phase='db_drained' WHERE owner_id='old'"))
        cdc.ownership.releaseDrained("old",10) shouldBe 1
        legacy.get("w").get.state shouldBe "ready"
        cdc.ownership.hasOutstandingQuota("old") shouldBe false
        legacy.transaction(c=>legacy.update(c,"UPDATE pekko_table_admission_lock SET policy_hash=? WHERE id=1","cutover-blocked"))
        val restarted=new TableSchedulingStore(ds,limits,ownershipEnabled=true)
        intercept[IllegalStateException](restarted.transaction(_=>fail("old admission resumed")))
        restarted.read(c=>restarted.query(c,"SELECT policy_hash FROM pekko_table_admission_lock WHERE id=1")(_.getString(1)).head) shouldBe "cutover-blocked"
      } finally {release.countDown();pool.shutdownNow();ds.close()}
    }
  }
}
