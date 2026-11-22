package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.cdc._
import spray.json._
import java.lang.reflect.InvocationTargetException
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Real storage semantics; isolated archive experiment, no deletion by production code. */
class CaptureArchiveMySqlSpec extends AnyWordSpec with Matchers {
  "capture archive" should {
    "bind full frames, reject corruption, and keep archived occurrence identities" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_ARCHIVE_PROBE").contains("1"))cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      val url=sys.env("CAPTURE_STATE_URL");require(url.contains("pekko_test_shared_state") && !url.contains(":3306/"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10)
      val admin=DriverManager.getConnection(url,user,password)
      try {val q=admin.createStatement();try q.execute(s"CREATE DATABASE pekko_test_archive_$suffix")finally q.close()}finally admin.close()
      val isolated=url.replace("pekko_test_shared_state","pekko_test_archive_"+suffix)
      def open():Connection=DriverManager.getConnection(isolated,user,password)
      val id="archive_"+suffix
      val s=new MySQLCaptureStore(()=>open(),id,"source-uuid",10000000);s.initialize()
      val profile="""{"database":"demo","protocol":1}"""
      val p=s.rememberProfile(profile);p shouldBe 1L
      val schema=s.rememberSchema("orders","{}")
      val offset="""{"file":"binlog.000001","pos":100}"""
      s.cover(offset,Vector(1L,2L));s.verifyOffset(offset)
      val pages=new CaptureAckPaging(()=>open(),s);pages.initialize()
      val identity=CaptureAckPageIdentity("task",offset,3L,3L,"intent","fence","guardian")
      def transaction[A](f:Connection=>A):A={val c=open();c.setAutoCommit(false);try {val value=f(c);c.commit();value}finally c.close()}
      transaction(c=>pages.beginOn(c,identity))
      val rolledBack=open();rolledBack.setAutoCommit(false)
      try {pages.stepOn(rolledBack,identity,0,1);rolledBack.rollback()}finally rolledBack.close()
      transaction(c=>pages.statusOn(c,identity)).revision shouldBe 0L
      intercept[IllegalArgumentException](transaction(c=>pages.containsOn(c,identity,1L)))
      val reopened=new CaptureAckPaging(()=>open(),new MySQLCaptureStore(()=>open(),id,"source-uuid",10000000))
      var page=transaction(c=>reopened.statusOn(c,identity));var steps=0
      while(!page.done && steps<20){page=transaction(c=>reopened.stepOn(c,identity,page.revision,1));steps+=1}
      page.done shouldBe true
      page.anchorMatchesOffset shouldBe true
      transaction(c=>pages.containsOn(c,identity,1L)) shouldBe true
      transaction(c=>pages.containsOn(c,identity,999L)) shouldBe false
      intercept[IllegalArgumentException](transaction(c=>pages.containsOn(c,identity.copy(guardianAttempt="other"),1L)))
      val badNode=open();try {
        val q=badNode.prepareStatement("UPDATE pekko_capture_ack_page_nodes_v1 SET ack_digest=? WHERE capture_id=? AND ack_seq=3")
        try {q.setString(1,"0"*64);q.setString(2,id);q.executeUpdate() shouldBe 1}finally q.close()
        intercept[IllegalArgumentException](transaction(c=>pages.containsOn(c,identity,1L)))
        val repair=badNode.prepareStatement("UPDATE pekko_capture_ack_page_nodes_v1 n JOIN pekko_capture_log l ON n.capture_id=l.capture_id AND n.ack_seq=l.seq SET n.ack_digest=l.digest WHERE n.capture_id=? AND n.ack_seq=3")
        try {repair.setString(1,id);repair.executeUpdate() shouldBe 1}finally repair.close()
      }finally badNode.close()
      val stale=new MySQLCaptureStore(()=>open(),id,"source-uuid",10000000);stale.initialize();stale.verifyOffset(offset)
      val checkDefault=open();try {val q=checkDefault.createStatement();try {val r=q.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'pekko_capture_archive_%'")
        try {r.next();r.getInt(1) shouldBe 0}finally r.close()}finally q.close()}finally checkDefault.close()
      val seal=s.getClass.getMethods.find(_.getName=="sealArchiveRange")
      withClue("bounded archive API must exist"){seal should not be empty}
      def call(name:String,a:Long,b:Long):String={
        val method=s.getClass.getMethods.find(_.getName==name).getOrElse(fail(name+" missing"))
        try String.valueOf(method.invoke(s,Long.box(a),Long.box(b)))catch{case e:InvocationTargetException=>throw e.getCause}
      }
      val used= {
        val c=open();try {val q=c.prepareStatement("SELECT used_bytes FROM pekko_capture_readers WHERE capture_id=?")
          try {q.setString(1,id);val r=q.executeQuery();try {r.next();r.getLong(1)}finally r.close()}finally q.close()}finally c.close()
      }
      val owner=open();try {
        val q=owner.prepareStatement("SELECT GET_LOCK(?,0)");try {q.setString(1,"pekko_capture_"+id);val r=q.executeQuery();try {r.next();r.getInt(1) shouldBe 1}finally r.close()}finally q.close()
        intercept[IllegalArgumentException](call("sealArchiveRange",1L,3L))
      }finally owner.close()
      val noCapacity=new MySQLCaptureStore(()=>open(),id,"source-uuid",used)
      noCapacity.initialize()
      val limited=noCapacity.getClass.getMethod("sealArchiveRange",java.lang.Long.TYPE,java.lang.Long.TYPE)
      val capacity=intercept[InvocationTargetException](limited.invoke(noCapacity,Long.box(1),Long.box(3)))
      capacity.getCause.getMessage should include ("capture archive capacity exhausted")
      val root=call("sealArchiveRange",1L,3L);root should fullyMatch regex "[0-9a-f]{64}"
      call("sealArchiveRange",1L,3L) shouldBe root
      call("verifyArchiveRange",1L,3L) shouldBe root
      val archiveBytes={val c=open();try {val q=c.prepareStatement("SELECT used_bytes FROM pekko_capture_archive_budget_v1 WHERE capture_id=?")
        try {q.setString(1,id);val r=q.executeQuery();try {r.next();r.getLong(1)}finally r.close()}finally q.close()}finally c.close()}
      val full=new MySQLCaptureStore(()=>open(),id,"source-uuid",used+archiveBytes);full.initialize()
      intercept[IllegalArgumentException](full.rememberSchema("new_table","{}"))
      val restarted=new MySQLCaptureStore(()=>open(),id,"source-uuid",10000000);restarted.initialize();restarted.verifyOffset(offset)
      val point=CapturePoint("binlog.000001",110,0)
      s.registerAnchored("req","orders","generation",schema.id,offset)
      val descriptor=s.pendingBDescriptor(offset,"req").get
      val anchored=CaptureAckPageIdentity("req",offset,descriptor.head,descriptor.anchor,descriptor.intentDigest,"a"*64,"guardian")
      transaction(c=>pages.beginOn(c,anchored))
      var anchoredPage=transaction(c=>pages.statusOn(c,anchored))
      while(!anchoredPage.done)anchoredPage=transaction(c=>pages.stepOn(c,anchored,anchoredPage.revision,1))
      val control=Vector((s.markerId("req",false),"SUBSCRIBE","orders","generation"))
      transaction(c=>s.controlsBoundOn(c,control,"req",descriptor,anchored,pages)) shouldBe true
      val witness=transaction(c=>CapturePinnedProof.verifyOn(c,id,"source-uuid","req",offset,"a"*64,"guardian"))
      witness.mode shouldBe "PENDING"
      CapturePinnedWitness.parse(witness.json) shouldBe witness
      transaction(c=>CapturePinnedProof.verifyExactOn(c,CapturePinnedWitness.parse(witness.json)))
      intercept[IllegalArgumentException](CapturePinnedWitness.parse(JsObject(witness.json.parseJson.asJsObject.fields.removed("headDigest")).compactPrint))
      intercept[IllegalArgumentException](transaction(c=>CapturePinnedProof.verifyOn(c,id,"source-uuid","req",offset,"b"*64,"guardian")))
      s.bind("req",point,"{}",stop=false)
      val bound=s.pendingBDescriptor(offset,"req").get
      transaction(c=>s.controlsBoundOn(c,control,"req",bound,anchored,pages)) shouldBe true
      transaction(c=>CapturePinnedProof.verifyOn(c,id,"source-uuid","req",offset,"a"*64,"guardian")).mode shouldBe "REPLAY"
      val protocol=MySQLCdcGroupProtocol(id,"capture-v1","source-uuid")
      val envelope=MySQLCdcEnvelope(1,CdcOperation.Create,JsObject("id"->JsNumber(1)),None,Some(JsObject("id"->JsNumber(1))),
        MySQLCdcSourcePosition(id,"db","orders",false,Some(point.file),Some(120L),Some(0),Some(1)))
      val group=MySQLCdcGroup(MySQLCdcGroup.id(protocol,envelope),Vector("single.c"->envelope))
      val groupSeq=s.append(group).get
      s.read("req",0,10).map(_.group) shouldBe Vector(group)
      val consumer="c"*64
      s.registerConsumer(consumer,"WORKFLOW","req","source",0)
      val barrier=open();try {val q=barrier.createStatement();try {
        q.executeUpdate("UPDATE pekko_capture_gc_state_v1 SET barrier_status='OFFLINE' WHERE capture_id='"+id+"'") shouldBe 1
        intercept[IllegalArgumentException](s.projectConsumerJournal(consumer,"WORKFLOW","req","source",groupSeq,1,"checkpoint"))
        intercept[IllegalArgumentException](s.registerConsumer("d"*64,"WORKFLOW","req","source",0))
        val r=q.executeQuery("SELECT journal_floor FROM pekko_capture_consumers_v1 WHERE consumer_id='"+consumer+"'")
        try {r.next();r.getLong(1) shouldBe 0L}finally r.close()
        q.executeUpdate("UPDATE pekko_capture_gc_state_v1 SET barrier_status='OPEN' WHERE capture_id='"+id+"'") shouldBe 1
      }finally q.close()}finally barrier.close()
      s.projectConsumerJournal(consumer,"WORKFLOW","req","source",groupSeq,1,"checkpoint")
      s.registerConsumer(consumer,"WORKFLOW","req","source",0)
      intercept[IllegalArgumentException](s.projectConsumerJournal(consumer,"WORKFLOW","req","source",groupSeq,1,"different"))
      val retention=open();try {val q=retention.createStatement();try {
        val r=q.executeQuery("SELECT journal_floor,audit_floor,observed_floor FROM pekko_capture_consumers_v1 WHERE consumer_id='"+consumer+"'")
        try {r.next();r.getLong(1) shouldBe 0L;r.getLong(2) shouldBe 0L;r.getLong(3) shouldBe groupSeq}finally r.close()
        val state=q.executeQuery("SELECT discovery_status FROM pekko_capture_gc_state_v1 WHERE capture_id='"+id+"'")
        try {state.next();state.getString(1) shouldBe "UNKNOWN"}finally state.close()
      }finally q.close()}finally retention.close()
      call("sealArchiveRange",4L,groupSeq)
      restarted.read("req",0,10).map(_.group) shouldBe Vector(group)
      val database=open()
      try {
        val corruptGroup=database.prepareStatement("UPDATE pekko_capture_archive_frames_v1 SET payload='tampered' WHERE capture_id=? AND seq=?")
        try {corruptGroup.setString(1,id);corruptGroup.setLong(2,groupSeq);corruptGroup.executeUpdate() shouldBe 1}finally corruptGroup.close()
        intercept[IllegalArgumentException](restarted.read("req",0,10))
        val restoreGroup=database.prepareStatement("UPDATE pekko_capture_archive_frames_v1 a JOIN pekko_capture_log l ON a.capture_id=l.capture_id AND a.seq=l.seq SET a.payload=l.payload WHERE a.capture_id=? AND a.seq=?")
        try {restoreGroup.setString(1,id);restoreGroup.setLong(2,groupSeq);restoreGroup.executeUpdate() shouldBe 1}finally restoreGroup.close()
        restarted.read("req",0,10).map(_.group) shouldBe Vector(group)
        val dropArchive=database.prepareStatement("DELETE FROM pekko_capture_archive_frames_v1 WHERE capture_id=? AND seq=2")
        try {dropArchive.setString(1,id);dropArchive.executeUpdate() shouldBe 1}finally dropArchive.close()
        intercept[IllegalArgumentException](s.verifyOffset(offset))
        val restoreFrame=database.prepareStatement("INSERT INTO pekko_capture_archive_frames_v1 SELECT l.*,1 FROM pekko_capture_log l WHERE l.capture_id=? AND l.seq=2")
        try {restoreFrame.setString(1,id);restoreFrame.executeUpdate() shouldBe 1}finally restoreFrame.close()
        s.verifyOffset(offset)
        val q=database.prepareStatement("UPDATE pekko_capture_archive_frames_v1 SET resume_json='tampered' WHERE capture_id=? AND seq=3")
        try {q.setString(1,id);q.executeUpdate() shouldBe 1}finally q.close()
        intercept[IllegalArgumentException](call("verifyArchiveRange",1L,3L))
        intercept[IllegalArgumentException](s.verifyOffset(offset))
        intercept[IllegalArgumentException](stale.verifyOffset(offset))
        val restore=database.prepareStatement("UPDATE pekko_capture_archive_frames_v1 a JOIN pekko_capture_log l ON a.capture_id=l.capture_id AND a.seq=l.seq SET a.resume_json=l.resume_json WHERE a.capture_id=? AND a.seq=3")
        try {restore.setString(1,id);restore.executeUpdate()}finally restore.close()
        call("verifyArchiveRange",1L,3L) shouldBe root
        val corrupt=database.prepareStatement("UPDATE pekko_capture_log SET resume_json='changed-live' WHERE capture_id=? AND seq=3")
        try {corrupt.setString(1,id);corrupt.executeUpdate() shouldBe 1}finally corrupt.close()
        intercept[IllegalArgumentException](s.verifyOffset(offset))
        val undo=database.prepareStatement("UPDATE pekko_capture_log l JOIN pekko_capture_archive_frames_v1 a ON a.capture_id=l.capture_id AND a.seq=l.seq SET l.resume_json=a.resume_json WHERE l.capture_id=? AND l.seq=3")
        try {undo.setString(1,id);undo.executeUpdate()}finally undo.close()
        s.verifyOffset(offset)
        val remove=database.prepareStatement("DELETE FROM pekko_capture_log WHERE capture_id=? AND seq=1")
        try {remove.setString(1,id);remove.executeUpdate() shouldBe 1}finally remove.close()
        intercept[IllegalArgumentException](s.rememberProfile(profile))
        intercept[CaptureAdmissionException](s.recoveryInventory)
      }finally database.close()
    }
  }
}
