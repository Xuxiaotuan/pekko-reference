package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.control.SchedulingLimits


import cn.xuyinyin.magic.cdc.guardian.{SnapshotOwnerIdentity}
import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapJournal,BootstrapJournalAccess}
import cn.xuyinyin.magic.cdc.control.{SnapshotOwnerJdbc, TableSchedulingStore}
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import java.sql.{DriverManager,SQLException}
import java.util.UUID
import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files,Path}
import java.util.concurrent.TimeUnit
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Real Journal event and MySQL next-key lock only; not guardian or Sink evidence. */
class CdcJournalFenceMySqlSpec extends AnyWordSpec with Matchers {
  "Journal source fence" should {
    "lock the real pending event and the following sequence range" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CDC_JOURNAL_FENCE").contains("1"))cancel("dedicated MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      Class.forName("com.mysql.cj.jdbc.Driver") // SBT can isolate consecutive testOnly classloaders.
      val url=sys.env("CDC_MANAGED_TEST_JDBC_URL");require(url.contains("/pekko_test_"))
      val user=sys.env("CDC_MANAGED_TEST_USER");val password=sys.env("CDC_MANAGED_TEST_PASSWORD")
      val uuid=sys.env("CAPTURE_EXPECT_STATE_UUID")
      val hc=new HikariConfig();hc.setJdbcUrl(url);hc.setUsername(user);hc.setPassword(password);hc.setMaximumPoolSize(4)
      val ds=new HikariDataSource(hc)
      val scheduling=new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
      val identity=SnapshotOwnerIdentity("journal-fence-"+UUID.randomUUID(),"probe",ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toString)
      scheduling.ownership.get.registerOwner(identity)
      SnapshotOwnerJdbc.install(SnapshotOwnerJdbc.Context(identity,scheduling,Map(SnapshotOwnerJdbc.endpoint(url)->uuid),()=>()))
      val config=ConfigFactory.parseMap(Map[String,AnyRef]("pekko.persistence.journal.plugin"->"jdbc-journal",
        "pekko.persistence.snapshot-store.plugin"->"jdbc-snapshot-store","pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
        "pekko-persistence-jdbc.shared-databases.slick.db.url"->url,"pekko-persistence-jdbc.shared-databases.slick.db.user"->user,
        "pekko-persistence-jdbc.shared-databases.slick.db.password"->password).asJava).withFallback(ConfigFactory.load("application-test"))
      val kit=ActorTestKit("journal-fence-"+UUID.randomUUID().toString.take(8),config)
      implicit val ec:scala.concurrent.ExecutionContext=kit.system.executionContext
      implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
      val id="fence-"+UUID.randomUUID();val digest="a"*64
      try {
        val ref=kit.spawn(BootstrapJournal(id,validateReceipt=(p,d,_)=>Future.successful(p=="permit" && d==digest)))
        val access=new BootstrapJournalAccess(ref)
        val s=Await.result(access.update(_.copy(phase="PAUSED",definition="definition",executionId="exec",failure=Some("local_release_unconfirmed"))),15.seconds)
        Await.result(access.resumePendingBWithReceipt(s.revision,"exec","definition","permit",digest),15.seconds) shouldBe "ACCEPTED"
        val evidence=Await.result(access.persistedRecoveryReceipt(),15.seconds).get
        val verifier=CdcJournalFenceVerifier.create(kit.system,()=>SnapshotOwnerJdbc.open(url,user,password,"control",managed=true))
        val lock=DriverManager.getConnection(url,user,password);val contender=DriverManager.getConnection(url,user,password)
        try {
          lock.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);lock.setAutoCommit(false)
          val forged=cn.xuyinyin.magic.testsupport.ForgedJournalReceipts.recovery(evidence)
          intercept[IllegalArgumentException](verifier.verifyOn(lock,forged))
          lock.rollback()
          val offsetTable="offset_lock_"+UUID.randomUUID().toString.replace("-","").take(12)
          val setup=contender.createStatement()
          try {
            setup.execute(s"CREATE TABLE `$offsetTable` (id VARCHAR(36) NOT NULL,offset_key VARCHAR(1255),offset_val VARCHAR(1255),record_insert_ts TIMESTAMP(6) NOT NULL,record_insert_seq INT NOT NULL,PRIMARY KEY(id)) ENGINE=InnoDB")
            setup.execute(s"INSERT INTO `$offsetTable` VALUES('m','key','value',CURRENT_TIMESTAMP(6),1)")
          } finally setup.close()
          val source=contender.prepareStatement("SELECT writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest FROM event_journal WHERE persistence_id=? AND sequence_number=?")
          source.setString(1,evidence.persistenceId);source.setLong(2,evidence.sequenceNr)
          val stored=try {val r=source.executeQuery();try {require(r.next());(r.getString(1),r.getLong(2),r.getString(3),r.getBytes(4),r.getInt(5),r.getString(6))}finally r.close()}finally source.close()
          lock.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);lock.setAutoCommit(false)
          val offsetLock=lock.prepareStatement(s"SELECT offset_key,offset_val FROM `$offsetTable` WHERE offset_val IS NOT NULL LIMIT 2 FOR UPDATE")
          try {val r=offsetLock.executeQuery();try {require(r.next() && !r.next())}finally r.close()}finally offsetLock.close()
          contender.setAutoCommit(false);val session=contender.createStatement();try session.execute("SET innodb_lock_wait_timeout=1")finally session.close()
          verifier.verifyOn(lock,evidence)
          val insert=contender.prepareStatement("INSERT INTO event_journal(persistence_id,sequence_number,writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest,deleted) VALUES(?,?,?,?,?,?,?,?,FALSE)")
          try {insert.setString(1,evidence.persistenceId);insert.setLong(2,evidence.sequenceNr+1);insert.setString(3,stored._1);insert.setLong(4,stored._2)
            insert.setString(5,stored._3);insert.setBytes(6,stored._4);insert.setInt(7,stored._5);insert.setString(8,stored._6)
            val failure=intercept[SQLException](insert.executeUpdate());failure.getErrorCode shouldBe 1205
            val dir=java.nio.file.Path.of(sys.env("CDC_JOURNAL_FENCE_EVIDENCE"));java.nio.file.Files.createDirectories(dir)
            java.nio.file.Files.writeString(dir.resolve("range-lock-result.txt"),s"scope=MySQL RR Journal lock only\npersistence_id=${evidence.persistenceId}\nsequence=${evidence.sequenceNr}\ncontender_error=${failure.getErrorCode}\n")
          } finally insert.close()
          contender.rollback()
          val dir=Path.of(sys.env("CDC_JOURNAL_FENCE_EVIDENCE"));Files.createDirectories(dir)
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
            .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path").split(File.pathSeparator).iterator).toVector.distinct.mkString(File.pathSeparator)
          val pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-cp",cp,
            CdcJournalFenceContender.getClass.getName.stripSuffix("$"),evidence.persistenceId,evidence.sequenceNr.toString)
          pb.redirectErrorStream(true);pb.redirectOutput(dir.resolve("contender-jvm.log").toFile)
          pb.environment().put("CDC_MANAGED_TEST_JDBC_URL",url);pb.environment().put("CDC_MANAGED_TEST_USER",user);pb.environment().put("CDC_MANAGED_TEST_PASSWORD",password)
          val child=pb.start();require(child.waitFor(30,TimeUnit.SECONDS),"journal contender did not terminate")
          child.exitValue() shouldBe 0
          Files.readString(dir.resolve("contender-jvm.log")) should include ("lock_error=1205")
          val offsetPb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-cp",cp,
            CdcOffsetFenceContender.getClass.getName.stripSuffix("$"),offsetTable)
          offsetPb.redirectErrorStream(true);offsetPb.redirectOutput(dir.resolve("offset-contender-jvm.log").toFile)
          offsetPb.environment().put("CDC_MANAGED_TEST_JDBC_URL",url);offsetPb.environment().put("CDC_MANAGED_TEST_USER",user);offsetPb.environment().put("CDC_MANAGED_TEST_PASSWORD",password)
          val offsetChild=offsetPb.start();require(offsetChild.waitFor(30,TimeUnit.SECONDS),"offset contender did not terminate")
          offsetChild.exitValue() shouldBe 0
          Files.readString(dir.resolve("offset-contender-jvm.log")) should include ("lock_error=1205")
          lock.rollback()
        } finally {lock.close();contender.close()}
      } finally {kit.shutdownTestKit();ds.close()}
    }
  }
}

/** A separate JVM attempts to insert a new nonempty SDK offset while its scan is locked. */
object CdcOffsetFenceContender {
  def main(args:Array[String]):Unit={
    val table=args.head;require(table.matches("offset_lock_[a-f0-9]{12}"))
    val c=DriverManager.getConnection(sys.env("CDC_MANAGED_TEST_JDBC_URL"),sys.env("CDC_MANAGED_TEST_USER"),sys.env("CDC_MANAGED_TEST_PASSWORD"))
    try {
      c.setAutoCommit(false);val q=c.createStatement();try {
        q.execute("SET innodb_lock_wait_timeout=1")
        try {q.execute(s"INSERT INTO `$table` VALUES('z','new-key','new-value',CURRENT_TIMESTAMP(6),2)");throw new IllegalStateException("offset range allowed concurrent insert")}
        catch {case e:SQLException=>require(e.getErrorCode==1205);println("lock_error="+e.getErrorCode)}
      } finally q.close()
      c.rollback()
    } finally c.close()
  }
}

/** A separate JVM attempts an insert into the held persistence-id range. */
object CdcJournalFenceContender {
  def main(args:Array[String]):Unit={
    val Array(pid,sequence)=args
    val c=DriverManager.getConnection(sys.env("CDC_MANAGED_TEST_JDBC_URL"),sys.env("CDC_MANAGED_TEST_USER"),sys.env("CDC_MANAGED_TEST_PASSWORD"))
    try {
      val q=c.prepareStatement("SELECT writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest FROM event_journal WHERE persistence_id=? AND sequence_number=?")
      q.setString(1,pid);q.setLong(2,sequence.toLong)
      val row=try {val r=q.executeQuery();try {require(r.next());(r.getString(1),r.getLong(2),r.getString(3),r.getBytes(4),r.getInt(5),r.getString(6))}finally r.close()}finally q.close()
      c.setAutoCommit(false);val t=c.createStatement();try t.execute("SET innodb_lock_wait_timeout=1")finally t.close()
      val insert=c.prepareStatement("INSERT INTO event_journal(persistence_id,sequence_number,writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest,deleted) VALUES(?,?,?,?,?,?,?,?,FALSE)")
      try {
        insert.setString(1,pid);insert.setLong(2,sequence.toLong+1);insert.setString(3,row._1);insert.setLong(4,row._2)
        insert.setString(5,row._3);insert.setBytes(6,row._4);insert.setInt(7,row._5);insert.setString(8,row._6)
        try {insert.executeUpdate();throw new IllegalStateException("journal range allowed concurrent insert")}
        catch {case e:SQLException=>require(e.getErrorCode==1205);println("lock_error="+e.getErrorCode)}
      } finally insert.close()
      c.rollback()
    } finally c.close()
  }
}
