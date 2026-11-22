package cn.xuyinyin.magic.cdc.guardian

/** Database-derived guardian state; these values do not grant authority by themselves. */
final case class SnapshotOwner(identity:SnapshotOwnerIdentity,phase:String,operation:Option[String],manifest:Long,sealedManifest:Option[Long],address:Option[String])
final case class SnapshotDatabaseObservation(instanceId:String,complete:Boolean,sessions:Int,transactions:Int,unattributed:Int,evidence:String,observedAt:Long,endedSessions:Vector[Long]=Vector.empty) {
  def empty:Boolean=complete && sessions==0 && transactions==0 && unattributed==0
}
final case class SnapshotWorkerLaunch(bootId:String,profileHash:String,phase:String,identity:Option[SnapshotOwnerIdentity],operation:Option[String],proof:Option[String])
