package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.api.http.routes.CdcTaskRoutes
import cn.xuyinyin.magic.connectors.mysql.source.{DebeziumEngineAccess,DebeziumEngineFactory,MySQLCdcStateConfig}
import cn.xuyinyin.magic.workflow.scheduling.{SnapshotGuardianWire,SnapshotOwnerBootstrap,SnapshotOwnerJdbc,SnapshotSupervisorSettings}
import com.typesafe.config.ConfigFactory
import java.nio.file.{Files,Path}
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
    val (binding,guardianCheck)=try {
      supervisor.metadata.verifyWorker(boot,ctx.identity,config.getString("pekko.workflow.snapshot-ha.launch-profile"))
      val b=supervisor.binding
      val client=new SnapshotGuardianWire.WorkerClient(supervisor.socket,supervisor.secret.clone(),b)
      b->(()=>client.attest(ctx.identity,config.getString("pekko.workflow.snapshot-ha.launch-profile")))
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
    val factory=new DebeziumEngineFactory {def create(p:Properties):DebeziumEngineAccess={
      p.setProperty("database.ssl.mode","disabled");DebeziumEngineFactory.real.create(p)
    }}
    val eventLock=new AnyRef
    val observe:(String,JsObject)=>Unit=(kind,data)=>eventLock.synchronized {
      val file=Path.of(directory,boot+"-events.jsonl")
      Files.writeString(file,JsObject(data.fields+("kind"->JsString(kind))).compactPrint+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
    }
    val kernel=new CdcBootstrapKernel(settings,system,observe,factory,managed=true,recoveryStore=Some(store))
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
