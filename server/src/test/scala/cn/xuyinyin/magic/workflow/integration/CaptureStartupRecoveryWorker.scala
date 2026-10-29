package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.connectors.mysql.sink.MySQLCdcApplySinkNode
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.execution.{WorkflowExecutionEngine,ReliableRunContext}
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.events.WorkflowEvents.ExecutionCheckpointAdvanced
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import java.sql.{Connection,DriverManager}
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext,Future}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.jdk.CollectionConverters._
import spray.json._

/** Independent JVM; production capture/Source/Sink/Actor, test-only transparent timing facilities. */
object CaptureStartupRecoveryWorker {
  import CdcJournalWindowSupport._
  def main(args:Array[String]):Unit={
    val Array(schema,captureId,batchSize,startupGate)=args
    validateSchema(schema)
    val settings=Settings.environment();val state=settings.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
    val gate=new CountDownLatch(1)
    val barrierGate=new java.util.concurrent.atomic.AtomicBoolean(false)
    val callbackGate=new CountDownLatch(1)
    val holdSchemaCallback=new java.util.concurrent.atomic.AtomicBoolean(startupGate=="SCHEMA_AND_CALLBACK")
    var gateStore:MySQLCaptureStore=null
    val event:(String,JsObject)=>Unit=(k,v)=>{emit(k,v.fields.toSeq:_*)
      if(k=="B_BOUND" && barrierGate.compareAndSet(true,false)){emit("CUTPOINT","window"->JsString("B_COMMITTED_ACK_PENDING"));gate.await()}
      if(k=="RAW_ACK_RETURNED" && holdSchemaCallback.get() && Vector("a","b","c","d","e","f","g").forall(t=>gateStore.schema(t).nonEmpty) && holdSchemaCallback.compareAndSet(true,false)){
        emit("SDK_CALLBACK_HELD","stage"->JsString("after_original_ACK_return_before_callback_return"));callbackGate.await()
      }}
    val sdkProxy=new CaptureStartupRecoverySqlProxy(settings.host,settings.port,event,if(startupGate=="BEFORE_POINT")startupGate else "NONE")
    val proxy=new MySQLCommitResponseProxy(settings.host,state.port,event)
    val controlProxy=new MySQLCommitResponseProxy(settings.host,settings.port,event)
    val armL=new AtomicReference[String]("");val armControl=new AtomicReference[String]("")
    val factory=new CaptureStartupRecoveryFactory(event,Set("SCHEMA_EMITTED","SCHEMA_AND_CALLBACK").contains(startupGate))
    val applicationArm=new AtomicReference[String]("")
    def takeApplication(mode:String):Boolean={val current=applicationArm.get();current==mode && applicationArm.compareAndSet(current,"")}
    val cfg=ConfigFactory.parseMap(Map[String,AnyRef](
      "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url"->state.url(schema),
      "pekko-persistence-jdbc.shared-databases.slick.db.user"->state.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password"->state.password,
      "pekko.workflow.event-sourcing.snapshot-every"->Int.box(100000),
      "pekko.loglevel"->"WARNING","pekko.stdout-loglevel"->"WARNING").asJava).withFallback(ConfigFactory.load("application-test"))
    val kit=ActorTestKit("capture-"+captureId,cfg)
    implicit val ec:ExecutionContext=kit.system.executionContext
    def stateConnection(port:Int=state.port):Connection=DriverManager.getConnection(state.copy(port=port).url(schema),state.user,state.password)
    def observedConnection():Connection={
      val c=stateConnection(proxy.port);var barrier=false
      Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
        def invoke(o:Any,m:Method,a:Array[AnyRef]):AnyRef={
          if(m.getName=="prepareStatement" && a!=null && a(0).toString.startsWith("UPDATE pekko_capture_subscriptions SET status='CAPTURE_READY'"))barrier=true
          if(m.getName=="commit" && barrier){val mode=armL.getAndSet("");if(mode.nonEmpty)proxy.arm(mode,JsObject("stage"->JsString("B_L_COMMIT")))}
          try m.invoke(c,Option(a).getOrElse(Array.empty[AnyRef]):_*)catch {case e:InvocationTargetException=>throw e.getCause}
        }
      }).asInstanceOf[Connection]
    }
    val lineage=withConnection(settings,schema)(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
    val store=new MySQLCaptureStore(()=>observedConnection(),captureId,lineage,sys.env.get("CAPTURE_LOG_CAPACITY").fold(64L*1024*1024)(_.toLong))
    gateStore=store
    val source=MySQLCdcSourceConfig(captureId,settings.host,settings.port,schema,"source_rows",settings.user,settings.password,761025L,batchSize.toInt,100)
    val sdkFactory=new DebeziumEngineFactory {
      def create(props:java.util.Properties):DebeziumEngineAccess={
        if(startupGate!="ORIGINAL"){props.setProperty("database.port",sdkProxy.port.toString);props.setProperty("database.ssl.mode","disabled")}
        factory.create(props)
      }
    }
    val capture=new SharedMySQLCapture(source,MySQLCdcStateConfig(state.url(schema),state.user,state.password,"capture_offsets","capture_history",0),store,sdkFactory,event) {
      override protected def sourceConnection():Connection={
        val c=DriverManager.getConnection(settings.copy(port=controlProxy.port).url(schema),settings.user,settings.password)
        Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
          def invoke(o:Any,m:Method,a:Array[AnyRef]):AnyRef={
            if(m.getName=="commit"){val mode=armControl.getAndSet("");if(mode.nonEmpty)controlProxy.arm(mode,JsObject("stage"->JsString("SOURCE_MARKER_COMMIT")))}
            try {val result=m.invoke(c,Option(a).getOrElse(Array.empty[AnyRef]):_*)
              if(m.getName=="commit")emit("SOURCE_CONTROL_COMMIT_RETURNED")
              result
            }catch{case e:InvocationTargetException=>throw e.getCause}
          }
        }).asInstanceOf[Connection]
      }
    }
    val log=new CapturedLogSource(store,schema)
    val sink=new MySQLCdcApplySinkNode {
      override def commitBatch(n:Node,w:String,e:String,b:SourceBatch,rows:Vector[String],onLog:String=>Unit)(implicit blockingEc:ExecutionContext):Future[BatchCommitResult]=
        super.commitBatch(n,w,e,b,rows,onLog)(blockingEc).flatMap {r=>
          emit("T_COMMITTED","batch"->batchJson(b))
          if(takeApplication("T_J")){emit("CUTPOINT","window"->JsString("T_COMMITTED_J_PENDING"));Future {gate.await();r}(ExecutionContext.global)}
          else Future.successful(r)
        }(ec)
    }
    val registry=NodeRegistry.builtins().withSource(log).withSink(sink)
    val engine=new WorkflowExecutionEngine(nodeRegistry=registry)(kit.system,ec) {
      override def execute(w:Workflow,context:ReliableRunContext,onLog:String=>Unit):Future[cn.xuyinyin.magic.workflow.engine.ExecutionResult]={
        super.execute(w,context.copy(checkpointCommitted=cp=>{
          if(takeApplication("REJECT_J"))context.checkpointCommitted(cp.copy(batchSequence=cp.batchSequence+1))
          else context.checkpointCommitted(cp).map {r=>emit("J_COMMITTED","checkpoint"->checkpointJson(cp));r}
        }),onLog)
      }
    }
    val actors=scala.collection.mutable.Map.empty[String,org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command]]
    def workflow(id:String,table:String):Workflow={
      val n=Node("source","source","mysql.cdc.log","captured log",Position(0,0),JsObject("subscriptionId"->JsString(id),"initializedTarget"->JsBoolean(true),"batchSize"->JsNumber(batchSize.toInt)))
      val t=Node("target","sink","mysql.cdc.apply","real target",Position(1,0),JsObject("host"->JsString(state.host),"port"->JsNumber(state.port),"database"->JsString(schema),"table"->JsString("target_"+table),"username"->JsString(state.user),"passwordEnv"->JsString("CDC_WINDOW_MYSQL_PASSWORD")))
      Workflow("shared-"+captureId+"-"+id,"capture apply","fixture known baseline; not online initialization","1","test",Nil,List(n,t),List(Edge("edge",n.id,t.id)),WorkflowMetadata("test","test"))
    }
    def actorState(id:String):JsObject={
      val p=kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
      actors(id)!EventSourcedWorkflowActor.GetSummary(p.ref)
      val s=p.receiveMessage(20.seconds)
      JsObject("status"->JsString(s.status.value),"executionId"->s.currentExecution.map(x=>JsString(x.executionId)).getOrElse(JsNull),
        "recovery"->s.currentExecution.flatMap(_.recovery).map(r=>JsObject("attemptsStarted"->JsNumber(r.attemptsStarted),"phase"->JsString(r.phase),"reasonCode"->JsString(r.reasonCode))).getOrElse(JsNull),
        "checkpoints"->JsArray(s.currentExecution.toVector.flatMap(_.checkpoints).map(checkpointJson)))
    }
    try {
      store.initialize()
      capture.start()
      emit("PROCESS_READY","pid"->JsNumber(ProcessHandle.current().pid()))
      Iterator.continually(StdIn.readLine()).takeWhile(line=>line!=null && line!="STOP").foreach {line=>
        val cmd=line.split(" ")
        try cmd(0) match {
          case "CAPTURE_ADMISSION"=>
            val e=capture.startupEvidence
            emit("CAPTURE_ADMISSION","runtimeAttemptId"->JsString(e.runtimeAttemptId),"phase"->JsString(e.phase),
              "reasonCode"->e.reasonCode.map(JsString(_)).getOrElse(JsNull),"loadedCheckpoint"->JsString(e.loadedCheckpoint),
              "snapshot"->JsString(e.snapshot),"currentCheckpoint"->JsString(e.currentCheckpoint),
              "ackFloor"->JsNumber(e.ackFloor),"durableAck"->JsNumber(e.durableAck),"confirmedAck"->JsNumber(e.confirmedAck))
          case "SUBSCRIBE"=>Future {capture.subscribe(cmd(1),cmd(2),cmd(3));emit("SUBSCRIBE_ACCEPTED","id"->JsString(cmd(1)))}.failed.foreach(e=>emit("COMMAND_FAILED","command"->JsString("SUBSCRIBE"),"error"->JsString(e.toString)))
          case "PREPARE"=>store.register(cmd(1),cmd(2),cmd(3),store.schema(cmd(2)).get.id);emit("PREPARED")
          case "CANCEL_PENDING"=>capture.cancelPending(cmd(1));emit("PENDING_CANCELED")
          case "UNSUBSCRIBE"=>capture.unsubscribe(cmd(1));emit("UNSUBSCRIBE_ACCEPTED")
          case "APPLY"|"RECOVER_APPLY"=>
            val w=workflow(cmd(1),cmd(2));val a=kit.spawn(EventSourcedWorkflowActor(w.id,engine)(ec),"apply-"+cmd(1));actors(cmd(1))=a
            if(cmd(0)=="APPLY"){
              val p=kit.createTestProbe[EventSourcedWorkflowActor.Reply]()
              a!EventSourcedWorkflowActor.DefineWorkflow(w,0,p.ref);require(p.receiveMessage(20.seconds).isInstanceOf[EventSourcedWorkflowActor.Defined])
              a!EventSourcedWorkflowActor.ExecuteManual("start",p.ref);require(p.receiveMessage(20.seconds).isInstanceOf[EventSourcedWorkflowActor.ExecutionAccepted])
            }
            emit("APPLY_READY","id"->JsString(cmd(1)))
          case "ARM_L"=>armL.set(cmd(1));emit("ARMED")
          case "ARM_CONTROL"=>armControl.set(cmd(1));emit("ARMED")
          case "ARM_ACK"=>if(cmd(1)=="B_BOUND")barrierGate.set(true)else factory.armed.set(cmd(1));emit("ARMED")
          case "ARM_APP"=>applicationArm.set(cmd(1));emit("ARMED")
          case "RESOLVE_TX"=>proxy.resolveHeldTransaction();controlProxy.resolveHeldTransaction();emit("TX_RESOLVE_REQUESTED")
          case "DROP_RESPONSE"=>proxy.dropHeldResponse();controlProxy.dropHeldResponse();emit("RESPONSE_RELEASED")
          case "RELEASE_CALLBACK"=>callbackGate.countDown();emit("CALLBACK_GATE_RELEASED")
          case "RELEASE_SDK"=>factory.release();sdkProxy.release();emit("SDK_GATE_RELEASED")
          case "RELEASE"=>gate.countDown();factory.release();sdkProxy.release();emit("GATE_RELEASED")
          case "STATE"=>
            // Observe Actor first; the subsequent Journal export must contain that confirmed prefix.
            val actorSnapshots=JsObject(actors.keys.toVector.map(id=>id->actorState(id)).toMap)
            val c=stateConnection()
            val j=try {
              val raw=query(c,"SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering")
              val serializer=SerializationExtension(kit.system.toClassic)
              JsArray(raw.elements.map {v=>val f=v.asJsObject.fields
                val bytes=java.util.HexFormat.of().parseHex(f("payload_hex").convertTo[String])
                val e=serializer.deserialize(bytes,f("event_ser_id").convertTo[String].toInt,f("event_ser_manifest").convertTo[String]).get
                JsObject(f++Map("decodedType"->JsString(e.getClass.getSimpleName),"checkpoint"->(e match {case x:ExecutionCheckpointAdvanced=>checkpointJson(x.checkpoint);case _=>JsNull})))
              })
            }finally c.close()
            val d=stateConnection()
            val offsets=try query(d,s"SELECT offset_key,offset_val FROM `${MySQLCdcSourceNode.connectorStateTables(MySQLCdcStateConfig(state.url(schema),state.user,state.password,"capture_offsets","capture_history",0),captureId)._1}`")finally d.close()
            emit("STATE","L"->store.evidence,"D"->offsets,"J"->j,
              "actors"->actorSnapshots,"captureFailure"->capture.failure.map(x=>JsString(x.toString)).getOrElse(JsNull))
          case "CRASH"=>System.out.flush();Runtime.getRuntime.halt(86)
          case _=>throw new IllegalArgumentException("unknown command")
        }catch {case e:Throwable=>emit("COMMAND_FAILED","command"->JsString(cmd(0)),"error"->JsString(e.toString))}
      }
    }catch {case e:Throwable=>emit("START_FAILED","error"->JsString(e.toString));throw e}
    finally {factory.release();gate.countDown();callbackGate.countDown();capture.close();sdkProxy.close();proxy.close();controlProxy.close();kit.shutdownTestKit()}
  }
}
