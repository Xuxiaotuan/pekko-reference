package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureAckPaging,CaptureControlFence,CaptureFenceBinding,CaptureFenceReceipt,MySQLCaptureStore}
import cn.xuyinyin.magic.cdc.bootstrap.BootstrapJournalAccess
import cn.xuyinyin.magic.cdc.recovery.{CdcExecutionException, ExecutionFailure}
import cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc
import cn.xuyinyin.magic.cdc.guardian.SnapshotGuardianWire
import org.apache.pekko.actor.typed.ActorSystem
import java.sql.Connection
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** All JDBC source work runs on the invoking session thread. V2 remains gated at the runtime entrance. */
private[cdc] final class CdcFenceRecoverySession(cfg:CdcCaptureSettings,store:CdcTaskStore,system:ActorSystem[_],
  access:BootstrapJournalAccess,attest:(SnapshotGuardianWire.WorkerAttestationContext,String)=>SnapshotGuardianWire.VerifiedWorkerAttestation,
  wrapSource:(String,Connection)=>Connection=(_,c)=>c) {
  final case class Result(ready:Boolean,readerGate:Option[CdcTaskStore.ReaderRecoveryGate])
  private val url=s"jdbc:mysql://${cfg.source.host}:${cfg.source.port}/${cfg.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000"
  private def open(user:String,password:String)=wrapSource(user,ManagedCdcJdbc.open(url,user,password,"source",managed=true))
  private def closeConfirmed(c:Connection):Unit=try c.close() catch {case NonFatal(e)=>throw uncertain("source-session-close",e)}
  private def source[A](f:(Connection,CaptureControlFence.ProtectedController)=>A):A={
    val observer=open(cfg.sourceObserverUsername.getOrElse(throw new IllegalStateException("observer missing")),cfg.sourceObserverPassword.get)
    try {
      observer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);observer.setAutoCommit(false)
      val writer=open(cfg.source.username,cfg.source.password)
      try {
        writer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);writer.setAutoCommit(false)
        val controller=open(cfg.sourceControllerUsername.getOrElse(throw new IllegalStateException("controller missing")),cfg.sourceControllerPassword.get)
        try {
          controller.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);controller.setAutoCommit(false)
          val protectedController=CaptureControlFence.protectController(observer,controller,cfg.sourceLineage,cfg.source.database,writer)
          f(controller,protectedController)
        } finally closeConfirmed(controller)
      } finally closeConfirmed(writer)
    } finally closeConfirmed(observer)
  }
  private def uncertain(stage:String,cause:Throwable)=new CdcExecutionException(
    ExecutionFailure(ExecutionFailure.ReleaseUnknown,stage,releaseConfirmed=false),cause)

  /** Returns false after durable release while Reader/B proof still needs to advance. */
  def run(p:CdcBRecoveryPermit,b:CaptureFenceBinding,manifestDigest:String,startReader:Boolean):Result={
    require(attest!=null && p.captureId==cfg.id && p.claimedBy.nonEmpty,"managed v2 permit required")
    val attempt=p.claimedBy.get
    val receipt=CaptureFenceReceipt(b,if(p.markerPresent)b.version else Math.addExact(b.version,1L),!p.markerPresent)
    val capture=CdcCaptureFenceVerifier.create(cfg,store)
    val journal=CdcJournalFenceVerifier.create(system,()=>ManagedCdcJdbc.open(CdcJdbc.withTimeouts(cfg.controlState.jdbcUrl),
      cfg.controlState.username,cfg.controlState.password,"control",managed=true))
    def challenge(c:Connection,pc:CaptureControlFence.ProtectedController):String=>SnapshotGuardianWire.VerifiedWorkerAttestation={
      val context=SnapshotGuardianWire.WorkerAttestationContext(attempt,manifestDigest,b.sourceUuid,b.database,
        ManagedCdcJdbc.endpoint(c.getMetaData.getURL),pc.account,p.id,store.fenceBindingDigest(b))
      nonce=>attest(context,nonce)
    }
    def existingDigest=store.receiptDigestForPermit(p.id,p.taskId)
    var digest=existingDigest
    val initial=Await.result(access.read(),10.seconds)
    if(p.consumed && initial.phase=="QUEUED" && digest.exists(d=>initial.recoveryPermit.contains(p.id) &&
      initial.recoveryReceiptDigest.contains(d) && store.fenceRecoveryReady(p.id,p.taskId,d,initial.executionId,capture)))return Result(true,None)
    // Resume a release already committed on the source before a prior crash.
    val released=digest.exists {d=>source {(c,pc)=>
      if(pc.matchesReleased(receipt,p.id,d)){
        val evidence=Await.result(access.persistedRecoveryReceipt(),10.seconds).getOrElse(throw new IllegalStateException("pending Journal evidence missing"))
        if(!store.fenceReleaseRecorded(p.id,p.taskId,d,attempt))
          pc.withVerifiedRelease(receipt,p.id,d) {proof=>store.recordFenceReleased(p.id,p.taskId,d,evidence,proof,challenge(c,pc),capture,journal)}
        true
      }else false
    }}
    if(!released){
      if(!p.consumed){
        var committing=false
        try source {(c,pc)=>
          val found=pc.observe()
          require(found.phase=="FENCED" && found.epoch==b.epoch && found.ownerToken==b.ownerToken &&
            found.operation==b.operation && found.action==b.action && found.table==b.table && found.generation==b.generation,
            "source fence identity changed")
          if(found.version==b.version){
            pc.withVerifiedFence(b,p.markerPresent) {sourceProof=>
              store.withFenceMarkerWriteAuthorization(p.id,p.taskId,sourceProof,challenge(c,pc),capture) {authorization=>
                require(pc.write(b,p.markerPresent,authorization)==receipt,"source marker result differs from intent")
                committing=true;c.commit()
              }
            }
          }else require(found.version==receipt.versionAfter && pc.matches(receipt),"source marker version changed")
        } catch {
          case e:CdcExecutionException if !e.failure.releaseConfirmed=>throw e
          case NonFatal(error)=>
          val committed=committing && (try source {(_,pc)=>pc.matches(receipt)}catch{case NonFatal(_)=>false})
          if(!committed)throw uncertain("source-marker-commit",error)
        }
        digest=Some(source {(c,pc)=>pc.withVerifiedReceipt(receipt) {proof=>
          store.recordFenceReceipt(p.id,p.taskId,proof,challenge(c,pc),capture)
        }})
      }
      val d=digest.getOrElse(throw new IllegalStateException("durable receipt missing"))
      val previous=Await.result(access.read(),10.seconds)
      if(previous.phase!="RECOVERY_PENDING"){
        val answer=Await.result(access.resumePendingBWithReceipt(previous.revision,previous.executionId,previous.definition,p.id,d),10.seconds)
        require(answer=="ACCEPTED","Journal recovery binding rejected")
      }
      val evidence=Await.result(access.persistedRecoveryReceipt(),10.seconds).getOrElse(throw new IllegalStateException("pending Journal evidence missing"))
      require(evidence.permitId==p.id && evidence.receiptDigest==d,"Journal receipt changed")
      var releaseCommitting=false
      try source {(c,pc)=>pc.withVerifiedReceipt(receipt) {proof=>
        val fresh=challenge(c,pc)
        if(!p.consumed){
          try require(store.consumeFencePermit(p.id,p.taskId,d,evidence,proof,fresh,capture,journal),"permit consume rejected")
          catch {case e:CdcExecutionException if e.failure.code==ExecutionFailure.CommitUnknown && e.failure.releaseConfirmed=>
            if(!store.consumedFenceReceipt(p.id,p.taskId,d,attempt))throw e
          }
        }
        store.withConsumedFencePermit(p.id,p.taskId,d,evidence,proof,fresh,capture,journal) {consumed=>
          pc.release(receipt,consumed);releaseCommitting=true;c.commit()
        }
      }} catch {
        case e:CdcExecutionException if !e.failure.releaseConfirmed=>throw e
        case NonFatal(error)=>
        val committed=releaseCommitting && (try source {(_,pc)=>pc.matchesReleased(receipt,p.id,d)}catch{case NonFatal(_)=>false})
        if(!committed)throw uncertain("source-release-commit",error)
      }
      source {(c,pc)=>pc.withVerifiedRelease(receipt,p.id,d) {proof=>
        store.recordFenceReleased(p.id,p.taskId,d,evidence,proof,challenge(c,pc),capture,journal)
      }}
    }
    val d=digest.getOrElse(throw new IllegalStateException("release receipt digest missing"))
    val state=Await.result(access.read(),10.seconds)
    val gate=if(startReader){
      val evidence=Await.result(access.persistedRecoveryReceipt(),10.seconds).getOrElse(throw new IllegalStateException("pending Journal evidence missing"))
      Some(source {(c,pc)=>pc.withVerifiedRelease(receipt,p.id,d) {proof=>
        require(store.fenceReleaseRecorded(p.id,p.taskId,d,attempt),"source release confirmation missing")
        val fresh=challenge(c,pc)
        store.readerRecoveryGate(p.id,p.taskId,p.captureId,d,evidence,fresh,capture,journal,
          use=>source {(_,next)=>next.withVerifiedRelease(receipt,p.id,d)(use)})
      }})
    }else None
    if(!store.fenceRecoveryReady(p.id,p.taskId,d,state.executionId,capture)){
      advanceReadyProof(p,d,state.executionId,capture)
      if(!store.fenceRecoveryReady(p.id,p.taskId,d,state.executionId,capture))return Result(false,gate)
    }
    if(state.phase=="RECOVERY_PENDING"){
      val answer=Await.result(access.completeRecoveryReceipt(state.revision,p.id,d),10.seconds)
      require(answer=="ACCEPTED","Journal recovery completion rejected")
    }else require(state.recoveryPermit.contains(p.id) && state.recoveryReceiptDigest.contains(d) && state.phase=="QUEUED",
      "Journal recovery state changed")
    Result(true,gate)
  }
  /** Advance at most one page. A changed D creates a distinct, retained proof generation. */
  private def advanceReadyProof(p:CdcBRecoveryPermit,digest:String,execution:String,capture:CdcCaptureFenceVerifier):Unit={
    val openState=()=>ManagedCdcJdbc.open(CdcJdbc.withTimeouts(cfg.sdkState.jdbcUrl),cfg.sdkState.username,cfg.sdkState.password,"control",managed=true)
    val log=new MySQLCaptureStore(openState,cfg.id,cfg.sourceLineage,cfg.maxLogBytes)
    val pages=new CaptureAckPaging(openState,log)
    try store.withReadyProofState(p.id,p.taskId,digest,execution,capture) {(c,old,currentD)=>
      log.pendingBDescriptorOn(c,currentD,p.taskId).filter(d=>d.status=="CAPTURE_READY" && d.barrier.nonEmpty).foreach {descriptor=>
        val identity=old.identity.copy(offset=currentD,head=descriptor.head,anchor=descriptor.anchor,intent=descriptor.intentDigest,
          priorHead=Some(old.identity.head),priorDigest=Some(old.headDigest))
        pages.beginOn(c,identity)
        val state=pages.statusOn(c,identity)
        if(!state.done)pages.stepOn(c,identity,state.revision,128)
      }
    } catch {case e:CdcExecutionException if e.failure.code==ExecutionFailure.CommitUnknown && e.failure.releaseConfirmed=>
      // Retry will read the same immutable identity/revision under state locks.
      ()
    }
  }
}
