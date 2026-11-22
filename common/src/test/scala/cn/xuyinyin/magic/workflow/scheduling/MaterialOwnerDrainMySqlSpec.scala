package cn.xuyinyin.magic.cdc.control


import cn.xuyinyin.magic.cdc.guardian.SnapshotSupervisorSettings

import cn.xuyinyin.magic.cdc.guardian.MySqlOwnerDrainObserver

import cn.xuyinyin.magic.cdc.control.SchedulingLimits


import cn.xuyinyin.magic.cdc.guardian.{SnapshotOwnerIdentity}
import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.cdc.platform.{CdcCaptureSettings,CdcPlatformLimits,CdcPlatformSettings}
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** State-only fixture: no guardian process, CDC Reader, target or Sink. */
class MaterialOwnerDrainMySqlSpec extends AnyWordSpec with Matchers {
  "material account drain observation" should {
    "reject foreign and untagged material sessions before allowing an empty observation" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_ARCHIVE_PROBE").contains("1"))cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      Class.forName("com.mysql.cj.jdbc.Driver") // SBT may use a fresh test classloader between suites.
      val base=sys.env("CAPTURE_STATE_URL")
      require(base.contains(":25822/") && base.contains("pekko_test_shared_state"))
      val adminUser=sys.env("CDC_WINDOW_MYSQL_USER");val adminPassword=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10)
      val schema="pekko_test_material_drain_"+suffix;val materialUser="drain_"+suffix;val password=UUID.randomUUID().toString.replace("-","")
      def exec(c:Connection,sql:String):Unit={val q=c.createStatement();try {q.execute(sql);()}finally q.close()}
      val admin=DriverManager.getConnection(base,adminUser,adminPassword)
      try {
        exec(admin,s"CREATE DATABASE `$schema`")
        val actualHost={
          val q=admin.createStatement()
          try {val r=q.executeQuery("SELECT SUBSTRING_INDEX(USER(),'@',-1)")
            try {require(r.next());val host=r.getString(1);require(host.matches("[A-Za-z0-9_.:-]+"));host} finally r.close()
          } finally q.close()
        }
        Vector("localhost","127.0.0.1",actualHost).distinct.foreach {host=>
          exec(admin,s"CREATE USER '$materialUser'@'$host' IDENTIFIED BY '$password'")
          exec(admin,s"GRANT SELECT,UPDATE ON `$schema`.* TO '$materialUser'@'$host'")
        }
      }finally admin.close()
      val url=base.replace("pekko_test_shared_state",schema)
      val hc=new HikariConfig();hc.setJdbcUrl(url);hc.setUsername(adminUser);hc.setPassword(adminPassword);hc.setMaximumPoolSize(4)
      val ds=new HikariDataSource(hc)
      try {
        val setup=ds.getConnection
        try {
          Vector("db/mysql/pekko-table-scheduling-schema.sql","db/mysql/pekko-snapshot-ownership-schema.sql").foreach {name=>
            val in=getClass.getClassLoader.getResourceAsStream(name)
            val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
            sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(exec(setup,_))
          }
          exec(setup,"CREATE TABLE material_probe(id INT PRIMARY KEY) ENGINE=InnoDB")
          exec(setup,"INSERT INTO material_probe VALUES(1)")
        }finally setup.close()
        val store=new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
        val ownership=store.ownership.get
        val owner=SnapshotOwnerIdentity("old-"+suffix,"domain",1,"identity")
        ownership.registerOwner(owner)
        val state=MySQLCdcStateConfig(url,adminUser,adminPassword,"offsets","history",0)
        val source=MySQLCdcSourceConfig("capture","127.0.0.1",25821,"source","pekko_capture_control","source_"+suffix,"x",101,128,100)
        val capture=CdcCaptureSettings("capture",source,state.copy(username="sdk_"+suffix),state,"source-uuid","state-uuid",
          materialState=Some(state.copy(username=materialUser,password=password)))
        val exclusive=SnapshotSupervisorSettings.exclusiveCdcAccounts(Vector(CdcPlatformSettings(Vector(capture),CdcPlatformLimits())))
        val endpoint=SnapshotOwnerJdbc.endpoint(url)
        val previous=DriverManager.getConnection(url,materialUser,password)
        val instance=try {
          val (uuid,started,connectionId)=SnapshotOwnerJdbc.databaseIdentity(previous)
          ownership.registerSession(owner.bootId,endpoint,uuid,started,connectionId,"control",materialUser)
          (endpoint,uuid,started)
        }finally previous.close()
        val observer=new MySqlOwnerDrainObserver(ownership,_=>DriverManager.getConnection(
          SnapshotSupervisorSettings.appendParameters(url,"connectionAttributes=pekko_owner:observer,pekko_role:observer"),adminUser,adminPassword),exclusiveAccounts=exclusive)
        def observe()=observer.observe(owner.bootId,instance)
        val foreign=DriverManager.getConnection(url+"&connectionAttributes=pekko_owner:foreign,pekko_role:control",materialUser,password)
        val foreignId=SnapshotOwnerJdbc.databaseIdentity(foreign)._3
        println(s"DRAIN_PROBE foreign_connection=$foreignId event=transaction_open at_ms=${System.currentTimeMillis()}")
        try {
          foreign.setAutoCommit(false);val q=foreign.createStatement();try {val r=q.executeQuery("SELECT * FROM material_probe FOR UPDATE");try while(r.next())() finally r.close()}finally q.close()
          val found=observe();found.complete shouldBe true;found.empty shouldBe false;found.unattributed should be >= 1
        }finally {
          println(s"DRAIN_PROBE foreign_connection=$foreignId event=rollback_start at_ms=${System.currentTimeMillis()}")
          foreign.rollback()
          println(s"DRAIN_PROBE foreign_connection=$foreignId event=rollback_return at_ms=${System.currentTimeMillis()}")
          foreign.close()
          println(s"DRAIN_PROBE foreign_connection=$foreignId event=close_return at_ms=${System.currentTimeMillis()}")
        }
        val lockProbe=DriverManager.getConnection(url,adminUser,adminPassword)
        try {
          lockProbe.setAutoCommit(false)
          val q=lockProbe.createStatement();q.setQueryTimeout(2)
          try {val r=q.executeQuery("SELECT * FROM material_probe WHERE id=1 FOR UPDATE NOWAIT");try while(r.next())() finally r.close()} finally q.close()
          println(s"DRAIN_PROBE foreign_connection=$foreignId event=independent_lock_acquired at_ms=${System.currentTimeMillis()}")
          lockProbe.rollback()
        } finally lockProbe.close()
        val untagged=DriverManager.getConnection(url,materialUser,password)
        try {val found=observe();found.complete shouldBe true;found.empty shouldBe false;found.unattributed should be >= 1}finally untagged.close()
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        var drained=observe()
        println(s"DRAIN_PROBE foreign_connection=$foreignId event=observe at_ms=${System.currentTimeMillis()} evidence=${drained.evidence}")
        while(drained.complete && !drained.empty && System.nanoTime()<deadline){
          Thread.sleep(50);drained=observe()
          println(s"DRAIN_PROBE foreign_connection=$foreignId event=observe at_ms=${System.currentTimeMillis()} evidence=${drained.evidence}")
        }
        val atDeadline=drained
        if(drained.complete && !drained.empty){
          val diagnosticDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
          while(drained.complete && !drained.empty && System.nanoTime()<diagnosticDeadline){Thread.sleep(200);drained=observe()}
          println(s"DRAIN_PROBE foreign_connection=$foreignId event=diagnostic_end at_ms=${System.currentTimeMillis()} evidence=${drained.evidence}")
        }
        withClue(atDeadline.evidence){atDeadline.complete shouldBe true;atDeadline.empty shouldBe true}
        info(s"schema=$schema materialUser=$materialUser endpoint=$endpoint uuid=${instance._2} foreignAndUntaggedRejected=true afterCloseEmpty=true")
      }finally ds.close()
    }
  }
}
