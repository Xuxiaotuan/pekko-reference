package cn.xuyinyin.magic.workflow.scheduling

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._
import spray.json._

class TableSchedulingRuntimeSpec extends AnyWordSpec with Matchers with org.scalatest.concurrent.Eventually {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(org.scalatest.time.Span(3,org.scalatest.time.Seconds))
  "durable snapshot ingress" should {
    "defer a new window while the old SQL release reply is held" in {
      implicit val ec:ExecutionContext=ExecutionContext.global
      val hc=new HikariConfig();hc.setJdbcUrl("jdbc:h2:mem:release-race-"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");hc.setUsername("sa");hc.setMaximumPoolSize(2)
      val ds=new HikariDataSource(hc)
      val cn=ds.getConnection
      try {val in=getClass.getClassLoader.getResourceAsStream("db/h2/pekko-table-scheduling-schema.sql");val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(q=>{val st=cn.createStatement();try st.execute(q) finally st.close()})
      }finally cn.close()
      val armed=new java.util.concurrent.atomic.AtomicBoolean(false)
      val committed=new java.util.concurrent.CountDownLatch(1);val reply=new java.util.concurrent.CountDownLatch(1)
      val gated=new HikariDataSource(){
        override def getConnection:java.sql.Connection={
          val actual=ds.getConnection
          java.lang.reflect.Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[java.sql.Connection]),(_,method,args)=>{
            val result=try method.invoke(actual,Option(args).getOrElse(Array.empty[AnyRef]):_*)
              catch{case e:java.lang.reflect.InvocationTargetException=>throw e.getCause}
            if(method.getName=="commit" && armed.compareAndSet(true,false)){
              committed.countDown();require(reply.await(10,java.util.concurrent.TimeUnit.SECONDS),"test release reply gate timeout")
            }
            result
          }).asInstanceOf[java.sql.Connection]
        }
      }
      val store=new TableSchedulingStore(gated,SchedulingLimits(2,2,4,2,1048576,8))
      val runtime=new TableSchedulingRuntime(store,"node")
      val definition=ScheduledTableDefinition("window",1L,"{}",TableResourceDemand("same","same",2,1024))
      try {
        store.enqueue(definition,"execution","request")
        val old=store.claim(1).head
        Await.result(runtime.bind(old),3.seconds) shouldBe true
        armed.set(true)
        val releasing=runtime.release("execution","ready",true)
        committed.await(3,java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        val next=store.claim(1).head
        next.id should not be old.id
        Await.result(runtime.bind(next),3.seconds) shouldBe false
        store.get("window").get.state shouldBe "held"
        reply.countDown();Await.result(releasing,3.seconds)
        Await.result(runtime.bind(next),3.seconds) shouldBe true
        Await.result(runtime.release("execution","completed",true),3.seconds)
        // SQL committed READY, but neither its reply nor readback reached the old Actor.
        store.enqueue(definition.copy(workflowId="uncertain"),"uncertain-execution","uncertain-request")
        val uncertain=store.claim(1).head
        Await.result(runtime.bind(uncertain),3.seconds) shouldBe true
        store.release(uncertain,"ready",true) shouldBe true
        val replacement=store.claim(1).head
        Await.result(runtime.bind(replacement),3.seconds) shouldBe false
        Await.result(runtime.release("uncertain-execution","ready",true,Some(replacement.id)),3.seconds)
        store.get("uncertain").get.grantId shouldBe Some(replacement.id)
        Await.result(runtime.bind(replacement),3.seconds) shouldBe true
        Await.result(runtime.release("uncertain-execution","ready",true,Some(uncertain.id)),3.seconds)
        store.get("uncertain").get.state shouldBe "running"
        runtime.owns(replacement) shouldBe true
        Await.result(runtime.release("uncertain-execution","completed",true,Some(replacement.id)),3.seconds)
      }finally{reply.countDown();ds.close()}
    }

    "queue definitions without activating entities and canonicalize connection aliases" in {
      val kit = ActorTestKit("table-ingress", ConfigFactory.parseString("pekko.actor.provider=local").withFallback(ConfigFactory.load("application-test")))
      implicit val ec: ExecutionContext = kit.system.executionContext
      val c = new HikariConfig();c.setJdbcUrl("jdbc:h2:mem:ingress-"+java.util.UUID.randomUUID()+";MODE=MySQL");c.setUsername("sa");c.setMaximumPoolSize(2)
      val ds=new HikariDataSource(c)
      val connection=ds.getConnection
      try { val in=getClass.getClassLoader.getResourceAsStream("db/h2/pekko-table-scheduling-schema.sql");val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(q=>{val s=connection.createStatement();try s.execute(q) finally s.close()})
      } finally connection.close()
      val store=new TableSchedulingStore(ds,SchedulingLimits(2,2,4,2,1048576,8))
      val runtime=new TableSchedulingRuntime(store,"node-one",Map("localhost:3306"->"mysql-instance","127.0.0.1:3306"->"mysql-instance"))
      val base=WorkflowFixtures.mysqlCdcWorkflow
      val workflow=base.copy(id="managed",nodes=base.nodes.map(n=>n.copy(nodeType=if(n.`type`=="source") "mysql.snapshot" else "mysql.write",
        config=JsObject(n.config.fields ++ Map("host"->JsString(if(n.`type`=="source") "localhost" else "127.0.0.1"),"port"->JsNumber(3306),
          "splitRows"->JsNumber(4),"chunkSize"->JsNumber(2),"maxPageBytes"->JsNumber(1024),"snapshotConsistency"->JsString("quiescent"))))))
      try {
        val first=Await.result(runtime.submit(workflow,"request-1"),5.seconds)
        first.state shouldBe "ready"
        first.definition.demand.connections shouldBe Map("mysql-instance"->4)
        Await.result(runtime.submit(workflow,"request-1"),5.seconds).executionId shouldBe first.executionId
        store.claim(1).size shouldBe 1
        Await.result(runtime.listAfter(None,8),5.seconds).size shouldBe 1
        Await.result(runtime.submit(workflow.copy(id="second"),"request-2"),5.seconds)
        val region=kit.createTestProbe[org.apache.pekko.cluster.sharding.typed.ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
        val dispatcher=kit.spawn(BoundedTableDispatcher(runtime,region.ref,100.millis))
        val firstDelivery=region.receiveMessage(5.seconds).message.asInstanceOf[EventSourcedWorkflowActor.ActivateSnapshot]
        val redelivery=region.receiveMessage(5.seconds).message.asInstanceOf[EventSourcedWorkflowActor.ActivateSnapshot]
        redelivery.grant.id shouldBe firstDelivery.grant.id
        runtime.store.get("second").get.state shouldBe "ready"
        kit.stop(dispatcher)
        val coldRegion=kit.createTestProbe[org.apache.pekko.cluster.sharding.typed.ShardingEnvelope[EventSourcedWorkflowActor.Command]]()
        val supervisor=kit.spawn(cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor.withSharding(coldRegion.ref,Some(runtime)))
        Await.result(runtime.register(workflow.copy(id="third"),1L),5.seconds)
        val replies=kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
        supervisor ! cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor.ExecuteManual("third","manual",replies.ref)
        replies.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        coldRegion.expectNoMessage(200.millis)
        runtime.store.get("third").get.state shouldBe "ready"
        supervisor ! cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor.DefineWorkflow(workflow.copy(id="third",name="out-of-band"),1L,replies.ref)
        replies.expectMessageType[EventSourcedWorkflowActor.DefinitionRejected]
        coldRegion.expectNoMessage(100.millis)
        val a=kit.spawn(org.apache.pekko.actor.typed.scaladsl.Behaviors.ignore[EventSourcedWorkflowActor.Command])
        val b=kit.spawn(org.apache.pekko.actor.typed.scaladsl.Behaviors.ignore[EventSourcedWorkflowActor.Command])
        val other=kit.spawn(org.apache.pekko.actor.typed.scaladsl.Behaviors.ignore[EventSourcedWorkflowActor.Command])
        runtime.attach("a",a)(kit.system) shouldBe true
        runtime.attach("b",b)(kit.system) shouldBe true
        runtime.attach("other",other)(kit.system) shouldBe false
        kit.stop(a)
        eventually {runtime.attachedCount shouldBe 1}
        runtime.attach("other",other)(kit.system) shouldBe true
        kit.stop(b);kit.stop(other)
        eventually {runtime.attachedCount shouldBe 0}
        intercept[IllegalStateException](new TableSchedulingStore(ds,store.limits.copy(maxTables=3)).claim(1))
      } finally {ds.close();kit.shutdownTestKit()}
    }
  }
}
