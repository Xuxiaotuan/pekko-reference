package cn.xuyinyin.magic.cdc.control



import cn.xuyinyin.magic.cdc.control.{CutoverAuthorizationKey, CredentialBinding}
import javax.sql.DataSource
import java.sql.ResultSet

/** Control-plane persistence for a completed, externally proven cutover. */
private[control] final class SnapshotCutoverStore(val store:ManagedControlTransactions) {
  import store.{query,update,transaction}
  private def decodeAuthorization(r:ResultSet)=CutoverAuthorizationKey(
    r.getString("deployment_id"),r.getString("host_id"),r.getString("operation_id"),r.getString("old_owner_id"),
    r.getLong("old_manifest"),r.getString("policy_hash"),r.getString("isolation_proof_digest"),r.getString("account_drain_digest"))
  private def decodeBinding(r:ResultSet)=CredentialBinding(
    r.getString("workflow_id"),r.getString("execution_id"),r.getString("node_id"),r.getString("node_role"),
    r.getString("permit_id"),r.getString("owner_id"),r.getString("operation_id"),r.getString("endpoint"),
    r.getString("database_name"),r.getString("legacy_user"),r.getString("runtime_user"),r.getString("password_env"),
    r.getString("definition_hash"),r.getString("phase"))
  def authorization(key:CutoverAuthorizationKey):Option[CutoverAuthorizationKey]=store.read { c=>
    query(c,"SELECT * FROM pekko_snapshot_cutover_authorizations WHERE deployment_id=? AND host_id=? AND operation_id=?",
      Seq(key.deploymentId,key.hostId,key.operationId))(decodeAuthorization).headOption
  }
  def authorizationByOperation(deploymentId:String,hostId:String,operationId:String):Option[CutoverAuthorizationKey]=store.read { c=>
    query(c,"SELECT * FROM pekko_snapshot_cutover_authorizations WHERE deployment_id=? AND host_id=? AND operation_id=? AND phase='COMMITTED'",
      Seq(deploymentId,hostId,operationId))(decodeAuthorization).headOption
  }
  def binding(workflowId:String,executionId:String,nodeId:String,role:String):Option[CredentialBinding]=store.read { c=>
    query(c,"SELECT * FROM pekko_snapshot_credential_bindings WHERE workflow_id=? AND execution_id=? AND node_id=? AND node_role=? AND phase='ACTIVE'",
      Seq(workflowId,executionId,nodeId,role))(decodeBinding).headOption
  }
  def recordAuthorization(key:CutoverAuthorizationKey):Unit=transaction { c=>
    val existing=query(c,"SELECT * FROM pekko_snapshot_cutover_authorizations WHERE deployment_id=? AND host_id=? AND operation_id=?",
      Seq(key.deploymentId,key.hostId,key.operationId))(decodeAuthorization).headOption
    existing match {
      case Some(value)=>require(value==key,"cutover authorization conflicts with durable evidence")
      case None=>
        update(c,"INSERT INTO pekko_snapshot_cutover_authorizations (deployment_id,host_id,operation_id,old_owner_id,old_manifest,policy_hash,isolation_proof_digest,account_drain_digest,phase,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
          key.deploymentId,key.hostId,key.operationId,key.oldOwnerId,key.oldManifest,key.policyHash,key.isolationProofDigest,key.accountDrainDigest,"COMMITTED",System.currentTimeMillis())
    }
  }
  def recordBinding(binding:CredentialBinding,authorization:CutoverAuthorizationKey):Unit=transaction { c=>
    val auth=query(c,"SELECT * FROM pekko_snapshot_cutover_authorizations WHERE deployment_id=? AND host_id=? AND operation_id=? AND phase='COMMITTED'",
      Seq(authorization.deploymentId,authorization.hostId,authorization.operationId))(decodeAuthorization).headOption
    require(auth.contains(authorization),"cutover authorization is not committed")
    require(binding.operationId==authorization.operationId,"credential operation differs from cutover authorization")
    val permit=query(c,"SELECT p.owner_id,p.execution_id,p.permit_id,p.phase,t.phase,t.grant_id,t.owner_id FROM pekko_snapshot_writer_permissions p JOIN pekko_table_tasks t ON t.workflow_id=p.workflow_id WHERE p.workflow_id=? AND p.execution_id=? AND p.permit_id=?",
      Seq(binding.workflowId,binding.executionId,binding.permitId))(r=>(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7))).headOption
    require(permit.nonEmpty,"credential binding requires a durable recovery permit")
    val row=permit.get
    require(row._1==binding.ownerId && row._2==binding.executionId && row._3==binding.permitId &&
      Set("recovering","active").contains(row._4) && Set("recovering","running").contains(row._5) && row._6!=null && row._7==binding.ownerId,
      "credential binding permit is not active for this owner")
    val existing=query(c,"SELECT * FROM pekko_snapshot_credential_bindings WHERE workflow_id=? AND execution_id=? AND node_id=? AND node_role=?",
      Seq(binding.workflowId,binding.executionId,binding.nodeId,binding.nodeRole))(decodeBinding).headOption
    existing match {
      case Some(value)=>require(value==binding,"credential binding conflicts with durable permit")
      case None=>update(c,"INSERT INTO pekko_snapshot_credential_bindings (workflow_id,execution_id,node_id,node_role,permit_id,owner_id,operation_id,endpoint,database_name,legacy_user,runtime_user,password_env,definition_hash,phase,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        binding.workflowId,binding.executionId,binding.nodeId,binding.nodeRole,binding.permitId,binding.ownerId,binding.operationId,binding.endpoint,binding.databaseName,binding.legacyUser,binding.runtimeUser,binding.passwordEnv,binding.definitionHash,binding.phase,System.currentTimeMillis())
    }
  }
}
