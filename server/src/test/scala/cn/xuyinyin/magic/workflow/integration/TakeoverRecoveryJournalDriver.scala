package cn.xuyinyin.magic.workflow.integration

import java.sql.{Connection,Driver,DriverPropertyInfo}
import java.util.Properties
import cn.xuyinyin.magic.workflow.scheduling.SnapshotOwnerJdbcDriver

/** Test-only fixed window after RECOVERING committed but before Journal recovery can read any business event. */
final class TakeoverRecoveryJournalDriver extends Driver {
  private val delegate=new SnapshotOwnerJdbcDriver()
  def connect(url:String,p:Properties):Connection={
    val c=delegate.connect(url,p)
    if(c!=null) {
      val gate=System.getProperty("takeover.test.recovery-gate")
      CdcJournalWindowSupport.emit("RECOVERY_JOURNAL_WAIT")
      val q=c.prepareStatement("SELECT GET_LOCK(?,90)");q.setString(1,gate)
      try {val r=q.executeQuery();try require(r.next()&&r.getInt(1)==1)finally r.close()}finally q.close()
      val r=c.prepareStatement("DO RELEASE_LOCK(?)");r.setString(1,gate);try r.execute()finally r.close()
    };c
  }
  def acceptsURL(url:String):Boolean=delegate.acceptsURL(url)
  def getPropertyInfo(url:String,p:Properties):Array[DriverPropertyInfo]=delegate.getPropertyInfo(url,p)
  def getMajorVersion:Int=delegate.getMajorVersion
  def getMinorVersion:Int=delegate.getMinorVersion
  def jdbcCompliant():Boolean=delegate.jdbcCompliant()
  def getParentLogger:java.util.logging.Logger=delegate.getParentLogger
}
