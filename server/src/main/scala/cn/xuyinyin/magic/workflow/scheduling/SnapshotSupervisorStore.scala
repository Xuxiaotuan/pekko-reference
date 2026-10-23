package cn.xuyinyin.magic.workflow.scheduling

import java.sql.{Connection,ResultSet}
import java.util.UUID

/** Control-plane only: never a business Journal/Snapshot payload. */
final case class GuardianBinding(deploymentId:String,hostId:String,bootId:String,publicKey:String) {
  require(Vector(deploymentId,hostId,bootId,publicKey).forall(_.nonEmpty))
  require(deploymentId.length<=255 && hostId.length<=128 && bootId.length<=128)
}
final case class SupervisorControl(guardian:GuardianBinding,bootId:String,epoch:Long)
final case class SnapshotWorkerLaunch(bootId:String,profileHash:String,phase:String,identity:Option[SnapshotOwnerIdentity],operation:Option[String],proof:Option[String])

/** One fixed deployment per host. Unknown tickets retain their slot; no leases or PID adoption. */
final class SnapshotSupervisorStore(val store:TableSchedulingStore,val binding:GuardianBinding,val maxWorkers:Int) {
  require(maxWorkers>0 && maxWorkers<=1024 && store.ownership.nonEmpty)
  import store.{query,update,transaction}
  private val ha=store.ownership.get
  private val hostArgs=Seq(binding.deploymentId,binding.hostId)
  private def host(c:Connection)=query(c,"SELECT * FROM pekko_snapshot_host_control WHERE deployment_id=? AND host_id=?",hostArgs)(r=>
    (GuardianBinding(r.getString("deployment_id"),r.getString("host_id"),r.getString("guardian_boot"),r.getString("public_key")),Option(r.getString("controller_boot")),r.getLong("controller_epoch"),r.getInt("max_workers"))).headOption
  private def checked(c:Connection)={val h=host(c).getOrElse(throw new IllegalStateException("guardian deployment not enrolled"));require(h._1==binding && h._4==maxWorkers,"guardian identity or capacity policy changed");h}
  def enroll():Unit=transaction { c=>
    host(c) match {
      case Some(_)=>checked(c);()
      case None=>update(c,"INSERT INTO pekko_snapshot_host_control (deployment_id,host_id,guardian_boot,public_key,max_workers,controller_epoch) VALUES (?,?,?,?,?,0)",binding.deploymentId,binding.hostId,binding.bootId,binding.publicKey,maxWorkers);()
    }
  }
  def claimController(boot:String):SupervisorControl=transaction { c=>
    require(boot.nonEmpty && boot.length<=128)
    val h=checked(c);val next=if(h._2.contains(boot))h._3 else Math.addExact(h._3,1L)
    update(c,"UPDATE pekko_snapshot_host_control SET controller_boot=?,controller_epoch=? WHERE deployment_id=? AND host_id=?",boot,next,binding.deploymentId,binding.hostId)
    SupervisorControl(binding,boot,next)
  }
  def bindGuardianProcess(pid:Long,start:String):Unit=transaction { c=>
    checked(c);require(pid>0 && start.nonEmpty)
    val previous=query(c,"SELECT guardian_pid,guardian_start FROM pekko_snapshot_host_control WHERE deployment_id=? AND host_id=?",hostArgs)(r=>Option(r.getObject(1)).map(_=>(r.getLong(1),r.getString(2)))).head
    require(previous.forall(_==((pid,start))),"guardian JVM incarnation changed; old worker control cannot be adopted")
    update(c,"UPDATE pekko_snapshot_host_control SET guardian_pid=?,guardian_start=? WHERE deployment_id=? AND host_id=?",pid,start,binding.deploymentId,binding.hostId);()
  }
  private[scheduling] def bindGuardianCapability(id:String):Unit=transaction { c=>
    checked(c)
    val previous=query(c,"SELECT guardian_capability FROM pekko_snapshot_host_control WHERE deployment_id=? AND host_id=?",hostArgs)(r=>Option(r.getString(1))).head
    require(previous.forall(_==id),"original guardian process-control capability was replaced")
    update(c,"UPDATE pekko_snapshot_host_control SET guardian_capability=? WHERE deployment_id=? AND host_id=?",id,binding.deploymentId,binding.hostId);()
  }
  def current:Option[SupervisorControl]=store.read(c=>{val h=checked(c);h._2.map(SupervisorControl(binding,_,h._3))})
  def fenced[A](control:SupervisorControl)(f: => A):A=transaction { c=>
    val h=checked(c);require(control.guardian==binding && h._2.contains(control.bootId) && h._3==control.epoch,"stale supervisor control epoch");f
  }
  def pause(control:SupervisorControl,reason:String):Unit=fenced(control){store.read(c=>{update(c,"UPDATE pekko_snapshot_host_control SET pause_reason=? WHERE deployment_id=? AND host_id=?",reason.take(255),binding.deploymentId,binding.hostId);()})}
  def clearPause(control:SupervisorControl):Unit=fenced(control){store.read(c=>{update(c,"UPDATE pekko_snapshot_host_control SET pause_reason=NULL WHERE deployment_id=? AND host_id=?",binding.deploymentId,binding.hostId);()})}
  private def decode(r:ResultSet)=SnapshotWorkerLaunch(r.getString("boot_id"),r.getString("profile_hash"),r.getString("phase"),Option(r.getObject("process_id")).map(_=>SnapshotOwnerIdentity(r.getString("boot_id"),r.getString("deployment_id"),r.getLong("process_id"),r.getString("start_identity"))),Option(r.getString("operation_id")),Option(r.getString("signed_proof")))
  private def launch(c:Connection,boot:String)=query(c,"SELECT * FROM pekko_snapshot_worker_launches WHERE boot_id=? AND deployment_id=? AND host_id=? AND guardian_boot=?",Seq(boot,binding.deploymentId,binding.hostId,binding.bootId))(decode).headOption
  def launch(boot:String):Option[SnapshotWorkerLaunch]=store.read(launch(_,boot))
  def pending:Vector[SnapshotWorkerLaunch]=store.read(c=>query(c,"SELECT * FROM pekko_snapshot_worker_launches WHERE deployment_id=? AND host_id=? AND guardian_boot=? AND phase NOT IN ('retired','cancelled') ORDER BY boot_id LIMIT ?",Seq(binding.deploymentId,binding.hostId,binding.bootId,maxWorkers+1))(decode))
  def prepare(control:SupervisorControl,boot:String,profile:String):SnapshotWorkerLaunch=fenced(control){
    require(boot.matches("[A-Za-z0-9-]{1,128}") && profile.nonEmpty && profile.length<=64)
    store.read { c=>
      require(query(c,"SELECT boot_id FROM pekko_snapshot_worker_launches WHERE boot_id=?",Seq(boot))(_.getString(1)).isEmpty,"worker boot cannot be reused")
      require(ha.owner(boot).isEmpty && pending.size<maxWorkers,"worker slots remain charged")
      update(c,"INSERT INTO pekko_snapshot_worker_launches (boot_id,deployment_id,host_id,guardian_boot,profile_hash,phase) VALUES (?,?,?,?,?,'prepared')",boot,binding.deploymentId,binding.hostId,binding.bootId,profile)
      launch(c,boot).get
    }
  }
  def dispatch(control:SupervisorControl,boot:String):SnapshotWorkerLaunch=fenced(control){
    store.read { c=>val t=launch(c,boot).get;require(Set("prepared","dispatching","launched").contains(t.phase),"launch is terminal")
      if(t.phase=="prepared")update(c,"UPDATE pekko_snapshot_worker_launches SET phase='dispatching' WHERE boot_id=?",boot)
      launch(c,boot).get
    }
  }
  /** Guardian inventory is authoritative even if the creating supervisor epoch changed during fork. */
  private[scheduling] def attach(boot:String,id:SnapshotOwnerIdentity):Unit=transaction { c=>
    checked(c);val t=launch(c,boot).get
    require(id.bootId==boot && id.deploymentId==binding.deploymentId && Set("dispatching","launched").contains(t.phase))
    require(t.identity.forall(_==id),"worker launch identity changed")
    update(c,"UPDATE pekko_snapshot_worker_launches SET phase='launched',process_id=?,start_identity=? WHERE boot_id=?",id.pid,id.startIdentity,boot);()
  }
  def cancel(control:SupervisorControl,boot:String):Unit=fenced(control){
    store.read { c=>val t=launch(c,boot).get;require(t.identity.isEmpty && Set("prepared","dispatching","cancelled").contains(t.phase))
      update(c,"UPDATE pekko_snapshot_worker_launches SET phase='cancelled' WHERE boot_id=?",boot);()
    }
  }
  def operation(control:SupervisorControl,boot:String):String=fenced(control){store.read { c=>
    val t=launch(c,boot).get;require(t.phase=="launched")
    val op=t.operation.getOrElse(ha.owner(boot).flatMap(_.operation).getOrElse(UUID.randomUUID().toString))
    update(c,"UPDATE pekko_snapshot_worker_launches SET operation_id=? WHERE boot_id=?",op,boot);op
  }}
  def recordProof(control:SupervisorControl,boot:String,raw:String)(confirm: => Unit):Unit=fenced(control){store.read { c=>
    val t=launch(c,boot).get;require(t.phase=="launched" && t.operation.nonEmpty)
    require(t.proof.forall(_==raw),"isolation proof changed")
    update(c,"UPDATE pekko_snapshot_worker_launches SET signed_proof=? WHERE boot_id=?",raw,boot);confirm
  }}
  def retire(control:SupervisorControl,boot:String):Unit=fenced(control){store.read { c=>
    val t=launch(c,boot).get;val owner=ha.owner(boot)
    require(t.phase=="retired" || (t.phase=="launched" && t.proof.nonEmpty && owner.exists(o=>Set("db_drained","startup_failed").contains(o.phase)) && !ha.hasOutstandingQuota(boot)),"worker retirement is unconfirmed")
    update(c,"UPDATE pekko_snapshot_worker_launches SET phase='retired' WHERE boot_id=?",boot);()
  }}
  def verifyWorker(boot:String,id:SnapshotOwnerIdentity,profile:String):Unit=store.read { c=>
    checked(c);val t=launch(c,boot).getOrElse(throw new IllegalArgumentException("missing managed launch ticket"))
    require(t.phase=="launched" && t.identity.contains(id) && t.profileHash==profile && t.operation.isEmpty,"worker launch ticket is not authorized")
  }
}
