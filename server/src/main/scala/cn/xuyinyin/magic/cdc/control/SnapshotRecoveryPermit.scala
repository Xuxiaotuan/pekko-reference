package cn.xuyinyin.magic.cdc.control

/** Runtime view of a recovery permit stored in the control database; not a Journal event. */
final case class SnapshotRecoveryPermit(workflowId:String,owner:String,generation:Long,id:String,grantId:String,executionId:String,revision:Long,definitionHash:String,phase:String)
