package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.{Connection,DriverManager}
import java.util.UUID
import java.util.concurrent.{CountDownLatch,TimeUnit}
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import spray.json._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Long ACK ancestry against an isolated MySQL. The retained L is never GC'd. */
class CaptureAckLongMySqlSpec extends AnyWordSpec with Matchers {
  "ACK proof paging" should {
    "resume a chain beyond the old 4096 bound and reject an absent parent" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_ACK_LONG_PROBE").contains("1"))cancel("isolated MySQL required")
      Class.forName("com.mysql.cj.jdbc.Driver")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      val url=sys.env("CAPTURE_STATE_URL");require(url.contains("pekko_test_shared_state") && !url.contains(":3306/"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val schema="pekko_test_ack_long_"+suffix
      val admin=DriverManager.getConnection(url,user,password)
      try {val q=admin.createStatement();try q.execute("CREATE DATABASE "+schema)finally q.close()}finally admin.close()
      val isolated=url.replace("pekko_test_shared_state",schema)
      def open():Connection=DriverManager.getConnection(isolated,user,password)
      def tx[A](f:Connection=>A):A={val c=open();c.setAutoCommit(false);try {val x=f(c);c.commit();x}finally c.close()}
      val store=new MySQLCaptureStore(()=>open(),"long_"+suffix,"source-uuid",50000000);store.initialize()
      val schemaDef=store.rememberSchema("orders","{}")
      val offset="""{"file":"mysql-bin.000001","pos":100}"""
      store.cover(offset,Vector(store.schemaSequence("orders",schemaDef.id)))
      store.registerAnchored("req","orders","generation",schemaDef.id,offset)
      for(_<-1 to 4100)store.cover(offset)
      val d=store.pendingBDescriptor(offset,"req").get
      withClue("legacy proof must hit its fixed chain budget") {
        intercept[IllegalArgumentException](store.recoverablePendingProofAt(offset,"req"))
      }
      val pages=new CaptureAckPaging(()=>open(),store);pages.initialize()
      val identity=CaptureAckPageIdentity("req",offset,d.head,d.anchor,d.intentDigest,"a"*64,"test-guardian")
      println(s"ACK_IDENTITY capture=${store.captureId} task=${identity.task} D=${identity.offset} head=${identity.head} anchor=${identity.anchor} intent=${identity.intent} fence=${identity.sourceFence} guardian=${identity.guardianAttempt}")
      tx(c=>pages.beginOn(c,identity))
      val first=tx(c=>pages.stepOn(c,identity,0,128));first.done shouldBe false
      val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
        .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
      val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
      def worker(revision:Long):Unit={
        val p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx128m","-cp",cp,
          CaptureAckPageRestartWorker.getClass.getName.stripSuffix("$"),isolated,store.captureId,d.head.toString,d.anchor.toString,d.intentDigest,revision.toString)
          .redirectErrorStream(true).start()
        val exited=p.waitFor(60,TimeUnit.SECONDS)
        if(!exited)p.destroyForcibly()
        val output=scala.io.Source.fromInputStream(p.getInputStream).mkString
        require(exited && p.exitValue()==0,"ACK paging JVM failed: "+output)
        println(output.trim)
      }
      worker(first.revision)
      worker(first.revision+1)
      val afterJvms=tx(c=>pages.statusOn(c,identity))
      afterJvms.revision shouldBe first.revision+2
      val rolled=open();rolled.setAutoCommit(false)
      try {pages.stepOn(rolled,identity,afterJvms.revision,128);rolled.rollback()}finally rolled.close()
      val newStore=new MySQLCaptureStore(()=>open(),store.captureId,"source-uuid",50000000)
      val reopened=new CaptureAckPaging(()=>open(),newStore)
      tx(c=>reopened.statusOn(c,identity)).revision shouldBe afterJvms.revision
      intercept[IllegalArgumentException](tx(c=>reopened.statusOn(c,identity.copy(sourceFence="changed"))))
      intercept[IllegalArgumentException](tx(c=>reopened.stepOn(c,identity.copy(sourceFence="changed"),afterJvms.revision,128)))
      val cut=new CountDownLatch(1)
      val proxy=new MySQLCommitResponseProxy("127.0.0.1",sys.env("CAPTURE_STATE_PORT").toInt,
        (event,_)=>if(event=="PROXY_COMMIT_OK_HELD")cut.countDown(),advertisePlaintextOnly=true)
      try {
        val viaProxy=isolated.replace(":"+sys.env("CAPTURE_STATE_PORT")+"/",":"+proxy.port+"/")
        val c=DriverManager.getConnection(viaProxy,user,password);c.setAutoCommit(false)
        try {
          reopened.stepOn(c,identity,afterJvms.revision,128)
          proxy.arm("C2",JsObject("stage"->JsString("ack-page")))
          val commit=Future {try {c.commit();false}catch {case _:java.sql.SQLException=>true}}
          require(cut.await(15,TimeUnit.SECONDS),"actual ACK page COMMIT was not observed")
          proxy.dropHeldResponse()
          Await.result(commit,15.seconds) shouldBe true
        }finally c.close()
      }finally proxy.close()
      val afterUnknown=tx(c=>reopened.statusOn(c,identity))
      afterUnknown.revision shouldBe afterJvms.revision+1
      println(s"ACK_COMMIT_UNKNOWN identity=${d.intentDigest} guardian=${identity.guardianAttempt} revision=${afterUnknown.revision} pin=present")
      val inj=open();try {
        val q=inj.createStatement();try {
          q.execute("CREATE TABLE ack_fault_backup LIKE pekko_capture_log")
          q.execute("INSERT INTO ack_fault_backup SELECT * FROM pekko_capture_log WHERE capture_id='"+store.captureId+"' AND seq=100")
          q.execute("DELETE FROM pekko_capture_log WHERE capture_id='"+store.captureId+"' AND seq=100")
        }finally q.close()
      }finally inj.close()
      var page=afterUnknown;var failed=false;var passes=0
      while(!failed && !page.done && passes<100) {
        try {page=tx(c=>reopened.stepOn(c,identity,page.revision,128));println(s"ACK_PAGE revision=${page.revision} done=${page.done} identity=${d.intentDigest} pin=present")}
        catch {case e:IllegalArgumentException=>
          require(Option(e.getMessage).exists(_.contains("capture proof references a missing frame")),"unexpected ACK page failure: "+e.getMessage)
          println(s"ACK_MISSING_FRAME seq=100 revision=${page.revision} error=${e.getMessage}");failed=true}
        passes+=1
      }
      failed shouldBe true
      tx(c=>reopened.statusOn(c,identity)).done shouldBe false
      val repair=open();try {val q=repair.createStatement();try q.execute("INSERT INTO pekko_capture_log SELECT * FROM ack_fault_backup")finally q.close()}finally repair.close()
      while(!page.done && passes<200){page=tx(c=>reopened.stepOn(c,identity,page.revision,128));println(s"ACK_PAGE revision=${page.revision} done=${page.done} identity=${d.intentDigest} pin=present");passes+=1}
      page.done shouldBe true
      page.anchorMatchesOffset shouldBe true
      tx(c=>newStore.controlsBoundOn(c,Vector((store.markerId("req",false),"SUBSCRIBE","orders","generation")),"req",d,identity,reopened)) shouldBe true
      val oldWitness=tx(c=>CapturePinnedProof.verifyOn(c,store.captureId,"source-uuid","req",offset,"a"*64,"test-guardian"))
      val later="""{"file":"mysql-bin.000001","pos":101}"""
      tx(c=>CapturePinnedProof.advancedIfCompleteOn(c,oldWitness,later)) shouldBe None
      val originalHeadDigest={val c=open();try {val q=c.prepareStatement("SELECT digest FROM pekko_capture_log WHERE capture_id=? AND seq=?")
        try {q.setString(1,store.captureId);q.setLong(2,d.head);val r=q.executeQuery();try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}finally c.close()}
      store.bind("req",CapturePoint("mysql-bin.000001",100,1),"{}",stop=false)
      val barrier=store.subscription("req").get.bSequence.get
      store.cover(later,Vector(barrier))
      val advanced=store.pendingBDescriptor(later,"req").get
      val both=CaptureAckPageIdentity("req",later,advanced.head,advanced.anchor,advanced.intentDigest,"a"*64,"test-guardian",Some(d.head),Some(originalHeadDigest))
      intercept[IllegalArgumentException](tx(c=>CapturePinnedProof.verifyAdvancedOn(c,oldWitness,later)))
      tx(c=>CapturePinnedProof.advancedIfCompleteOn(c,oldWitness,later)) shouldBe None
      tx(c=>reopened.beginOn(c,both));var next=tx(c=>reopened.statusOn(c,both));var steps=0
      tx(c=>CapturePinnedProof.advancedIfCompleteOn(c,oldWitness,later)) shouldBe None
      while(!next.done && steps<200){next=tx(c=>reopened.stepOn(c,both,next.revision,128));steps+=1}
      next.done shouldBe true
      tx(c=>newStore.completeModeOn(c,"req",advanced,both,reopened)) shouldBe "COVERED"
      val readyWitness=tx(c=>CapturePinnedProof.verifyAdvancedOn(c,oldWitness,later))
      tx(c=>CapturePinnedProof.advancedIfCompleteOn(c,oldWitness,later)) shouldBe Some(readyWitness)
      readyWitness.mode shouldBe "COVERED"
      readyWitness.identity.priorHead shouldBe Some(d.head)
      tx(c=>CapturePinnedProof.verifyExactOn(c,readyWitness))
      val fork=both.copy(priorHead=Some(d.intentSeq),priorDigest=Some(d.intentDigest))
      tx(c=>reopened.beginOn(c,fork));var forkPage=tx(c=>reopened.statusOn(c,fork));var rejected=false;var forkSteps=0
      while(!rejected && !forkPage.done && forkSteps<200){
        try forkPage=tx(c=>reopened.stepOn(c,fork,forkPage.revision,128))
        catch {case e:IllegalArgumentException=>require(Option(e.getMessage).exists(_.contains("prior ACK head is not in verified ancestry")));rejected=true}
        forkSteps+=1
      }
      rejected shouldBe true
      tx(c=>reopened.statusOn(c,fork)).done shouldBe false
      println(s"ACK_DUAL_ANCESTOR oldHead=${d.head} newHead=${advanced.head} barrier=$barrier forkRejected=$rejected")
      println(s"ACK_LONG schema=$schema head=${d.head} anchor=${d.anchor} pages=$passes restored=true")
    }
  }
}

/** One page per JVM so that the next process must read committed proof state. */
object CaptureAckPageRestartWorker {
  def main(args:Array[String]):Unit={
    require(args.length==6)
    Class.forName("com.mysql.cj.jdbc.Driver")
    val Array(url,capture,head,anchor,intent,revision)=args
    val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
    def open():Connection=DriverManager.getConnection(url,user,password)
    val store=new MySQLCaptureStore(()=>open(),capture,"source-uuid",50000000)
    val pages=new CaptureAckPaging(()=>open(),store)
    val i=CaptureAckPageIdentity("req","""{"file":"mysql-bin.000001","pos":100}""",head.toLong,anchor.toLong,intent,"a"*64,"test-guardian")
    val c=open();c.setAutoCommit(false)
    try {val page=pages.stepOn(c,i,revision.toLong,128);c.commit();println(s"ACK_JVM pid=${ProcessHandle.current().pid()} revision=${page.revision}")}
    finally c.close()
  }
}
