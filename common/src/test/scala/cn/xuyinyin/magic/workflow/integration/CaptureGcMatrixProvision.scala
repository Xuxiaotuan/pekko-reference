package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import java.sql.{Connection,DriverManager}
import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import spray.json._

/** Fixture provisioning only. Birth and ledger guards themselves are production implementations. */
private[integration] object CaptureGcMatrixProvision {
  import CdcJournalWindowSupport._
  def birth(c:Connection,store:MySQLCaptureStore,url:String,password:String,account:String,definer:String):Unit={
    require(url.contains(":25832/pekko_gc_") && account.matches("lg_[a-f0-9]{12}@127.0.0.1"))
    val schema=c.getCatalog;val user=account.takeWhile(_!='@')
    val admins=query(c,"SELECT USER,HOST FROM mysql.user WHERE NOT (HOST='localhost' AND USER IN ('mysql.infoschema','mysql.session','mysql.sys') AND account_locked='Y')")
      .elements.map(_.asJsObject.fields).map(f=>f("USER").convertTo[String]+"@"+f("HOST").convertTo[String]).toSet
    require(admins==Set("root@localhost"),"GC matrix requires its fresh dedicated MySQL and no other client accounts")
    executeSql(c,"CREATE TABLE pekko_capture_gc_birth_v1(namespace_version INT,server_uuid VARCHAR(64),database_name VARCHAR(64),capture_id VARCHAR(64) PRIMARY KEY,source_lineage VARCHAR(255),generation VARCHAR(128),writer_account VARCHAR(128),journal_database VARCHAR(64),birth_nonce CHAR(64),status VARCHAR(16),journal_initially_empty BOOLEAN,writer_issued_after_birth BOOLEAN) ENGINE=InnoDB")
    val uuid=query(c,"SELECT @@server_uuid AS uuid").elements.head.asJsObject.fields("uuid").convertTo[String]
    val nonce=MySQLCdcGroup.hash(schema+store.captureId)
    val q=c.prepareStatement("INSERT INTO pekko_capture_gc_birth_v1 VALUES(1,?,?,?,?,?,?,?,?,'READY',TRUE,TRUE)")
    try {Vector(uuid,schema,store.captureId,store.lineage,"g1",account,schema,nonce).zipWithIndex.foreach {case(v,i)=>q.setString(i+1,v)};q.executeUpdate()}finally q.close()
    val create=c.prepareStatement(s"CREATE USER '$user'@'127.0.0.1' IDENTIFIED BY ?")
    try {create.setString(1,password);create.executeUpdate()}finally create.close()
    executeSql(c,s"GRANT SELECT ON `$schema`.* TO '$user'@'127.0.0.1'")
    Vector("pekko_sync_cdc_group_ledger","event_journal","snapshot","pekko_capture_readers","pekko_capture_gc_state_v1","pekko_capture_consumers_v1",
      "pekko_capture_log","pekko_capture_subscriptions","pekko_capture_fences","pekko_capture_schemas",
      "pekko_capture_gc_retention_v1","pekko_capture_gc_pins_v1","pekko_capture_gc_checkpoints_v1","pekko_capture_gc_frames_v1","pekko_capture_gc_operations_v1").foreach {t=>
      executeSql(c,s"GRANT INSERT,UPDATE,DELETE ON `$schema`.`$t` TO '$user'@'127.0.0.1'")
    }
    val w=DriverManager.getConnection(url,user,password)
    try {w.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);w.setAutoCommit(false)
      CaptureNamespaceBirth.activateFreshOn(c,w,CaptureNamespaceBirth.Expected(uuid,schema,store.captureId,store.lineage,"g1",account,definer,nonce,admins,Set.empty))
      w.commit()
    }finally w.close()
  }
}
