package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.connectors.mysql.sink.MySQLCdcApplySinkNode
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.execution.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.tags.ExternalIntegration
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files,Path}
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue,CountDownLatch,TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicInteger}
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

class ManagedCaptureReleaseSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "managed real capture termination" should {
    for(mode<-Vector("quiet","busy","stale release attempt","observer disconnected","callback failure with application","synchronous SDK close held","SDK runner finally held","failed SDK runner finally held")) {
      s"confirm physical release after $mode without touching another account" taggedAs ExternalIntegration in {
        val callbackFailure=mode=="callback failure with application";val disconnected=mode=="observer disconnected"
        val stale=mode=="stale release attempt";val busy=mode=="busy" || stale || callbackFailure || disconnected;val held=mode=="synchronous SDK close held"
        val runnerFailed=mode=="failed SDK runner finally held";val runnerHeld=mode=="SDK runner finally held" || runnerFailed
        if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive dual-MySQL slot required")
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        require(!Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port))
        def uuid(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        val lineage=uuid(source);val stateLineage=uuid(state)
        require(lineage==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateLineage==sys.env("CAPTURE_EXPECT_STATE_UUID"))
        val suffix=UUID.randomUUID().toString.replace("-","").take(12)
        val schema="pekko_test_cdc_window_"+suffix;val id="release_"+suffix
        val sourceUser="cs_"+suffix;val stateUser="ct_"+suffix;val secret=UUID.randomUUID().toString
        val out=Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve(id);Files.createDirectories(out)
        initialize(source,schema);initialize(state,schema)
        def sql(s:Settings,statement:String)=withConnection(s,schema)(executeSql(_,statement))
        sql(source,"CREATE TABLE a LIKE source_rows");sql(source,"INSERT INTO a SELECT * FROM source_rows WHERE id<=4")
        sql(state,"CREATE TABLE target_a LIKE source_rows");sql(state,"INSERT INTO target_a SELECT * FROM source_rows WHERE id<=4")
        withConnection(state,schema){c=>
          val in=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          val ddl=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
        }
        sql(source,s"CREATE USER '$sourceUser'@'127.0.0.1' IDENTIFIED BY '$secret'")
        sql(source,s"GRANT REPLICATION SLAVE,REPLICATION CLIENT,SHOW DATABASES ON *.* TO '$sourceUser'@'127.0.0.1'")
        sql(source,s"GRANT ALL PRIVILEGES ON `$schema`.* TO '$sourceUser'@'127.0.0.1'")
        sql(state,s"CREATE USER '$stateUser'@'127.0.0.1' IDENTIFIED BY '$secret'")
        sql(state,s"GRANT ALL PRIVILEGES ON `$schema`.* TO '$stateUser'@'127.0.0.1'")
        val config=MySQLCdcStateConfig(state.url(schema),stateUser,secret,"capture_offsets","capture_history",0)
        val store=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),stateUser,secret),id,lineage,64L*1024*1024)
        store.initialize()
        val observerProxy=if(disconnected)Some(new MySQLCommitResponseProxy(source.host,source.port,(_,_)=>()))else None
        val realObserver=CaptureResourceObserver.exclusiveAccounts(
          ()=>DriverManager.getConnection(source.copy(port=observerProxy.fold(source.port)(_.port)).url(schema)+"&connectTimeout=5000&socketTimeout=5000",source.user,source.password),
          ()=>DriverManager.getConnection(state.url(schema),state.user,state.password),sourceUser,stateUser,lineage,stateLineage)
        val staleObservation=new AtomicBoolean(stale)
        val observer=new CaptureResourceObserver {
          def beforeStart(attempt:String,ownerId:Long):Unit=realObserver.beforeStart(attempt,ownerId)
          def sample(attempt:String,ownerId:Long,includeOwner:Boolean):CaptureServerRelease={
            val observed=realObserver.sample(attempt,ownerId,includeOwner)
            if(staleObservation.get())observed.copy(runtimeAttemptId="previous-test-attempt")else observed
          }
          def close():Unit=realObserver.close()
        }
        val events=new ConcurrentLinkedQueue[JsObject]()
        val failNext=new AtomicBoolean(false);val failedCallback=new java.util.concurrent.atomic.AtomicReference[JsValue]()
        val closeEntered=new CountDownLatch(1);val releaseClose=new CountDownLatch(1);val closeCalls=new AtomicInteger()
        val wrappedFactory=new DebeziumEngineFactory {
          def create(properties:java.util.Properties):DebeziumEngineAccess={
            val delegate=DebeziumEngineFactory.real.create(properties)
            new DebeziumEngineAccess {
              def start(consumer:DebeziumBatchConsumer)=delegate.start(consumer)
              override def localTerminationConfirmed:Boolean=delegate.localTerminationConfirmed
              def close():Unit={closeCalls.incrementAndGet();closeEntered.countDown();if(held)releaseClose.await();delegate.close()}
            }
          }
        }
        val runnerFactory=new RunnerFinallyGateFactory
        val capture=new SharedMySQLCapture(MySQLCdcSourceConfig(id,source.host,source.port,schema,"a",sourceUser,secret,
          (id.hashCode.toLong & 0x7fffffffL)+100000,128,100),config,store,if(runnerHeld)runnerFactory else wrappedFactory,
          observe=(k,v)=>{events.add(JsObject(v.fields+("kind"->JsString(k))))
            if(k=="RAW_CALLBACK" && failNext.compareAndSet(true,false)){
              failedCallback.set(v.fields("id"));throw new IllegalStateException("b03-injected-callback-failure")
            }},resourceObserver=Some(observer),
          closeBudget=if(held || runnerHeld)CaptureCloseBudget(100,100)else CaptureCloseBudget())
        val other=DriverManager.getConnection(source.url(schema),source.user,source.password)
        var kit:ActorTestKit=null
        var beforeFault:JsObject=null
        def rows(s:Settings,table:String)=withConnection(s,schema)(query(_,s"SELECT * FROM $table ORDER BY id"))
        def applicationState:JsObject=JsObject("source"->rows(source,"a"),"target"->rows(state,"target_a"),
          "T"->withConnection(state,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
          "J"->withConnection(state,schema)(query(_,"SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering")))
        try {
          capture.start();awaitCondition("production ready",90.seconds)(capture.startupEvidence.phase=="ADMISSION_READY")
          if(mode=="quiet" || busy){
            capture.subscribe("a","a","g1");awaitCondition("B bound")(store.subscription("a").exists(_.b.nonEmpty))
            val actorConfig=ConfigFactory.parseMap(Map[String,AnyRef](
              "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
              "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
              "pekko-persistence-jdbc.shared-databases.slick.db.url"->state.url(schema),
              "pekko-persistence-jdbc.shared-databases.slick.db.user"->state.user,
              "pekko-persistence-jdbc.shared-databases.slick.db.password"->state.password,
              "pekko.loglevel"->"WARNING","pekko.stdout-loglevel"->"WARNING").asJava).withFallback(ConfigFactory.load("application-test"))
            kit=ActorTestKit("release-"+id,actorConfig)
            implicit val ec:ExecutionContext=kit.system.executionContext
            val registry=NodeRegistry.builtins().withSource(new CapturedLogSource(store,schema)).withSink(new MySQLCdcApplySinkNode)
            val engine=new WorkflowExecutionEngine(nodeRegistry=registry)(kit.system,ec)
            val src=Node("source","source","mysql.cdc.log","log",Position(0,0),JsObject("subscriptionId"->JsString("a"),"initializedTarget"->JsBoolean(true),"batchSize"->JsNumber(128)))
            val dst=Node("target","sink","mysql.cdc.apply","target",Position(1,0),JsObject("host"->JsString(state.host),"port"->JsNumber(state.port),"database"->JsString(schema),"table"->JsString("target_a"),"username"->JsString(state.user),"passwordEnv"->JsString("CDC_WINDOW_MYSQL_PASSWORD")))
            val workflow=Workflow("release-"+id,"release","copied baseline","1","test",Nil,List(src,dst),List(Edge("edge","source","target")),WorkflowMetadata("test","test"))
            val actor=kit.spawn(EventSourcedWorkflowActor(workflow.id,engine)(ec));val reply=kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
            actor ! EventSourcedWorkflowActor.DefineWorkflow(workflow,0,reply.ref)
            reply.receiveMessage(20.seconds) shouldBe a[EventSourcedWorkflowActor.Defined]
            actor ! EventSourcedWorkflowActor.ExecuteManual("start",reply.ref)
            reply.receiveMessage(20.seconds) shouldBe a[EventSourcedWorkflowActor.ExecutionAccepted]
            if(busy){
            sql(source,"UPDATE a SET label='release-active',revision=revision+1 WHERE id=1")
            awaitCondition("durable captured group")(store.read("a",store.subscription("a").get.bSequence.get,16).nonEmpty)
              awaitCondition("Actor and Sink full fields",30.seconds)(rows(source,"a")==rows(state,"target_a"))
              val summary=kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
              awaitCondition("Actor Journal checkpoint",30.seconds){actor ! EventSourcedWorkflowActor.GetSummary(summary.ref);summary.receiveMessage(5.seconds).currentExecution.exists(_.checkpoints.nonEmpty)}
            }
            write(out,"application-before-close.json",applicationState)
            kit.shutdownTestKit();kit=null
            if(callbackFailure){
              beforeFault=applicationState
              failNext.set(true)
              sql(source,"UPDATE a SET label='b04-callback-not-applied',revision=revision+1 WHERE id=1")
              awaitCondition("real callback exception",60.seconds)(capture.failure.nonEmpty)
              val failed=Option(failedCallback.get()).getOrElse(fail("failed callback identity missing"))
              val acked=events.iterator().asScala.toVector.filter(_.fields.get("kind").contains(JsString("RAW_ACK_RETURNED"))).map(_.fields("id"))
              acked should not contain failed
              val after=applicationState
              after.fields("T") shouldBe beforeFault.fields("T");after.fields("J") shouldBe beforeFault.fields("J")
              after.fields("source") should not be after.fields("target")
              write(out,"callback-failure-application.json",JsObject("before"->beforeFault,"after"->after,"failedCallback"->failed,
                "acknowledged"->JsArray(acked),"error"->JsString(capture.failure.get.toString),"events"->JsArray(events.iterator().asScala.toVector)))
            }
          }
          if(runnerFailed){
            failNext.set(true)
            sql(source,"UPDATE a SET label='runner-failure',revision=revision+1 WHERE id=1")
            runnerFactory.entered.await(30,TimeUnit.SECONDS) shouldBe true
            awaitCondition("actual failed completion",30.seconds)(runnerFactory.completion!=null && runnerFactory.completion.isCompleted)
            runnerFactory.completion.value.get.isFailure shouldBe true
          }
          if(disconnected)observerProxy.foreach(_.close())
          val offset=MySQLCdcSourceNode.connectorStateTables(config,id)._1
          write(out,"before.json",JsObject("L"->store.evidence,
            "D"->withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM `$offset`")),
            "source"->withConnection(source,schema)(query(_,"SELECT * FROM a ORDER BY id"))))
          val began=System.nanoTime();val first=capture.requestClose();capture.requestClose() shouldBe first
          if(held)closeEntered.await(5,TimeUnit.SECONDS) shouldBe true
          val response=Await.result(first,12.seconds)
          val elapsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)
          if(runnerHeld)runnerFactory.entered.await(5,TimeUnit.SECONDS) shouldBe true
          if(held || runnerHeld){
            response.localTermination shouldBe "UNKNOWN";response.reusable shouldBe false
            response.ownerReleased shouldBe false
            if(held)closeCalls.get() shouldBe 1
            if(runnerHeld){
              runnerFactory.completion.isCompleted shouldBe true
              runnerFactory.completion.value.get.isFailure shouldBe runnerFailed
              val afterCompletion=Await.result(capture.refreshRelease(),2.seconds)
              afterCompletion.localTermination shouldBe "UNKNOWN";afterCompletion.reusable shouldBe false
              afterCompletion.operationId shouldBe response.operationId
              write(out,"completion-before-runner-exit.json",release(afterCompletion))
            }
            elapsedMs should be < 2000L
          }else {
            response.localTermination shouldBe "CONFIRMED"
            if(stale){response.reasonCode shouldBe Some("RELEASE_ATTEMPT_MISMATCH");response.reusable shouldBe false;response.ownerReleased shouldBe false}
            if(disconnected){response.serverRelease shouldBe "UNKNOWN";response.reusable shouldBe false;response.ownerReleased shouldBe false}
          }
          write(out,"initial-response.json",JsObject(release(response).fields++Map("elapsedMs"->JsNumber(elapsedMs),
            "closeCalls"->JsNumber(closeCalls.get()),"testInjectedCloseGate"->JsBoolean(held),
            "testInjectedRunnerGate"->JsBoolean(runnerHeld),"realCompletionSucceeded"->JsBoolean(runnerHeld && runnerFactory.completion.value.exists(_.isSuccess)),
            "realCompletionFailed"->JsBoolean(runnerHeld && runnerFactory.completion.value.exists(_.isFailure)))))
          staleObservation.set(false);releaseClose.countDown();runnerFactory.release.countDown()
          if(disconnected){
            val after=Await.result(capture.refreshRelease(),12.seconds)
            after.serverRelease shouldBe "UNKNOWN";after.reusable shouldBe false;after.ownerReleased shouldBe false
            val application=applicationState;application.fields("source") shouldBe application.fields("target")
            write(out,"observer-disconnected-result.json",JsObject("initial"->release(response),"rechecked"->release(after),
              "application"->application,"otherSessionAlive"->JsBoolean(query(other,"SELECT 1 AS alive").elements.nonEmpty),
              "scope"->JsString("real observer transport severed; no restored observation or automatic restart")))
          }else {
          val samples=Vector.newBuilder[JsValue]
          awaitCondition("same close operation physically released",180.seconds){
            val e=Await.result(capture.refreshRelease(),12.seconds)
            e.operationId shouldBe response.operationId;samples+=release(e);e.reusable
          }
          val finalEvidence=capture.releaseEvidence
          finalEvidence.reusable shouldBe true
          val stillAlive=query(other,"SELECT 1 AS alive").elements.head.asJsObject.fields("alive")
          stillAlive shouldBe JsString("1")
          val application=applicationState
          if(mode=="quiet" || busy){
            if(!callbackFailure)application.fields("source") shouldBe application.fields("target")
            else {application.fields("T") shouldBe beforeFault.fields("T");application.fields("J") shouldBe beforeFault.fields("J")}
            application.fields("J").asInstanceOf[JsArray].elements should not be empty
            if(busy)application.fields("T").asInstanceOf[JsArray].elements should not be empty
          }
          write(out,"result.json",JsObject("status"->JsString("REUSABLE"),"release"->release(finalEvidence),
            "samples"->JsArray(samples.result()),"otherAccountStillAlive"->JsBoolean(true),
            "L"->store.evidence,"events"->JsArray(events.iterator().asScala.toVector),
            "application"->application,"scope"->JsString(if(callbackFailure)"copied baseline; applied update followed by injected callback failure; no automatic recovery" else if(mode=="quiet" || busy)"copied baseline; real Actor/Journal/Sink, busy includes one applied update" else "Reader termination only; no Actor/Sink execution"),
            "testInjectedCloseGate"->JsBoolean(held),"testInjectedStaleAttempt"->JsBoolean(stale),"closeCalls"->JsNumber(closeCalls.get())))
          }
        }finally {if(kit!=null)kit.shutdownTestKit();releaseClose.countDown();runnerFactory.release.countDown();other.close();observerProxy.foreach(_.close());capture.close()}
      }
    }
  }
  private def release(e:CaptureReleaseEvidence):JsObject=JsObject("attempt"->JsString(e.runtimeAttemptId),"operation"->JsString(e.operationId),
    "local"->JsString(e.localTermination),"server"->JsString(e.serverRelease),"ownerReleased"->JsBoolean(e.ownerReleased),
    "reusable"->JsBoolean(e.reusable),"reason"->e.reasonCode.map(JsString(_)).getOrElse(JsNull),"observedAt"->JsNumber(e.observedAt))
}
