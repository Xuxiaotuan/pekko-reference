package cn.xuyinyin.magic.workflow.integration

import CdcJournalWindowSupport._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import spray.json._
import java.nio.file.Path
import java.sql.{Connection, DriverManager}
import java.net.URLClassLoader
import java.io.File

/** Laboratory observations and explicit operator actions, never production takeover authority. */
private[integration] object OwnerIsolationProbeSupport {
  final case class ReleaseObservation(processDead: Boolean, identityEstablished: Boolean, complete: Boolean,
    sessions: Int, transactions: Int, unattributed: Int) {
    def permitsTestRequeue: Boolean = processDead && identityEstablished && complete && sessions == 0 && transactions == 0 && unattributed == 0
  }
  def connection(s: Settings, schema: String, owner: String, role: String): Connection = {
    validateSchema(schema)
    require(owner.matches("[a-zA-Z0-9-]+") && role.matches("[a-z-]+"))
    DriverManager.getConnection(s.url(schema) + s"&connectTimeout=5000&socketTimeout=5000&connectionAttributes=probe_owner:$owner,probe_role:$role", s.user, s.password)
  }
  def provision(s: Settings, schema: String, gate: String): Unit = {
    initialize(s, schema)
    withConnection(s, schema) { c =>
      Vector("db/mysql/pekko-snapshot-batch-evidence-schema.sql", "db/mysql/pekko-table-scheduling-schema.sql").foreach { name =>
        val in = getClass.getClassLoader.getResourceAsStream(name)
        val sql = try scala.io.Source.fromInputStream(in).mkString finally in.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c, _))
      }
      executeSql(c, "DELETE FROM source_rows WHERE id > 8")
      executeSql(c, "INSERT INTO source_rows VALUES (-9,'负键🙂',-123.4500,'','2026-10-23 00:00:00.000001',2),(9007199254740993,'大键🙂',99999999999999.9999,NULL,'2026-10-23 23:59:59.999999',3)")
      executeSql(c, "CREATE TABLE owner_probe_journal_gate (id INT PRIMARY KEY, armed BOOLEAN NOT NULL) ENGINE=InnoDB")
      executeSql(c, "INSERT INTO owner_probe_journal_gate VALUES (1,false)")
      require(gate.matches("owner-probe-[a-f0-9]+"))
      executeSql(c, s"""CREATE TRIGGER owner_probe_journal_wait BEFORE INSERT ON event_journal FOR EACH ROW
        BEGIN
          IF NEW.persistence_id='workflow-table-00' AND (SELECT armed FROM owner_probe_journal_gate WHERE id=1) THEN
            SET @owner_probe_wait = GET_LOCK('$gate',90);
            DO RELEASE_LOCK('$gate');
          END IF;
        END""")
    }
  }
  def workflow(s: Settings, schema: String): Workflow = {
    val w = TableSchedulingExperimentSupport.workflow(s, schema, 0)
    w.copy(nodes = w.nodes.map { n =>
      val extra: Map[String, JsValue] = if (n.nodeType == "mysql.snapshot") Map("table" -> JsString("source_rows"),
        "splitParallelism" -> JsNumber(1), "splitRows" -> JsNumber(4)) else Map("table" -> JsString("target_rows"))
      n.copy(config = JsObject(n.config.fields ++ extra))
    })
  }
  def database(s: Settings, schema: String, owner: String, knownIds: Vector[Long]): JsObject = {
    require(owner.matches("[a-zA-Z0-9-]+"))
    val started = java.time.Instant.now().toString
    val c = connection(s, schema, "observer", "observer")
    try {
      // Keep untagged connections visible: empty attributes are not evidence that an old session is gone.
      val sessions = query(c, s"""SELECT p.ID AS id,p.COMMAND AS command,p.STATE AS state,p.INFO AS info,
        a.ATTR_VALUE AS owner,r.ATTR_VALUE AS role
        FROM information_schema.processlist p
        LEFT JOIN performance_schema.session_connect_attrs a ON p.ID=a.PROCESSLIST_ID AND a.ATTR_NAME='probe_owner'
        LEFT JOIN performance_schema.session_connect_attrs r ON p.ID=r.PROCESSLIST_ID AND r.ATTR_NAME='probe_role'
        WHERE p.DB='$schema' AND p.ID<>CONNECTION_ID() ORDER BY p.ID""")
      val tx = query(c, "SELECT trx_id,trx_state,trx_started,trx_mysql_thread_id,trx_query,trx_rows_locked,trx_rows_modified FROM information_schema.innodb_trx ORDER BY trx_mysql_thread_id")
      val ids = knownIds.toSet ++ sessions.elements.map(_.asJsObject).filter(_.fields("owner") == JsString(owner))
        .map(_.fields("id").convertTo[String].toLong)
      val oldSessions = sessions.elements.filter { r => val f=r.asJsObject.fields; f("owner")==JsString(owner) || ids(f("id").convertTo[String].toLong) }
      val oldTx = tx.elements.filter(r => ids(r.asJsObject.fields("trx_mysql_thread_id").convertTo[String].toLong))
      val untagged = sessions.elements.filter(_.asJsObject.fields("owner") == JsNull)
      JsObject("complete" -> JsBoolean(true), "started" -> JsString(started), "finished" -> JsString(java.time.Instant.now().toString),
        "owner" -> JsString(owner), "knownIds" -> JsArray(ids.toVector.sorted.map(JsNumber(_))), "sessions" -> sessions,
        "transactionsAll" -> tx, "oldSessions" -> JsArray(oldSessions), "oldTransactions" -> JsArray(oldTx),
        "unattributed" -> JsArray(untagged), "server" -> query(c,"SELECT @@server_uuid AS server_uuid,VERSION() AS version"))
    } finally c.close()
  }
  def observed(s: Settings, schema: String, owner: String, ids: Vector[Long]): JsObject =
    try database(s,schema,owner,ids) catch { case scala.util.control.NonFatal(e) =>
      JsObject("complete" -> JsBoolean(false), "errorClass" -> JsString(e.getClass.getName),
        "error" -> JsString(Option(e.getMessage).getOrElse("").replace(s.password,"<redacted>")))
    }
  def verdict(dead: Boolean, established: Boolean, db: JsObject): ReleaseObservation = {
    def size(k:String):Int = db.fields.get(k).collect {case a:JsArray=>a.elements.size}.getOrElse(-1)
    ReleaseObservation(dead,established,db.fields.get("complete").contains(JsBoolean(true)),size("oldSessions"),size("oldTransactions"),size("unattributed"))
  }
  def process(child: Child): JsObject = {
    val pb = new ProcessBuilder("/bin/ps","-o","pid=,ppid=,stat=,lstart=","-p",child.pid.toString).start()
    val ps=try scala.io.Source.fromInputStream(pb.getInputStream).mkString finally pb.waitFor()
    JsObject("pid"->JsNumber(child.pid),"alive"->JsBoolean(child.alive),"ps"->JsString(ps),"at"->JsString(java.time.Instant.now().toString))
  }
  def signal(child:Child,name:String):Unit = {
    require(Set("STOP","CONT").contains(name) && child.alive)
    require(new ProcessBuilder("/bin/kill","-"+name,child.pid.toString).start().waitFor()==0)
  }
  def start(s:Settings,schema:String,mode:String,scenario:String,gate:String,path:Path):Child = {
    val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
      .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
    val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
    val pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,
      RealMySQLOwnerIsolationProcess.getClass.getName.stripSuffix("$"),schema,mode,scenario,gate)
    pb.redirectErrorStream(true)
    Map("CDC_WINDOW_MYSQL_HOST"->s.host,"CDC_WINDOW_MYSQL_PORT"->s.port.toString,"CDC_WINDOW_MYSQL_USER"->s.user,"CDC_WINDOW_MYSQL_PASSWORD"->s.password)
      .foreach{case(k,v)=>pb.environment().put(k,v)}
    new Child(pb.start(),path)
  }
  def capture(s:Settings,schema:String):JsObject = JsObject(
    "T"->withConnection(s,schema)(query(_,"SELECT * FROM pekko_sync_batch_ledger ORDER BY partition_id,batch_sequence")),
    "J"->TableSchedulingExperimentSupport.journal(s,schema),"D"->JsString("N/A: static Split snapshot; no CDC engine or ACK"),
    "digests"->withConnection(s,schema)(query(_,"SELECT * FROM pekko_snapshot_batch_evidence ORDER BY batch_id")),
    "control"->withConnection(s,schema)(query(_,"SELECT * FROM pekko_table_tasks ORDER BY workflow_id")),
    "comparison"->comparison(s,schema),"audit"->withConnection(s,schema)(query(_,"SELECT * FROM target_audit ORDER BY audit_id")))
  def requeue(s:Settings,schema:String,owner:String,grant:String,proof:ReleaseObservation):Int = {
    require(proof.permitsTestRequeue,"test/operator requeue refused: isolation or database release is unconfirmed")
    withConnection(s,schema){c=>
      c.setAutoCommit(false)
      try {
        query(c,"SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1 FOR UPDATE")
        val ps=c.prepareStatement("UPDATE pekko_table_tasks SET phase='ready',grant_id=NULL,owner_id=NULL WHERE workflow_id='table-00' AND owner_id=? AND grant_id=? AND phase='running'")
        ps.setString(1,owner);ps.setString(2,grant)
        val n=try ps.executeUpdate() finally ps.close();require(n==1,"old grant changed; do not requeue")
        c.commit();n
      } catch {case scala.util.control.NonFatal(e)=>c.rollback();throw e}
    }
  }
}
