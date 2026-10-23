package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.scheduling._
import java.nio.file.{Files,Path}
import java.sql.{Connection,Driver,DriverManager,DriverPropertyInfo,SQLException}
import java.util.{Properties,UUID}
import java.lang.reflect.{InvocationTargetException,Proxy}
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import spray.json._
import scala.util.control.NonFatal

private[integration] object SupervisorRecoveryTest {
  import CdcJournalWindowSupport._
  def gate(dir:Path,name:String):Unit={emit(name);awaitCondition("test timing gate "+name,60.seconds)(Files.exists(dir.resolve(name+".release")))}
  def append(p:Path,value:JsObject):Unit=Files.writeString(p,value.compactPrint+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
  def workflow(s:Settings,schema:String,port:Option[Int],role:String):cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow={
    val w=OwnerIsolationProbeSupport.workflow(s,schema)
    w.copy(nodes=w.nodes.map { n=>
      if(port.nonEmpty && ((role=="target" && n.nodeType=="mysql.write") || (role=="source" && n.nodeType=="mysql.snapshot")))
        n.copy(config=JsObject(n.config.fields.updated("port",JsNumber(port.get)))) else n
    })
  }
  def capture(settings:SnapshotSupervisorSettings,schema:String):JsObject={
    val s=Settings.environment();val c=settings.dataSource.getConnection
    try JsObject("data"->OwnerIsolationProbeSupport.capture(s,schema),
      "owners"->query(c,"SELECT * FROM pekko_snapshot_owners ORDER BY owner_id"),
      "permissions"->query(c,"SELECT * FROM pekko_snapshot_writer_permissions ORDER BY workflow_id"),
      "instances"->query(c,"SELECT * FROM pekko_snapshot_owner_instances ORDER BY owner_id,instance_id"),
      "sessions"->query(c,"SELECT * FROM pekko_snapshot_owner_sessions ORDER BY owner_id,instance_id,connection_id"),
      "audit"->query(c,"SELECT * FROM pekko_snapshot_ownership_audit ORDER BY event_sequence"),
      "launches"->query(c,"SELECT * FROM pekko_snapshot_worker_launches ORDER BY boot_id"),
      "controller"->query(c,"SELECT * FROM pekko_snapshot_host_control ORDER BY host_id"))finally c.close()
  }
}

/** Test entry around the production guardian service; gates only process creation and RPC reply timing. */
object SupervisorRecoveryGuardian {
  import CdcJournalWindowSupport._
  import SnapshotGuardianWire._
  def main(args:Array[String]):Unit={
    val Array(config,directory,schema,scenario,gateName)=args;val dir=Path.of(directory)
    val settings=new SnapshotSupervisorSettings(Path.of(config));val lock=settings.lock("guardian")
    try {
      settings.metadata.enroll();val self=ProcessHandle.current()
      settings.metadata.bindGuardianProcess(self.pid(),self.info().startInstant().orElseThrow().toString)
      var count=0
      val key=settings.signingKey
      def builder(t:SnapshotWorkerLaunch):ProcessBuilder={
        val index=count;count+=1
        val original=settings.metadata.current.exists(_.epoch==1)
        if(scenario=="S2_FORK_PENDING" && index==0)SupervisorRecoveryTest.gate(dir,"BEFORE_FORK")
        val mapped=if(!original) {
          if(scenario=="S12_T_BEFORE_J" && index==1)"KILL_T_BEFORE_J"
          else if(scenario=="S11_PERMIT_REPLY_LOST" && index==1)"PERMIT_REPLY_LOST"
          else "NONE"
        } else scenario match {
          case "S5_TARGET_TX"=>"KILL_TARGET_TX"
          case "S6_T_BEFORE_J"=>"KILL_T_BEFORE_J"
          case "S7_J_AFTER_STORE"=>"KILL_J_AFTER_STORE"
          case "S8_COMMITTED_UNKNOWN"=>"COMMIT_APPLIED_REPLY_LOST"
          case "S8_UNCOMMITTED_UNKNOWN"=>"COMMIT_NOT_APPLIED_UNKNOWN"
          case "S10_TARGET_RESIDUAL"=>"KILL_DB_TARGET_WAIT"
          case "S10_SOURCE_RESIDUAL"=>"KILL_DB_SOURCE_WAIT"
          case "S10_JOURNAL_RESIDUAL"=>"KILL_DB_JOURNAL_WAIT"
          case "S10_SNAPSHOT_RESIDUAL"=>"KILL_DB_SNAPSHOT_WAIT"
          case _=>"KILL_READ"
        }
        val mode=if(scenario=="S12_RECOVERY_JOURNAL" && index==1)"recovery-gated" else if(original || mapped=="KILL_T_BEFORE_J")"fresh" else "recover"
        Files.writeString(dir.resolve(t.bootId+".commands"),"")
        val b=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC",
          "-Dpekko.workflow.snapshot-ha.supervisor-config="+Path.of(config).toAbsolutePath,
          "-Dpekko.workflow.snapshot-ha.launch-profile="+t.profileHash,
          "-cp",System.getProperty("java.class.path"),SupervisorRecoveryWorker.getClass.getName.stripSuffix("$"),schema,t.bootId,dir.toString,mapped,mode,gateName,(scenario=="S4_PRE_BOOTSTRAP" && original).toString)
        b.environment().put("RUN_MYSQL_SNAPSHOT_TAKEOVER","1");b.redirectErrorStream(true);b.redirectOutput(dir.resolve(t.bootId+".worker.log").toFile)
        if(Files.exists(dir.resolve("proxy-port"))) {
          val port=Files.readString(dir.resolve("proxy-port")).trim
          b.environment().put("SUPERVISOR_TEST_PROXY_PORT",port)
          b.environment().put("SUPERVISOR_TEST_PROXY_ROLE",if(scenario=="S10_TARGET_RESIDUAL")"target" else if(scenario=="S10_SOURCE_RESIDUAL")"source" else "journal")
        }
        emit("WORKER_FORK","boot"->JsString(t.bootId),"index"->JsNumber(index));b
      }
      var service=Option(new SnapshotGuardianService(settings.metadata,new ManagedJvmIsolation(settings.binding.deploymentId,settings.maxWorkers),builder,key))
      val server=new SnapshotGuardianWire.Server(settings.socket,settings.secret,settings.binding,key,(control,method,a)=>{
        if(Files.exists(dir.resolve("lose-capability"))) {
          Files.delete(dir.resolve("lose-capability"))
          service=None
          service=Some(new SnapshotGuardianService(settings.metadata,new ManagedJvmIsolation(settings.binding.deploymentId,settings.maxWorkers),builder,key))
        }
        service.getOrElse(throw new IllegalStateException("original guardian capability lost")).handle(control,method,a)
      },frame=>{
        SupervisorRecoveryTest.append(dir.resolve("guardian-wire.jsonl"),frame)
        val request=text(frame.fields("request").asJsObject,"payload").parseJson.asJsObject
        if(scenario=="S3_ACK_LOST" && text(request,"method")=="LAUNCH" && count==1)SupervisorRecoveryTest.gate(dir,"LAUNCH_REPLY")
      })
      emit("GUARDIAN_READY","binding"->settings.binding.toJson)
      try server.run()finally server.close()
    }finally {try lock.close()finally settings.close()}
  }
}

/** Independent JVM: all state transitions are performed by production SnapshotSupervisorRuntime/Store. */
object SupervisorRecoverySupervisor {
  import CdcJournalWindowSupport._
  import SnapshotGuardianWire._
  def main(args:Array[String]):Unit={
    val Array(config,directory,schema,scenario,mode)=args;val dir=Path.of(directory)
    val settings=new SnapshotSupervisorSettings(Path.of(config))
    var lock:AutoCloseable=null
    try {
      try lock=settings.lock("supervisor")catch{case _:IllegalStateException=>emit("LOCK_BLOCKED");return}
      val marker=if(mode=="A")scenario match {
        case "S11_TICKET_REPLY_LOST"=>"INSERT INTO pekko_snapshot_worker_launches"
        case "S11_DISPATCH_REPLY_LOST"=>"SET phase='dispatching'"
        case _=>"never-injected"
      } else if(mode=="B")scenario match {
        case "S11_EPOCH_REPLY_LOST"=>"SET controller_boot=?"
        case "S9_FENCE_REPLY_LOST"=>"SET phase='fenced'"
        case "S11_QUOTA_REPLY_LOST"=>"SET phase='ready',grant_id=NULL"
        case "S11_RETIRE_REPLY_LOST"=>"SET phase='retired'"
        case _=>"never-injected"
      }else "never-injected"
      val ds=new SupervisorControlReplyLoss(settings.dataSource,marker,()=>emit("CONTROL_COMMIT_REPLY_DROPPED"),()=>SupervisorRecoveryTest.gate(dir,"CONTROL_READBACK_WAIT"))
      val table=new TableSchedulingStore(ds,settings.limits,ownershipEnabled=true)
      val metadata=new SnapshotSupervisorStore(table,settings.binding,settings.maxWorkers)
      val boot=UUID.randomUUID().toString
      val control=try metadata.claimController(boot)catch{case NonFatal(e)=>metadata.current.filter(_.bootId==boot).getOrElse(throw e)}
      val client=new SnapshotGuardianWire.Client(settings.socket,settings.secret,control,frame=>SupervisorRecoveryTest.append(dir.resolve(mode+"-wire.jsonl"),frame))
      emit("CONTROLLER_BOUND","controller"->control.toJson)
      val observer=new MySqlOwnerDrainObserver(table.ownership.get,_=>{
        val user=if(scenario=="S10_OBSERVER_DENIED" && !Files.exists(dir.resolve("observer.release")))"missing_observer_"+schema.takeRight(12)else Settings.environment().user
        java.sql.DriverManager.getConnection(Settings.environment().url(schema)+"&connectionAttributes=pekko_owner:observer,pekko_role:observer",user,Settings.environment().password)
      })
      var firstProof=true;var firstObservation=true
      val runtime=new SnapshotSupervisorRuntime(metadata,control,client,(owner,instance)=>{
        val observation=observer.observe(owner,instance)
        SupervisorRecoveryTest.append(dir.resolve(mode+"-observations.jsonl"),observation.evidence.parseJson.asJsObject)
        observation
      },settings.profileHash,100,(kind,value)=>{
        emit(kind,"value"->JsString(value));write(dir,mode+"-last-state.json",SupervisorRecoveryTest.capture(settings,schema))
        if(mode=="A" && scenario=="S2_PREPARED" && kind=="LAUNCH_PREPARED")SupervisorRecoveryTest.gate(dir,"PREPARED_GATE")
        if(mode=="B" && scenario=="S9_PROOF_REPLY_LOST" && kind=="PROOF_RECEIVED" && firstProof){firstProof=false;SupervisorRecoveryTest.gate(dir,"PROOF_RECEIVED_GATE")}
        if(mode=="B" && scenario=="S10_OBSERVATION_RESTART" && kind=="DATABASE_OBSERVED" && firstObservation) {
          val c=settings.dataSource.getConnection
          val rows=try query(c,s"SELECT empty_rounds FROM pekko_snapshot_owner_instances WHERE owner_id='$value'")finally c.close()
          if(rows.elements.nonEmpty && rows.elements.forall(_.asJsObject.fields("empty_rounds")==JsString("1"))){firstObservation=false;SupervisorRecoveryTest.gate(dir,"FIRST_OBSERVATION_GATE")}
        }
        if(mode=="B" && scenario=="S15_AFTER_PROOF" && kind=="PROOF_RECORDED" && firstProof){firstProof=false;SupervisorRecoveryTest.gate(dir,"AFTER_PROOF_GATE")}
      })
      val end=System.nanoTime()+240000000000L
      while(System.nanoTime()<end){runtime.tick();Thread.sleep(100)}
      throw new IllegalStateException("test supervisor lifetime exceeded")
    }finally {try {if(lock!=null)lock.close()}finally settings.close()}
  }
}

/** Transparent DataSource fault: only after the real matching SQL transaction commits. */
private[integration] final class SupervisorControlReplyLoss(delegate:javax.sql.DataSource,marker:String,afterCommit:()=>Unit,beforeReadback:()=>Unit) extends javax.sql.DataSource {
  private val armed=new java.util.concurrent.atomic.AtomicBoolean(true)
  private val readback=new java.util.concurrent.atomic.AtomicBoolean(false)
  private def beforeBorrow():Unit=if(readback.compareAndSet(true,false))beforeReadback()
  def getConnection:Connection={beforeBorrow();wrap(delegate.getConnection)}
  def getConnection(user:String,password:String):Connection={beforeBorrow();wrap(delegate.getConnection(user,password))}
  private def wrap(c:Connection):Connection={
    var matched=false
    Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),(_,method,args)=>{
      if(method.getName=="prepareStatement" && args!=null && args.headOption.exists(_.toString.contains(marker)))matched=true
      try {
        val result=method.invoke(c,Option(args).getOrElse(Array.empty[AnyRef]):_*)
        if(method.getName=="commit" && matched && armed.compareAndSet(true,false)){readback.set(true);afterCommit();throw new SQLException("test: committed control reply unavailable","08007")}
        result
      }catch{case e:InvocationTargetException=>throw e.getCause}
    }).asInstanceOf[Connection]
  }
  def getLogWriter:java.io.PrintWriter=delegate.getLogWriter
  def setLogWriter(v:java.io.PrintWriter):Unit=delegate.setLogWriter(v)
  def setLoginTimeout(v:Int):Unit=delegate.setLoginTimeout(v)
  def getLoginTimeout:Int=delegate.getLoginTimeout
  def getParentLogger:java.util.logging.Logger=delegate.getParentLogger
  def unwrap[T](c:Class[T]):T=delegate.unwrap(c)
  def isWrapperFor(c:Class[_]):Boolean=delegate.isWrapperFor(c)
}

/** Installed only in the worker fixture JVM, before bootstrap creates its control pool. */
private[integration] object SupervisorPermitReplyLossDriver {
  def install(dir:Path,boot:String):Unit={
    val delegate=new com.mysql.cj.jdbc.Driver()
    val armed=new java.util.concurrent.atomic.AtomicBoolean(true)
    val lostThread=new java.util.concurrent.atomic.AtomicLong(0L)
    val wrapper=new Driver {
      def acceptsURL(url:String):Boolean=delegate.acceptsURL(url)
      def connect(url:String,p:Properties):Connection={
        val c=delegate.connect(url,p)
        if(c==null || !url.contains("pekko_role:control"))c else {
          var matched=false
          Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),(_,method,args)=>{
            // Stop before the first readback SQL, including its admission-lock query. Holding
            // the lock while waiting here would test an artificial lock leak, not reply loss.
            if(method.getName=="prepareStatement" && args!=null && lostThread.compareAndSet(Thread.currentThread().getId,0L)) {
              CdcJournalWindowSupport.emit("PERMIT_READBACK_SQL_HELD","sql"->JsString(args.head.toString))
              SupervisorRecoveryTest.gate(dir,"PERMIT_READBACK_WAIT")
            }
            if(method.getName=="prepareStatement" && args!=null && args.headOption.exists(_.toString.contains("INSERT INTO pekko_snapshot_writer_permissions")))matched=true
            try {val result=method.invoke(c,Option(args).getOrElse(Array.empty[AnyRef]):_*)
              if(method.getName=="commit" && matched && armed.compareAndSet(true,false)) {
                lostThread.set(Thread.currentThread().getId);CdcJournalWindowSupport.emit("PERMIT_COMMIT_REPLY_DROPPED")
                throw new SQLException("test: real permit commit reply lost","08007")
              }
              result
            }catch{case e:InvocationTargetException=>throw e.getCause}
          }).asInstanceOf[Connection]
        }
      }
      def getPropertyInfo(url:String,p:Properties):Array[DriverPropertyInfo]=delegate.getPropertyInfo(url,p)
      def getMajorVersion:Int=delegate.getMajorVersion
      def getMinorVersion:Int=delegate.getMinorVersion
      def jdbcCompliant():Boolean=delegate.jdbcCompliant()
      def getParentLogger:java.util.logging.Logger=delegate.getParentLogger
    }
    val registered=DriverManager.getDrivers.asScala.toVector
    registered.foreach(DriverManager.deregisterDriver);DriverManager.registerDriver(wrapper);registered.foreach(DriverManager.registerDriver)
  }
}
