package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.workflow.scheduling.SnapshotSupervisorSettings
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import java.nio.file.Path

/** Restricted to a single outstanding snapshot grant for one anchored B. */
private[cdc] object CdcManagedRecovery {
  def recover(store:CdcTaskStore,settings:CdcPlatformSettings,supervisorConfig:Path,guardianCheck:()=>Unit):Unit={
    guardianCheck()
    store.ownerAttempt.filter(_._2!="STOPPED").foreach {case(attempt,_)=>
      val managed=store.managedOwner(attempt).getOrElse(throw new IllegalStateException("old CDC owner has no managed binding"))
      val manifest=store.managedManifest(attempt).getOrElse(throw new IllegalStateException("old CDC resource manifest missing"))
      val supervisor=new SnapshotSupervisorSettings(supervisorConfig)
      try {
        val ticket=supervisor.metadata.launch(managed.bootId).getOrElse(throw new IllegalStateException("old guardian ticket missing"))
        val ownership=supervisor.tableStore.ownership.get
        val old=ownership.owner(managed.bootId).getOrElse(throw new IllegalStateException("old guardian owner missing"))
        val inventory=ownership.instances(managed.bootId).map {case(endpoint,uuid,_)=>
          endpoint->(uuid,ownership.databaseUsers(managed.bootId,endpoint))
        }.toMap
        val proof=CdcManagedIsolation.verify(managed,manifest,supervisor.binding,ticket,old,inventory)
        guardianCheck()
        store.soleOutstandingTask(attempt) match {
          case Some(task)=>
            val cfg=settings.captures.find(_.id==task.captureId).getOrElse(throw new IllegalStateException("capture configuration missing"))
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
          case None=>require(store.releaseEmptyDrainedOwner(attempt,proof,System.currentTimeMillis()),"old CDC grants or permits remain")
        }
      } finally supervisor.close()
    }
    guardianCheck()
  }
}
