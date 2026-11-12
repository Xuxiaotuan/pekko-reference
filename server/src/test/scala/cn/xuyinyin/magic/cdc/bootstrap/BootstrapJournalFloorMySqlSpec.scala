package cn.xuyinyin.magic.cdc.bootstrap

import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureConsumerIdentity,MySQLCaptureStore}
import cn.xuyinyin.magic.tags.ExternalIntegration
import com.typesafe.config.ConfigFactory
import java.sql.{Connection,DriverManager}
import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files,Path}
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Actual JDBC Actor Journal event, exact sequence and same-transaction retention projection. */
class BootstrapJournalFloorMySqlSpec extends AnyWordSpec with Matchers {
  "bootstrap Journal floor" should {
    "advance only from the latest durable actor event and reject stale or deleted evidence" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_BOOTSTRAP_FLOOR_PROBE").contains("1"))cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      Class.forName("com.mysql.cj.jdbc.Driver")
      val root=sys.env("CAPTURE_STATE_URL");require(root.contains(":25822/") && root.contains("pekko_test_shared_state"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val schema="pekko_test_jfloor_"+suffix;val capture="floor_"+suffix
      val admin=DriverManager.getConnection(root,user,password)
      try {val q=admin.createStatement();try q.execute("CREATE DATABASE "+schema)finally q.close()}finally admin.close()
      val url=root.replace("pekko_test_shared_state",schema)
      def open():Connection=DriverManager.getConnection(url,user,password)
      def exec(sql:String):Unit={val c=open();try {val q=c.createStatement();try q.execute(sql)finally q.close()}finally c.close()}
      def floor:Long={val c=open();try {val q=c.createStatement();try {val r=q.executeQuery("SELECT journal_floor FROM pekko_capture_consumers_v1")
        try {require(r.next());r.getLong(1)}finally r.close()}finally q.close()}finally c.close()}
      val ddl=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-persistence-schema.sql")
      val text=try scala.io.Source.fromInputStream(ddl).mkString finally ddl.close();text.split(";").map(_.trim).filter(_.nonEmpty).foreach(exec)
      val store=new MySQLCaptureStore(()=>open(),capture,"lineage",10000000);store.initialize()
      store.rememberProfile("""{"database":"db"}""");store.rememberSchema("orders","{}")
      // This is a test-only fixture to exercise the verifier, not a claim of namespace birth.
      exec(s"UPDATE pekko_capture_gc_state_v1 SET discovery_status='COMPLETE' WHERE capture_id='$capture'")
      val consumer=CaptureConsumerIdentity.bootstrap(capture,"req","generation")
      store.registerConsumer(consumer,"BOOTSTRAP","req","bootstrap",0)
      val config=ConfigFactory.parseMap(Map[String,AnyRef]("pekko.actor.provider"->"local",
        "pekko.persistence.journal.plugin"->"jdbc-journal","jdbc-journal.use-shared-db"->"slick",
        "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
        "pekko-persistence-jdbc.shared-databases.slick.db.url"->url,
        "pekko-persistence-jdbc.shared-databases.slick.db.user"->user,
        "pekko-persistence-jdbc.shared-databases.slick.db.password"->password).asJava).withFallback(ConfigFactory.load("application-test"))
      val kit=ActorTestKit("jfloor-"+suffix,config)
      implicit val ec:scala.concurrent.ExecutionContext=kit.system.executionContext
      implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
      try {
        val actor=kit.spawn(BootstrapJournal(capture+"-req"))
        val access=new BootstrapJournalAccess(actor)
        val first=Await.result(access.update(_.copy(definition="definition",executionId="execution",deltaAfter=1)),15.seconds)
        store.projectConsumerJournal(consumer,"BOOTSTRAP","req","bootstrap",1,first.revision,"observed-1")
        floor shouldBe 0L
        val receipt=Await.result(access.persistedProgress(),10.seconds).get
        val verifier=BootstrapJournalProgressVerifier.create(kit.system,()=>open(),()=>open())
        val forged=cn.xuyinyin.magic.testsupport.ForgedJournalReceipts.bootstrap(receipt)
        intercept[IllegalArgumentException](store.projectBootstrapVerified(consumer,"req","generation","definition",forged,verifier))
        floor shouldBe 0L
        intercept[IllegalArgumentException](store.projectBootstrapVerified(consumer,"req","generation","wrong",receipt,verifier))
        floor shouldBe 0L
        // The new JVM must recover Journal state and issue a fresh, local identity capability.
        kit.stop(actor)
        val dir=Path.of(sys.env("CDC_RESTART_RECEIPT_EVIDENCE"));Files.createDirectories(dir)
        val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
          .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
        val cp=(loaders++System.getProperty("java.class.path").split(File.pathSeparator).iterator).toVector.distinct.mkString(File.pathSeparator)
        val pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-cp",cp,
          BootstrapJournalReceiptRestartProbe.getClass.getName.stripSuffix("$"),capture)
        pb.environment().put("PROBE_JDBC_URL",url);pb.environment().put("PROBE_JDBC_USER",user);pb.environment().put("PROBE_JDBC_PASSWORD",password)
        pb.redirectErrorStream(true);pb.redirectOutput(dir.resolve("receipt-restart-child.log").toFile)
        val child=pb.start();require(child.waitFor(60,TimeUnit.SECONDS),"restart probe timed out")
        child.exitValue() shouldBe 0
        Files.readString(dir.resolve("receipt-restart-child.log")) should include ("RESTART_RECEIPT verified=true forgedRejected=true before=0 after=1")
        floor shouldBe 1L
        // A live old reference remains usable in this JVM while the persisted event is unchanged.
        store.projectBootstrapVerified(consumer,"req","generation","definition",receipt,verifier)
        floor shouldBe 1L
        val resumedActor=kit.spawn(BootstrapJournal(capture+"-req"))
        val resumed=new BootstrapJournalAccess(resumedActor)
        val lock=open();lock.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);lock.setAutoCommit(false)
        try {
          verifier.verifyOn(lock,receipt,capture,"req","definition")
          val next=resumed.update(_.copy(deltaAfter=2))
          intercept[java.util.concurrent.TimeoutException](Await.result(next,300.millis))
          lock.rollback()
          val second=Await.result(next,15.seconds)
          store.projectConsumerJournal(consumer,"BOOTSTRAP","req","bootstrap",2,second.revision,"observed-2")
        }finally lock.close()
        intercept[IllegalArgumentException](store.projectBootstrapVerified(consumer,"req","generation","definition",receipt,verifier))
        floor shouldBe 1L
        val latest=Await.result(resumed.persistedProgress(),10.seconds).get
        exec(s"UPDATE event_journal SET deleted=TRUE WHERE persistence_id='bootstrap-v1-$capture-req' AND sequence_number=${latest.sequenceNr}")
        intercept[IllegalArgumentException](store.projectBootstrapVerified(consumer,"req","generation","definition",latest,verifier))
        floor shouldBe 1L
        exec(s"UPDATE event_journal SET deleted=FALSE WHERE persistence_id='bootstrap-v1-$capture-req' AND sequence_number=${latest.sequenceNr}")
        store.projectBootstrapVerified(consumer,"req","generation","definition",latest,verifier)
        floor shouldBe 2L
        Vector("SCANNING","REPLAYING","ACTIVATING","STREAMING").foreach {phase=>
          Await.result(resumed.update(_.copy(phase=phase)),10.seconds)
        }
        val streaming=Await.result(resumed.read(),10.seconds)
        val activation="""{"bootstrap":"req","definition":"definition","baselineRoot":""}"""
        val gcProof=BootstrapGcCheckpoint.create(capture,"lineage","req","generation",streaming,activation,"target_orders","db")
        intercept[cn.xuyinyin.magic.cdc.recovery.CdcExecutionException](Await.result(resumed.update(_.copy(gcCheckpoint=Some(gcProof))),10.seconds))
        Await.result(resumed.sealGcCheckpoint(streaming.revision-1,gcProof),10.seconds).accepted shouldBe false
        Await.result(resumed.sealGcCheckpoint(streaming.revision,gcProof),10.seconds).accepted shouldBe true
        Await.result(resumed.read(),10.seconds).gcCheckpoint shouldBe Some(gcProof)
        kit.stop(resumedActor)
        val restored=new BootstrapJournalAccess(kit.spawn(BootstrapJournal(capture+"-req")))
        Await.result(restored.read(),10.seconds).gcCheckpoint shouldBe Some(gcProof)
        println(s"BOOTSTRAP_JOURNAL_FLOOR schema=$schema actualSequence=${latest.sequenceNr} javaForgedRejected=true staleRejected=true deletedRejected=true nextWriterBlocked=true finalFloor=2 auditFloor=0")
      }finally kit.shutdownTestKit()
    }
  }
}

/** Runs in a fresh JVM against the same isolated MySQL Journal and capture state. */
object BootstrapJournalReceiptRestartProbe {
  def main(args:Array[String]):Unit={
    import org.apache.pekko.actor.typed.ActorSystem
    import org.apache.pekko.actor.typed.scaladsl.Behaviors
    Class.forName("com.mysql.cj.jdbc.Driver")
    val capture=args.head;val url=sys.env("PROBE_JDBC_URL");val user=sys.env("PROBE_JDBC_USER");val password=sys.env("PROBE_JDBC_PASSWORD")
    require(capture.matches("floor_[a-f0-9]{10}") && url.contains(":25822/pekko_test_jfloor_"))
    def open():Connection=DriverManager.getConnection(url,user,password)
    def floor:Long={val c=open();try {val q=c.createStatement();try {val r=q.executeQuery("SELECT journal_floor FROM pekko_capture_consumers_v1");try {require(r.next());r.getLong(1)}finally r.close()}finally q.close()}finally c.close()}
    val config=ConfigFactory.parseMap(Map[String,AnyRef]("pekko.actor.provider"->"local",
      "pekko.persistence.journal.plugin"->"jdbc-journal","jdbc-journal.use-shared-db"->"slick",
      "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url"->url,
      "pekko-persistence-jdbc.shared-databases.slick.db.user"->user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password"->password).asJava).withFallback(ConfigFactory.load("application-test"))
    val system=ActorSystem[Nothing](Behaviors.empty,"receipt-restart",config)
    implicit val ec:scala.concurrent.ExecutionContext=system.executionContext
    implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=system.scheduler
    try {
      val access=new BootstrapJournalAccess(system.systemActorOf(BootstrapJournal(capture+"-req"),"journal"))
      val receipt=Await.result(access.persistedProgress(),20.seconds).get
      val store=new MySQLCaptureStore(()=>open(),capture,"lineage",10000000)
      val consumer=CaptureConsumerIdentity.bootstrap(capture,"req","generation")
      val verifier=BootstrapJournalProgressVerifier.create(system,()=>open(),()=>open())
      require(floor==0L)
      val forged=cn.xuyinyin.magic.testsupport.ForgedJournalReceipts.bootstrap(receipt)
      val rejected=try {store.projectBootstrapVerified(consumer,"req","generation","definition",forged,verifier);false}catch {case _:IllegalArgumentException=>true}
      require(rejected && floor==0L)
      store.projectBootstrapVerified(consumer,"req","generation","definition",receipt,verifier)
      require(floor==1L)
      println("RESTART_RECEIPT verified=true forgedRejected=true before=0 after=1")
    }finally {system.terminate();Await.result(system.whenTerminated,20.seconds)}
  }
}
