package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.workflow.scheduling.SnapshotSupervisorSettings
import cn.xuyinyin.magic.workflow.scheduling.SnapshotOwnerJdbc
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureAckPage,CaptureAckPageIdentity,CaptureAckPaging,CaptureControlFence,CaptureFenceBinding,CaptureFenceObservation,MySQLCaptureStore}
import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import java.nio.file.Path
import java.sql.Connection
import java.security.SecureRandom
import scala.util.control.NonFatal

/** Restricted to a single outstanding snapshot grant for one anchored B. */
private[cdc] object CdcManagedRecovery {
  /**
    * Dormant until the Reader and permit consumer enforce v2 end to end.
    * The returned candidate must still be atomically turned into a permit;
    * failures leave the durable source fence in place for a later retry.
    */
  private def fencePendingB(store:CdcTaskStore,cfg:CdcCaptureSettings,attempt:String,taskId:String,
    isolation:CdcManagedIsolation.Verified,guardianCheck:()=>Unit):CdcManagedPendingB.Verified={
    require(cfg.sourceFenceV2 && isolation!=null,"guardian and source fencing required")
    guardianCheck()
    CdcManagedPendingB.requireSourceIsolation(cfg,isolation)
    // This first observation supplies identity for an intent only, never authority to release a grant.
    val candidate=CdcManagedPendingB.candidateForIntent(cfg,taskId)
    val url=s"jdbc:mysql://${cfg.source.host}:${cfg.source.port}/${cfg.source.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000"
    def closeConfirmed(c:Connection):Unit=try c.close() catch {case NonFatal(e)=>
      throw new CdcExecutionException(ExecutionFailure(ExecutionFailure.ReleaseUnknown,"source-fence-session-close",releaseConfirmed=false),e)
    }
    def onProtected[A](f:(Connection,CaptureControlFence.ProtectedController)=>A):A={
      val observer=SnapshotOwnerJdbc.open(url,cfg.sourceObserverUsername.getOrElse(throw new IllegalStateException("source observer missing")),
        cfg.sourceObserverPassword.getOrElse(throw new IllegalStateException("source observer password missing")),"source",managed=true)
      try {
        observer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);observer.setAutoCommit(false)
        val writer=SnapshotOwnerJdbc.open(url,cfg.source.username,cfg.source.password,"source",managed=true)
        try {
          writer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);writer.setAutoCommit(false)
          CaptureControlFence.protect(observer,writer,cfg.sourceLineage,cfg.source.database)
          val controller=SnapshotOwnerJdbc.open(url,cfg.sourceControllerUsername.getOrElse(throw new IllegalStateException("source controller missing")),
            cfg.sourceControllerPassword.getOrElse(throw new IllegalStateException("source controller password missing")),"source",managed=true)
          try {
            controller.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);controller.setAutoCommit(false)
            val protectedController=CaptureControlFence.protectController(observer,controller,cfg.sourceLineage,cfg.source.database,writer)
            f(controller,protectedController)
          } finally closeConfirmed(controller)
        } finally closeConfirmed(writer)
      } finally closeConfirmed(observer)
    }
    def exact(o:CaptureFenceObservation,b:CaptureFenceBinding):Boolean=
      o.sourceUuid==b.sourceUuid && o.database==b.database && o.epoch==b.epoch && o.ownerToken==b.ownerToken &&
        o.phase=="FENCED" && o.version==b.version && o.operation==b.operation && o.action==b.action &&
        o.table==b.table && o.generation==b.generation
    val existing=store.fenceIntent(attempt)
    var binding:Option[CaptureFenceBinding]=existing
    var commitStarted=false
    try onProtected {(controller,protectedController)=>
      guardianCheck()
      val current=protectedController.observe()
      val selected=existing.getOrElse {
        require(current.phase=="OPEN","source fence is not open")
        val bytes=new Array[Byte](32);new SecureRandom().nextBytes(bytes)
        CaptureFenceBinding(cfg.sourceLineage,cfg.source.database,Math.addExact(current.epoch,1L),
          bytes.map(b=>f"${b & 0xff}%02x").mkString,current.version,MySQLCdcGroup.hash(cfg.id+"|B|"+taskId),
          "SUBSCRIBE",candidate.table,candidate.generation)
      }
      // Commit this exact intent before the source transaction can be committed.
      store.recordFenceIntent(attempt,isolation,candidate,selected)
      binding=Some(selected)
      guardianCheck()
      protectedController.acquire(selected)
      commitStarted=true
      controller.commit()
    } catch {
      case e:CdcExecutionException if !e.failure.releaseConfirmed=>throw e
      case NonFatal(error)=>
      // A commit reply may be lost. Only a fresh transaction seeing the entire
      // persisted binding proves that the source commit took effect.
      val confirmed=commitStarted && binding.exists {b=>
        try onProtected {(controller,protectedController)=>
          val result=exact(protectedController.observe(),b)
          controller.rollback();result
        } catch {case NonFatal(_)=>false}
      }
      if(!confirmed)throw error
    }
    guardianCheck()
    val verified=scanFencedControls(store,cfg,attempt,taskId,binding.get,isolation,candidate,guardianCheck)
    guardianCheck()
    verified
  }
  /** Each page and its durable cursor are checked while the same source fence is locked. */
  private def scanFencedControls(store:CdcTaskStore,cfg:CdcCaptureSettings,attempt:String,taskId:String,
    binding:CaptureFenceBinding,isolation:CdcManagedIsolation.Verified,candidate:CdcManagedPendingB.Verified,
    guardianCheck:()=>Unit):CdcManagedPendingB.Verified={
    def same(v:CdcManagedPendingB.Verified):Boolean=v.offset==candidate.offset && v.markerPresent==candidate.markerPresent &&
      v.table==candidate.table && v.generation==candidate.generation && v.taskId==candidate.taskId && v.captureId==candidate.captureId
    def matches(s:CdcFenceScan,v:CdcManagedPendingB.Verified):Boolean=s.attempt==attempt && s.taskId==v.taskId &&
      s.captureId==v.captureId && s.offset==v.offset && s.markerPresent==v.markerPresent && s.binding==binding
    var finished=false
    var last:Option[CdcManagedPendingB.Verified]=None
    val openState=()=>SnapshotOwnerJdbc.open(CdcJdbc.withTimeouts(cfg.sdkState.jdbcUrl),cfg.sdkState.username,cfg.sdkState.password,"control",managed=true)
    val log=new MySQLCaptureStore(openState,cfg.id,cfg.sourceLineage,cfg.maxLogBytes)
    val pages=new CaptureAckPaging(openState,log)
    pages.initialize()
    while(!finished){
      guardianCheck()
      val v=CdcManagedPendingB.withFencedCandidate(cfg,taskId,binding,isolation) { (locked,source,descriptor)=>
        require(same(locked),"pending B changed after source fence intent")
        val ack=CaptureAckPageIdentity(taskId,locked.offset,descriptor.head,descriptor.anchor,descriptor.intentDigest,
          store.fenceBindingDigest(binding),attempt)
        var expectedAck:Option[CaptureAckPage]=None
        val ackDone=try store.withFenceState(attempt,isolation,locked,binding) {c=>
          require(log.pendingBDescriptorOn(c,locked.offset,taskId).contains(descriptor),"B descriptor changed")
          pages.beginOn(c,ack)
          val state=pages.statusOn(c,ack)
          val next=if(state.done)state else pages.stepOn(c,ack,state.revision,128)
          expectedAck=Some(next)
          if(next.done)log.completeModeOn(c,taskId,descriptor,ack,pages)
          next.done
        } catch {case e:CdcExecutionException if e.failure.code==ExecutionFailure.CommitUnknown && e.failure.releaseConfirmed=>
          val expected=expectedAck.getOrElse(throw e)
          CaptureControlFence.lock(source,binding)
          store.withFenceState(attempt,isolation,locked,binding) {c=>
            require(log.pendingBDescriptorOn(c,locked.offset,taskId).contains(descriptor),"B descriptor changed")
            val observed=pages.statusOn(c,ack)
            require(observed==expected,"ACK proof commit result unknown")
            if(observed.done)log.completeModeOn(c,taskId,descriptor,ack,pages)
            observed.done
          }
        }
        val before=store.scanProgress(attempt)
        before.foreach(s=>require(matches(s,locked),"source scan evidence changed"))
        if(!ackDone)()
        else if(before.exists(_.done))finished=true
        else {
          val cursor=before.map(_.cursor).getOrElse("")
          val query=if(cursor.isEmpty)"SELECT operation_id,action,table_name,generation FROM pekko_capture_control ORDER BY BINARY operation_id LIMIT 128"
            else "SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE BINARY operation_id > BINARY ? ORDER BY BINARY operation_id LIMIT 128"
          val q=source.prepareStatement(query)
          q.setQueryTimeout(5);if(cursor.nonEmpty)q.setString(1,cursor)
          val values=try {val r=q.executeQuery();val b=Vector.newBuilder[(String,String,String,String)]
            try while(r.next())b+=((r.getString(1),r.getString(2),r.getString(3),r.getString(4)))finally r.close();b.result()
          }finally q.close()
          require(values.forall(v=>v._1!=null && v._1.matches("[a-f0-9]{64}")) &&
            values.map(_._1).sliding(2).forall(p=>p.size<2 || p.head.compareTo(p.last)<0) &&
            values.headOption.forall(_._1.compareTo(cursor)>0),"invalid source control ordering")
          val next=values.lastOption.map(_._1).getOrElse(cursor);val done=values.size<128
          guardianCheck()
          try store.withFenceState(attempt,isolation,locked,binding) {c=>
            require(log.controlsBoundOn(c,values,taskId,descriptor,ack,pages),"source control contains an unbound or uncovered marker")
            store.advanceFenceScanOn(c,attempt,isolation,locked,binding,cursor,next,done)
          }
          catch {case e:CdcExecutionException if e.failure.code==ExecutionFailure.CommitUnknown && e.failure.releaseConfirmed=>
            val committed=store.scanProgress(attempt).exists(s=>matches(s,locked) && s.previousCursor==cursor && s.cursor==next && s.done==done)
            if(!committed)throw e
          }
          finished=done
        }
      }
      last=Some(v)
    }
    last.getOrElse(throw new IllegalStateException("source scan did not run"))
  }
  /** A lost task-store COMMIT reply is resolved only by a full v2 readback. */
  private def issueFencedPermit(store:CdcTaskStore,cfg:CdcCaptureSettings,attempt:String,proof:CdcManagedIsolation.Verified,
    pending:CdcManagedPendingB.Verified,guardianCheck:()=>Unit):Unit={
    val fence=pending.sourceFence.getOrElse(throw new IllegalArgumentException("source fence proof missing"))
    CdcManagedPendingB.withFencedCandidate(cfg,pending.taskId,fence,proof) {(locked,connection,boundProof)=>
      require(locked.offset==pending.offset && locked.markerPresent==pending.markerPresent &&
        locked.table==pending.table && locked.generation==pending.generation,"pending B changed before permit commit")
      require(store.scanProgress(attempt).exists(s=>s.done && s.binding==fence && s.taskId==locked.taskId && s.captureId==locked.captureId &&
        s.offset==locked.offset && s.markerPresent==locked.markerPresent),"source control scan incomplete or changed")
      guardianCheck()
      try store.releaseForPendingB(attempt,proof,locked,System.currentTimeMillis(),fence)
      catch {case error:CdcExecutionException if error.failure.code==ExecutionFailure.CommitUnknown && error.failure.releaseConfirmed=>
        val readback=store.versionedPermitForOldOwner(attempt)
        val committed=store.ownerAttempt.contains(attempt->"STOPPED") && readback.exists {case(p,actual)=>
          actual==fence && p.taskId==locked.taskId && p.captureId==locked.captureId && p.offset==locked.offset &&
            p.markerPresent==locked.markerPresent && p.table==locked.table && p.generation==locked.generation &&
            p.operation==proof.operationId && p.proof==proof.signedProof
        }
        if(!committed)throw error
      }
    }
  }
  def recover(store:CdcTaskStore,settings:CdcPlatformSettings,supervisorConfig:Path,guardianCheck:()=>Unit,allowPendingB:Boolean=true):Unit={
    guardianCheck()
    store.ownerAttempt.filter(_._2!="STOPPED").foreach {case(attempt,_)=>
      val managed=store.managedOwner(attempt).getOrElse(throw new IllegalStateException("old CDC owner has no managed binding"))
      val manifest=store.managedManifest(attempt).getOrElse(throw new IllegalStateException("old CDC resource manifest missing"))
      val supervisor=new SnapshotSupervisorSettings(supervisorConfig)
      try {
        val ticket=supervisor.metadata.launch(managed.bootId).getOrElse(throw new IllegalStateException("old guardian ticket missing"))
        val ownership=supervisor.metadata.ownerRegistry
        val old=ownership.owner(managed.bootId).getOrElse(throw new IllegalStateException("old guardian owner missing"))
        val inventory=ownership.instances(managed.bootId).map {case(endpoint,uuid,_)=>
          endpoint->(uuid,ownership.databaseUsers(managed.bootId,endpoint))
        }.toMap
        val proof=CdcManagedIsolation.verify(managed,manifest,supervisor.binding,ticket,old,inventory)
        guardianCheck()
        // Re-entering the initial B proof would discard the owner binding of an
        // existing receipt and Journal. The replacement must first be delegated.
        if(store.hasClaimedVersionedFence(attempt)){
          store.freezeVersionedOwner(attempt,proof,System.currentTimeMillis())
          throw new IllegalStateException("versioned recovery owner delegation required")
        }
        store.soleOutstandingTask(attempt) match {
          case Some(task)=>
            require(allowPendingB,"pending B recovery is not admitted for standalone managed workers")
            val cfg=settings.captures.find(_.id==task.captureId).getOrElse(throw new IllegalStateException("capture configuration missing"))
            if(cfg.sourceFenceV2){
              val pending=fencePendingB(store,cfg,attempt,task.taskId,proof,guardianCheck)
              issueFencedPermit(store,cfg,attempt,proof,pending,guardianCheck)
            }else {
              val pending=CdcManagedPendingB.verify(cfg,task.taskId)
              guardianCheck()
              try store.releaseForPendingB(attempt,proof,pending,System.currentTimeMillis())
              catch {case error:CdcExecutionException if error.failure.code==ExecutionFailure.CommitUnknown && error.failure.releaseConfirmed=>
                val readback=store.permitForOldOwner(attempt)
                val committed=store.ownerAttempt.contains(attempt->"STOPPED") && readback.exists(p=>
                  p.taskId==pending.taskId && p.captureId==pending.captureId && p.offset==pending.offset && p.markerPresent==pending.markerPresent &&
                    p.table==pending.table && p.generation==pending.generation && p.operation==proof.operationId && p.proof==proof.signedProof)
                if(!committed)throw error
              }
            }
          case None=>require(store.releaseEmptyDrainedOwner(attempt,proof,System.currentTimeMillis()),"old CDC grants or permits remain")
        }
      } finally supervisor.close()
    }
    guardianCheck()
  }
}
