package cn.xuyinyin.magic.connectors.mysql.source.capture

import java.sql.Connection
import java.util.Locale

/** Administrator-provisioned fresh namespace; the administrator is a trust boundary. */
private[magic] object CaptureNamespaceBirth {
  final case class Expected(serverUuid:String,database:String,capture:String,sourceLineage:String,generation:String,
    writer:String,definer:String,nonce:String,trustedAdmins:Set[String],auxiliaryAccounts:Set[String])
  private def rows(c:Connection,sql:String,args:Any*):Vector[Vector[String]]={
    val q=c.prepareStatement(sql);q.setQueryTimeout(5)
    try {args.zipWithIndex.foreach {case(a,i)=>q.setObject(i+1,a)};val r=q.executeQuery()
      try {val n=r.getMetaData.getColumnCount;val b=Vector.newBuilder[Vector[String]]
        while(r.next())b+=(1 to n).map(r.getString).toVector
        b.result()}finally r.close()
    }finally q.close()
  }
  private def account(s:String):(String,String)={
    require(s.matches("[A-Za-z0-9_]+@[A-Za-z0-9_.%:-]+"),"invalid exact MySQL account")
    val p=s.split("@",2);p(0)->p(1)
  }
  private def grantee(s:String):String={val (u,h)=account(s);"'"+u+"'@'"+h+"'"}
  private def inventory(observer:Connection,e:Expected):Unit={
    // ACCOUNT LOCK blocks new logins but leaves existing connections alive.  A
    // locked account must therefore remain in the explicit trust inventory.
    // The server's own locked internal identities are not client principals.
    val internal=Set("mysql.infoschema@localhost","mysql.session@localhost","mysql.sys@localhost")
    val accounts=rows(observer,"SELECT USER,HOST,account_locked FROM mysql.user")
    val all=accounts.filterNot(r=>internal(r(0)+"@"+r(1)) && r(2)=="Y").map(r=>r(0)+"@"+r(1)).toSet
    val declared=e.trustedAdmins++e.auxiliaryAccounts+e.writer
    declared.foreach(account)
    require(e.trustedAdmins.nonEmpty && !e.trustedAdmins(e.writer) && !e.auxiliaryAccounts(e.writer) &&
      (e.trustedAdmins intersect e.auxiliaryAccounts).isEmpty && all==declared,"namespace account inventory is incomplete")
    require(accounts.forall(r=>r(2)!="Y" || e.trustedAdmins(r(0)+"@"+r(1)) || internal(r(0)+"@"+r(1))),
      "locked non-admin account may retain an active session")
    val safeGlobal=Set("USAGE","REPLICATION CLIENT","REPLICATION SLAVE","SHOW DATABASES","PROCESS")
    e.auxiliaryAccounts.foreach {a=>
      val (u,h)=account(a)
      val grants=rows(observer,s"SHOW GRANTS FOR '$u'@'$h'").map(_.head.toUpperCase(Locale.ROOT))
      require(grants.nonEmpty && grants.forall(g=>g.startsWith("GRANT ") && g.contains(" ON ") &&
        !g.contains("WITH GRANT OPTION") && !g.contains("WITH ADMIN OPTION") && !g.contains("GRANT PROXY")),"auxiliary account has an unsupported role or grant")
      val p=grantee(a)
      val global=rows(observer,"SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=?",p).map(_.head.toUpperCase(Locale.ROOT)).toSet
      require(global.subsetOf(safeGlobal),"auxiliary account has unsafe global privileges")
      // Schema-level grants can contain wildcard patterns; reject them regardless of schema.
      val namespace=rows(observer,"SELECT 1 FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=? UNION ALL "+
        "SELECT 1 FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE=? AND TABLE_SCHEMA=? UNION ALL "+
        "SELECT 1 FROM information_schema.COLUMN_PRIVILEGES WHERE GRANTEE=? AND TABLE_SCHEMA=? LIMIT 1",p,p,e.database,p,e.database)
      require(namespace.isEmpty,"auxiliary account has object privileges")
    }
  }
  /** No account, schema, grant, trigger, or birth row is created by this method. */
  def withVerified[A](observer:Connection,writer:Connection,e:Expected)(use: =>A):A={
    require(e.capture.matches("[A-Za-z0-9_-]{1,64}") && e.sourceLineage.nonEmpty && e.generation.nonEmpty &&
      e.nonce.matches("[a-f0-9]{64}"),"invalid namespace birth identity")
    CaptureLedgerGuard.withVerified(observer,writer,e.serverUuid,e.database,e.writer,e.definer) {
      val observerAccount=rows(observer,"SELECT CURRENT_USER()").head.head
      require(e.trustedAdmins(observerAccount),"observer is not the declared trusted administrator")
      inventory(observer,e)
      val allowed=Set("SELECT")
      val p=grantee(e.writer)
      val broad=rows(observer,"SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=? UNION ALL "+
        "SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=?",p,p)
        .map(_.head.toUpperCase(Locale.ROOT)).toSet
      require(broad.subsetOf(Set("USAGE","SELECT")),"writer has broad write privileges on birth evidence")
      val birthPrivileges=rows(observer,"SELECT PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE=? AND TABLE_SCHEMA=? AND TABLE_NAME='pekko_capture_gc_birth_v1'",p,e.database)
        .map(_.head.toUpperCase(Locale.ROOT)).toSet
      require(birthPrivileges.subsetOf(allowed),"writer can modify birth evidence")
      val columns=rows(observer,"SELECT PRIVILEGE_TYPE FROM information_schema.COLUMN_PRIVILEGES WHERE GRANTEE=? AND TABLE_SCHEMA=? AND TABLE_NAME='pekko_capture_gc_birth_v1'",p,e.database)
        .map(_.head.toUpperCase(Locale.ROOT)).toSet
      require(columns.subsetOf(allowed),"writer can modify birth evidence columns")
      val birth=rows(writer,"SELECT namespace_version,server_uuid,database_name,capture_id,source_lineage,generation,writer_account,journal_database,birth_nonce,status,journal_initially_empty,writer_issued_after_birth FROM pekko_capture_gc_birth_v1 WHERE capture_id=? FOR SHARE",e.capture)
      require(birth.size==1 && birth.head==Vector("1",e.serverUuid,e.database,e.capture,e.sourceLineage,e.generation,e.writer,e.database,e.nonce,"READY","1","1"),
        "namespace birth evidence missing or changed")
      val connectionId=rows(writer,"SELECT CONNECTION_ID()").head.head
      def birthLock():Unit=require(rows(observer,"""SELECT 1 FROM performance_schema.metadata_locks l JOIN performance_schema.threads t ON t.THREAD_ID=l.OWNER_THREAD_ID
        WHERE t.PROCESSLIST_ID=? AND l.OBJECT_TYPE='TABLE' AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='pekko_capture_gc_birth_v1' AND l.LOCK_STATUS='GRANTED' LIMIT 1""",connectionId,e.database).nonEmpty,
        "namespace birth metadata lock missing")
      birthLock()
      val result=use
      birthLock();inventory(observer,e)
      result
    }
  }

  /** Fresh-only activation; DBA birth attestation and credential isolation are explicit prerequisites. */
  def activateFreshOn(observer:Connection,writer:Connection,e:Expected):Unit=withVerified(observer,writer,e){
    require(writer.getTransactionIsolation==Connection.TRANSACTION_REPEATABLE_READ,"fresh activation requires repeatable-read range locks")
    val tables=rows(observer,"SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME IN ('event_journal','snapshot')",e.database)
    require(tables.size==2 && tables.forall(_(1)=="InnoDB"),"fresh Journal tables missing or nontransactional")
    val head=rows(writer,"SELECT lineage,next_seq,used_bytes,frontier FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",e.capture)
    require(head.size==1 && head.head==Vector(e.sourceLineage,"1","0",null),"capture is not freshly initialized")
    val state=rows(writer,"SELECT discovery_status,barrier_status FROM pekko_capture_gc_state_v1 WHERE capture_id=? FOR UPDATE",e.capture)
    require(state.size==1 && state.head==Vector("UNKNOWN","OPEN"),"fresh capture admission state changed")
    require(rows(writer,"SELECT capture_id FROM pekko_capture_readers LIMIT 2 FOR UPDATE")==Vector(Vector(e.capture)) &&
      rows(writer,"SELECT capture_id FROM pekko_capture_gc_state_v1 LIMIT 2 FOR UPDATE")==Vector(Vector(e.capture)) &&
      rows(writer,"SELECT capture_id FROM pekko_capture_gc_birth_v1 LIMIT 2 FOR SHARE")==Vector(Vector(e.capture)) &&
      rows(writer,"SELECT 1 FROM pekko_capture_log LIMIT 1 FOR UPDATE").isEmpty && rows(writer,"SELECT 1 FROM pekko_capture_consumers_v1 LIMIT 1 FOR UPDATE").isEmpty &&
      rows(writer,"SELECT 1 FROM pekko_capture_subscriptions LIMIT 1 FOR UPDATE").isEmpty && rows(writer,"SELECT 1 FROM pekko_capture_fences LIMIT 1 FOR UPDATE").isEmpty &&
      rows(writer,"SELECT 1 FROM pekko_capture_schemas LIMIT 1 FOR UPDATE").isEmpty,"fresh namespace has retained capture history")
    require(rows(writer,"SELECT ordering FROM event_journal LIMIT 1 FOR UPDATE").isEmpty &&
      rows(writer,"SELECT persistence_id FROM snapshot LIMIT 1 FOR UPDATE").isEmpty,"fresh namespace Journal or snapshot is not empty")
    val q=writer.prepareStatement("UPDATE pekko_capture_gc_state_v1 SET discovery_status='COMPLETE' WHERE capture_id=? AND discovery_status='UNKNOWN' AND barrier_status='OPEN'")
    try {q.setString(1,e.capture);require(q.executeUpdate()==1,"fresh capture activation changed")}finally q.close()
  }
}
