package cn.xuyinyin.magic.workflow.scheduling

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.execution.WorkflowExecutionEngine
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds,Span}
import scala.concurrent.{ExecutionContext,Future,Promise}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.execution.ReliableRunContext
import cn.xuyinyin.magic.workflow.engine.ExecutionResult
import spray.json._
import scala.concurrent.duration._

class SnapshotOwnershipActorSpec extends AnyWordSpec with Matchers with Eventually {
  implicit override val patienceConfig:PatienceConfig=PatienceConfig(timeout=Span(10,Seconds))
  "the persistent Actor factory" should {
    "reject cold recovery under another live writer and allow it only after a durable permit" in {
      val url="jdbc:h2:mem:owner-gate-"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"
      val hc=new HikariConfig();hc.setJdbcUrl(url);hc.setUsername("sa");hc.setMaximumPoolSize(4)
      val ds=new HikariDataSource(hc)
      val c=ds.getConnection
      try Vector("db/h2/pekko-persistence-schema.sql","db/h2/pekko-table-scheduling-schema.sql","db/h2/pekko-snapshot-ownership-schema.sql").foreach { path=>
        val in=getClass.getClassLoader.getResourceAsStream(path);val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { q=>val st=c.createStatement();try st.execute(q)finally st.close() }
      }finally c.close()
      val config=ConfigFactory.parseString(s"""pekko.actor.provider=local
        pekko-persistence-jdbc.shared-databases.slick.db.url="$url"
        pekko-persistence-jdbc.shared-databases.slick.db.user="sa"
        pekko-persistence-jdbc.shared-databases.slick.db.password=""
      """).withFallback(ConfigFactory.load("application-test"))
      val kit=ActorTestKit("owner-gate",config)
      implicit val system:org.apache.pekko.actor.typed.ActorSystem[_]=kit.system
      implicit val ec:ExecutionContext=system.executionContext
      val store=new TableSchedulingStore(ds,SchedulingLimits(2,1,4,1,1048576,8),ownershipEnabled=true)
      val ha=store.ownership.get
      val old=SnapshotOwnerIdentity("old","domain",1,"old-start")
      ha.registerOwner(old);ha.registerOwner(SnapshotOwnerIdentity("new","domain",2,"new-start"))
      ha.registerInstance("old","mysql","uuid",1)
      val runtime=new TableSchedulingRuntime(store,"new")
      val engine=new WorkflowExecutionEngine() {
        override def tableScheduling=Some(runtime)
        override def execute(w:Workflow,c:ReliableRunContext,log:String=>Unit):Future[ExecutionResult]=Promise[ExecutionResult]().future
      }
      val configNode=JsObject("host"->JsString("mysql"),"splitRows"->JsNumber(1),"splitParallelism"->JsNumber(1),"maxPageBytes"->JsNumber(1024),"snapshotConsistency"->JsString("quiescent"))
      val source=Node("s","source","mysql.snapshot","s",Position(0,0),configNode)
      val target=source.copy(id="t",`type`="sink",nodeType="mysql.write")
      val workflow=Workflow("w","w","","1","test",Nil,List(source,target),List(Edge("e","s","t")),WorkflowMetadata("now","now",None,None))
      try {
        // The old prefix is legitimately absent after Snapshot retention. The read path must not recreate a writer.
        Vector(false,true).foreach { legacy=>
          import EventSourcedWorkflowActor._
          import org.apache.pekko.actor.typed.scaladsl.adapter._
          import org.apache.pekko.serialization.{SerializationExtension,Serializers}
          val id=if(legacy)"legacy-snapshot" else "retained-snapshot"
          store.register(runtime.definition(workflow.copy(id=id),1L))
          val saved=WorkflowState(workflowJson=Some(workflow.copy(id=id).toJson.compactPrint),revision=7L,status=Completed,
            recentExecutions=Vector(StoredExecutionSummary("old-execution",1,2,true,"completed",1,true)),
            lastAcceptedTriggerBySchedule=Vector(ScheduleWatermark("daily",5000)))
          val serialization=SerializationExtension(system.toClassic)
          val serializer=serialization.findSerializerFor(saved)
          val payload=if(!legacy)serializer.toBinary(saved)else {
            val old=cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec.LegacyWorkflowState(
              saved.workflowJson,saved.revision,saved.status,None,saved.recentExecutions,saved.lastAcceptedTriggerBySchedule,Vector.empty)
            serialization.findSerializerFor(old).toBinary(old)
          }
          val connection=ds.getConnection
          try {
            val q=connection.prepareStatement("INSERT INTO \"snapshot\" (\"persistence_id\",\"sequence_number\",\"created\",\"snapshot_ser_id\",\"snapshot_ser_manifest\",\"snapshot_payload\") VALUES (?,?,?,?,?,?)")
            try {q.setString(1,"workflow-"+id);q.setLong(2,100);q.setLong(3,1);q.setInt(4,serializer.identifier);q.setString(5,Serializers.manifestFor(serializer,saved));q.setBytes(6,payload);q.executeUpdate()}finally q.close()
          }finally connection.close()
          val summary=kit.createTestProbe[WorkflowSummary]()
          kit.spawn(EventSourcedWorkflowActor(id,engine)) ! GetSummary(summary.ref)
          val read=summary.receiveMessage(5.seconds)
          read.revision shouldBe 7L;read.recentExecutions.map(_.executionId) shouldBe Vector("old-execution")
          val reply=kit.createTestProbe[Reply]()
          kit.spawn(EventSourcedWorkflowActor(id,engine)) ! ExecuteScheduled("daily",5000,"daily-5000",reply.ref)
          reply.receiveMessage(5.seconds) shouldBe a[DuplicateExecution]
          store.get(id).get.state shouldBe "registered"
          ha.permit(id) shouldBe None
        }
        val summary=kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        val readiness=kit.spawn(EventSourcedWorkflowActor("__readiness_probe__",engine))
        readiness ! EventSourcedWorkflowActor.GetSummary(summary.ref)
        summary.receiveMessage(5.seconds).workflowId shouldBe "__readiness_probe__"
        store.enqueue(runtime.definition(workflow,1L),"e","r")
        val first=store.claim(1).head;ha.acquire(first,"old");store.bind(first,"old")
        val probe=kit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
        val refused=kit.spawn(EventSourcedWorkflowActor("w",engine))
        refused ! EventSourcedWorkflowActor.ActivateSnapshot(first)
        probe.expectTerminated(refused,5.seconds);probe.expectNoMessage(100.millis)
        store.get("w").get.owner shouldBe Some("old")
        eventually {runtime.attachedCount shouldBe 0}
        ha.beginRevocation("old","op")
        ha.confirmIsolation(SnapshotIsolationReceipt("op",old,"managed-child-jvm",1,"{}")) shouldBe true
        ha.recordObservation("old",SnapshotDatabaseObservation("mysql",true,0,0,0,"{}",1000),100)
        ha.recordObservation("old",SnapshotDatabaseObservation("mysql",true,0,0,0,"{}",1200),100) shouldBe true
        ha.releaseDrained("old",8) shouldBe 1
        val next=store.claim(1).head
        val coldStale=kit.spawn(EventSourcedWorkflowActor("w",engine))
        // This grant was captured before owner binding and therefore has owner=None: ID must still match.
        coldStale ! EventSourcedWorkflowActor.ActivateSnapshot(first)
        probe.expectTerminated(coldStale,5.seconds)
        ha.permit("w").get.owner shouldBe "old"
        store.get("w").get.state shouldBe "held"
        val permitted=kit.spawn(EventSourcedWorkflowActor("w",engine))
        permitted ! EventSourcedWorkflowActor.ActivateSnapshot(next)
        eventually {
          val connection=ds.getConnection
          try {val q=connection.createStatement();try {val rows=q.executeQuery("SELECT COUNT(*) FROM \"event_journal\" WHERE \"persistence_id\"='workflow-w'");try {rows.next();rows.getInt(1) should be > 0}finally rows.close()}finally q.close()}finally connection.close()
        }
        val status=kit.spawn(EventSourcedWorkflowActor("w",engine))
        status ! EventSourcedWorkflowActor.GetStatus(probe.ref)
        probe.receiveMessage(5.seconds).state shouldBe "snapshot_running"
        ha.permit("w").get.owner shouldBe "new"
        ha.permit("w").get.generation shouldBe 2
        store.get("w").get.state shouldBe "recovering"
        kit.stop(permitted)
      } finally {kit.shutdownTestKit();ds.close()}
    }
  }
}
