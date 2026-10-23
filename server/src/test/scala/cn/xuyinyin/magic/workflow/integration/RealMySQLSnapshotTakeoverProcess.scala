package cn.xuyinyin.magic.workflow.integration

import CdcJournalWindowSupport._
import TakeoverTestWire.grantFormat
import cn.xuyinyin.magic.connectors.mysql.source.MySQLSnapshotSourceNode
import cn.xuyinyin.magic.connectors.mysql.sink.MySQLSinkNode
import cn.xuyinyin.magic.infrastructure.cluster.sharding.WorkflowSharding
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.execution._
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.engine.ExecutionResult
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.scheduling._
import cn.xuyinyin.magic.workflow.snapshot._
import cn.xuyinyin.magic.workflow.spi.BatchSourceSession
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariDataSource
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.typed.{Cluster,Join}
import org.apache.pekko.cluster.typed.{ClusterSingleton,SingletonActor}
import cn.xuyinyin.magic.workflow.scheduler.{SchedulerCoordinator,ScheduleCalculator}
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import spray.json._
import java.sql.Connection
import java.lang.reflect.{Proxy,InvocationTargetException}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Await,ExecutionContext,Future,Promise}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Gates alter timing only. Admission, JDBC attribution, writer recovery and retirement use production implementations. */
object RealMySQLSnapshotTakeoverProcess {
  def main(args:Array[String]):Unit={
    require(args.length==6,"schema, boot, mode, scenario, gate and seed required")
    require(sys.env.get("RUN_MYSQL_SNAPSHOT_TAKEOVER").contains("1"))
    val Array(schema,boot,mode,scenario,gate,seed)=args
    val s=Settings.environment();validateSchema(schema)
    val base=ConfigFactory.parseMap(Map[String,AnyRef](
      "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url"->(s.url(schema)+"&socketTimeout=120000&connectTimeout=5000"),
      "pekko-persistence-jdbc.shared-databases.slick.db.user"->s.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password"->s.password,
      "pekko.cluster.downing-provider-class"->"",
      "pekko.workflow.table-scheduling.enabled"->java.lang.Boolean.TRUE,
      "pekko.workflow.table-scheduling.jdbc.url"->s.url(schema),
      "pekko.workflow.table-scheduling.jdbc.username"->s.user,
      "pekko.workflow.table-scheduling.jdbc.password"->s.password,
      "pekko.workflow.table-scheduling.max-active-tables"->Int.box(2),
      "pekko.workflow.table-scheduling.max-active-tables-per-node"->Int.box(1),
      "pekko.workflow.table-scheduling.max-connections-per-instance"->Int.box(4),
      "pekko.workflow.table-scheduling.max-concurrent-starts"->Int.box(1),
      "pekko.workflow.table-scheduling.max-page-reservation"->"24 KiB",
      "pekko.workflow.table-scheduling.candidate-page"->Int.box(16),
      "pekko.workflow.table-scheduling.max-pending-operations"->Int.box(64),
      "pekko.workflow.table-scheduling.instance-aliases"->java.util.Collections.emptyList[AnyRef](),
      "pekko.workflow.snapshot-ha.instances"->java.util.Collections.emptyList[AnyRef](),
      "pekko.workflow.snapshot-ha.enabled"->java.lang.Boolean.TRUE,
      "pekko.workflow.snapshot-ha.owner-id"->boot,
      "pekko.workflow.snapshot-ha.deployment-id"->"takeover-experiment",
      "pekko.workflow.event-sourcing.snapshot-every"->Int.box(if(scenario=="KILL_DB_SNAPSHOT_WAIT")1 else 1000)
    ).asJava).withFallback(ConfigFactory.load("application-multinode-test")).withFallback(ConfigFactory.load())
    val prepared=SnapshotOwnerBootstrap.prepare(base)
    if(mode=="bootstrap-gated") {
      emit("WORKER_READY","owner"->JsString(boot),"pid"->JsNumber(ProcessHandle.current().pid()),"mode"->JsString(mode),"actorSystemCreated"->JsBoolean(false))
      scala.io.Source.stdin.getLines().foreach(_=>())
      return
    }
    val config=if(mode!="recovery-gated")prepared else {
      System.setProperty("takeover.test.recovery-gate",gate+"-recovery")
      ConfigFactory.parseMap(Map[String,AnyRef]("pekko-persistence-jdbc.shared-databases.slick.db.driver"->classOf[TakeoverRecoveryJournalDriver].getName).asJava).withFallback(prepared)
    }
    val kit=ActorTestKit("snapshot-takeover",config)
    implicit val system:org.apache.pekko.actor.typed.ActorSystem[_]=kit.system
    implicit val ec:ExecutionContext=system.executionContext
    val cluster=Cluster(system)
    cluster.manager ! Join(if(seed=="self")cluster.selfMember.address else org.apache.pekko.actor.AddressFromURIString(seed))
    awaitCondition("worker joined the same cluster",40.seconds){cluster.selfMember.status==MemberStatus.Up}
    val release=Promise[Done]();val armed=new AtomicBoolean(mode=="fresh")
    val candidateGate=new AtomicBoolean(mode!="fresh" && scenario=="STALE_GRANT")
    def identity(b:SourceBatch):JsObject=JsObject("batchId"->JsString(b.batchId),"split"->JsString(b.partitionId),"sequence"->JsNumber(b.batchSequence),"rows"->JsArray(b.rows.map(_.parseJson)))
    def cut(window:String,b:SourceBatch):Unit=emit("CUTPOINT","window"->JsString(window),"batch"->identity(b))
    val source=new MySQLSnapshotSourceNode {
      override def openSplit(n:Node,e:String,p:SnapshotSplitPlanState,split:SnapshotSplit,cp:Option[BatchCheckpoint],log:String=>Unit,closeEc:ExecutionContext)(implicit x:ExecutionContext):BatchSourceSession={
        emit("READER_OPEN","split"->JsString(split.id),"resume"->cp.map(v=>JsString(v.cursor.value)).getOrElse(JsNull))
        val session=super.openSplit(n,e,p,split,cp,log,closeEc)(x)
        BatchSourceSession(session.source.mapAsync(1){ b=>
          emit("PAGE_READ","batch"->identity(b))
          if(candidateGate.compareAndSet(true,false)) {
            emit("CANDIDATE_READ_GATE");release.future.map(_=>b)(x)
          } else if(Set("SIGSTOP_READ","KILL_READ","OBSERVER_DENIED","KILL_DB_SNAPSHOT_WAIT","COORDINATOR_RESTART","B_RECOVERY_CRASH","COMPETING_CANDIDATES","STALE_GRANT","FENCE_COMMIT_REPLY_LOST","DRAIN_COMMIT_REPLY_LOST","QUOTA_COMMIT_REPLY_LOST","REVOKE_COMMIT_REPLY_LOST","WRONG_ISOLATION_RECEIPT","OBSERVER_SEPARATE_ACCOUNT","CRON_RECOVERY","KILL_BOOTSTRAP","STARTUP_COMMIT_REPLY_LOST").contains(scenario)&&armed.compareAndSet(true,false)) {
            cut("READ_BEFORE_T",b);release.future.map(_=>b)(x)
          } else Future.successful(b)
        },()=>session.close())
      }
    }
    val sink=new MySQLSinkNode {
      override def commitBatch(n:Node,w:String,e:String,b:SourceBatch,rows:Vector[String],log:String=>Unit)(implicit x:ExecutionContext):Future[BatchCommitResult]={
        val inject=Set("KILL_TARGET_TX","KILL_DB_TARGET_WAIT").contains(scenario)&&armed.compareAndSet(true,false)
        val delegate=if(inject)new MySQLSinkNode {
          override def createSplitDataSource(h:String,p:Int,d:String,u:String,pw:String):HikariDataSource={
            val ds=super.createSplitDataSource(h,p,d,u,pw)
            new HikariDataSource() {
              override def getConnection:Connection={
                val c=ds.getConnection
                Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),(_,method,a)=>{
                  if(method.getName=="commit") {
                    emit("TARGET_TRANSACTION_OPEN","batch"->identity(b));cut(scenario,b)
                    if(scenario=="KILL_DB_TARGET_WAIT") {
                      val q=c.prepareStatement("SELECT GET_LOCK(?,90)");q.setString(1,gate)
                      try {val r=q.executeQuery();try require(r.next()&&r.getInt(1)==1)finally r.close()}finally q.close()
                      executeSql(c,s"DO RELEASE_LOCK('$gate')")
                    } else Await.result(release.future,90.seconds)
                  }
                  try method.invoke(c,Option(a).getOrElse(Array.empty[AnyRef]):_*)catch{case error:InvocationTargetException=>throw error.getCause}
                }).asInstanceOf[Connection]
              }
              override def close():Unit=ds.close()
            }
          }
        }else this
        val result=if(inject)delegate.commitBatch(n,w,e,b,rows,log)(x)else super.commitBatch(n,w,e,b,rows,log)(x)
        result.flatMap { committed=>
          emit("T_CONFIRMED","batch"->identity(b),"replay"->JsBoolean(committed.isInstanceOf[AlreadyCommitted]))
          if(scenario=="KILL_T_BEFORE_J"&&armed.compareAndSet(true,false)){cut(scenario,b);release.future.map(_=>committed)(x)}else Future.successful(committed)
        }(x)
      }
    }
    val engine=new WorkflowExecutionEngine(nodeRegistry=NodeRegistry.builtins().withSource(source).withSink(sink)) {
      override def execute(w:Workflow,c:ReliableRunContext,log:String=>Unit):Future[ExecutionResult]={
        emit("ENGINE_STARTED","executionId"->JsString(c.executionId),"grant"->c.schedulingGrant.map(g=>JsString(g.id)).getOrElse(JsNull))
        val wrapped=c.splitContext.map(original=>original.copy(update=u=>{
          val before=scenario=="KILL_DB_JOURNAL_WAIT"&&u.isInstanceOf[CommitSnapshotPage]&&armed.compareAndSet(true,false)
          val begin=if(before){emit("BEFORE_JOURNAL_PAGE");release.future}else Future.successful(Done)
          begin.flatMap(_=>original.update(u))(ec).flatMap { state=>
            emit("J_CONFIRMED","update"->JsString(u.getClass.getSimpleName),"rows"->JsNumber(state.rowsCommitted))
            if(scenario=="KILL_J_AFTER_STORE"&&u.isInstanceOf[CommitSnapshotPage]&&armed.compareAndSet(true,false)) {
              emit("CUTPOINT","window"->JsString(scenario));release.future.map(_=>state)(ec)
            } else Future.successful(state)
          }(ec)
        }))
        super.execute(w,c.copy(splitContext=wrapped),m=>emit("ENGINE_LOG","message"->JsString(m)))
      }
    }
    val runtime=engine.tableScheduling.get
    val region=WorkflowSharding.init(system,engine,10)
    kit.spawn(BoundedTableDispatcher(runtime,region,100.millis))
    val scheduler=if(scenario=="CRON_RECOVERY")Some(ClusterSingleton(system).init(SingletonActor(SchedulerCoordinator(region,retryBase=2.seconds),"SchedulerCoordinator")))else None
    def fire(at:Long):Unit={
      val ref=scheduler.get;ref ! SchedulerCoordinator.Fire("cron",at)
      val probe=kit.createTestProbe[SchedulerCoordinator.State]()
      awaitCondition("durable scheduler trigger acknowledged",20.seconds) {
        ref ! SchedulerCoordinator.GetState(probe.ref)
        probe.receiveMessage(3.seconds).pendingTriggers.isEmpty
      }
    }
    def coldQueries():Unit={
      val summary=kit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
      val status=kit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      val history=kit.createTestProbe[EventSourcedWorkflowActor.ExecutionHistoryResponse]()
      region ! org.apache.pekko.cluster.sharding.typed.ShardingEnvelope("__readiness_probe__",EventSourcedWorkflowActor.GetSummary(summary.ref))
      require(summary.receiveMessage(10.seconds).workflowId=="__readiness_probe__")
      region ! org.apache.pekko.cluster.sharding.typed.ShardingEnvelope("table-00",EventSourcedWorkflowActor.GetStatus(status.ref))
      val state=status.receiveMessage(10.seconds)
      region ! org.apache.pekko.cluster.sharding.typed.ShardingEnvelope("table-00",EventSourcedWorkflowActor.GetExecutionHistory(0,20,history.ref))
      val recent=history.receiveMessage(10.seconds)
      if(state.state=="completed")require(recent.executions.size==2 && recent.executions.exists(_.status=="skipped"),"completed and skipped history must survive cold reads")
      emit("COLD_QUERY_RESULT","status"->JsString(state.state),"history"->JsString(recent.toString))
    }
    if(mode=="fresh") {
      if(scheduler.nonEmpty) {
        Await.result(runtime.register(OwnerIsolationProbeSupport.workflow(s,schema),1L),20.seconds)
        coldQueries()
        val reply=kit.createTestProbe[SchedulerCoordinator.ScheduleReply]()
        scheduler.get ! SchedulerCoordinator.Add(SchedulerCoordinator.Schedule("cron","table-00",ScheduleCalculator.CronSchedule("0 0 0 1 1 ?")),reply.ref)
        require(reply.receiveMessage(20.seconds)==SchedulerCoordinator.ScheduleAdded("cron"))
        fire(1000)
      }else Await.result(runtime.submit(OwnerIsolationProbeSupport.workflow(s,schema),"takeover-one"),20.seconds)
    }
    emit("WORKER_READY","owner"->JsString(boot),"pid"->JsNumber(ProcessHandle.current().pid()),"address"->JsString(cluster.selfMember.address.toString),
      "uid"->JsNumber(cluster.selfMember.uniqueAddress.longUid),"mode"->JsString(mode),"automaticDispatch"->JsBoolean(true))
    try scala.io.Source.stdin.getLines().foreach {
      case "RELEASE"=>release.trySuccess(Done)
      case "MEMBERS"=>emit("MEMBERS","members"->JsArray(cluster.state.members.toVector.map(m=>JsString(m.uniqueAddress.toString))))
      case "CRON_OVERLAP"=>fire(2000);emit("CRON_OVERLAP_ACK")
      case "CRON_DUPLICATE"=>fire(1000);fire(1500);emit("CRON_DUPLICATE_ACK")
      case "COLD_QUERY"=>coldQueries()
      case command if command.startsWith("STALE ")=>
        val old=command.stripPrefix("STALE ").parseJson.convertTo[TableGrant]
        region ! org.apache.pekko.cluster.sharding.typed.ShardingEnvelope("table-00",cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.ActivateSnapshot(old))
        val probe=kit.createTestProbe[cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.StatusResponse]()
        region ! org.apache.pekko.cluster.sharding.typed.ShardingEnvelope("table-00",cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.GetStatus(probe.ref))
        val status=probe.receiveMessage(10.seconds)
        emit("STALE_PROCESSED_STATUS","oldGrant"->JsString(old.id),"status"->JsString(status.state))
      case _=>()
    } finally {release.trySuccess(Done);kit.shutdownTestKit()}
  }
}
