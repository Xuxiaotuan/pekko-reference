package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.Node
import cn.xuyinyin.magic.workflow.engine.ExecutionFailure
import java.sql.Connection
import java.util.concurrent.{Semaphore,TimeUnit}
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import spray.json._

/** Explicit service admission. Not a 700k-table scheduler or an owner takeover mechanism. */
final class BootstrapAdmission(readers:Int=2,writers:Int=2,runners:Int=8) {
  require(readers>0 && writers>0 && runners>0)
  private val read=new Semaphore(readers);private val write=new Semaphore(writers);private val run=new Semaphore(runners)
  def reserveRun():Unit=require(run.tryAcquire(),"bootstrap active session budget exhausted; leave request in durable scheduler")
  def releaseRun():Unit=run.release()
  def evidence:JsObject=JsObject("readAvailable"->JsNumber(read.availablePermits()),"writeAvailable"->JsNumber(write.availablePermits()),"runAvailable"->JsNumber(run.availablePermits()))
  private def use[A](s:Semaphore)(f: =>A):A={require(s.tryAcquire(5,TimeUnit.SECONDS),"bootstrap admission timed out");var release=true
    try f catch {case e:Throwable=>if(!ExecutionFailure.from(e,"bootstrap").releaseConfirmed)release=false;throw e}finally if(release)s.release()}
  def reading[A](f: =>A):A=use(read)(f)
  def writing[A](f: =>A):A=use(write)(f)
}
final case class BootstrapLimits(chunkRows:Int=32,pageRows:Int=8,groupBatch:Int=128,windowRows:Int=10000,windowBytes:Long=8L*1024*1024) {
  require(chunkRows>0 && pageRows>0 && groupBatch>0 && groupBatch<=1000 && windowRows>=chunkRows && windowBytes>0)
}

/** Serial table initialization + application, independent from the shared Reader callback thread.
  * Call run on a dedicated blocking execution context. Journal asks use the actor dispatcher.
  */
final class BootstrapRunner(val id:String,val table:String,val generation:String,definition:String,
  capture:SharedMySQLCapture,logStore:MySQLCaptureStore,spool:BootstrapStore,target:BootstrapTarget,
  journal:BootstrapJournalAccess,source:BootstrapSnapshot,sink:BootstrapReplaySink,sinkNode:Node,
  database:String,leaseOpen:()=>Connection,admission:BootstrapAdmission,limits:BootstrapLimits=BootstrapLimits(),
  observe:(String,JsObject)=>Unit=(_,_)=>())(implicit sinkEc:ExecutionContext) {
  require(id.matches("[A-Za-z0-9_-]{1,96}"))
  private val boundDefinition=MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"definition"->JsString(definition),
    "capture"->JsString(logStore.captureId),"lineage"->JsString(logStore.lineage),"database"->JsString(database),"table"->JsString(table),
    "generation"->JsString(generation),"target"->JsString(target.table),"schema"->JsString(source.codec.schemaId)))
  private val log=new CapturedLogSource(logStore,database)
  private def waitFor[A](f:Future[A]):A=Await.result(f,40.seconds)
  private def waitSink[A](f:Future[A]):A=try waitFor(f) catch {
    case e:java.util.concurrent.TimeoutException=>throw new cn.xuyinyin.magic.workflow.engine.CdcExecutionException(
      ExecutionFailure(ExecutionFailure.ReleaseUnknown,"bootstrap-replay-wait",releaseConfirmed=false),e)
  }
  def state:BootstrapProgress=waitFor(journal.read())
  private def update(f:BootstrapProgress=>BootstrapProgress):BootstrapProgress={val n=waitFor(journal.update(f));observe("J_COMMITTED",json(n));n}
  private def canonical(v:JsValue)=MySQLCdcGroup.canonical(v)
  def json(s:BootstrapProgress):JsObject=JsObject("revision"->JsNumber(s.revision),"phase"->JsString(s.phase),"executionId"->JsString(s.executionId),
    "index"->JsNumber(s.index),"attempt"->JsNumber(s.attempt),"window"->s.window.map(JsString(_)).getOrElse(JsNull),"appliedPage"->JsNumber(s.appliedPage),
    "fence"->s.fence.map(JsString(_)).getOrElse(JsNull),"deltaAfter"->JsNumber(s.deltaAfter),"completionRoot"->JsString(s.completionRoot),
    "recoveryAttempts"->JsNumber(s.recoveryAttempts),"cancelRequested"->JsBoolean(s.cancelRequested),"failure"->s.failure.map(JsString(_)).getOrElse(JsNull))
  private def healthy():Unit=capture.failure.foreach(throw _)
  private def poll(label:String)(ready: =>Boolean):Unit={val deadline=System.nanoTime()+30000000000L
    while(!ready){healthy();require(System.nanoTime()<deadline,"bootstrap wait expired: "+label);Thread.sleep(25)}}
  private def canceled:Boolean=state.cancelRequested
  private def emit(kind:String,fields:(String,JsValue)*):Unit=observe(kind,JsObject(fields.toMap))
  private def fence(name:String,action:String,context:String):CaptureFence={
    capture.requestFence(name,id,action,context)
    poll(action){logStore.fence(name).exists(_.covered)}
    val f=logStore.fence(name).get;emit(action+"_COVERED","fence"->JsString(name),"point"->f.point.get.json.parseJson,"sequence"->JsNumber(f.sequence.get));f
  }
  private def number(spec:String,name:String):String=spec.parseJson.asJsObject.fields(name).asInstanceOf[JsString].value
  private def lower(spec:String):Option[String]=spec.parseJson.asJsObject.fields("lower") match{case JsString(s)=>Some(s);case JsNull=>None;case _=>throw new IllegalArgumentException("invalid range")}
  private def context(window:String):String=canonical(JsObject("bootstrap"->JsString(id),"window"->JsString(window),"schema"->JsString(source.codec.schemaId)))
  private def recover(reason:String):Unit={val s=state
    if(s.recoveryAttempts>=3)update(_.copy(phase="PAUSED",failure=Some(ExecutionFailure.BudgetExhausted)))
    else update(_.copy(recoveryAttempts=s.recoveryAttempts+1,failure=Some(reason)))
  }
  private def verifyWindow(w:String,m:String):Unit={
    val manifest=m.parseJson.asJsObject.fields
    require(manifest("spec")==spool.spec(w).parseJson,"sealed window spec changed")
    val proof=manifest("proof").asJsObject.fields
    val lo=logStore.fence(w+"_L").get;val hi=logStore.fence(w+"_H").get
    require(lo.covered && hi.covered && lo.subscriptionId==id && hi.subscriptionId==id && lo.context==context(w) && hi.context==context(w),"sealed window fence proof missing")
    require(proof("LOW")==lo.point.get.json.parseJson && proof("HIGH")==hi.point.get.json.parseJson && proof("lowSequence")==JsNumber(lo.sequence.get) && proof("highSequence")==JsNumber(hi.sequence.get),"sealed window fence proof changed")
    val raw=proof("rawPages").asInstanceOf[JsArray].elements
    require(raw.size==spool.pageCount(w,"RAW"),"sealed input pages missing")
    raw.zipWithIndex.foreach {case(h,i)=>require(h==JsString(MySQLCdcGroup.hash(canonical(JsArray(spool.page(w,"RAW",i))))),"sealed input page proof changed")}
    var after=lo.sequence.get;var root="";var count=0L;var more=true
    while(more){val rows=logStore.readThrough(id,after,hi.id,limits.groupBatch);more=rows.nonEmpty
      rows.foreach {r=>root=MySQLCdcGroup.hash(root+r.group.canonicalJson);count+=1;after=r.sequence}}
    require(proof("groupRoot")==JsString(root) && proof("groups")==JsNumber(count),"sealed input L proof changed")
  }
  private def audit():String={
    val s=state;var ordinal=0L;var root="";var bound:Option[String]=None;var done=false
    while(!done){val rows=spool.completed(ordinal-1,128);done=rows.isEmpty
      rows.foreach {case(i,w,parent,expected)=>
        require(i==ordinal && parent==root,"bootstrap completed index has gaps")
        val spec=spool.spec(w);require(lower(spec)==bound,"bootstrap completed ranges have gaps/overlaps")
        val manifest=spool.manifest(w).get;verifyWindow(w,manifest);val digest=MySQLCdcGroup.hash(parent+"|"+i+"|"+w+"|"+MySQLCdcGroup.hash(manifest))
        require(expected==digest,"bootstrap completion root corrupted")
        val pages=manifest.parseJson.asJsObject.fields("pages").asInstanceOf[JsArray].elements.size
        (0 until math.max(1,pages)).foreach {p=>target.verifyPage(w,p,manifest,if(pages==0)Vector.empty else spool.verifyFinal(w,p,manifest))}
        root=expected;bound=Some(number(spec,"upper"));ordinal+=1
      }
    }
    require(ordinal==s.index && root==s.completionRoot && bound==s.upper,"bootstrap baseline coverage proof incomplete")
    val f=logStore.fence(s.fence.get).get;require(f.covered,"activation fence not covered")
    var after=logStore.subscription(id).get.bSequence.get;var more=true
    while(more){val entries=logStore.readThrough(id,after,f.id,limits.groupBatch);more=entries.nonEmpty
      if(entries.nonEmpty){val b=log.readBatch(id,after,entries.size,"source",s.executionId,0).get
        b.rows.foreach(g=>target.verifyGroup(g,b.cursor.upperBound));after=entries.last.sequence}}
    canonical(JsObject("version"->JsNumber(1),"bootstrap"->JsString(id),"definition"->JsString(MySQLCdcGroup.hash(boundDefinition)),
      "baselineRoot"->JsString(root),"windows"->JsNumber(ordinal),"B"->logStore.subscription(id).get.b.get.json.parseJson,
      "F"->f.point.get.json.parseJson,"fenceSequence"->JsNumber(f.sequence.get),"schema"->JsString(source.codec.schemaId)))
  }
  private def scanStep():Unit={
    var s=state
    if(s.window.isEmpty && s.planned && s.nextLower==s.upper){
      // Empty input has no ranges. Nonempty input finishes exactly at frozen upper U.
      emit("BEFORE_F");val f=fence(id+"_F","F",context("F"));emit("F_READY","sequence"->JsNumber(f.sequence.get))
      update(_.copy(phase="REPLAYING",fence=Some(f.id),deltaAfter=logStore.subscription(id).get.bSequence.get));return
    }
    if(s.window.isEmpty){val u=source.nextUpper(s.nextLower,s.upper.get,limits.chunkRows)
      val w=id+"_"+s.index+"_"+s.attempt
      val spec=canonical(JsObject("index"->JsNumber(s.index),"lower"->s.nextLower.map(JsString(_)).getOrElse(JsNull),"upper"->JsString(u),"schema"->JsString(source.codec.schemaId)))
      spool.register(w,spec);s=update(_.copy(window=Some(w),manifest=None,appliedPage=0))
      emit("WINDOW_REGISTERED","window"->JsString(w),"spec"->spec.parseJson)
    }
    val w=s.window.get;val spec=spool.spec(w)
    var manifest=spool.manifest(w)
    if(manifest.isEmpty){
      // A persisted window from a previous invocation is abandoned before runLoop calls this.
      val low=fence(w+"_L","LOW",context(w))
      admission.reading {source.scan(w,lower(spec),number(spec,"upper"),limits.pageRows,spool,()=>canceled)}
      emit("READ_CLOSED","window"->JsString(w))
      if(canceled)return
      val high=fence(w+"_H","HIGH",context(w))
      val fold=new BootstrapFold(lower(spec),number(spec,"upper"),limits.windowBytes,source.codec.primaryKey)
      val rawHashes=Vector.newBuilder[JsValue]
      (0 until spool.pageCount(w,"RAW")).foreach {p=>val rows=spool.page(w,"RAW",p);fold.snapshot(rows);rawHashes+=JsString(MySQLCdcGroup.hash(canonical(JsArray(rows))))}
      var after=low.sequence.get;var more=true;var count=0L;var root=""
      while(more){val entries=logStore.readThrough(id,after,high.id,limits.groupBatch);more=entries.nonEmpty
        entries.foreach {r=>r.group.members.foreach {case(_,e)=>fold.event(e.op.code,e.key,e.after)}
          root=MySQLCdcGroup.hash(root+r.group.canonicalJson);count+=1;after=r.sequence;emit("FOLD_GROUP","window"->JsString(w),"sequence"->JsNumber(r.sequence))}}
      val proof=canonical(JsObject("LOW"->low.point.get.json.parseJson,"HIGH"->high.point.get.json.parseJson,"lowSequence"->JsNumber(low.sequence.get),"highSequence"->JsNumber(high.sequence.get),"rawPages"->JsArray(rawHashes.result()),"groups"->JsNumber(count),"groupRoot"->JsString(root)))
      manifest=Some(spool.seal(w,proof,fold.result,limits.pageRows));emit("SEALED","window"->JsString(w),"manifest"->manifest.get.parseJson)
    }
    val m=manifest.get
    verifyWindow(w,m)
    if(state.manifest.isEmpty)update(_.copy(manifest=Some(MySQLCdcGroup.hash(m))))
    require(state.manifest.contains(MySQLCdcGroup.hash(m)),"Journal sealed output identity changed")
    val pages=m.parseJson.asJsObject.fields("pages").asInstanceOf[JsArray].elements.size
    (0 until state.appliedPage).foreach {p=>target.verifyPage(w,p,m,if(pages==0)Vector.empty else spool.verifyFinal(w,p,m))}
    while(state.appliedPage<math.max(1,pages) && !canceled){val p=state.appliedPage
      val rows=if(pages==0)Vector.empty else spool.verifyFinal(w,p,m)
      admission.writing {val fresh=target.applyPage(w,p,m,rows);emit("BASELINE_T","window"->JsString(w),"page"->JsNumber(p),"new"->JsBoolean(fresh))}
      update(_.copy(appliedPage=p+1))
    }
    if(!canceled){val root=spool.complete(state.index,w,state.completionRoot)
      update(x=>x.copy(index=x.index+1,nextLower=Some(number(spec,"upper")),window=None,manifest=None,appliedPage=0,attempt=0,completionRoot=root));emit("WINDOW_COMPLETE","window"->JsString(w));if(state.nextLower==state.upper)emit("LAST_WINDOW_COMPLETE","window"->JsString(w))}
  }
  private def replay(tail:Boolean):Boolean={val s=state
    val entries=if(tail)logStore.read(id,s.deltaAfter,limits.groupBatch)else logStore.readThrough(id,s.deltaAfter,s.fence.get,limits.groupBatch)
    if(entries.nonEmpty){val b=log.readBatch(id,s.deltaAfter,entries.size,"source",s.executionId,s.batchSequence).get
      require(b.rows.size==entries.size,"capture finite replay moved")
      admission.writing {waitSink(sink.commitBatch(sinkNode,id,s.executionId,b,b.rows,_=>()));emit("REPLAY_T","batchSequence"->JsNumber(b.batchSequence),"groups"->JsNumber(b.rows.size),"lastSequence"->JsNumber(entries.last.sequence))}
      update(_.copy(deltaAfter=entries.last.sequence,batchSequence=s.batchSequence+1));true
    }else false
  }
  private def cancelStep():Unit={
    val s=logStore.subscription(id)
    s.foreach {sub=>if(sub.status=="B_PENDING")capture.cancelPending(id)
      else if(sub.status=="CAPTURE_READY"){capture.unsubscribe(id);poll("K"){logStore.subscription(id).exists(_.k.nonEmpty)}}}
    val finalSub=logStore.subscription(id)
    if(target.activation.nonEmpty && finalSub.exists(_.k.nonEmpty))while(replay(tail=true)){}
    update(_.copy(phase="CANCELED"));emit("CANCELED","K"->finalSub.flatMap(_.k).map(_.json.parseJson).getOrElse(JsNull))
  }

  // A scheduled step owns no JDBC connection or run permit after a confirmed return.
  // Keeping this small per-active-runner state is optional: cache eviction can use the
  // control index's exact clean outcome, while process restart must not reuse that proof.
  private var stepPrepared=false
  private var stepLastClean:Option[BootstrapProgress]=None
  private var stepLastObserved:Option[BootstrapProgress]=None
  private var stepReleaseUnknown=false
  private var stepRestoring=false
  private var stepAudit:Option[StepAudit]=None
  private var stepCancelIssued=false

  private final class StepAudit(val accepted:BootstrapProgress) {
    private var ordinal=0L
    private var root=""
    private var bound:Option[String]=None
    private var baseline=true
    private val sub=logStore.subscription(id).get
    private val f=logStore.fence(accepted.fence.get).get
    require(f.covered,"activation fence not covered")
    private var after=sub.bSequence.get
    private var finite=true
    private var proof:Option[String]=None

    /** One completed window or one group batch; never a table-wide loop in one step. */
    def advance():Option[String]={
      if(baseline){
        val rows=spool.completed(ordinal-1,1)
        if(rows.nonEmpty){
          val (i,w,parent,expected)=rows.head
          require(i==ordinal && parent==root,"bootstrap completed index has gaps")
          val spec=spool.spec(w)
          require(lower(spec)==bound,"bootstrap completed ranges have gaps/overlaps")
          val manifest=spool.manifest(w).get
          verifyWindow(w,manifest)
          val digest=MySQLCdcGroup.hash(parent+"|"+i+"|"+w+"|"+MySQLCdcGroup.hash(manifest))
          require(expected==digest,"bootstrap completion root corrupted")
          val pages=manifest.parseJson.asJsObject.fields("pages").asInstanceOf[JsArray].elements.size
          (0 until math.max(1,pages)).foreach {p=>target.verifyPage(w,p,manifest,if(pages==0)Vector.empty else spool.verifyFinal(w,p,manifest))}
          root=expected;bound=Some(number(spec,"upper"));ordinal+=1
        }else {
          require(ordinal==accepted.index && root==accepted.completionRoot && bound==accepted.upper,"bootstrap baseline coverage proof incomplete")
          baseline=false
        }
        None
      }else if(finite){
        val entries=logStore.readThrough(id,after,f.id,limits.groupBatch)
        if(entries.nonEmpty){
          val b=log.readBatch(id,after,entries.size,"source",accepted.executionId,0).get
          b.rows.foreach(g=>target.verifyGroup(g,b.cursor.upperBound));after=entries.last.sequence
          None
        }else {
          val p=canonical(JsObject("version"->JsNumber(1),"bootstrap"->JsString(id),"definition"->JsString(MySQLCdcGroup.hash(boundDefinition)),
            "baselineRoot"->JsString(root),"windows"->JsNumber(ordinal),"B"->sub.b.get.json.parseJson,
            "F"->f.point.get.json.parseJson,"fenceSequence"->JsNumber(f.sequence.get),"schema"->JsString(source.codec.schemaId)))
          proof=Some(p);finite=false;after=f.sequence.get
          if(accepted.phase=="STREAMING"){
            require(target.activation.contains(p),"durable STREAMING has no target activation proof")
            if(after==accepted.deltaAfter)proof else None
          }else proof
        }
      }else if(accepted.phase=="STREAMING"){
        val entries=logStore.read(id,after,limits.groupBatch).takeWhile(_.sequence<=accepted.deltaAfter)
        if(entries.nonEmpty){
          val b=log.readBatch(id,after,entries.size,"source",accepted.executionId,0).get
          b.rows.foreach(g=>target.verifyGroup(g,b.cursor.upperBound));after=entries.last.sequence
          if(after==accepted.deltaAfter)proof else None
        }else {
          require(after==accepted.deltaAfter,"streaming Journal cursor is not retained in L")
          proof
        }
      }else proof
    }
  }

  private def scheduledCancel():Boolean={
    logStore.subscription(id) match {
      case Some(sub) if sub.status=="B_PENDING"=>
        // Bind can win after the read. A known effective route must progress through K.
        try capture.cancelPending(id) catch {
          case e:IllegalArgumentException if logStore.subscription(id).exists(_.status=="CAPTURE_READY")=>return true
        }
      case Some(sub) if sub.status=="CAPTURE_READY"=>
        if(!stepCancelIssued){capture.unsubscribe(id);stepCancelIssued=true;return true}
        return false
      case _=>()
    }
    val sub=logStore.subscription(id)
    if(sub.exists(_.status=="CAPTURE_READY"))return false
    if(target.activation.nonEmpty && sub.exists(_.k.nonEmpty) && replay(tail=true))return true
    update(_.copy(phase="CANCELED"));emit("CANCELED","K"->sub.flatMap(_.k).map(_.json.parseJson).getOrElse(JsNull));true
  }

  private def scheduledScan():Boolean={
    val s=state
    if(s.window.isEmpty && s.planned && s.nextLower==s.upper){
      val name=id+"_F"
      logStore.fence(name) match {
        case None=>emit("BEFORE_F");capture.requestFence(name,id,"F",context("F"));true
        case Some(f) if f.covered=>
          emit("F_READY","sequence"->JsNumber(f.sequence.get))
          update(_.copy(phase="REPLAYING",fence=Some(f.id),deltaAfter=logStore.subscription(id).get.bSequence.get));true
        case _=>false
      }
    }else {scanStep();true}
  }

  /** Execute on the bounded blocking pool. One snapshot window (one RR view), one
    * group batch or one audit unit is the scheduling quantum; JDBC/query/fence
    * timeouts still bound each blocking operation. No empty tail loop owns a thread.
    * continuation is accepted only from the caller's same-owner, durably completed,
    * release-confirmed previous grant. The Runner independently checks its full J.
    */
  def step(stop:()=>Boolean=()=>false,continuation:Option[BootstrapProgress]=None):BootstrapStepResult=synchronized {
    if(stepReleaseUnknown)return BootstrapStepResult(stepLastObserved.getOrElse(state),false,false,0,validationComplete=false)
    val initial=state
    if(stop() || Set("PAUSED","CANCELED").contains(initial.phase))
      return BootstrapStepResult(initial,false,true,0)
    if(capture.cachedStartupEvidence.phase!="ADMISSION_READY"){
      healthy();return BootstrapStepResult(initial,false,true,250,validationComplete=false)
    }
    admission.reserveRun()
    val leaseJdbc=new BootstrapJdbc(leaseOpen,1)
    var release=true
    var didWork=false
    var complete=true
    try leaseJdbc.connection {c=>
      val name="bootstrap-"+MySQLCdcGroup.hash(logStore.captureId+"|"+id).take(40)
      val acquired=leaseJdbc.query(c,"SELECT GET_LOCK(?,0) AS acquired",name).head("acquired")=="1"
      require(acquired,"bootstrap already running")
      try {
        val before=state
        val proof=stepLastClean.orElse(continuation)
        proof.foreach(p=>require(BootstrapStepContinuation.matches(p,before),"clean bootstrap step no longer matches Journal"))
        if(!stepPrepared){
          spool.initialize(boundDefinition,before.definition.nonEmpty)
          source.validate(Some(logStore.lineage));target.claim()
          if(before.definition.isEmpty)update(_.copy(definition=MySQLCdcGroup.hash(boundDefinition),executionId=java.util.UUID.randomUUID().toString))
          else {
            require(before.definition==MySQLCdcGroup.hash(boundDefinition),"bootstrap definition changed")
            if(proof.isEmpty){recover(ExecutionFailure.Interrupted);stepRestoring=true}
            else {
              // Cache replacement loses every former RR transaction even if the
              // previous scheduling grant closed cleanly after a retryable failure.
              // Do this only on first preparation, never between normal steps.
              if(BootstrapStepContinuation.needsReadViewRecovery(before,before.window.exists(w=>spool.manifest(w).nonEmpty)))
                stepRestoring=true
              if(before.phase=="STREAMING")require(target.activation.nonEmpty,"durable STREAMING has no target activation proof")
            }
          }
          stepPrepared=true
          emit("BOOTSTRAP_REGISTERED","executionId"->JsString(state.executionId))
        }
        if(!Set("PAUSED","CANCELED").contains(state.phase) && !stop()){
          healthy()
          if(canceled){stepAudit=None;didWork=scheduledCancel()}
          else {
            val s=state
            if(stepRestoring && BootstrapStepContinuation.needsReadViewRecovery(s,s.window.exists(w=>spool.manifest(w).nonEmpty))){
              val old=s.window.get;val next=id+"_"+s.index+"_"+(s.attempt+1)
              spool.register(next,spool.spec(old))
              update(_.copy(attempt=s.attempt+1,window=Some(next),manifest=None,appliedPage=0))
              emit("ABANDONED","window"->JsString(old));didWork=true
            }
            if(stepRestoring && state.phase!="STREAMING")stepRestoring=false
            if(stepRestoring){
              if(stepAudit.isEmpty)stepAudit=Some(new StepAudit(state))
              if(stepAudit.get.advance().nonEmpty){stepAudit=None;stepRestoring=false}
              didWork=true;complete= !stepRestoring
            }else if(logStore.subscription(id).isEmpty){capture.subscribe(id,table,generation);didWork=true}
            else if(logStore.subscription(id).exists(_.b.isEmpty))complete=false
            else if(!state.planned){
              val upper=source.maximum();update(_.copy(phase="SCANNING",upper=upper,planned=true));didWork=true
            }else state.phase match {
              case "SCANNING"=>didWork=scheduledScan()
              case "REPLAYING"=>
                if(!replay(false))update(_.copy(phase="ACTIVATING",deltaAfter=logStore.fence(state.fence.get).get.sequence.get))
                didWork=true
              case "ACTIVATING"=>
                if(stepAudit.isEmpty)stepAudit=Some(new StepAudit(state))
                stepAudit.get.advance().foreach {proof=>
                  emit("BEFORE_ACTIVATE","proof"->proof.parseJson)
                  if(!canceled && !stop()){
                    target.activate(proof);emit("ACTIVATION_T","proof"->proof.parseJson)
                    if(!canceled){val accepted=update(_.copy(phase="STREAMING"));if(accepted.phase=="STREAMING")emit("STREAMING","proof"->proof.parseJson)}
                  }
                  stepAudit=None
                }
                didWork=true;complete=state.phase=="STREAMING"
              case "STREAMING"=>didWork=replay(true)
              case other=>throw new IllegalArgumentException("unexpected bootstrap phase: "+other)
            }
          }
        }
      }catch {case NonFatal(e)=>
        val failure=ExecutionFailure.from(e,"bootstrap-step")
        emit("FAILURE","code"->JsString(failure.code),"error"->JsString(e.toString))
        stepAudit=None
        if(failure.releaseConfirmed && ExecutionFailure.Retryable(failure.code) && capture.failure.isEmpty){recover(failure.code);stepRestoring=true}
        else {release=failure.releaseConfirmed;update(_.copy(phase="PAUSED",failure=Some(failure.code)))}
      }finally {
        if(leaseJdbc.query(c,"SELECT RELEASE_LOCK(?) AS released",name).head("released")!="1")
          throw new cn.xuyinyin.magic.workflow.engine.CdcExecutionException(
            ExecutionFailure(ExecutionFailure.ReleaseUnknown,"bootstrap-step-lease",releaseConfirmed=false),
            new IllegalStateException("bootstrap lease release unconfirmed"))
      }
    }catch {case NonFatal(e)=>
      if(!ExecutionFailure.from(e,"bootstrap-step-close").releaseConfirmed){release=false;stepReleaseUnknown=true}
      throw e
    }finally if(release)admission.releaseRun()
    val accepted=state
    stepLastObserved=Some(accepted)
    if(!release)stepReleaseUnknown=true
    if(release)stepLastClean=Some(accepted)
    BootstrapStepResult(accepted,didWork,release,if(didWork)0 else 250,complete && !stepRestoring)
  }

  def run(stop:()=>Boolean=()=>false):Unit={
    admission.reserveRun();val leaseJdbc=new BootstrapJdbc(leaseOpen,1);var release=true
    try leaseJdbc.connection {c=>
      val name="bootstrap-"+MySQLCdcGroup.hash(logStore.captureId+"|"+id).take(40)
      val lock=leaseJdbc.query(c,"SELECT GET_LOCK(?,0) AS acquired",name).head("acquired");require(lock=="1","bootstrap already running")
      try {
        spool.initialize(boundDefinition,state.definition.nonEmpty);source.validate(Some(logStore.lineage));target.claim()
        val s=state
        if(s.phase=="CANCELED" || s.phase=="PAUSED")return
        if(s.definition.isEmpty)update(_.copy(definition=MySQLCdcGroup.hash(boundDefinition),executionId=java.util.UUID.randomUUID().toString))
        else {require(s.definition==MySQLCdcGroup.hash(boundDefinition),"bootstrap definition changed");recover(ExecutionFailure.Interrupted)}
        if(state.phase=="PAUSED")return
        if(state.phase=="STREAMING"){
          val proof=audit();require(target.activation.contains(proof),"durable STREAMING has no target activation proof")
          var after=logStore.fence(state.fence.get).get.sequence.get;val last=state.deltaAfter;var more=true
          while(more){val entries=logStore.read(id,after,limits.groupBatch).takeWhile(_.sequence<=last);more=entries.nonEmpty
            if(entries.nonEmpty){val b=log.readBatch(id,after,entries.size,"source",state.executionId,0).get
              b.rows.foreach(g=>target.verifyGroup(g,b.cursor.upperBound));after=entries.last.sequence}}
          require(after==last,"streaming Journal cursor is not retained in L")
        }
        emit("BOOTSTRAP_REGISTERED","executionId"->JsString(state.executionId))
        if(!canceled){
          // A quiet skipped SDK snapshot may need a server heartbeat (~48s by default).
          // This is a bounded compatibility wait, not the future cold-task scheduler.
          val readyDeadline=System.nanoTime()+90000000000L
          while(!canceled && !stop() && capture.startupEvidence.phase!="ADMISSION_READY") {
            healthy();require(System.nanoTime()<readyDeadline,"capture admission wait expired")
            Thread.sleep(250)
          }
          if(stop())return
          if(!canceled)capture.subscribe(id,table,generation)
          if(!canceled)poll("B"){logStore.subscription(id).exists(_.b.nonEmpty)}
          if(!canceled && !state.planned){val upper=source.maximum();update(_.copy(phase="SCANNING",upper=upper,planned=true))}
        }
        var recovering=true
        while(!stop() && !Set("PAUSED","CANCELED").contains(state.phase)) {
          try {
            healthy()
            if(canceled)cancelStep()
            else {
              // Any incomplete read view is discarded on invocation/retry; sealed windows survive unchanged.
              if(recovering){val p=state;p.window.foreach {w=>if(BootstrapStepContinuation.needsReadViewRecovery(p,spool.manifest(w).nonEmpty)){
                val next=id+"_"+p.index+"_"+(p.attempt+1)
                spool.register(next,spool.spec(w))
                update(_.copy(attempt=p.attempt+1,window=Some(next),manifest=None,appliedPage=0));emit("ABANDONED","window"->JsString(w))}}
                recovering=false}
              state.phase match {
                case "SCANNING"=>scanStep()
                case "REPLAYING"=>if(!replay(false))update(_.copy(phase="ACTIVATING",deltaAfter=logStore.fence(state.fence.get).get.sequence.get))
                case "ACTIVATING"=>
                  val proof=audit();emit("BEFORE_ACTIVATE","proof"->proof.parseJson)
                  if(!canceled){target.activate(proof);emit("ACTIVATION_T","proof"->proof.parseJson)
                    if(!canceled){val accepted=update(_.copy(phase="STREAMING"));if(accepted.phase=="STREAMING")emit("STREAMING","proof"->proof.parseJson)}}
                case "STREAMING"=>if(!replay(true))Thread.sleep(50)
                case other=>throw new IllegalArgumentException("unexpected bootstrap phase: "+other)
              }
            }
          }catch {case NonFatal(e)=>
            val failure=ExecutionFailure.from(e,"bootstrap")
            emit("FAILURE","code"->JsString(failure.code),"error"->JsString(e.toString))
            if(canceled && failure.releaseConfirmed && capture.failure.isEmpty)cancelStep()
            else if(failure.releaseConfirmed && ExecutionFailure.Retryable(failure.code) && capture.failure.isEmpty){recover(failure.code);recovering=true}
            else {release=failure.releaseConfirmed;update(_.copy(phase="PAUSED",failure=Some(failure.code)))}
          }
        }
      }catch {case NonFatal(e)=>
        val failure=ExecutionFailure.from(e,"bootstrap-prepare");release=failure.releaseConfirmed
        emit("FAILURE","code"->JsString(failure.code),"error"->JsString(e.toString))
        update(_.copy(phase="PAUSED",failure=Some(failure.code)))
      }finally {require(leaseJdbc.query(c,"SELECT RELEASE_LOCK(?) AS released",name).head("released")=="1","bootstrap lease release unconfirmed")}
    }catch {case NonFatal(e)=>
      if(!ExecutionFailure.from(e,"bootstrap-session-close").releaseConfirmed)release=false
      throw e
    }finally if(release)admission.releaseRun()
  }
}
