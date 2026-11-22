package cn.xuyinyin.magic.cdc.guardian

import java.sql.Connection

/** Observe the MySQL server incarnation and this physical connection. */
object MySqlDatabaseIdentity {
  def read(c:Connection):(String,Long,Long)={
    val q=c.createStatement();q.setQueryTimeout(5)
    try {
      val r=q.executeQuery("SELECT @@server_uuid,UNIX_TIMESTAMP(NOW(3))*1000,CONNECTION_ID()")
      val (uuid,clock,id)=try {require(r.next());(r.getString(1),r.getBigDecimal(2).longValue(),r.getLong(3))}finally r.close()
      val u=q.executeQuery("SHOW GLOBAL STATUS LIKE 'Uptime'")
      val uptime=try {require(u.next());u.getLong(2)}finally u.close()
      (uuid,clock-uptime*1000,id)
    } finally q.close()
  }
}
