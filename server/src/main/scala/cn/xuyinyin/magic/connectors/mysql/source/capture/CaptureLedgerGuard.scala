package cn.xuyinyin.magic.connectors.mysql.source.capture

import java.sql.{Connection,PreparedStatement}
import java.util.Locale

/** A scoped ledger guard. A trusted observer and a dedicated namespace are still required. */
private[magic] object CaptureLedgerGuard {
  private val ledger="pekko_sync_cdc_group_ledger"
  private val dangerous=Set("ALTER","ALTER ROUTINE","CREATE","CREATE ROUTINE","CREATE USER","CREATE ROLE","DROP","DROP ROLE",
    "EVENT","FILE","GRANT OPTION","REFERENCES","ROLE_ADMIN","SUPER","SYSTEM_USER","TRIGGER")
  private def rows(c:Connection,sql:String,args:Any*):Vector[Vector[String]]={
    val q=c.prepareStatement(sql);q.setQueryTimeout(5)
    try {args.zipWithIndex.foreach {case(a,i)=>q.setObject(i+1,a)};val r=q.executeQuery()
      try {val n=r.getMetaData.getColumnCount;val b=Vector.newBuilder[Vector[String]]
        while(r.next())b+=(1 to n).map(r.getString).toVector
        b.result()}finally r.close()
    }finally q.close()
  }
  private def identity(c:Connection):(String,String,String,String)={
    val r=rows(c,"SELECT @@server_uuid,DATABASE(),CURRENT_USER(),CURRENT_ROLE()").head
    (r(0),r(1),r(2),r(3))
  }
  /** Caller owns the transaction; commit follows this scope while its metadata lock is still held. */
  def withVerified[A](observer:Connection,writer:Connection,serverUuid:String,database:String,account:String,definer:String)(use: =>A):A={
    require(!writer.getAutoCommit && database.matches("pekko_gc_[a-z0-9_]{1,48}") && account.matches("[A-Za-z0-9_]+@[A-Za-z0-9_.%:-]+"),
      "ledger guard requires a dedicated namespace and writer transaction")
    val host=account.split("@",2)(1)
    require(!host.contains("%") && !host.contains("_"),"ledger writer host wildcard is not allowed")
    val wi=identity(writer);val oi=identity(observer)
    require(wi._1==serverUuid && oi._1==serverUuid && wi._2==database && oi._2==database && wi._3==account && wi._4=="NONE",
      "ledger namespace, account or active role conflict")
    // A real table read holds a MySQL metadata lock until the caller ends its transaction.
    rows(writer,"SELECT group_id FROM pekko_sync_cdc_group_ledger LIMIT 1")
    require(rows(observer,"SELECT @@performance_schema").head.head=="1","ledger metadata lock verification unavailable")
    val connectionId=rows(writer,"SELECT CONNECTION_ID()").head.head
    def requireLock():Unit=require(rows(observer,"""SELECT 1 FROM performance_schema.metadata_locks l
      JOIN performance_schema.threads t ON t.THREAD_ID=l.OWNER_THREAD_ID
      WHERE t.PROCESSLIST_ID=? AND l.OBJECT_TYPE='TABLE' AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?
      AND l.LOCK_STATUS='GRANTED' LIMIT 1""",connectionId,database,ledger).nonEmpty,"ledger metadata lock lost or unobservable")
    requireLock()
    // A COMMIT followed by another SELECT can reacquire the same metadata lock on the
    // same connection. Bind this scope to the active transaction event, not just the lock.
    def transactionEvent():String={
      val current=rows(observer,"""SELECT e.EVENT_ID FROM performance_schema.events_transactions_current e
        JOIN performance_schema.threads t ON t.THREAD_ID=e.THREAD_ID
        WHERE t.PROCESSLIST_ID=? AND e.STATE='ACTIVE'""",connectionId)
      require(current.size==1 && current.head.head!=null,"ledger transaction unobservable or inactive")
      current.head.head
    }
    val eventId=transactionEvent()
    val grants=rows(writer,"SHOW GRANTS FOR CURRENT_USER() ").map(_.head.toUpperCase(Locale.ROOT))
    require(grants.nonEmpty && grants.forall(g=>g.startsWith("GRANT ") && g.contains(" ON ") &&
      !g.contains("WITH GRANT OPTION") && !g.contains("WITH ADMIN OPTION") && !g.contains("GRANT PROXY") && !g.contains("ALL PRIVILEGES")),
      "ledger writer has an unsupported role or grant")
    val grantedNames=grants.flatMap(g=>g.stripPrefix("GRANT ").take(g.indexOf(" ON ")-6).split(",").map(_.trim)).toSet
    require(grantedNames.subsetOf(Set("USAGE","SELECT","INSERT","UPDATE","DELETE","LOCK TABLES")) &&
      grantedNames.intersect(dangerous).isEmpty,"ledger writer has unsupported granted privileges")
    val parts=account.split("@",2);val grantee="'"+parts(0)+"'@'"+parts(1)+"'"
    val privileges=rows(observer,"SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=? UNION ALL "+
      "SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=? AND TABLE_SCHEMA=? UNION ALL "+
      "SELECT PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE=? AND TABLE_SCHEMA=? AND TABLE_NAME=?",
      grantee,grantee,database,grantee,database,ledger).map(_.head.toUpperCase(Locale.ROOT)).toSet
    require(privileges.intersect(dangerous).isEmpty,"ledger writer has dangerous privileges")
    val triggers=rows(observer,"SELECT TRIGGER_NAME,EVENT_MANIPULATION,ACTION_TIMING,ACTION_STATEMENT,DEFINER FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND EVENT_OBJECT_TABLE=?",database,ledger)
    def normalized(s:String)=s.trim.toUpperCase(Locale.ROOT).replaceAll("\\s+"," ").replaceAll("\\s*=\\s*","=")
    val expected=Map("pekko_ledger_no_update"->"UPDATE","pekko_ledger_no_delete"->"DELETE")
    require(triggers.size==2 && triggers.forall {r=>expected.get(r(0)).contains(r(1)) && r(2)=="BEFORE" && r(4)==definer &&
      normalized(r(3))=="SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LEDGER IMMUTABLE'"},"ledger immutability trigger conflict")
    // InnoDB does not fire child-table triggers for referential actions. Both CASCADE
    // and SET NULL can therefore change a supposedly immutable ledger row.
    require(rows(observer,"SELECT 1 FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=? AND TABLE_NAME=? AND (UPDATE_RULE NOT IN ('RESTRICT','NO ACTION') OR DELETE_RULE NOT IN ('RESTRICT','NO ACTION')) LIMIT 1",database,ledger).isEmpty,
      "foreign key action can bypass ledger triggers")
    val result=use
    require(!writer.getAutoCommit && identity(writer)==wi && identity(observer)==oi,"ledger guard scope changed")
    requireLock()
    require(transactionEvent()==eventId,"ledger transaction changed within guard scope")
    result
  }
}
