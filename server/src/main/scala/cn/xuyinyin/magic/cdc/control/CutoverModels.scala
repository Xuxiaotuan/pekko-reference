package cn.xuyinyin.magic.cdc.control

/** Durable cutover identity. Passwords and mutable process details never belong here. */
final case class CutoverAuthorizationKey(deploymentId:String,hostId:String,operationId:String,oldOwnerId:String,
  oldManifest:Long,policyHash:String,isolationProofDigest:String,accountDrainDigest:String) {
  require(deploymentId.nonEmpty && hostId.nonEmpty && operationId.nonEmpty && oldOwnerId.nonEmpty)
  require(oldManifest>=0 && policyHash.matches("[0-9a-f]{64}") && isolationProofDigest.matches("[0-9a-f]{64}") && accountDrainDigest.matches("[0-9a-f]{64}"))
}

/** Runtime credentials are selected by this durable tuple; the secret is resolved only from passwordEnv. */
final case class CredentialBinding(workflowId:String,executionId:String,nodeId:String,nodeRole:String,permitId:String,
  ownerId:String,operationId:String,endpoint:String,databaseName:String,legacyUser:String,runtimeUser:String,
  passwordEnv:String,definitionHash:String,phase:String) {
  require(workflowId.nonEmpty && executionId.nonEmpty && nodeId.nonEmpty && Set("source","sink","target").contains(nodeRole))
  require(permitId.nonEmpty && ownerId.nonEmpty && operationId.nonEmpty && endpoint.nonEmpty && databaseName.nonEmpty)
  require(legacyUser.nonEmpty && runtimeUser.nonEmpty && passwordEnv.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") && definitionHash.matches("[0-9a-f]{64}"))
  require(phase=="ACTIVE")
}

/** Non-secret worker configuration used to materialize a binding after permit acquisition. */
final case class CredentialBindingPlan(workflowId:String,nodeId:String,nodeRole:String,operationId:String,hostId:String,
  endpoint:String,databaseName:String,legacyUser:String,runtimeUser:String,passwordEnv:String,definitionHash:String) {
  require(workflowId.nonEmpty && nodeId.nonEmpty && Set("source","sink","target").contains(nodeRole) && operationId.nonEmpty && hostId.nonEmpty)
  require(endpoint.nonEmpty && databaseName.nonEmpty && legacyUser.nonEmpty && runtimeUser.nonEmpty)
  require(passwordEnv.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") && definitionHash.matches("[0-9a-f]{64}"))
}
