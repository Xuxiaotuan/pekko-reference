package cn.xuyinyin.magic.workflow.scheduling

import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import spray.json._

/** Durable intent for one exact account lock; it is not an isolation or drain certificate. */
private[scheduling] object LegacyAccountLockIntent {
  final case class Plan(operationId:String,serverUuid:String,account:LegacyMySqlAccountLock.Account,protectedUsers:Set[String],
    trustedProxyPrincipals:Set[LegacyMySqlAccountLock.Account]=Set.empty) {
    require(operationId.matches("[A-Za-z0-9-]{1,128}") && serverUuid.nonEmpty && serverUuid.length<=128)
    require(protectedUsers.nonEmpty && protectedUsers.size<=64 && !protectedUsers(account.user))
    require(protectedUsers.forall(u=>u.matches("[A-Za-z0-9_]{1,32}")))
    require(trustedProxyPrincipals.size<=64 && !trustedProxyPrincipals(account))
    private[scheduling] val digest:String={
      val users=JsArray(protectedUsers.toVector.sorted.map(JsString(_)))
      val value=if(trustedProxyPrincipals.isEmpty)users else JsArray(users,
        JsArray(trustedProxyPrincipals.toVector.sortBy(p=>(p.user,p.host)).map(p=>JsArray(JsString(p.user),JsString(p.host)))))
      val bytes=value.compactPrint.getBytes(StandardCharsets.UTF_8)
      MessageDigest.getInstance("SHA-256").digest(bytes).map(b=>f"${b & 0xff}%02x").mkString
    }
  }
  final case class State(plan:Plan,phase:String)
}

/** Uses the existing inter-version lock for the intent; never stores database credentials. */
private[scheduling] final class LegacyAccountLockIntent(metadata:SnapshotSupervisorStore) {
  import LegacyAccountLockIntent._
  private val db=metadata.store
  private def read(plan:Plan):Option[String]=db.read { c=>
    val b=metadata.binding
    val rows=db.query(c,"SELECT operation_id,server_uuid,legacy_user,legacy_host,protected_digest,phase FROM pekko_legacy_account_lock_intent WHERE deployment_id=? AND host_id=?",Seq(b.deploymentId,b.hostId)) { r=>
      (r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6))
    }
    rows.headOption.map { row=>
      require(row._1==plan.operationId && row._2==plan.serverUuid && row._3==plan.account.user && row._4==plan.account.host && row._5==plan.digest,"legacy account intent conflicts with existing operation")
      require(Set("INTENT","ACCOUNT_LOCK_OBSERVED")(row._6),"unknown legacy account intent state")
      row._6
    }
  }
  def prepare(control:SupervisorControl,plan:Plan):State=metadata.fenced(control) {
    val phase=read(plan).getOrElse {
      db.read { c=>
        val b=metadata.binding
        db.update(c,"INSERT INTO pekko_legacy_account_lock_intent (deployment_id,host_id,operation_id,server_uuid,legacy_user,legacy_host,protected_digest,phase) VALUES (?,?,?,?,?,?,?,'INTENT')",
          b.deploymentId,b.hostId,plan.operationId,plan.serverUuid,plan.account.user,plan.account.host,plan.digest)
      };"INTENT"
    }
    State(plan,phase)
  }
  /** Observation can become stale immediately; it must never authorize cutover or resource release. */
  def recordAccountLockObservation(control:SupervisorControl,plan:Plan,url:String,adminUser:String,adminPassword:String):State={
    // Commit the intent first: MySQL account DDL can commit even if the response is lost.
    prepare(control,plan)
    metadata.fenced(control) {
      val phase=read(plan).getOrElse(throw new IllegalStateException("legacy account intent missing"))
      // Keep the control epoch fenced while the dedicated, bounded DDL connection is in use.
      LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,plan.serverUuid,plan.account,plan.protectedUsers,plan.trustedProxyPrincipals)
      if(phase=="INTENT")db.read { c=>
        val b=metadata.binding
        require(db.update(c,"UPDATE pekko_legacy_account_lock_intent SET phase='ACCOUNT_LOCK_OBSERVED' WHERE deployment_id=? AND host_id=? AND operation_id=? AND phase='INTENT'",b.deploymentId,b.hostId,plan.operationId)==1)
      }
      State(plan,"ACCOUNT_LOCK_OBSERVED")
    }
  }
  /** Revalidate under the controller epoch; this observation is not a recovery permit. */
  def verifyDrain(control:SupervisorControl,plan:Plan,url:String,adminUser:String,adminPassword:String):Unit={
    metadata.fenced(control) {
      require(read(plan).contains("ACCOUNT_LOCK_OBSERVED"),"legacy account lock has not been observed")
      LegacyMySqlAccountLock.lock(url,adminUser,adminPassword,plan.serverUuid,plan.account,plan.protectedUsers,plan.trustedProxyPrincipals)
      LegacyMySqlAccountLock.verifyDrain(url,adminUser,adminPassword,plan.serverUuid,plan.account,plan.trustedProxyPrincipals)
    }
  }
}
