package cn.xuyinyin.magic.connectors.mysql.source.capture

import java.sql.Connection

/** Test-only exploration; never connected to Reader admission or recovery permits.
  * A successful call is not a guardian, Journal, external-writer or post-commit fence proof.
  * Existing transaction contents cannot be inspected reliably; callers must provide a new
  * dedicated transaction and must not reuse it after an error.
  */
private[capture] object SourceFenceMarkerWriter {
  def write(c:Connection,captureId:String,sourceUuid:String,database:String,epoch:Long,token:String,version:Long,
    operation:String,action:String,table:String,generation:String):Unit={
    require(c!=null && !c.getAutoCommit,"source fence requires a caller-owned transaction")
    try writeInTransaction(c,captureId,sourceUuid,database,epoch,token,version,operation,action,table,generation)
    catch {case error:Throwable=>
      try c.rollback() catch {case rollback:Throwable=>
        error.addSuppressed(rollback)
        try c.close() catch {case close:Throwable=>error.addSuppressed(close)}
      }
      throw error
    }
  }
  private def writeInTransaction(c:Connection,captureId:String,sourceUuid:String,database:String,epoch:Long,token:String,version:Long,
    operation:String,action:String,table:String,generation:String):Unit={
    require(captureId!=null && captureId.matches("[A-Za-z0-9_-]{1,64}") && sourceUuid!=null && sourceUuid.nonEmpty &&
      database!=null && database.matches("[A-Za-z_][A-Za-z0-9_]*") && epoch>0 && token!=null && token.nonEmpty && version>=0,"invalid source fence identity")
    require(operation!=null && operation.matches("[A-Za-z0-9_-]{1,128}") &&
      Set("SUBSCRIBE","STOP","LOW","HIGH","F","WAKE")(action) &&
      table!=null && table.nonEmpty && table.length<=128 && generation!=null && generation.nonEmpty && generation.length<=128,
      "invalid source control marker")
    val server=c.createStatement();server.setQueryTimeout(5)
    val identity=try {val r=server.executeQuery("SELECT @@server_uuid,DATABASE()")
      try {require(r.next(),"source identity unavailable");val actual=r.getString(1)->r.getString(2);require(!r.next(),"source identity ambiguous");actual}finally r.close()
    }finally server.close()
    require(identity==(sourceUuid->database),"source lineage or database changed")
    val lock=c.prepareStatement("SELECT source_uuid,epoch,owner_token,version,state FROM pekko_capture_source_fence_v1 WHERE capture_id=? FOR UPDATE")
    lock.setQueryTimeout(5)
    try {lock.setString(1,captureId);val r=lock.executeQuery()
      try {require(r.next(),"source fence missing")
        val valid=r.getString(1)==sourceUuid && r.getLong(2)==epoch && r.getString(3)==token && r.getLong(4)==version && r.getString(5)=="FENCED"
        require(valid && !r.next(),"source fence mismatch")
      }finally r.close()
    }finally lock.close()
    val insert=c.prepareStatement("INSERT INTO pekko_capture_control VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE operation_id=operation_id")
    insert.setQueryTimeout(5)
    try {insert.setString(1,operation);insert.setString(2,action);insert.setString(3,table);insert.setString(4,generation);insert.executeUpdate()}finally insert.close()
    val verify=c.prepareStatement("SELECT operation_id,action,table_name,generation FROM pekko_capture_control WHERE operation_id=? FOR UPDATE")
    verify.setQueryTimeout(5)
    try {verify.setString(1,operation);val r=verify.executeQuery()
      try {require(r.next(),"source marker missing after write")
        val same=r.getString(1)==operation && r.getString(2)==action && r.getString(3)==table && r.getString(4)==generation
        require(same && !r.next(),"source marker identity conflict")
      }finally r.close()
    }finally verify.close()
  }
}
