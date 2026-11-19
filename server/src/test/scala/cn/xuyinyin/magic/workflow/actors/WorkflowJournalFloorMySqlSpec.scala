package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.connectors.mysql.source.capture.LegacyWorkflowProjectionSyntax._

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.{TestNodeRegistry, WorkflowFixtures}
import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.engine.ExecutionResult
import cn.xuyinyin.magic.workflow.execution.{ReliableRunContext, WorkflowExecutionEngine}
import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL
import cn.xuyinyin.magic.workflow.spi.{CheckpointedNodeSource, NodeSource}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureConsumerIdentity,CapturedLogSource,CapturePoint,MySQLCaptureStore}
import cn.xuyinyin.magic.cdc._
import spray.json._
import com.typesafe.config.ConfigFactory
import java.sql.{Connection, DriverManager}
import java.util.UUID
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

private final class FloorTestEngine(registry:cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry,
  captured:Promise[ReliableRunContext],completion:Promise[ExecutionResult])(implicit system:org.apache.pekko.actor.typed.ActorSystem[_], ec:ExecutionContext) extends WorkflowExecutionEngine(nodeRegistry=registry) {
  override def execute(w:WorkflowDSL.Workflow,r:ReliableRunContext,l:String=>Unit):Future[ExecutionResult]={captured.trySuccess(r);completion.future}
}

/** Exercises actor-issued workflow receipts against an isolated, real JDBC Journal. */
class WorkflowJournalFloorMySqlSpec extends AnyWordSpec with Matchers {
  "workflow Journal proof" should {
    "replay preserved old MySQL Journal bytes into the current actor" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_WORKFLOW_FLOOR_PROBE").contains("1")) cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      Class.forName("com.mysql.cj.jdbc.Driver")
      val root = sys.env("CAPTURE_STATE_URL")
      require(root.contains(":25822/") && root.contains("pekko_test_shared_state"))
      val user = sys.env("CDC_WINDOW_MYSQL_USER"); val password = sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix = UUID.randomUUID().toString.replace("-", "").take(10)
      val schema = "pekko_test_wreplay_" + suffix
      val admin = DriverManager.getConnection(root, user, password)
      try { val q = admin.createStatement(); try q.execute("CREATE DATABASE " + schema) finally q.close() } finally admin.close()
      val url = root.replace("pekko_test_shared_state", schema)
      def open(): Connection = DriverManager.getConnection(url, user, password)
      val ddl = getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-persistence-schema.sql")
      val schemaSql = try scala.io.Source.fromInputStream(ddl).mkString finally ddl.close()
      val conn = open()
      val saved = java.nio.file.Files.readString(java.nio.file.Path.of("docs/experiments/2026-10-25-capture-startup/R02-original-trigger-eaa005be087a/final.json")).parseJson.asJsObject
      val rows = saved.fields("worker").asJsObject.fields("J").asInstanceOf[JsArray].elements
      val workflowId = "old-replay-" + suffix
      try {
        schemaSql.split(";").map(_.trim).filter(_.nonEmpty).foreach { sql => val q = conn.createStatement(); try q.execute(sql) finally q.close() }
        val insert = conn.prepareStatement("INSERT INTO event_journal (persistence_id,sequence_number,writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest) VALUES (?,?,?,?,?,?,?,?)")
        try rows.foreach { row =>
          val f = row.asJsObject.fields
          def string(k: String): String = f(k).asInstanceOf[JsString].value
          insert.setString(1, "workflow-" + workflowId); insert.setLong(2, string("sequence_number").toLong)
          insert.setString(3, "historical-fixture"); insert.setLong(4, 0L); insert.setString(5, "")
          insert.setBytes(6, java.util.HexFormat.of.parseHex(string("payload_hex")))
          insert.setInt(7, string("event_ser_id").toInt); insert.setString(8, string("event_ser_manifest")); insert.executeUpdate()
        } finally insert.close()
      } finally conn.close()
      val config = ConfigFactory.parseMap(Map[String,AnyRef]("pekko.actor.provider" -> "local", "pekko.persistence.journal.plugin" -> "jdbc-journal",
        "jdbc-journal.use-shared-db" -> "slick", "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
        "pekko-persistence-jdbc.shared-databases.slick.db.url" -> url, "pekko-persistence-jdbc.shared-databases.slick.db.user" -> user,
        "pekko-persistence-jdbc.shared-databases.slick.db.password" -> password).asJava).withFallback(ConfigFactory.load("application-test"))
      val kit = ActorTestKit("wreplay-" + suffix, config)
      implicit val ec: ExecutionContext = kit.system.executionContext
      implicit val system: org.apache.pekko.actor.typed.ActorSystem[_] = kit.system
      val captured = Promise[ReliableRunContext](); val completion = Promise[ExecutionResult]()
      val engine = new FloorTestEngine(new TestNodeRegistry().snapshot, captured, completion)
      try {
        val actor = kit.spawn(EventSourcedWorkflowActor(workflowId, engine))
        val reply = kit.createTestProbe[EventSourcedWorkflowActor.ReliableRunState]()
        actor ! EventSourcedWorkflowActor.GetReliableRunState(reply.ref)
        val state = reply.receiveMessage(20.seconds).currentExecution.get
        state.boundary should not be empty
        state.checkpoints.map(_.batchId) should contain ("06787ce1d8b5dd965498ca0928c6c3361dd3630fd399cc26467921b7602cd6db")
        println(s"WORKFLOW_OLD_JOURNAL_REPLAY schema=$schema historicalEvents=${rows.size} checkpointRestored=true")
      } finally kit.shutdownTestKit()
    }
    "bind the actual checkpoint event and reject stale or deleted evidence" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_WORKFLOW_FLOOR_PROBE").contains("1")) cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      Class.forName("com.mysql.cj.jdbc.Driver")
      val root=sys.env("CAPTURE_STATE_URL"); require(root.contains(":25822/") && root.contains("pekko_test_shared_state"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val schema="pekko_test_wfloor_"+suffix
      val admin=DriverManager.getConnection(root,user,password)
      try { val q=admin.createStatement();try q.execute("CREATE DATABASE "+schema) finally q.close() } finally admin.close()
      val url=root.replace("pekko_test_shared_state",schema)
      def open():Connection=DriverManager.getConnection(url,user,password)
      def exec(sql:String):Unit={val c=open();try {val q=c.createStatement();try q.execute(sql)finally q.close()}finally c.close()}
      val ddl=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-persistence-schema.sql")
      val text=try scala.io.Source.fromInputStream(ddl).mkString finally ddl.close();text.split(";").map(_.trim).filter(_.nonEmpty).foreach(exec)
      val capture="wfloor_"+suffix
      val store=new MySQLCaptureStore(()=>open(),capture,"source-uuid",10000000);store.initialize()
      store.rememberProfile("""{"database":"db","protocol":1}""")
      val schemaDef=store.rememberSchema("orders","""{"columns":[{"name":"id","jdbcType":-5,"typeName":"BIGINT"}],"primaryKeyColumnNames":["id"]}""")
      val offset="""{"file":"binlog.000001","pos":100}"""
      store.cover(offset,Vector(1L,2L));store.registerAnchored("req","orders","generation",schemaDef.id,offset)
      store.bind("req",CapturePoint("binlog.000001",110,0),"{}",stop=false)
      val protocol=MySQLCdcGroupProtocol(capture,"capture-v1","source-uuid")
      val row=MySQLCdcEnvelope(1,CdcOperation.Create,JsObject("id"->JsNumber(1)),None,Some(JsObject("id"->JsNumber(1))),
        MySQLCdcSourcePosition(capture,"db","orders",false,Some("binlog.000001"),Some(120L),Some(0),Some(1)))
      store.append(MySQLCdcGroup(MySQLCdcGroup.id(protocol,row),Vector("single.c"->row))).get
      val config=ConfigFactory.parseMap(Map[String,AnyRef]("pekko.actor.provider"->"local",
        "pekko.persistence.journal.plugin"->"jdbc-journal","jdbc-journal.use-shared-db"->"slick",
        "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
        "pekko-persistence-jdbc.shared-databases.slick.db.url"->url,
        "pekko-persistence-jdbc.shared-databases.slick.db.user"->user,
        "pekko-persistence-jdbc.shared-databases.slick.db.password"->password).asJava).withFallback(ConfigFactory.load("application-test"))
      val kit=ActorTestKit("wfloor-"+suffix,config)
      implicit val ec:ExecutionContext=kit.system.executionContext
      implicit val system:org.apache.pekko.actor.typed.ActorSystem[_]=kit.system
      val registry=new TestNodeRegistry
      val captured=Promise[ReliableRunContext]();val completion=Promise[ExecutionResult]()
      val engine=new FloorTestEngine(registry.snapshot,captured,completion)
      val id="wfloor-"+suffix
      val workflow=WorkflowFixtures.linearWorkflow.copy(nodes=WorkflowFixtures.linearWorkflow.nodes
        .updated(0,WorkflowFixtures.linearWorkflow.nodes.head.copy(nodeType="mysql.snapshot"))
        .updated(2,WorkflowFixtures.linearWorkflow.nodes(2).copy(nodeType="mysql.write")))
      try {
        val entity=kit.spawn(EventSourcedWorkflowActor(id,engine));val reply=kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
        entity ! EventSourcedWorkflowActor.DefineWorkflow(workflow,0L,reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined(id,1L))
        entity ! EventSourcedWorkflowActor.ExecuteManual("request",reply.ref)
        val execution=reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted].executionId
        val run=Await.result(captured.future,10.seconds)
        val verifier=WorkflowJournalProgressVerifier.create(kit.system,()=>open(),()=>open())
        val source=new CapturedLogSource(store,"db",Some(verifier))
        val sourceNode=workflow.nodes.head.copy(config=JsObject("subscriptionId"->JsString("req"),"initializedTarget"->JsBoolean(true)))
        val consumer=CaptureConsumerIdentity.workflow(capture,"req",sourceNode.id,execution)
        store.registerConsumer(consumer,"WORKFLOW","req",sourceNode.id,0)
        // Test fixture only: production namespace admission is independently guarded.
        exec(s"UPDATE pekko_capture_gc_state_v1 SET discovery_status='COMPLETE' WHERE capture_id='$capture'")
        val batch=source.readBatch("req",0,10,sourceNode.id,execution,0,Some(consumer)).get.copy(acknowledgementId=Some(consumer))
        val boundary=Await.result(source.discoverBoundary(sourceNode,None,_=>()),10.seconds)
        Await.result(run.initializeBoundary(boundary),10.seconds)
        val first=BatchCheckpoint(batch.sourceNodeId,batch.partitionId,batch.batchSequence,batch.batchId,batch.cursor,1,1)
        def asProof(r:EventSourcedWorkflowActor.PersistedCheckpoint)=cn.xuyinyin.magic.cdc.compat.LegacyJournalReceiptAdapter.fromActor(r)
        val receipt=Await.result(run.checkpointWithReceipt.get(first),10.seconds).get
        Await.result(run.checkpointWithReceipt.get(first),10.seconds) shouldBe None
        intercept[IllegalArgumentException](Await.result(source.acknowledgeCommittedBatchWithReceipt(sourceNode,batch.copy(batchId="wrong"),Some(receipt),_=>()),10.seconds))
        Await.result(source.acknowledgeCommittedBatchWithReceipt(sourceNode,batch,Some(receipt),_=>()),10.seconds)
        intercept[IllegalArgumentException](store.projectWorkflowVerified("f"*64,"req",sourceNode.id,
          batch.cursor.value.parseJson.asJsObject.fields("sequence").asInstanceOf[JsNumber].value.toLongExact,asProof(receipt),verifier))
        val floorCheck=open();try {val q=floorCheck.createStatement();try {val r=q.executeQuery("SELECT journal_floor,observed_floor FROM pekko_capture_consumers_v1")
          try {r.next();r.getLong(1) shouldBe batch.cursor.value.parseJson.asJsObject.fields("sequence").asInstanceOf[JsNumber].value.toLongExact;r.getLong(2) shouldBe r.getLong(1)} finally r.close()}finally q.close()}finally floorCheck.close()
        val lock=open();lock.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);lock.setAutoCommit(false)
        try {
          val forged=cn.xuyinyin.magic.testsupport.ForgedJournalReceipts.workflow(receipt)
          intercept[IllegalArgumentException](asProof(forged))
          val forgedHandle=cn.xuyinyin.magic.testsupport.ForgedJournalReceipts.checkpoint(asProof(receipt))
          intercept[IllegalArgumentException](verifier.verifyOn(lock,forgedHandle,id,execution,first))
          intercept[IllegalArgumentException](verifier.verifyOn(lock,asProof(receipt),id,"wrong",first))
          verifier.verifyOn(lock,asProof(receipt),id,execution,first)
          val second=first.copy(batchSequence=1,batchId=BatchId.sha256(execution,first.sourceNodeId,first.partitionId,1))
          val pending=run.checkpointWithReceipt.get(second)
          intercept[java.util.concurrent.TimeoutException](Await.result(pending,300.millis))
          lock.rollback()
          val next=Await.result(pending,10.seconds).get
          next.sequenceNr should be > receipt.sequenceNr
          intercept[IllegalArgumentException](verifier.verifyOn(lock,asProof(receipt),id,execution,first))
          lock.rollback()
          exec(s"UPDATE event_journal SET deleted=TRUE WHERE persistence_id='workflow-$id' AND sequence_number=${next.sequenceNr}")
          intercept[IllegalArgumentException](verifier.verifyOn(lock,asProof(next),id,execution,second))
          lock.rollback()
          exec(s"UPDATE event_journal SET deleted=FALSE WHERE persistence_id='workflow-$id' AND sequence_number=${next.sequenceNr}")
          verifier.verifyOn(lock,asProof(next),id,execution,second)
          println(s"WORKFLOW_JOURNAL_PROOF schema=$schema firstSeq=${receipt.sequenceNr} secondSeq=${next.sequenceNr} duplicateNoReceipt=true javaForgedRejected=true wrongIdentityRejected=true staleRejected=true deletedRejected=true nextWriterBlocked=true")
        } finally {lock.rollback();lock.close()}
      } finally kit.shutdownTestKit()
    }
  }
}
