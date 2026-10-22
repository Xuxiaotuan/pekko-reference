package cn.xuyinyin.magic.workflow.scheduling

import cn.xuyinyin.magic.workflow.checkpoint.BatchId
import cn.xuyinyin.magic.workflow.execution.WorkflowValidator
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorSystem, Extension, ExtensionId, DispatcherSelector}
import org.apache.pekko.actor.CoordinatedShutdown
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import spray.json._
import java.util.concurrent.{ConcurrentHashMap, RejectedExecutionException}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Bounded asynchronous SQL facade; no waiting Future or Actor per queued table. */
final class TableSchedulingRuntime(val store: TableSchedulingStore, val nodeId: String,
  aliases: Map[String,String] = Map.empty, maxPendingOperations: Int = 64)(implicit ec: ExecutionContext) {
  private val pending = new AtomicInteger()
  private val owned = new ConcurrentHashMap[String,TableGrant]()
  private val attached = scala.collection.mutable.Map.empty[String,org.apache.pekko.actor.typed.ActorRef[_]]
  private var watcher = Option.empty[org.apache.pekko.actor.typed.ActorRef[TableSchedulingRuntime.WatchCommand]]
  private val peakAttached = new AtomicInteger()
  require(maxPendingOperations > 0)
  def owns(grant: TableGrant): Boolean = Option(owned.get(grant.task.executionId.get)).exists(_.id == grant.id)
  def attachedCount: Int = synchronized(attached.size)
  def maxAttachedCount: Int = peakAttached.get()
  def attach(id: String, ref: org.apache.pekko.actor.typed.ActorRef[_])(implicit system: ActorSystem[_]): Boolean = synchronized {
    attached.get(id) match {
      case Some(existing) => existing == ref
      case None if attached.size >= store.limits.maxTablesPerNode => false
      case None =>
        attached.update(id,ref)
        peakAttached.accumulateAndGet(attached.size,math.max)
        if(watcher.isEmpty) watcher=Some(system.systemActorOf(TableSchedulingRuntime.watch(this),"table-admission-watch-"+java.util.UUID.randomUUID()))
        watcher.get ! TableSchedulingRuntime.Watch(id,ref)
        true
    }
  }
  private[scheduling] def detached(id: String, ref: org.apache.pekko.actor.typed.ActorRef[_]): Unit = synchronized {
    if(attached.get(id).contains(ref))attached.remove(id)
  }
  private def canonical(value: JsValue): String = value match {
    case JsObject(fields) => fields.toVector.sortBy(_._1).map { case (k,v) => JsString(k).compactPrint+":"+canonical(v) }.mkString("{",",","}")
    case JsArray(values) => values.map(canonical).mkString("[",",","]")
    case other => other.compactPrint
  }
  def io[A](work: => A): Future[A] = {
    if (pending.incrementAndGet() > maxPendingOperations) {
      pending.decrementAndGet(); Future.failed(new RejectedExecutionException("table scheduling capacity exhausted"))
    } else {
      val f = try Future(work) catch { case NonFatal(e) => Future.failed(e) }
      f.transform { value => pending.decrementAndGet(); value }(ExecutionContext.parasitic)
    }
  }
  private def positive(node: Node, key: String, default: Int): Int = node.config.fields.get(key) match {
    case None => default
    case Some(JsNumber(v)) if v.isValidInt && v.toInt > 0 => v.toInt
    case _ => throw new IllegalArgumentException(s"invalid scheduling resource parameter: $key")
  }
  private def instance(node: Node): String = {
    val host = node.config.fields.get("host").collect { case JsString(v) if v.nonEmpty => v.toLowerCase(java.util.Locale.ROOT) }
      .getOrElse(throw new IllegalArgumentException("managed snapshot host is required"))
    val endpoint = host + ":" + positive(node,"port",3306)
    aliases.getOrElse(endpoint,endpoint)
  }
  def definition(workflow: Workflow, revision: Long): ScheduledTableDefinition = {
    val pipeline = WorkflowValidator.validate(workflow).fold(e => throw new IllegalArgumentException(e.map(_.code).mkString(",")), identity)
    require(pipeline.source.nodeType == "mysql.snapshot" && pipeline.source.config.fields.contains("splitRows") && pipeline.sink.nodeType == "mysql.write")
    ScheduledTableDefinition(workflow.id, revision, canonical(workflow.toJson),
      TableResourceDemand(instance(pipeline.source),instance(pipeline.sink),positive(pipeline.source,"splitParallelism",2),positive(pipeline.source,"maxPageBytes",1048576)))
  }
  def executionId(workflowId: String, request: String): String = BatchId.sha256("scheduled-snapshot",workflowId,request,0L)
  def submit(workflow: Workflow, request: String, revision: Long = 1L, notBefore: Long = 0L): Future[ScheduledTable] = {
    val d = definition(workflow,revision)
    io(store.enqueue(d,executionId(workflow.id,request),request,notBefore))
  }
  def register(workflow: Workflow, revision: Long): Future[ScheduledTable] = {
    val d = definition(workflow,revision); io(store.register(d))
  }
  def enqueue(workflow: Workflow, revision: Long, id: String, request: String): Future[ScheduledTable] = {
    val d = definition(workflow,revision); io(store.enqueue(d,id,request))
  }
  def find(workflowId: String): Future[Option[ScheduledTable]] = io(store.get(workflowId))
  def listAfter(after: Option[String], limit: Int): Future[Vector[ScheduledTableSummary]] = io(store.listAfter(after,limit))
  def summary(id: String): Future[Option[ScheduledTableSummary]] = io(store.findSummary(id))
  def bind(grant: TableGrant): Future[Boolean] = io {
    // A previous window may be committed READY while its release reply is still in flight.
    // Leave the new grant HELD until the local old disposition has been reconciled.
    if(Option(owned.get(grant.task.executionId.get)).exists(_.id != grant.id)) false
    else {
    def remember(): Unit = {
      val previous=owned.putIfAbsent(grant.task.executionId.get,grant)
      require(previous==null || previous.id==grant.id,"another local grant still owns the execution")
    }
    val bound = try store.bind(grant,nodeId) catch { case NonFatal(error) =>
      val recorded=try store.get(grant.task.workflowId) catch { case NonFatal(_) => None }
      recorded.filter(t=>t.grantId.contains(grant.id) && t.executionId==grant.task.executionId && t.definition==grant.task.definition) match {
        case Some(t) if t.state=="running" && t.owner.contains(nodeId) => true
        case Some(t) if t.state=="held" && t.owner.isEmpty => remember(); throw error
        case _ => throw new cn.xuyinyin.magic.workflow.engine.CdcExecutionException(
          cn.xuyinyin.magic.workflow.engine.ExecutionFailure(cn.xuyinyin.magic.workflow.engine.ExecutionFailure.ReleaseUnknown,
            "table-admission",releaseConfirmed=false),error)
      }
    }
    val unstarted = !bound && store.get(grant.task.workflowId).exists(t => t.state == "held" && t.grantId.contains(grant.id) && t.owner.isEmpty)
    if (bound || unstarted) {
      remember()
    }
    bound
    }
  }
  def release(executionId: String, disposition: String, confirmed: Boolean, expectedGrantId: Option[String] = None): Future[Done] = {
    val grant = Option(owned.get(executionId))
    if(grant.exists(g => expectedGrantId.exists(_ != g.id))) return io {
      // A stale retry must never UPDATE the current grant. If a deferred new grant
      // encounters an old local mapping, clear it only after SQL confirms it is gone.
      val g=grant.get
      val durable=store.get(g.task.workflowId).getOrElse(throw new IllegalStateException("scheduling disposition is not confirmed"))
      if(!durable.grantId.contains(g.id))owned.remove(executionId,g)
      Done
    }
    grant.fold(Future.successful(Done)) { g => io {
      def recorded: Boolean = store.get(g.task.workflowId).exists { t =>
        if(confirmed) !t.grantId.contains(g.id)
        else t.grantId.contains(g.id) && t.state=="unknown"
      }
      val changed = try store.release(g,disposition,confirmed) catch {
        case NonFatal(error) => if(recorded) true else throw error
      }
      if(!changed && !recorded)throw new IllegalStateException("scheduling disposition is not confirmed")
      if (confirmed) owned.remove(executionId,g)
      Done
    }}
  }
  def defer(grant: TableGrant): Future[Done] = io {
    if(store.get(grant.task.workflowId).exists(t => t.state=="held" && t.owner.isEmpty && t.grantId.contains(grant.id)))
      store.release(grant,"ready",releaseConfirmed=true)
    Done
  }
  def quarantine(grant: TableGrant): Future[Done] = io { store.release(grant,"blocked",releaseConfirmed=false); Done }
  def reject(grant: TableGrant): Future[Done] = io {
    if(store.get(grant.task.workflowId).exists(t => t.state=="held" && t.owner.isEmpty && t.grantId.contains(grant.id)))
      store.release(grant,"blocked",releaseConfirmed=true)
    Done
  }
  def dispatchBatch(): Future[Vector[TableGrant]] = io {
    val fresh = store.claim(store.limits.maxStarts)
    (store.held(store.limits.maxTables) ++ fresh).groupBy(_.id).valuesIterator.map(_.head).toVector
  }
  def inFlightOperations: Int = pending.get()
}

private[scheduling] object TableSchedulingRuntime {
  sealed trait WatchCommand
  final case class Watch(id: String,ref: org.apache.pekko.actor.typed.ActorRef[_]) extends WatchCommand
  private final case class Gone(id: String,ref: org.apache.pekko.actor.typed.ActorRef[_]) extends WatchCommand
  def watch(runtime: TableSchedulingRuntime): org.apache.pekko.actor.typed.Behavior[WatchCommand] =
    org.apache.pekko.actor.typed.scaladsl.Behaviors.setup { context =>
      org.apache.pekko.actor.typed.scaladsl.Behaviors.receiveMessage {
        case Watch(id,ref) => context.watchWith(ref,Gone(id,ref)); org.apache.pekko.actor.typed.scaladsl.Behaviors.same
        case Gone(id,ref) => runtime.detached(id,ref); org.apache.pekko.actor.typed.scaladsl.Behaviors.same
      }
    }
}

final class TableSchedulingExtension(system: ActorSystem[_]) extends Extension {
  val runtime: Option[TableSchedulingRuntime] = {
    val root = system.settings.config
    if (!root.hasPath("pekko.workflow.table-scheduling.enabled") || !root.getBoolean("pekko.workflow.table-scheduling.enabled")) None else {
      val c = root.getConfig("pekko.workflow.table-scheduling")
      val db = c.getConfig("jdbc")
      val pool = new HikariConfig()
      pool.setJdbcUrl(db.getString("url"));pool.setUsername(db.getString("username"));pool.setPassword(db.getString("password"))
      pool.setMaximumPoolSize(2);pool.setMinimumIdle(0);pool.setConnectionTimeout(10000);pool.setInitializationFailTimeout(-1)
      val ds = new HikariDataSource(pool)
      CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate,"close-table-scheduling-pool") { () =>
        Future { ds.close(); Done }(system.dispatchers.lookup(DispatcherSelector.fromConfig("pekko.workflow.table-scheduling.jdbc-dispatcher")))
      }
      val limits = SchedulingLimits(c.getInt("max-active-tables"),c.getInt("max-active-tables-per-node"),c.getInt("max-connections-per-instance"),
        c.getInt("max-concurrent-starts"),c.getBytes("max-page-reservation"),c.getInt("candidate-page"))
      val aliases = c.getConfigList("instance-aliases").asScala.map(a => a.getString("from").toLowerCase(java.util.Locale.ROOT) -> a.getString("to")).toMap
      Some(new TableSchedulingRuntime(new TableSchedulingStore(ds,limits),java.util.UUID.randomUUID().toString,aliases,c.getInt("max-pending-operations"))(
        system.dispatchers.lookup(DispatcherSelector.fromConfig("pekko.workflow.table-scheduling.jdbc-dispatcher"))))
    }
  }
}
object TableSchedulingExtension extends ExtensionId[TableSchedulingExtension] {
  override def createExtension(system: ActorSystem[_]): TableSchedulingExtension = new TableSchedulingExtension(system)
}
