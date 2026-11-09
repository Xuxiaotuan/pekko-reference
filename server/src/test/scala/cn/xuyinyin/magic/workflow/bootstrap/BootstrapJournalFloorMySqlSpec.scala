package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureConsumerIdentity,MySQLCaptureStore}
import cn.xuyinyin.magic.tags.ExternalIntegration
import com.typesafe.config.ConfigFactory
import java.sql.{Connection,DriverManager}
import java.util.UUID
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
        val access=new BootstrapJournalAccess(kit.spawn(BootstrapJournal(capture+"-req")))
        val first=Await.result(access.update(_.copy(definition="definition",executionId="execution",deltaAfter=1)),15.seconds)
        store.projectConsumerJournal(consumer,"BOOTSTRAP","req","bootstrap",1,first.revision,"observed-1")
        floor shouldBe 0L
        val receipt=Await.result(access.persistedProgress(),10.seconds).get
        val verifier=BootstrapJournalProgressVerifier.create(kit.system,()=>open(),()=>open())
        intercept[IllegalArgumentException](store.projectBootstrapVerified(consumer,"req","generation","wrong",receipt,verifier))
        floor shouldBe 0L
        store.projectBootstrapVerified(consumer,"req","generation","definition",receipt,verifier)
        floor shouldBe 1L
        val lock=open();lock.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);lock.setAutoCommit(false)
        try {
          verifier.verifyOn(lock,receipt,capture,"req","definition")
          val next=access.update(_.copy(deltaAfter=2))
          intercept[java.util.concurrent.TimeoutException](Await.result(next,300.millis))
          lock.rollback()
          val second=Await.result(next,15.seconds)
          store.projectConsumerJournal(consumer,"BOOTSTRAP","req","bootstrap",2,second.revision,"observed-2")
        }finally lock.close()
        intercept[IllegalArgumentException](store.projectBootstrapVerified(consumer,"req","generation","definition",receipt,verifier))
        floor shouldBe 1L
        val latest=Await.result(access.persistedProgress(),10.seconds).get
        exec(s"UPDATE event_journal SET deleted=TRUE WHERE persistence_id='bootstrap-v1-$capture-req' AND sequence_number=${latest.sequenceNr}")
        intercept[IllegalArgumentException](store.projectBootstrapVerified(consumer,"req","generation","definition",latest,verifier))
        floor shouldBe 1L
        exec(s"UPDATE event_journal SET deleted=FALSE WHERE persistence_id='bootstrap-v1-$capture-req' AND sequence_number=${latest.sequenceNr}")
        store.projectBootstrapVerified(consumer,"req","generation","definition",latest,verifier)
        floor shouldBe 2L
        println(s"BOOTSTRAP_JOURNAL_FLOOR schema=$schema actualSequence=${latest.sequenceNr} staleRejected=true deletedRejected=true nextWriterBlocked=true finalFloor=2 auditFloor=0")
      }finally kit.shutdownTestKit()
    }
  }
}
