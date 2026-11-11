package cn.xuyinyin.magic.cdc.control




import cn.xuyinyin.magic.cdc.guardian.{SnapshotOwner, SnapshotDatabaseObservation}
import cn.xuyinyin.magic.cdc.guardian.{SnapshotIsolationReceipt}
/** The guardian's owner lifecycle and drain contract, independent of task scheduling. */
trait ManagedOwnerRegistry extends cn.xuyinyin.magic.cdc.guardian.ManagedSessionInventory {
  private[control] def transactionOwner:ManagedControlTransactions
  def owner(id:String):Option[SnapshotOwner]
  def instances(id:String):Vector[(String,String,Long)]
  def forgetEndedSessions(id:String,key:String,ended:Vector[Long]):Unit
  def beginRevocation(id:String,operation:String):String
  def confirmIsolation(receipt:SnapshotIsolationReceipt):Boolean
  def recordObservation(id:String,o:SnapshotDatabaseObservation,minIntervalMillis:Long):Boolean
  def releaseDrained(id:String,limit:Int):Int
  def hasOutstandingQuota(id:String):Boolean
  def recordFailedStart(receipt:SnapshotIsolationReceipt):Boolean
}
