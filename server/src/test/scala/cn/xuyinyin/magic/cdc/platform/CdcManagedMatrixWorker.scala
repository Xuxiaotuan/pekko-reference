package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.guardian.SnapshotOwnerBootstrap

import cn.xuyinyin.magic.cdc.guardian.SnapshotSupervisorSettings

import cn.xuyinyin.magic.api.http.routes.CdcTaskRoutes
import cn.xuyinyin.magic.connectors.mysql.source.{DebeziumEngineAccess,DebeziumEngineFactory,MySQLCdcStateConfig}
import cn.xuyinyin.magic.cdc.control.SnapshotOwnerJdbc
import cn.xuyinyin.magic.cdc.guardian.SnapshotGuardianWire
import com.typesafe.config.ConfigFactory
import java.nio.file.{Files,Path}
import java.lang.reflect.{InvocationTargetException,Method,Proxy}
import java.sql.{Connection,SQLException}
import java.util.Properties
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.{Behaviors,adapter}
import org.apache.pekko.http.scaladsl.Http
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._
import spray.json._

/** Isolated test-only managed worker; deliberately wires the pending production candidate. */
object CdcManagedMatrixWorker {
  def main(args:Array[String]):Unit={
    val Array(supervisorPath,workerPath,boot,directory)=args
    val base=ConfigFactory.parseFile(Path.of(workerPath).toFile).withFallback(ConfigFactory.load("application-test"))
    val overrideConfig=ConfigFactory.parseString(s"""pekko.workflow.snapshot-ha.owner-id="$boot"
      pekko.workflow.snapshot-ha.supervisor-config="$supervisorPath"""")
    val config=SnapshotOwnerBootstrap.prepare(overrideConfig.withFallback(base).resolve())
    val system=ActorSystem(Behaviors.empty[String],"managed-cdc-"+boot.take(8),config)
    implicit val ec:ExecutionContext=system.executionContext
    implicit val classic:org.apache.pekko.actor.ActorSystem=system.classicSystem
    val state=MySQLCdcStateConfig.load(config);val settings=CdcPlatformSettings.load(config).get
    val ctx=SnapshotOwnerJdbc.context.get
    val supervisor=new SnapshotSupervisorSettings(Path.of(supervisorPath))
    val (binding,guardianCheck,freshAttest)=try {
      supervisor.metadata.verifyWorker(boot,ctx.identity,config.getString("pekko.workflow.snapshot-ha.launch-profile"))
      val b=supervisor.binding
      val client=new SnapshotGuardianWire.WorkerClient(supervisor.socket,supervisor.secret.clone(),b)
      val profile=config.getString("pekko.workflow.snapshot-ha.launch-profile")
      (b,()=>client.attest(ctx.identity,profile),
        (context:SnapshotGuardianWire.WorkerAttestationContext,nonce:String)=>client.attestContextWithNonce(ctx.identity,profile,context,nonce))
    }finally supervisor.close()
    val owner=CdcManagedOwner(boot,ctx.identity.deploymentId,binding.hostId,binding.bootId,binding.publicKey,ctx.identity.pid,ctx.identity.startIdentity,
      config.getString("pekko.workflow.snapshot-ha.launch-profile"))
    if(Files.exists(Path.of(directory,"block-recovery"))){
      Files.writeString(Path.of(directory,boot+"-recovery-gated"),"gated")
      val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90)
      while(!Files.exists(Path.of(directory,"release-recovery")) && System.nanoTime()<deadline)Thread.sleep(25)
      require(Files.exists(Path.of(directory,"release-recovery")),"test recovery gate timed out")
    }
    val store=new CdcTaskStore(()=>SnapshotOwnerJdbc.open(CdcJdbc.withTimeouts(state.jdbcUrl),state.username,state.password,"control",managed=true),settings.limits)
    // Test-only exercise of the dormant production v2 steps; the regular runtime
    // remains closed until the Reader and permit consumer support v2.
    if(Files.exists(Path.of(directory,"probe-source-fence-v2"))){
      val probeSupervisor=new SnapshotSupervisorSettings(Path.of(supervisorPath))
      try {
        val attempt=store.ownerAttempt.filter(_._2!="STOPPED").get._1
        val managed=store.managedOwner(attempt).get
        val manifest=store.managedManifest(attempt).get
        val ticket=probeSupervisor.metadata.launch(managed.bootId).get
        val ownership=probeSupervisor.metadata.ownerRegistry
        val old=ownership.owner(managed.bootId).get
        val inventory=ownership.instances(managed.bootId).map {case(endpoint,uuid,_)=>
          endpoint->(uuid,ownership.databaseUsers(managed.bootId,endpoint))
        }.toMap
        val isolation=CdcManagedIsolation.verify(managed,manifest,probeSupervisor.binding,ticket,old,inventory)
        val task=store.soleOutstandingTask(attempt).get
        val cfg=settings.captures.find(_.id==task.captureId).get.copy(sourceFenceV2=true)
        val method=CdcManagedRecovery.getClass.getDeclaredMethods.find(m=>m.getName=="fencePendingB" && m.getParameterCount==6).get
        method.setAccessible(true)
        val result=method.invoke(CdcManagedRecovery,store,cfg,attempt,task.taskId,isolation,guardianCheck)
          .asInstanceOf[CdcManagedPendingB.Verified]
        val f=result.sourceFence.get
        Files.writeString(Path.of(directory,boot+"-fence-probe.json"),JsObject(
          "status"->JsString("FENCED_RESCAN_ONLY"),"taskId"->JsString(result.taskId),"D"->JsString(result.offset),
          "markerPresent"->JsBoolean(result.markerPresent),"epoch"->JsNumber(f.epoch),"version"->JsNumber(f.version),
          "operation"->JsString(f.operation),"table"->JsString(f.table),"generation"->JsString(f.generation)).prettyPrint)
        // Keep the test worker alive for evidence capture; do not start the
        // legacy runtime or release any production grant in this probe.
        while(true)Thread.sleep(1000)
      } finally probeSupervisor.close()
    }
    val factory=new DebeziumEngineFactory {def create(p:Properties):DebeziumEngineAccess={
      p.setProperty("database.ssl.mode","disabled");DebeziumEngineFactory.real.create(p)
    }}
    val eventLock=new AnyRef
    val observe:(String,JsObject)=>Unit=(kind,data)=>eventLock.synchronized {
      val file=Path.of(directory,boot+"-events.jsonl")
      Files.writeString(file,JsObject(data.fields+("kind"->JsString(kind))).compactPrint+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
    }
    val closeFaultArmed=new java.util.concurrent.atomic.AtomicBoolean(true)
    val heldConnections=scala.collection.mutable.ArrayBuffer.empty[Connection]
    val wrap:(String,Connection)=>Connection=(account,raw)=>{
      val selected=Files.exists(Path.of(directory,"inject-controller-close")) && settings.captures.exists(_.sourceControllerUsername.contains(account))
      if(!selected)raw else {
        val committed=new java.util.concurrent.atomic.AtomicBoolean(false)
        Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),(_,method:Method,arguments:Array[AnyRef])=>{
          if(method.getName=="close" && committed.get() && closeFaultArmed.compareAndSet(true,false)){
            val q=raw.createStatement();val connectionId=try {val r=q.executeQuery("SELECT CONNECTION_ID()");try {r.next();r.getLong(1)}finally r.close()}finally q.close()
            heldConnections.synchronized(heldConnections+=raw)
            Files.writeString(Path.of(directory,boot+"-controller-close-fault.json"),JsObject("connectionId"->JsNumber(connectionId),
              "scope"->JsString("test-only close failure after real commit; underlying JDBC connection retained")).compactPrint)
            throw new SQLException("injected controller close failure")
          }
          try {val result=method.invoke(raw,Option(arguments).getOrElse(Array.empty[AnyRef]):_*)
            if(method.getName=="commit")committed.set(true)
            result
          } catch {case e:InvocationTargetException=>throw e.getCause}
        }).asInstanceOf[Connection]
      }
    }
    val kernel=new CdcBootstrapKernel(settings,system,observe,factory,managed=true,recoveryStore=Some(store),freshAttest=Some(freshAttest),wrapSource=wrap)
    if(settings.captures.exists(_.sourceControllerUsername.nonEmpty)){
      def diagnostic(stage:String)(f: =>Unit):Unit=try f catch {case e:Throwable=>
        val cause=Iterator.iterate(Option(e))(_.flatMap(x=>Option(x.getCause))).takeWhile(_.nonEmpty).flatten.toVector
        val sql=cause.collectFirst {case x:java.sql.SQLException=>JsObject("state"->Option(x.getSQLState).fold[JsValue](JsNull)(JsString(_)),"code"->JsNumber(x.getErrorCode))}.getOrElse(JsNull)
        Files.writeString(Path.of(directory,boot+"-preflight-failure.json"),JsObject("stage"->JsString(stage),
          "classes"->JsArray(cause.map(x=>JsString(x.getClass.getName))),"sql"->sql).prettyPrint)
        throw e
      }
      diagnostic("store-initialize"){store.initialize()}
      diagnostic("managed-manifest"){kernel.prepareManagedManifest();()}
    }
    val runtime=new CdcPlatformRuntime(store,kernel,settings.limits,pollIntervalMillis=50,managedOwner=Some(owner),
      recoverManaged=()=>CdcManagedRecovery.recover(store,settings,Path.of(supervisorPath),guardianCheck),guardianCheck=guardianCheck)
    Await.result(runtime.captures(),30.seconds)
    val http=Await.result(Http().newServerAt("127.0.0.1",0).bind(new CdcTaskRoutes(Some(runtime)).routes),20.seconds)
    Files.writeString(Path.of(directory,boot+"-ready.json"),JsObject("port"->JsNumber(http.localAddress.getPort),
      "boot"->JsString(boot),"pid"->JsNumber(ProcessHandle.current().pid()),"attempt"->JsString(runtime.runtimeAttemptId),
      "ownerState"->JsString(runtime.status.ownerState),"reason"->runtime.status.reason.map(JsString(_)).getOrElse(JsNull)).compactPrint)
    while(true)Thread.sleep(1000)
  }
}
