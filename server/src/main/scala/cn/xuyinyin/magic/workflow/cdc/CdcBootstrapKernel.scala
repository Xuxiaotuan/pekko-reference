package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.connectors.mysql.source.{DebeziumEngineFactory,MySQLCdcStateConfig}
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.workflow.bootstrap._
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node,Position}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef,ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors,adapter}
import org.apache.pekko.actor.typed.scaladsl.adapter._
import java.sql.{Connection,DriverManager}
import java.util.UUID
import java.util.concurrent.{Executors,ThreadFactory,TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.concurrent.{Await,ExecutionContext,Future,Promise}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import spray.json._

/** Real kernel assembly; control/query rows never substitute for the existing T/J/D evidence. */
final class CdcBootstrapKernel(settings:CdcPlatformSettings,system:ActorSystem[_],
  observe:(String,JsObject)=>Unit=(_,_)=>(),engineFactory:DebeziumEngineFactory=DebeziumEngineFactory.real) extends CdcPlatformKernel {
  private implicit val actorEc:ExecutionContext=system.executionContext
  private implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=system.scheduler
  private val stopping=new AtomicBoolean()
  private val refresh=Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
    def newThread(r:Runnable):Thread={val t=new Thread(r,"cdc-capture-evidence");t.setDaemon(true);t}
  })
  private val admission=new BootstrapAdmission(settings.limits.snapshotSteps,settings.limits.snapshotSteps+settings.limits.tailSteps,
    settings.limits.snapshotSteps+settings.limits.tailSteps)
  private case class Capture(settings:CdcCaptureSettings,store:MySQLCaptureStore,reader:SharedMySQLCapture)
  private case class Session(runner:BootstrapRunner,journal:ActorRef[BootstrapJournal.Command],target:BootstrapTarget,pool:String)
  private val capturesById=mutable.Map.empty[String,Capture]
  private val sessions=mutable.Map.empty[String,Session]
  private val reservations=mutable.Map.empty[String,String]
  private var peakSessions=0
  private val journalStops=mutable.Map.empty[ActorRef[BootstrapJournal.Command],Future[Done]]
  private val closing=mutable.Map.empty[String,Future[CaptureReleaseEvidence]]
  @volatile private var observations=Map.empty[String,Long]
  @volatile private var captureViews=settings.captures.map(c=>CdcCaptureView(c.id,c.source.database,"NEW","WAITING",None))
  def captures:Vector[CdcCaptureView]={
    if(stopping.get())captureViews.map(_.copy(readiness="STOPPING",runtimeHealth="UNKNOWN"))
    else {val current=System.nanoTime();captureViews.map(v=>CdcCaptureHealth.current(v,observations.get(v.captureId),current))}
  }
  def resourceEvidence:JsObject=synchronized {JsObject(
    "cachedJournals"->JsNumber(sessions.size),"creatingSessions"->JsNumber(reservations.size),"peakCachedJournals"->JsNumber(peakSessions),
    "configuredCaptures"->JsNumber(settings.captures.size),"liveCaptures"->JsNumber(capturesById.size))}
  def captureReleaseEvidence:Vector[(String,CaptureReleaseEvidence)]=synchronized(capturesById.toVector.collect {case(id,c) if closing.contains(id)=>id->c.reader.releaseEvidence})
  private def stateOpen(c:CdcCaptureSettings):Connection=DriverManager.getConnection(CdcJdbc.withTimeouts(c.controlState.jdbcUrl),c.controlState.username,c.controlState.password)
  private def captureLogOpen(c:CdcCaptureSettings):Connection=DriverManager.getConnection(CdcJdbc.withTimeouts(c.controlState.jdbcUrl),c.sdkState.username,c.sdkState.password)
  private def sourceOpen(c:CdcCaptureSettings,observer:Boolean=false):Connection={
    val username=if(observer)c.sourceObserverUsername.getOrElse(c.source.username)else c.source.username
    val password=if(observer)c.sourceObserverPassword.getOrElse(c.source.password)else c.source.password
    DriverManager.getConnection(s"jdbc:mysql://${c.source.host}:${c.source.port}/${c.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000",username,password)
  }
  private def stateIdentity(c:CdcCaptureSettings):Unit={
    val jdbc=new BootstrapJdbc(()=>stateOpen(c),1)
    jdbc.connection {connection=>require(jdbc.query(connection,"SELECT @@server_uuid AS uuid").head("uuid")==c.stateLineage,"CDC platform state lineage changed")}
  }
  def start():Unit={
    require(!stopping.get(),"CDC kernel stopping")
    settings.captures.foreach {cfg=>
      stateIdentity(cfg)
      // L stays in the control schema, but every L connection belongs to the capture's observed account domain.
      val store=new MySQLCaptureStore(()=>captureLogOpen(cfg),cfg.id,cfg.sourceLineage,cfg.maxLogBytes)
      store.initialize()
      val observer=CaptureResourceObserver.exclusiveAccounts(()=>sourceOpen(cfg,observer=true),()=>stateOpen(cfg),
        cfg.source.username,cfg.sdkState.username,cfg.sourceLineage,cfg.stateLineage)
      val reader=new SharedMySQLCapture(cfg.source,cfg.sdkState.copy(jdbcUrl=CdcJdbc.withTimeouts(cfg.sdkState.jdbcUrl)),store,engineFactory,
        (kind,data)=>observe(kind,JsObject(data.fields+("captureId"->JsString(cfg.id)))),resourceObserver=Some(observer))
      synchronized {capturesById.update(cfg.id,Capture(cfg,store,reader))}
      reader.start()
    }
    refresh.scheduleWithFixedDelay(new Runnable {def run():Unit=refreshCaptures()},0L,250L,TimeUnit.MILLISECONDS)
  }
  private val nextEvidence=mutable.Map.empty[String,Long]
  private def refreshCaptures():Unit={
    if(!stopping.get()){
      val current=synchronized(capturesById.values.toVector)
      val now=System.currentTimeMillis()
      current.foreach {c=>
        if(nextEvidence.getOrElse(c.settings.id,0L)<=now){
          val view=try {
            val e=c.reader.startupEvidence
            nextEvidence.update(c.settings.id,now+(if(e.phase=="ADMISSION_READY")2000L else 250L))
            CdcCaptureView(c.settings.id,c.settings.source.database,e.phase,
              if(e.phase=="ADMISSION_READY" && c.reader.streamingConnected.contains(true))"HEALTHY"else if(e.phase=="STARTING")"STARTING"else"UNKNOWN",e.reasonCode)
          }catch {case NonFatal(_)=>CdcCaptureView(c.settings.id,c.settings.source.database,"UNKNOWN","UNKNOWN",Some("CAPTURE_UNAVAILABLE"))}
          observations=observations.updated(c.settings.id,System.nanoTime())
          captureViews=captureViews.map(old=>if(old.captureId==view.captureId)view else old)
        }
      }
    }
  }
  override def dispatchCaptureIds:Set[String]=captures.filter(c=>c.readiness=="ADMISSION_READY" && c.runtimeHealth=="HEALTHY").map(_.captureId).toSet
  override def admissibleTasks(pool:String):Option[Set[String]]=synchronized {
    val occupied=sessions.iterator.collect{case(id,s) if s.pool==pool=>id}.toSet++reservations.collect{case(id,p) if p==pool=>id}
    val maximum=if(pool=="TAIL")settings.limits.tailSteps else settings.limits.snapshotSteps
    if(occupied.size>=maximum)Some(occupied)else None
  }
  private def create(task:CdcTaskRequest,id:String,pool:String):Session={
    val c=synchronized(capturesById.getOrElse(task.captureId,throw new CdcPlatformException("CAPTURE_NOT_CONFIGURED")))
    val codec=new BootstrapRowCodec(c.store.schema(task.table).getOrElse(throw new CdcPlatformException("SCHEMA_NOT_READY")).definition)
    val journal=system.systemActorOf(BootstrapJournal(task.captureId+"-"+id),"cdc-journal-"+UUID.randomUUID().toString)
    try {
      val access=new BootstrapJournalAccess(journal)
      val spool=new BootstrapStore(()=>stateOpen(c.settings),id,c.settings.maxSpoolBytes)
      val target=new BootstrapTarget(()=>stateOpen(c.settings),task.targetTable,codec,id)
      val onEvent:(String,JsObject)=>Unit=(kind,data)=>observe(kind,JsObject(data.fields+("taskId"->JsString(id))))
      val source=new BootstrapSnapshot(()=>sourceOpen(c.settings),task.table,codec,c.settings.bootstrapLimits.windowRows,c.settings.bootstrapLimits.windowBytes,onEvent)
      val node=Node("target","sink","mysql.cdc.apply","CDC platform target",Position(0,0),JsObject(
        "host"->JsString(c.settings.targetHost),"port"->JsNumber(c.settings.targetPort),"database"->JsString(c.settings.targetDatabase),
        "table"->JsString(task.targetTable),"username"->JsString(c.settings.controlState.username),"password"->JsString(c.settings.controlState.password)))
      val sink=new BootstrapReplaySink(_=>stateOpen(c.settings))
      // Sink execution stays on the caller's bounded step worker, never the Actor dispatcher.
      val direct:ExecutionContext=ExecutionContext.parasitic
      val runner=new BootstrapRunner(id,task.table,task.generation,task.canonical,c.reader,c.store,spool,target,access,source,sink,node,
        c.settings.source.database,()=>stateOpen(c.settings),admission,c.settings.bootstrapLimits,onEvent)(direct)
      Session(runner,journal,target,pool)
    }catch {case e:Throwable=>stopJournal(journal);throw e}
  }
  private sealed trait Watch
  private case object Gone extends Watch
  private def stopJournal(ref:ActorRef[BootstrapJournal.Command]):Unit={
    val stopped=synchronized {journalStops.getOrElseUpdate(ref,{
      val done=Promise[Done]()
      system.systemActorOf(Behaviors.setup[Watch] {ctx=>
        ctx.watchWith(ref,Gone)
        Behaviors.receiveMessage {case Gone=>done.trySuccess(Done);Behaviors.stopped}
      },"cdc-journal-stop-"+UUID.randomUUID().toString)
      system.classicSystem.stop(ref.toClassic)
      done.future
    })}
    try {Await.result(stopped,5.seconds);synchronized(journalStops.remove(ref))}
    catch {case NonFatal(e)=>throw new CdcExecutionException(
      ExecutionFailure(ExecutionFailure.ReleaseUnknown,"cdc-journal-stop",releaseConfirmed=false),e)}
  }
  def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome={
    // A parked tail needs no Journal/Actor/validation session. The immutable clean
    // progress belongs to this runtime owner; an interrupted JVM never receives it.
    if(!cancel && continuation.exists(_.phase=="STREAMING") && !synchronized(sessions.contains(id))){
      val c=synchronized(capturesById.getOrElse(task.captureId,throw new CdcPlatformException("CAPTURE_NOT_CONFIGURED")))
      val p=continuation.get
      if(c.store.read(id,p.deltaAfter,1).isEmpty)
        return CdcKernelOutcome(p,true,false,true,settings.limits.tailDelayMillis,None)
    }
    val existing=synchronized {
      if(stopping.get() || stop())throw new CdcKernelDeferred
      sessions.get(id) match {
        case some@Some(_)=>some
        case None=>
          val occupied=sessions.values.count(_.pool==workPool)+reservations.values.count(_==workPool)
          val maximum=if(workPool=="TAIL")settings.limits.tailSteps else settings.limits.snapshotSteps
          if(occupied>=maximum)throw new CdcKernelDeferred
          reservations.update(id,workPool);None
      }
    }
    val session=existing.getOrElse {
      try {val value=create(task,id,workPool);synchronized {reservations.remove(id);sessions.update(id,value);peakSessions=math.max(peakSessions,sessions.size)};value}
      catch {case e:Throwable=>synchronized(reservations.remove(id));throw e}
    }
    var released=true
    try {
      if(cancel)Await.result(new BootstrapJournalAccess(session.journal).cancel(),10.seconds)
      val result=session.runner.step(stop,continuation)
      released=result.releaseConfirmed
      val proof=if(result.progress.phase=="STREAMING" && result.validationComplete)session.target.activation else None
      if(result.validationComplete || result.terminal || result.paused){
        stopJournal(session.journal);synchronized(sessions.remove(id))
      }
      CdcKernelOutcome(result.progress,released,result.didWork,result.validationComplete,result.nextDelayMillis,proof)
    }catch {case e:Throwable=>
      if(ExecutionFailure.from(e,"cdc-kernel").releaseConfirmed){
        try {stopJournal(session.journal);synchronized(sessions.remove(id))}catch {case release:Throwable=>release.addSuppressed(e);throw release}
      }
      throw e
    }
  }
  def closeAndVerify():Boolean={
    if(stopping.compareAndSet(false,true)){
      refresh.shutdown()
      synchronized {capturesById.foreach {case(id,c)=>closing.update(id,c.reader.requestClose())}}
    }
    // Retry observation of the same Journal stop promise; never allocate replacement writers.
    var localReleased=true
    val idle=synchronized(sessions.toVector)
    idle.foreach {case(id,s)=>
      try {stopJournal(s.journal);synchronized(sessions.remove(id))}catch {case NonFatal(_)=>localReleased=false}
    }
    val all=synchronized(capturesById.values.toVector)
    val evidence=all.map {c=>
      val request=synchronized(closing.get(c.settings.id))
      if(request.exists(_.isCompleted) && c.reader.releaseEvidence.reusable)true
      else {c.reader.refreshRelease();false}
    }
    val journalsReleased=synchronized {
      journalStops.filterInPlace((_,f)=> !f.value.exists(_.isSuccess));journalStops.isEmpty
    }
    localReleased && journalsReleased && evidence.forall(identity)
  }
}
