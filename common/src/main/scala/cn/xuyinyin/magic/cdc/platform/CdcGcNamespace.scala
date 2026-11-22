package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapJdbc,BootstrapJournal}
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import java.sql.Connection
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import spray.json._

/** Startup-only inventory. Partial inventories are rescanned; they never authorize deletion. */
private[cdc] object CdcGcNamespace {
  def initialize(c:Connection):Unit={
    val ddl=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-persistence-schema.sql")
    require(ddl!=null,"JDBC persistence schema resource missing")
    val statements=try scala.io.Source.fromInputStream(ddl).mkString.split(";").map(_.trim).filter(_.nonEmpty).toVector
    finally ddl.close()
    def tableExists(name:String):Boolean={
      val q=c.prepareStatement("SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=? LIMIT 1")
      q.setQueryTimeout(30);q.setString(1,name)
      try {val r=q.executeQuery();try r.next()finally r.close()}finally q.close()
    }
    def indexExists(name:String):Boolean={
      val q=c.prepareStatement("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name=? LIMIT 1")
      q.setQueryTimeout(30);q.setString(1,name)
      try {val r=q.executeQuery();try r.next()finally r.close()}finally q.close()
    }
    def exec(sql:String):Unit={val q=c.createStatement();q.setQueryTimeout(30);try q.executeUpdate(sql)finally q.close()}
    if(!tableExists("event_journal"))exec(statements(0))
    if(!indexExists("event_journal_ordering_idx"))exec(statements(1))
    if(!tableExists("event_tag"))exec(statements(2))
    if(!tableExists("snapshot"))exec(statements(3))
    exec("CREATE TABLE IF NOT EXISTS pekko_capture_gc_namespace_v1(capture_id VARCHAR(64) PRIMARY KEY,config_json LONGTEXT NOT NULL,config_digest CHAR(64) NOT NULL,phase VARCHAR(24) NOT NULL,owner_attempt VARCHAR(128) NOT NULL,inventory_root CHAR(64) NOT NULL,inventory_count BIGINT NOT NULL,reason VARCHAR(128) NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin")
  }
  def configuration(store:MySQLCaptureStore,g:CdcGcSettings,captures:Set[String]):String={
    val (uuid,db)=store.location
    MySQLCdcGroup.canonical(JsObject("version"->JsNumber(1),"uuid"->JsString(uuid),"database"->JsString(db),"capture"->JsString(store.captureId),
      "lineage"->JsString(store.lineage),"writer"->JsString(g.account),"definer"->JsString(g.ledgerDefiner),
      "admins"->JsArray(g.trustedAdmins.toVector.sorted.map(JsString(_))),"producers"->JsArray(g.producerAccounts.toVector.sorted.map(JsString(_))),
      "captures"->JsArray(captures.toVector.sorted.map(JsString(_)))))
  }
  /** Exact client inventory and protected permit metadata. DBA/control accounts remain explicitly trusted. */
  private def accounts(c:Connection,g:CdcGcSettings,db:String,j:BootstrapJdbc):Unit={
    val internal=Set("mysql.infoschema@localhost","mysql.session@localhost","mysql.sys@localhost")
    val all=j.query(c,"SELECT USER,HOST,account_locked FROM mysql.user").filterNot(r=>internal(r("user")+"@"+r("host")) && r("account_locked")=="Y")
      .map(r=>r("user")+"@"+r("host")).toSet
    require(all==g.trustedAdmins++g.producerAccounts+g.account,"GC namespace client inventory incomplete")
    require(g.trustedAdmins(j.query(c,"SELECT CURRENT_USER() AS account").head("account")),"GC observer is not a trusted controller")
    (g.producerAccounts+g.account).foreach {a=>
      val Array(user,host)=a.split("@",2);val grantee="'"+user+"'@'"+host+"'"
      val grants=j.query(c,s"SHOW GRANTS FOR '$user'@'$host'").flatMap(_.values).map(_.toUpperCase(java.util.Locale.ROOT))
      require(grants.forall(x=>x.startsWith("GRANT ") && x.contains(" ON ") && !x.contains("WITH GRANT OPTION") && !x.contains("GRANT PROXY")),"GC namespace producer roles/proxy unsupported")
      val global=j.query(c,"SELECT PRIVILEGE_TYPE AS p FROM information_schema.user_privileges WHERE grantee=?",grantee).map(_("p")).toSet
      require(global.subsetOf(Set("USAGE")),"GC namespace producer has global privileges")
      val broad=j.query(c,"SELECT PRIVILEGE_TYPE AS p FROM information_schema.schema_privileges WHERE grantee=?",grantee).map(_("p")).toSet
      require(broad.subsetOf(Set("SELECT","CREATE")),"GC namespace producer has broad mutation privileges")
      val tablePrivileges=j.query(c,"SELECT PRIVILEGE_TYPE AS p FROM information_schema.table_privileges WHERE grantee=? AND table_schema=?",grantee,db).map(_("p")).toSet
      require(tablePrivileges.subsetOf(Set("SELECT","INSERT","UPDATE","DELETE","CREATE","LOCK TABLES")),"GC namespace producer can alter or drop retained evidence")
      val protectedPrivileges=j.query(c,"SELECT PRIVILEGE_TYPE AS p FROM information_schema.table_privileges WHERE grantee=? AND table_schema=? AND table_name IN ('pekko_capture_gc_namespace_v1','event_journal','pekko_sync_cdc_group_ledger') UNION ALL SELECT PRIVILEGE_TYPE AS p FROM information_schema.column_privileges WHERE grantee=? AND table_schema=? AND table_name IN ('pekko_capture_gc_namespace_v1','event_journal','pekko_sync_cdc_group_ledger')",grantee,db,grantee,db).map(_("p")).toSet
      require(protectedPrivileges.subsetOf(Set("SELECT")),"GC namespace Journal, target evidence or permit is mutable by a producer")
    }
  }
  def load(c:Connection,store:MySQLCaptureStore,g:CdcGcSettings,captures:Set[String]):CaptureGcNamespace.Permit={
    CaptureGcNamespace.load(c,store.captureId,configuration(store,g,captures))
  }

  /** Caller holds the platform owner and the Reader advisory lease; Reader and table actors have not started. */
  def enable(open:()=>Connection,store:MySQLCaptureStore,g:CdcGcSettings,
    captures:Set[String],owner:String,system:ActorSystem[_],stop:()=>Boolean,observe:(String,JsObject)=>Unit=(_,_)=>()):CaptureGcNamespace.Permit={
    val jdbc=new BootstrapJdbc(open);import jdbc._
    val body=configuration(store,g,captures);val db=store.location._2
    connection {c=>initialize(c);accounts(c,g,db,jdbc)}
    val ready=connection(c=>query(c,"SELECT * FROM pekko_capture_gc_namespace_v1 WHERE capture_id=?",store.captureId).headOption)
    require(ready.exists(_("phase")=="COMPLETE") || g.enableLegacy,"old namespace requires explicit enable-legacy")
    val lease=open();var acquired=false
    try {
      acquired=query(lease,"SELECT GET_LOCK(?,0) AS acquired","pekko_capture_"+store.captureId).head("acquired")=="1"
      require(acquired,"old Reader still owns namespace")
      val producerUsers=(g.producerAccounts+g.account).map(_.takeWhile(_!='@'))
      require(query(lease,"SELECT USER FROM information_schema.processlist WHERE ID<>CONNECTION_ID()").forall(r=> !producerUsers(r("user"))),"old producer sessions are not drained")
      val drainDeadline=System.nanoTime()+3000000000L
      def transactionsPresent:Boolean=query(lease,"SELECT trx_mysql_thread_id FROM information_schema.innodb_trx WHERE trx_mysql_thread_id<>CONNECTION_ID()").nonEmpty
      var pending=transactionsPresent
      while(pending && System.nanoTime()<drainDeadline){Thread.sleep(300);pending=transactionsPresent}
      require(!pending,"old producer transaction drain is unproven")
      if(ready.exists(_("phase")=="COMPLETE"))return connection(c=>load(c,store,g,captures))
      transaction {c=>
        query(c,"SELECT lineage FROM pekko_capture_readers WHERE capture_id=? FOR UPDATE",store.captureId)
        require(query(c,"SELECT status FROM pekko_cdc_owner_v1 WHERE platform_id='default' AND owner_attempt=?",owner).head("status")=="ACTIVE","GC enabling owner changed")
        require(query(c,"SELECT grant_id FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') LIMIT 1").isEmpty,"old task grants are not drained")
        require(query(c,"SELECT request_id FROM pekko_capture_subscriptions WHERE capture_id=? AND status='B_PENDING' LIMIT 1",store.captureId).isEmpty,"unknown B blocks namespace enabling")
        require(query(c,"SELECT reclaimed_before FROM pekko_capture_gc_retention_v1 WHERE capture_id=?",store.captureId).head("reclaimed_before")=="1" &&
          query(c,"SELECT seq FROM pekko_capture_gc_frames_v1 WHERE capture_id=? LIMIT 1",store.captureId).isEmpty,"uncertified reclaimed namespace")
        update(c,"INSERT INTO pekko_capture_gc_namespace_v1 VALUES(?,?,?,'SCANNING',?,'',0,NULL) ON DUPLICATE KEY UPDATE config_json=VALUES(config_json),config_digest=VALUES(config_digest),phase='SCANNING',owner_attempt=VALUES(owner_attempt),reason=NULL",store.captureId,body,MySQLCdcGroup.hash(body),owner)
        update(c,"UPDATE pekko_capture_gc_state_v1 SET barrier_status='GC_ENABLING' WHERE capture_id=?",store.captureId)
        val serialization=SerializationExtension(system.classicSystem)
        var root="";var count=0
        def record(s:String):Unit={count+=1;require(count<=g.inventoryLimit && !stop(),"GC inventory budget exhausted or stopping");root=MySQLCdcGroup.hash(root+"|"+s);observe("GC_NAMESPACE_ITEM",JsObject("captureId"->JsString(store.captureId),"count"->JsNumber(count)))}
        def task(id:String):Map[String,String]={
          require(id.matches("[a-f0-9]{64}"),"unsupported pre-Runtime consumer identity")
          query(c,"SELECT * FROM pekko_cdc_tasks_v1 WHERE task_id=? FOR SHARE",id).headOption.getOrElse(throw new IllegalArgumentException("GC inventory has an orphan Journal or consumer"))
        }
        def pin(id:String,kind:String):Unit={update(c,"INSERT IGNORE INTO pekko_capture_gc_pins_v1 VALUES(?,?,?,1,0)",store.captureId,id,kind)}
        var cursor="";var more=true
        while(more){
          val page=query(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND request_id>? ORDER BY request_id LIMIT ? FOR UPDATE",store.captureId,cursor,g.pageSize)
          page.foreach {r=>val t=task(r("request_id"));require(t("capture_id")==store.captureId && t("table_name")==r("table_name") && t("generation")==r("generation"),"subscription registry definition mismatch")
            record(r("request_id")+"|"+r("schema_id"));pin("subscription:"+r("request_id"),"RECOVERY")}
          more=page.nonEmpty;if(more)cursor=page.last("request_id")
        }
        cursor="";more=true
        while(more){
          val page=query(c,"SELECT * FROM pekko_capture_consumers_v1 WHERE capture_id=? AND consumer_id>? ORDER BY consumer_id LIMIT ? FOR UPDATE",store.captureId,cursor,g.pageSize)
          page.foreach {r=>val t=task(r("subscription_id"));require(r("consumer_kind")=="BOOTSTRAP" && r("source_node")=="bootstrap" &&
            r("consumer_id")==CaptureConsumerIdentity.bootstrap(store.captureId,r("subscription_id"),t("generation")),"unsupported consumer protocol")
            record(r("consumer_id"));pin("consumer:"+r("consumer_id"),"CONSUMER")}
          more=page.nonEmpty;if(more)cursor=page.last("consumer_id")
        }
        cursor="";more=true
        while(more){
          val page=query(c,"SELECT persistence_id FROM event_journal WHERE persistence_id>? ORDER BY persistence_id,sequence_number LIMIT 1 FOR SHARE",cursor)
          more=page.nonEmpty
          page.foreach {r=>cursor=r("persistence_id")
            val prefix="bootstrap-v1-"+store.captureId+"-"
            require(captures.exists(x=>cursor.startsWith("bootstrap-v1-"+x+"-")),"unsupported foreign Journal in namespace")
            if(cursor.startsWith(prefix)){
              val id=cursor.stripPrefix(prefix);val t=task(id)
              val e=query(c,"SELECT * FROM event_journal WHERE persistence_id=? ORDER BY sequence_number DESC LIMIT 1 FOR SHARE",cursor).head
              require(Set("0","false")(e("deleted").toLowerCase),"latest Journal is deleted")
              val bytes=java.util.HexFormat.of().parseHex(query(c,"SELECT HEX(event_payload) AS payload FROM event_journal WHERE persistence_id=? AND sequence_number=?",cursor,e("sequence_number").toLong).head("payload"))
              val state=serialization.deserialize(bytes,e("event_ser_id").toInt,e("event_ser_manifest")).get match {
                case x:BootstrapJournal.Advanced=>x.state
                case _=>throw new IllegalArgumentException("unsupported Journal payload")
              }
              require(t("capture_id")==store.captureId && (state.definition.isEmpty || state.executionId.nonEmpty),"Journal definition missing")
              require(query(c,"SELECT consumer_id FROM pekko_capture_consumers_v1 WHERE capture_id=? AND subscription_id=?",store.captureId,id).size==1,"Journal consumer inventory incomplete")
              record(cursor+"|"+e("sequence_number")+"|"+MySQLCdcGroup.hash(java.util.HexFormat.of().formatHex(bytes)))
            }
          }
        }
        require(query(c,"SELECT persistence_id FROM snapshot LIMIT 1 FOR SHARE").isEmpty,"snapshot-only history requires a separate inventory")
        accounts(c,g,db,jdbc)
        update(c,"UPDATE pekko_capture_gc_namespace_v1 SET phase='COMPLETE',inventory_root=?,inventory_count=? WHERE capture_id=?",MySQLCdcGroup.hash(root),count,store.captureId)
        update(c,"UPDATE pekko_capture_gc_state_v1 SET discovery_status='COMPLETE',barrier_status='OPEN' WHERE capture_id=?",store.captureId)
        update(c,"UPDATE pekko_capture_gc_retention_v1 SET epoch=epoch+1 WHERE capture_id=?",store.captureId)
      }
      connection(c=>load(c,store,g,captures))
    }finally {if(acquired)query(lease,"SELECT RELEASE_LOCK(?) AS released","pekko_capture_"+store.captureId);lease.close()}
  }
}
