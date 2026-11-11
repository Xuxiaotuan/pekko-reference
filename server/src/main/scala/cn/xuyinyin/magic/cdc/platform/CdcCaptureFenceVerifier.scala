package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.connectors.mysql.source.MySQLDebeziumConnector
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CaptureCheckpoint,CaptureFenceBinding,CapturePinnedProof,CapturePinnedWitness}
import cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc
import java.sql.Connection

/** Actual SDK D and pinned ACK ancestry, checked on the caller's state transaction. */
private[cdc] final class CdcCaptureFenceVerifier private (cfg:CdcCaptureSettings,uuid:String,database:String,
  offsetTable:String,store:CdcTaskStore) {
  def currentDOn(c:Connection,permit:CdcBRecoveryPermit,binding:CaptureFenceBinding):String={
    require(!c.getAutoCommit && c.getTransactionIsolation==Connection.TRANSACTION_REPEATABLE_READ &&
      permit.captureId==cfg.id && binding.sourceUuid==cfg.sourceLineage && binding.database==cfg.source.database &&
      binding.table==permit.table && binding.generation==permit.generation,"capture fence identity changed")
    val identity=c.createStatement();identity.setQueryTimeout(5)
    try {val r=identity.executeQuery("SELECT @@server_uuid,DATABASE()");try {
      require(r.next() && r.getString(1)==uuid && r.getString(2)==database && !r.next(),"state database identity changed")
    } finally r.close()}finally identity.close()
    val q=c.prepareStatement(s"SELECT offset_key,offset_val FROM `$offsetTable` WHERE offset_val IS NOT NULL LIMIT 2 FOR UPDATE")
    q.setQueryTimeout(5)
    val rows=try {val r=q.executeQuery();val b=Vector.newBuilder[(String,String)]
      try while(r.next())b+=((r.getString(1),r.getString(2)))finally r.close();b.result()
    }finally q.close()
    val d=CaptureCheckpoint.decode(cfg.id,rows)
    require(d.status=="COMPLETE" && d.value.nonEmpty,"actual SDK D is not complete")
    d.value.get
  }
  def verifyOn(c:Connection,permit:CdcBRecoveryPermit,binding:CaptureFenceBinding):CapturePinnedWitness={
    require(currentDOn(c,permit,binding)==permit.offset,"actual SDK D changed")
    CapturePinnedProof.verifyOn(c,cfg.id,cfg.sourceLineage,permit.taskId,permit.offset,store.fenceBindingDigest(binding),permit.oldAttempt)
  }
}
private[cdc] object CdcCaptureFenceVerifier {
  def create(cfg:CdcCaptureSettings,store:CdcTaskStore):CdcCaptureFenceVerifier={
    def open(url:String,user:String,password:String)=ManagedCdcJdbc.open(CdcJdbc.withTimeouts(url),user,password,"control",managed=true)
    def identity(c:Connection):(String,String)={
      val q=c.createStatement();q.setQueryTimeout(5)
      try {val r=q.executeQuery("SELECT @@server_uuid,DATABASE()");try {require(r.next());(r.getString(1),r.getString(2))}finally r.close()}finally q.close()
    }
    val state=open(cfg.controlState.jdbcUrl,cfg.controlState.username,cfg.controlState.password)
    try {
      val sdk=open(cfg.sdkState.jdbcUrl,cfg.sdkState.username,cfg.sdkState.password)
      try {
        val a=identity(state);val b=identity(sdk)
        require(a==b && a._1==cfg.stateLineage && a._2!=null,"SDK and control state databases differ")
        val table=MySQLDebeziumConnector.connectorStateTables(cfg.sdkState,cfg.id)._1
        require(table.matches("[A-Za-z0-9_]{1,64}"),"invalid SDK offset table")
        val q=state.prepareStatement("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")
        try {q.setString(1,table);val r=q.executeQuery();try {
          require(r.next() && r.getString(1)=="InnoDB" && !r.next(),"SDK offset table missing or not InnoDB")
        } finally r.close()}finally q.close()
        new CdcCaptureFenceVerifier(cfg,a._1,a._2,table,store)
      } finally sdk.close()
    } finally state.close()
  }
}
