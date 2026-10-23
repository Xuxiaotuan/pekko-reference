package cn.xuyinyin.magic.workflow.scheduling

import java.sql.{Connection, PreparedStatement, ResultSet}
import javax.sql.DataSource
import scala.util.control.NonFatal

/** SQL owns run intent and admission only. Every grant mutation serializes on one small control row.
  * No TTL reclamation: an unknown owner or release keeps its entire resource reservation.
  */
final class TableSchedulingStore(dataSource: DataSource, val limits: SchedulingLimits, ownershipEnabled: Boolean = false) {
  val ownership: Option[SnapshotOwnershipStore] = Option.when(ownershipEnabled)(new SnapshotOwnershipStore(this))
  @volatile private[scheduling] var onBorrow: Connection => Unit = _ => ()
  private val occupied = "('held','recovering','running','drained','unknown')"
  private[scheduling] def read[A](f: Connection => A): A = {
    val c = dataSource.getConnection
    try { onBorrow(c); f(c) } finally c.close()
  }
  private[scheduling] def transaction[A](f: Connection => A): A = read(c => transactionOn(c)(f))
  private[scheduling] def transactionOn[A](c:Connection)(f:Connection=>A):A = {
    c.setAutoCommit(false)
    var transactionEnded=false
    try {
      val configured = cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.sha256(limits.toString)
      val policy = query(c, "SELECT policy_hash FROM pekko_table_admission_lock WHERE id=1 FOR UPDATE")(rs => Option(rs.getString(1))).headOption
        .getOrElse(throw new IllegalStateException("table admission schema is not provisioned"))
      if (policy.exists(_ != configured)) throw new IllegalStateException("table admission policy differs from the provisioned policy")
      if (policy.isEmpty) update(c,"UPDATE pekko_table_admission_lock SET policy_hash=? WHERE id=1",configured)
      val result = f(c); c.commit(); transactionEnded=true; result
    } catch { case NonFatal(e) => try {c.rollback();transactionEnded=true} catch { case NonFatal(cleanup) => e.addSuppressed(cleanup) }; throw e }
    finally if(transactionEnded)c.setAutoCommit(true)
  }
  private def bindValue(s: PreparedStatement, i: Int, v: Any): Unit = v match {
    case null => s.setNull(i, java.sql.Types.VARCHAR)
    case n: Int => s.setInt(i, n)
    case n: Long => s.setLong(i, n)
    case b: Boolean => s.setBoolean(i, b)
    case value => s.setString(i, value.toString)
  }
  private[scheduling] def query[A](c: Connection, sql: String, args: Seq[Any] = Nil)(f: ResultSet => A): Vector[A] = {
    val s = c.prepareStatement(sql); s.setQueryTimeout(30)
    try {
      args.zipWithIndex.foreach { case (v, i) => bindValue(s, i + 1, v) }
      val rs = s.executeQuery(); val b = Vector.newBuilder[A]
      try { while (rs.next()) b += f(rs); b.result() } finally rs.close()
    } finally s.close()
  }
  private[scheduling] def update(c: Connection, sql: String, args: Any*): Int = {
    val s = c.prepareStatement(sql); s.setQueryTimeout(30)
    try { args.zipWithIndex.foreach { case (v, i) => bindValue(s, i + 1, v) }; s.executeUpdate() } finally s.close()
  }
  private def decode(rs: ResultSet): ScheduledTable = ScheduledTable(
    ScheduledTableDefinition(rs.getString("workflow_id"), rs.getLong("revision"), rs.getString("workflow_json"),
      TableResourceDemand(rs.getString("source_instance"), rs.getString("target_instance"), rs.getInt("parallelism"), rs.getInt("page_bytes"))),
    rs.getString("phase"), Option(rs.getString("execution_id")), Option(rs.getString("request_key")),
    rs.getLong("queue_order"), rs.getLong("not_before"), Option(rs.getString("grant_id")), Option(rs.getString("owner_id")))
  private[scheduling] def get(c: Connection, id: String): Option[ScheduledTable] = query(c, "SELECT * FROM pekko_table_tasks WHERE workflow_id=?", Seq(id))(decode).headOption
  def get(id: String): Option[ScheduledTable] = read(get(_, id))
  private val summaryColumns = "workflow_id,revision,phase,execution_id,queue_order,source_instance,target_instance,parallelism,page_bytes"
  private def summary(rs: ResultSet): ScheduledTableSummary = ScheduledTableSummary(rs.getString("workflow_id"),rs.getLong("revision"),rs.getString("phase"),
    Option(rs.getString("execution_id")),rs.getLong("queue_order"),TableResourceDemand(rs.getString("source_instance"),rs.getString("target_instance"),rs.getInt("parallelism"),rs.getInt("page_bytes")))
  def findSummary(id: String): Option[ScheduledTableSummary] = read(c => query(c,s"SELECT $summaryColumns FROM pekko_table_tasks WHERE workflow_id=?",Seq(id))(summary).headOption)
  private[scheduling] def next(c: Connection): Long = {
    update(c, "UPDATE pekko_table_admission_lock SET queue_sequence=queue_sequence+1 WHERE id=1")
    query(c, "SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1")(_.getLong(1)).head
  }
  private def register(c: Connection, d: ScheduledTableDefinition): ScheduledTable = get(c, d.workflowId) match {
    case Some(existing) if existing.definition == d => existing
    case Some(existing) if Set("registered", "completed").contains(existing.state) && d.revision > existing.definition.revision =>
      update(c, "UPDATE pekko_table_tasks SET revision=?,workflow_json=?,source_instance=?,target_instance=?,parallelism=?,page_bytes=? WHERE workflow_id=?",
        d.revision, d.workflowJson, d.demand.sourceInstance, d.demand.targetInstance, d.demand.parallelism, d.demand.pageBytes, d.workflowId)
      get(c, d.workflowId).get
    case Some(_) => throw new IllegalStateException("scheduled definition conflicts with durable run")
    case None =>
      update(c, "INSERT INTO pekko_table_tasks (workflow_id,revision,workflow_json,source_instance,target_instance,parallelism,page_bytes,phase,queue_order,not_before) VALUES (?,?,?,?,?,?,?,'registered',?,0)",
        d.workflowId, d.revision, d.workflowJson, d.demand.sourceInstance, d.demand.targetInstance, d.demand.parallelism, d.demand.pageBytes, next(c))
      get(c, d.workflowId).get
  }
  def register(d: ScheduledTableDefinition): ScheduledTable = transaction(register(_, d))
  def enqueue(d: ScheduledTableDefinition, executionId: String, requestKey: String, notBefore: Long = 0L): ScheduledTable = {
    require(executionId.nonEmpty && executionId.length <= 64 && requestKey.nonEmpty && requestKey.length <= 255)
    transaction(c=>enqueueOn(c,d,executionId,requestKey,notBefore))
  }
  private[scheduling] def enqueueOn(c:Connection,d:ScheduledTableDefinition,executionId:String,requestKey:String,notBefore:Long):ScheduledTable = {
      val previous = get(c, d.workflowId)
      if (previous.exists(t => t.executionId.contains(executionId) && t.definition != d))
        throw new IllegalStateException("an execution request cannot change its definition")
      val current = register(c, d)
      val requestId = cn.xuyinyin.magic.workflow.checkpoint.BatchId.sha256("table-request",d.workflowId,requestKey,0L)
      val prior = query(c,"SELECT execution_id FROM pekko_table_requests WHERE request_id=?",Seq(requestId))(_.getString(1)).headOption
      if (prior.exists(_ != executionId) || (prior.nonEmpty && !current.executionId.contains(executionId)))
        throw new IllegalStateException("stale table scheduling request")
      if (current.executionId.contains(executionId)) current
      else if (!Set("registered", "completed").contains(current.state)) throw new IllegalStateException("scheduled table already has an unfinished execution")
      else {
        if (prior.isEmpty) update(c,"INSERT INTO pekko_table_requests (request_id,workflow_id,execution_id) VALUES (?,?,?)",requestId,d.workflowId,executionId)
        update(c, "UPDATE pekko_table_tasks SET phase=?,execution_id=?,request_key=?,queue_order=?,not_before=?,grant_id=NULL,owner_id=NULL WHERE workflow_id=?",
          if (notBefore > System.currentTimeMillis()) "waiting" else "ready", executionId, requestKey, next(c), notBefore, d.workflowId)
        get(c, d.workflowId).get
      }
  }
  private[scheduling] def active(c: Connection): Vector[ScheduledTable] = query(c, s"SELECT * FROM pekko_table_tasks WHERE phase IN $occupied")(decode)
  private def fits(d: TableResourceDemand, current: Vector[ScheduledTable]): Boolean = {
    val usage = current.flatMap(_.definition.demand.connections).groupMapReduce(_._1)(_._2)(_ + _)
    current.size < limits.maxTables && current.map(_.definition.demand.pageReservation).sum + d.pageReservation <= limits.maxPageReservation &&
      d.connections.forall { case (key, n) => usage.getOrElse(key, 0) + n <= limits.maxConnectionsPerInstance }
  }
  def claim(requested: Int): Vector[TableGrant] = transaction { c =>
    val due = query(c, "SELECT * FROM pekko_table_tasks WHERE phase='waiting' AND not_before<=? ORDER BY not_before,workflow_id LIMIT ?", Seq(System.currentTimeMillis(), limits.candidatePage))(decode)
    due.foreach(t => update(c, "UPDATE pekko_table_tasks SET phase='ready',queue_order=? WHERE workflow_id=?", next(c), t.workflowId))
    var current = active(c)
    val page = math.min(math.max(requested, 0), math.min(limits.candidatePage,math.max(0,limits.maxStarts-current.count(t=>Set("held","recovering").contains(t.state)))))
    val out = Vector.newBuilder[TableGrant]
    var count = 0
    if (current.size < limits.maxTables && page > 0) {
      val ready = query(c, s"SELECT $summaryColumns FROM pekko_table_tasks WHERE phase='ready' ORDER BY queue_order,workflow_id LIMIT ?", Seq(limits.candidatePage))(summary)
      ready.foreach { t =>
        if (count < page && fits(t.demand, current)) {
          val id = java.util.UUID.randomUUID().toString
          update(c, "UPDATE pekko_table_tasks SET phase='held',grant_id=?,owner_id=NULL WHERE workflow_id=?", id, t.workflowId)
          val saved = get(c, t.workflowId).get; current :+= saved; count += 1; out += TableGrant(saved, id)
        } else if (!fits(t.demand, Vector.empty)) {
          update(c, "UPDATE pekko_table_tasks SET phase='blocked' WHERE workflow_id=?", t.workflowId)
        } else if (count < page) update(c, "UPDATE pekko_table_tasks SET queue_order=? WHERE workflow_id=?", next(c), t.workflowId)
      }
    }
    out.result()
  }
  def held(limit: Int): Vector[TableGrant] = read(c => query(c,
    "SELECT * FROM pekko_table_tasks WHERE phase IN ('held','recovering','running','drained') ORDER BY queue_order LIMIT ?", Seq(math.min(limit, limits.maxTables)))(decode).map(t => TableGrant(t, t.grantId.get)))
  def bind(g: TableGrant, node: String): Boolean = if (ownership.nonEmpty) ownership.get.activate(g,node) else transaction { c =>
    require(node.nonEmpty && node.length <= 128)
    get(c, g.task.workflowId) match {
      case Some(t) if t.grantId.contains(g.id) && t.definition == g.task.definition && t.executionId == g.task.executionId && t.state == "running" => t.owner.contains(node)
      case Some(t) if t.grantId.contains(g.id) && t.definition == g.task.definition && t.executionId == g.task.executionId && t.state == "held" =>
        if (active(c).count(_.owner.contains(node)) >= limits.maxTablesPerNode) false
        else { update(c, "UPDATE pekko_table_tasks SET phase='running',owner_id=? WHERE workflow_id=? AND grant_id=?", node, t.workflowId, g.id); true }
      case _ => false
    }
  }
  def release(g: TableGrant, disposition: String, releaseConfirmed: Boolean, caller: Option[String] = None): Boolean = transaction { c =>
    require(Set("ready", "completed", "blocked").contains(disposition))
    get(c, g.task.workflowId) match {
      case Some(t) if t.grantId.contains(g.id) && t.executionId == g.task.executionId && Set("held", "recovering", "running", "drained").contains(t.state) &&
        ownership.forall(_.canRelease(c,t,caller)) =>
        if (!releaseConfirmed) update(c, "UPDATE pekko_table_tasks SET phase='unknown' WHERE workflow_id=?", t.workflowId)
        else update(c, "UPDATE pekko_table_tasks SET phase=?,queue_order=?,grant_id=NULL,owner_id=NULL WHERE workflow_id=?",
          disposition, next(c), t.workflowId)
        ownership.foreach(_.released(c,t,disposition,releaseConfirmed))
        true
      case _ => false
    }
  }
  def listAfter(after: Option[String], requested: Int): Vector[ScheduledTableSummary] = read { c =>
    val limit = math.min(math.max(requested, 1), 100)
    query(c, s"SELECT $summaryColumns FROM pekko_table_tasks WHERE workflow_id>? ORDER BY workflow_id LIMIT ?", Seq(after.getOrElse(""), limit))(summary)
  }
}
