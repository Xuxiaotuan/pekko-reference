package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.workflow.bootstrap._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import java.sql.{Connection,DriverManager,PreparedStatement}
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.util.concurrent.{CountDownLatch,Executors}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.jdk.CollectionConverters._
import spray.json._

/** Timing gates observe production effects. No manufactured SourceRecords, checkpoints or ACKs. */
object BootstrapMatrixWorker {
  import CdcJournalWindowSupport._
  def main(args:Array[String]):Unit={
    val Array(schema,captureId,batchSize)=args;validateSchema(schema)
    val settings=Settings.environment();val state=settings.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
    val arm=new AtomicReference[String]("");val filterTable=new AtomicReference[String]("");val latch=new AtomicReference[CountDownLatch](new CountDownLatch(1))
    val stopping=new AtomicBoolean(false)
    val runners=new java.util.concurrent.ConcurrentHashMap[String,BootstrapRunner]()
    val journals=new java.util.concurrent.ConcurrentHashMap[String,BootstrapJournalAccess]()
    val futures=new java.util.concurrent.ConcurrentHashMap[String,Future[Unit]]()
    val executor=Executors.newFixedThreadPool(10);val blocking=ExecutionContext.fromExecutorService(executor)
    def event(k:String,v:JsObject):Unit={emit(k,v.fields.toSeq:_*)
      val expected=arm.get()
      if(expected==k && (filterTable.get().isEmpty || v.fields.get("id").contains(JsString(filterTable.get()))) && arm.compareAndSet(expected,"")){
        emit("CUTPOINT",("window"->JsString(k))+:v.fields.toSeq:_*);latch.get().await()
      }
    }
    val proxy=new MySQLCommitResponseProxy(state.host,state.port,event)
    val controlProxy=new MySQLCommitResponseProxy(settings.host,settings.port,event)
    val closeStage=new AtomicReference[String]("BASELINE_TARGET_COMMIT")
    val closeUnknown=new AtomicBoolean(false);val rejectJournal=new AtomicBoolean(false)
    val armReplay=new AtomicReference[String]("")
    val armTarget=new AtomicReference[String]("");val armControl=new AtomicReference[String]("")
    val factory=new CaptureObservedFactory(event)
    val cfg=ConfigFactory.parseMap(Map[String,AnyRef](
      "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url"->state.url(schema),
      "pekko-persistence-jdbc.shared-databases.slick.db.user"->state.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password"->state.password,
      "pekko.loglevel"->"WARNING","pekko.stdout-loglevel"->"WARNING").asJava).withFallback(ConfigFactory.load("application-test"))
    val kit=ActorTestKit("bootstrap-"+captureId,cfg)
    implicit val ec:ExecutionContext=kit.system.executionContext
    implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
    def stateConnection(port:Int=state.port):Connection=DriverManager.getConnection(state.copy(port=port).url(schema)+"&connectTimeout=5000&socketTimeout=30000",state.user,state.password)
    def observed(open:()=>Connection,armed:AtomicReference[String],p:MySQLCommitResponseProxy,stage:String):Connection={val c=open()
      Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
        def invoke(o:Any,m:Method,a:Array[AnyRef]):AnyRef={
          if(m.getName=="commit"){val mode=armed.getAndSet("");if(mode.startsWith("REPEAT_"))armed.set(mode);if(mode.nonEmpty)p.arm(mode.stripPrefix("REPEAT_"),JsObject("stage"->JsString(stage)))}
          if(m.getName=="close" && stage==closeStage.get() && closeUnknown.compareAndSet(true,false)){c.close();throw new java.sql.SQLException("test: physical close confirmation lost")}
          val result=try m.invoke(c,Option(a).getOrElse(Array.empty[AnyRef]):_*)catch{case e:InvocationTargetException=>throw e.getCause}
          if(stage=="SOURCE_MARKER_COMMIT" && m.getName=="prepareStatement" && a!=null &&
            a(0).toString.startsWith("INSERT INTO pekko_capture_control ")){
            val statement=result.asInstanceOf[PreparedStatement]
            val parameters=scala.collection.mutable.Map.empty[Int,String]
            Proxy.newProxyInstance(classOf[PreparedStatement].getClassLoader,Array(classOf[PreparedStatement]),new InvocationHandler {
              def invoke(p:Any,method:Method,args:Array[AnyRef]):AnyRef={
                if(method.getName=="setString")parameters.update(args(0).asInstanceOf[java.lang.Integer].intValue(),args(1).asInstanceOf[String])
                if(method.getName=="executeUpdate")event("SOURCE_CONTROL_ATTEMPT",JsObject(
                  "operationId"->JsString(parameters(1)),"action"->JsString(parameters(2)),
                  "table"->JsString(parameters(3)),"generation"->JsString(parameters(4))))
                try method.invoke(statement,Option(args).getOrElse(Array.empty[AnyRef]):_*)
                catch {case e:InvocationTargetException=>throw e.getCause}
              }
            }).asInstanceOf[PreparedStatement]
          }else result
        }}).asInstanceOf[Connection]}
    val lineage=withConnection(settings,schema)(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
    val store=new MySQLCaptureStore(()=>stateConnection(),captureId,lineage,sys.env.get("CAPTURE_LOG_CAPACITY").fold(128L*1024*1024)(_.toLong))
    val sourceConfig=MySQLCdcSourceConfig(captureId,settings.host,settings.port,schema,"source_rows",settings.user,settings.password,(captureId.hashCode.toLong & 0x7fffffffL)+100000L,batchSize.toInt,100)
    val capture=new SharedMySQLCapture(sourceConfig,MySQLCdcStateConfig(state.url(schema),state.user,state.password,"capture_offsets","capture_history",0),store,factory,event){
      override protected def sourceConnection():Connection=observed(()=>DriverManager.getConnection(settings.copy(port=controlProxy.port).url(schema)+"&socketTimeout=30000",settings.user,settings.password),armControl,controlProxy,"SOURCE_MARKER_COMMIT")
    }
    val admission=new BootstrapAdmission()
    try {
      store.initialize();capture.start();emit("PROCESS_READY","pid"->JsNumber(ProcessHandle.current().pid()))
      Iterator.continually(StdIn.readLine()).takeWhile(l=>l!=null && l!="STOP").foreach {line=>
        val cmd=line.split(" ")
        try cmd(0) match {
          case "BOOT"=>
            val id=cmd(1);val table=cmd(2);val generation=cmd.lift(3).getOrElse("g1");val targetTable=cmd.lift(4).getOrElse("target_"+table)
            val codec=new BootstrapRowCodec(store.schema(table).get.definition)
            val observe:(String,JsObject)=>Unit=(k,v)=>event(k,JsObject(v.fields+("id"->JsString(id))))
            val real=kit.spawn(BootstrapJournal(captureId+"-"+id),"journal-"+id)
            val gate=kit.spawn(org.apache.pekko.actor.typed.scaladsl.Behaviors.receiveMessage[BootstrapJournal.Command] {cmd=>
              cmd match {case BootstrapJournal.Write(expected,n,r) if rejectJournal.compareAndSet(true,false)=>r!BootstrapJournal.Result(false,n.copy(revision=expected))
                case other=>real!other};org.apache.pekko.actor.typed.scaladsl.Behaviors.same
            },"journal-gate-"+id)
            val access=new BootstrapJournalAccess(gate);journals.put(id,access)
            val spool=new BootstrapStore(()=>stateConnection(),id,sys.env.get("BOOTSTRAP_SPOOL_CAPACITY").fold(64L*1024*1024)(_.toLong))
            val target=new BootstrapTarget(()=>observed(()=>stateConnection(proxy.port),armTarget,proxy,"BASELINE_TARGET_COMMIT"),targetTable,codec,id)
            val source=new BootstrapSnapshot(()=>DriverManager.getConnection(settings.url(schema)+"&socketTimeout=30000",settings.user,settings.password),table,codec,10000,8L*1024*1024,observe)
            val node=Node("target","sink","mysql.cdc.apply","real target",Position(0,0),JsObject("host"->JsString(state.host),"port"->JsNumber(state.port),"database"->JsString(schema),"table"->JsString(targetTable),"username"->JsString(state.user),"passwordEnv"->JsString("CDC_WINDOW_MYSQL_PASSWORD")))
            val replaySink=new BootstrapReplaySink(_=>observed(()=>DriverManager.getConnection(state.copy(port=proxy.port).url(schema),state.user,state.password),armReplay,proxy,"REPLAY_TARGET_COMMIT"))
            val r=new BootstrapRunner(id,table,generation,table+"|"+generation+"|"+targetTable+"|"+codec.schemaId,capture,store,spool,target,access,source,replaySink,node,schema,()=>stateConnection(),admission,BootstrapLimits(groupBatch=batchSize.toInt),observe)(blocking)
            runners.put(id,r)
            val f=Future(r.run(()=>stopping.get()))(blocking);futures.put(id,f)
            f.failed.foreach(e=>emit("RUN_FAILED","id"->JsString(id),"error"->JsString(e.toString)))
            emit("BOOT_ACCEPTED","id"->JsString(id))
          case "CANCEL"=>
            val result=Await.result(journals.get(cmd(1)).cancel(),20.seconds)
            emit("CANCEL_RESULT","id"->JsString(cmd(1)),"phase"->JsString(result.phase),
              "revision"->JsNumber(result.revision),"cancelRequested"->JsBoolean(result.cancelRequested))
            emit(if(result.cancelRequested)"CANCEL_ACCEPTED"else"CANCEL_NOT_ACCEPTED")
          case "RETRY_FENCE"=>
            val f=store.fence(cmd(1)).get
            capture.requestFence(f.id,f.subscriptionId,f.action,f.context)
            emit("FENCE_RETRIED","fence"->JsString(f.id))
          case "ARM"=>latch.set(new CountDownLatch(1));filterTable.set(cmd.lift(2).getOrElse(""));arm.set(cmd(1));emit("ARMED")
          case "ARM_NEXT"=>arm.set(cmd(1));filterTable.set("");emit("ARMED")
          case "REJECT_J"=>rejectJournal.set(true);emit("ARMED")
          case "ARM_REPLAY_CLOSE"=>closeStage.set("REPLAY_TARGET_COMMIT");closeUnknown.set(true);emit("ARMED")
          case "ARM_CLOSE"=>closeStage.set("BASELINE_TARGET_COMMIT");closeUnknown.set(true);emit("ARMED")
          case "ARM_REPLAY"=>armReplay.set(cmd(1));emit("ARMED")
          case "ARM_TARGET"=>armTarget.set(cmd(1));emit("ARMED")
          case "ARM_CONTROL"=>armControl.set(cmd(1));emit("ARMED")
          case "ARM_ACK"=>factory.armed.set(cmd(1));emit("ARMED")
          case "RELEASE"=>latch.get().countDown();factory.release();emit("GATE_RELEASED")
          case "RESOLVE_TX"=>proxy.resolveHeldTransaction();controlProxy.resolveHeldTransaction();emit("TX_RESOLVE_REQUESTED")
          case "DROP_RESPONSE"=>proxy.dropHeldResponse();controlProxy.dropHeldResponse();emit("RESPONSE_RELEASED")
          case "STATUS"=>emit("STATUS","actors"->JsObject(runners.asScala.toVector.map{case(id,r)=>id->r.json(r.state)}.toMap),"captureFailure"->capture.failure.map(x=>JsString(x.toString)).getOrElse(JsNull),"admission"->admission.evidence)
          case "STATE"=>
            val actorState=JsObject(runners.asScala.toVector.map{case(id,r)=>id->r.json(r.state)}.toMap)
            val c=stateConnection();val j=try {val raw=query(c,"SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering")
              val ser=SerializationExtension(kit.system.toClassic)
              JsArray(raw.elements.map {v=>val f=v.asJsObject.fields;val e=ser.deserialize(java.util.HexFormat.of().parseHex(f("payload_hex").convertTo[String]),f("event_ser_id").convertTo[String].toInt,f("event_ser_manifest").convertTo[String]).get
                JsObject(f+("decoded"->JsString(e.toString)))})}finally c.close()
            val d=stateConnection();val offsets=try query(d,s"SELECT offset_key,offset_val FROM `${MySQLCdcSourceNode.connectorStateTables(MySQLCdcStateConfig(state.url(schema),state.user,state.password,"capture_offsets","capture_history",0),captureId)._1}`")finally d.close()
            val spools=JsObject(runners.asScala.keys.toVector.map(id=>id->new BootstrapStore(()=>stateConnection(),id,64L*1024*1024).evidence).toMap)
            val failure=capture.failure.map(cn.xuyinyin.magic.workflow.engine.ExecutionFailure.from(_,"test-observation"))
            emit("STATE","admission"->admission.evidence,"L"->store.evidence,"D"->offsets,"J"->j,"actors"->actorState,"spools"->spools,
              "captureFailure"->capture.failure.map(x=>JsString(x.toString)).getOrElse(JsNull),
              "captureFailureCode"->failure.map(x=>JsString(x.code)).getOrElse(JsNull),
              "captureFailureStage"->failure.map(x=>JsString(x.stage)).getOrElse(JsNull),
              "captureReleaseConfirmed"->failure.map(x=>JsBoolean(x.releaseConfirmed)).getOrElse(JsNull))
          case "CRASH"=>System.out.flush();Runtime.getRuntime.halt(86)
          case _=>throw new IllegalArgumentException("unknown command")
        }catch{case e:Throwable=>emit("COMMAND_FAILED","command"->JsString(cmd(0)),"error"->JsString(e.toString))}
      }
    }catch {case e:Throwable=>emit("START_FAILED","error"->JsString(e.toString));throw e}
    finally {stopping.set(true);latch.get().countDown();factory.release();futures.values().asScala.foreach(f=>try Await.ready(f,35.seconds)catch{case _:Throwable=>()});capture.close();proxy.close();controlProxy.close();kit.shutdownTestKit();blocking.shutdown()}
  }
}
