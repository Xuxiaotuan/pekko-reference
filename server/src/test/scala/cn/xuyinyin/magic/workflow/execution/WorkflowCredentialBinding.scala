package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.cdc.control.CredentialBinding
import cn.xuyinyin.magic.cdc.control.SnapshotOwnerJdbc

/** Adapter for legacy execution nodes; the managed JDBC layer remains independent of Workflow DSL. */
private[magic] object WorkflowCredentialBinding {
  /** Apply only a durable, permit-bound runtime identity; persisted Workflow JSON is untouched. */
  def rebindRuntimeNode(node:ExecutionNode,workflowId:String,executionId:String,definitionHash:String):ExecutionNode={
    SnapshotOwnerJdbc.credentialBinding(workflowId,executionId,node.id,node.role) match {
      case None=>node
      case Some(binding) => applyCredentialBinding(node,binding,definitionHash)
    }
  }
  def rebindRuntimeNode(node:ExecutionNode,binding:CredentialBinding):ExecutionNode=
    applyCredentialBinding(node,binding,binding.definitionHash)
  private def applyCredentialBinding(node:ExecutionNode,binding:CredentialBinding,definitionHash:String):ExecutionNode={
    require(binding.definitionHash==definitionHash && binding.legacyUser==node.config.fields.get("username").collect{case spray.json.JsString(v)=>v}.getOrElse(""),"credential binding does not match the persisted node")
    val host=node.config.fields.get("host").collect{case spray.json.JsString(v)=>v}.getOrElse("localhost")
    val port=node.config.fields.get("port").collect{case spray.json.JsNumber(v)=>v.toInt}.getOrElse(3306)
    val database=node.config.fields.get("database").collect{case spray.json.JsString(v)=>v}.getOrElse("")
    require(SnapshotOwnerJdbc.endpoint(s"jdbc:mysql://$host:$port/$database")==binding.endpoint && database==binding.databaseName,"credential binding endpoint differs")
    node.copy(config=spray.json.JsObject((node.config.fields-"password")+ ("username"->spray.json.JsString(binding.runtimeUser))+ ("passwordEnv"->spray.json.JsString(binding.passwordEnv))))
  }
}
