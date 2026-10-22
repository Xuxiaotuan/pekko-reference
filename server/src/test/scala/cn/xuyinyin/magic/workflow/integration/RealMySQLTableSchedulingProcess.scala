package cn.xuyinyin.magic.workflow.integration

import CdcJournalWindowSupport._
import cn.xuyinyin.magic.connectors.mysql.source.MySQLSnapshotSourceNode
import cn.xuyinyin.magic.connectors.mysql.sink.MySQLSinkNode
import cn.xuyinyin.magic.infrastructure.cluster.sharding.WorkflowSharding
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.execution._
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.engine.ExecutionResult
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.scheduling._
import cn.xuyinyin.magic.workflow.snapshot._
import cn.xuyinyin.magic.workflow.spi.BatchSourceSession
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.typed.{Cluster,Join}
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.sharding.typed.GetClusterShardingStats
import org.apache.pekko.cluster.sharding.ShardRegion.ClusterShardingStats
import spray.json._
import java.util.concurrent.atomic.{AtomicBoolean,AtomicInteger}
import java.sql.{Connection,PreparedStatement}
import java.lang.reflect.{Method,Proxy,InvocationTargetException}
import scala.concurrent.{Await,ExecutionContext,Future,Promise}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Test-only gates preserve actual SQL commits, Source reads, Journal events and shared production scheduling. */
object RealMySQLTableSchedulingProcess {
  def main(args:Array[String]):Unit={
    val schema=args(0);val mode=args(1);val fault=args(2);val settings=Settings.environment()
    val config=ConfigFactory.parseMap(Map[String,AnyRef](
      "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url"->settings.url(schema),
      "pekko-persistence-jdbc.shared-databases.slick.db.user"->settings.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password"->settings.password,
      "pekko.cluster.sharding.remember-entities"->Boolean.box(true),
      "pekko.cluster.sharding.rebalance-interval"->"1h",
      "pekko.workflow.event-sourcing.snapshot-every"->Int.box(25),
      "pekko.loglevel"->"WARNING","pekko.stdout-loglevel"->"WARNING"
    ).asJava).withFallback(ConfigFactory.load("application-multinode-test"))
    val kit=ActorTestKit("table-scheduling-"+ProcessHandle.current().pid(),config)
    implicit val system:org.apache.pekko.actor.typed.ActorSystem[_]=kit.system
    implicit val ec:ExecutionContext=system.executionContext
    val cluster=Cluster(system);cluster.manager ! Join(cluster.selfMember.address)
    awaitCondition("single worker cluster up",30.seconds){cluster.selfMember.status==MemberStatus.Up}
    val held=Promise[Done]();val targetHeld=Promise[Done]();val armed=new AtomicBoolean(mode=="fresh")
    val readArmed=new AtomicBoolean(mode=="fresh");val controlArmed=new AtomicBoolean(mode=="fresh")
    val rejectReady=new AtomicBoolean(false)
    val dispositionGrant=new java.util.concurrent.atomic.AtomicReference[String]()
    val dispositionCalls=new AtomicInteger()
    val holdNextRead=new AtomicBoolean(false)
    def call(target:AnyRef,m:Method,args:Array[AnyRef]):AnyRef=try m.invoke(target,Option(args).getOrElse(Array.empty[AnyRef]):_*)
      catch {case e:InvocationTargetException=>throw e.getCause}
    val poolConfig=new HikariConfig();poolConfig.setJdbcUrl(settings.url(schema));poolConfig.setUsername(settings.user);poolConfig.setPassword(settings.password)
    poolConfig.setMaximumPoolSize(2);poolConfig.setMinimumIdle(0);poolConfig.setConnectionTimeout(10000)
    val controlPool=new HikariDataSource(poolConfig)
    val schedulingDataSource=new HikariDataSource(){
      override def getConnection:Connection={
        val cn=controlPool.getConnection;var lose=false;var holdReply=false
        Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),(_,method,parameters)=>{
          if(method.getName=="prepareStatement" && parameters!=null && parameters.nonEmpty && parameters(0).isInstanceOf[String]) {
            val sql=parameters(0).toString;val ps=call(cn,method,parameters).asInstanceOf[PreparedStatement]
            val values=scala.collection.mutable.Map.empty[Int,AnyRef]
            Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[PreparedStatement]),(_,m,a)=>{
              if(Set("setString","setLong","setInt").contains(m.getName)) values.update(a(0).asInstanceOf[Integer].intValue(),a(1))
              if(m.getName=="executeUpdate" && sql.contains("SET phase=?,queue_order=?,grant_id=NULL") &&
                values.get(1).contains("ready") && values.get(3).contains("table-00") && rejectReady.compareAndSet(true,false)) {
                emit("CONTROL_RELEASE_REJECTED","fault"->JsString(fault))
                throw new java.sql.SQLTransientConnectionException("test control release rejected before UPDATE","08006")
              }
              val result=call(ps,m,a)
              if(m.getName=="executeUpdate") {
                val release=fault=="RELEASE_REPLY_LOST" && sql.contains("SET phase=?,queue_order=?,grant_id=NULL") && values.get(1).contains("ready") && values.get(3).contains("table-00")
                val grant=fault=="GRANT_REPLY_LOST" && sql.contains("SET phase='held'") && values.get(2).contains("table-00")
                val bind=fault=="BIND_REPLY_LOST" && sql.contains("SET phase='running',owner_id=?") && values.get(2).contains("table-00")
                val heldRelease=fault=="RELEASE_REPLY_HELD" && sql.contains("SET phase=?,queue_order=?,grant_id=NULL") && values.get(1).contains("ready") && values.get(3).contains("table-00") &&
                  withConnection(settings,schema)(query(_,"SELECT COUNT(*) AS n FROM pekko_table_tasks WHERE workflow_id<>'table-00' AND phase='completed'")).elements.head.asJsObject.fields("n")==JsString("15")
                if((release||grant||bind||heldRelease)&&controlArmed.compareAndSet(true,false)){
                  if(heldRelease)holdReply=true else lose=true
                }
              }
              result
            }).asInstanceOf[PreparedStatement]
          } else {
            val result=call(cn,method,parameters)
            if(method.getName=="commit" && holdReply){
              holdReply=false;emit("CUTPOINT","window"->JsString("RELEASE_REPLY_HELD"),"otherTablesCompleted"->JsNumber(15))
              scala.concurrent.Await.result(held.future,30.seconds)
              emit("CONTROL_RELEASE_REPLY_RETURNED")
            }
            if(method.getName=="commit" && lose){lose=false;emit("CONTROL_COMMIT_RESULT_LOST","fault"->JsString(fault));throw new java.sql.SQLTransientConnectionException("test post-COMMIT scheduling observation lost","08006")}
            result
          }
        }).asInstanceOf[Connection]
      }
    }
    val store=new TableSchedulingStore(schedulingDataSource,SchedulingLimits(6,4,16,2,131072,128))
    val aliases=Map(s"${settings.host}:${settings.port}"->"mysql-local",s"localhost:${settings.port}"->"mysql-local")
    val runtime=new TableSchedulingRuntime(store,java.util.UUID.randomUUID().toString,aliases)(
      system.dispatchers.lookup(org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("pekko.workflow.jdbc-dispatcher")))
    val pools=new java.util.concurrent.ConcurrentLinkedQueue[HikariDataSource]()
    val readers=new AtomicInteger();val maxReaders=new AtomicInteger();val engineRuns=new AtomicInteger();val maxRuns=new AtomicInteger()
    val source=new MySQLSnapshotSourceNode {
      override def createSplitDataSource(h:String,p:Int,d:String,u:String,pw:String):HikariDataSource={val ds=super.createSplitDataSource(h,p,d,u,pw);pools.add(ds);ds}
      override def openSplit(n:Node,e:String,p:SnapshotSplitPlanState,s:SnapshotSplit,c:Option[BatchCheckpoint],l:String=>Unit,ce:ExecutionContext)(implicit x:ExecutionContext):BatchSourceSession={
        val count=readers.incrementAndGet();maxReaders.accumulateAndGet(count,math.max)
        emit("READER_OPEN","table"->n.config.fields("table"),"split"->JsString(s.id),"resume"->c.map(v=>JsString(v.cursor.value)).getOrElse(JsNull))
        val delegate=super.openSplit(n,e,p,s,c,l,ce)(x);val closed=new AtomicBoolean(false)
        BatchSourceSession(delegate.source.mapAsync(1){b=>
          emit("PAGE_READ","table"->n.config.fields("table"),"batchId"->JsString(b.batchId),"split"->JsString(b.partitionId),"sequence"->JsNumber(b.batchSequence))
          if(Set("READ_T","STOP_READ_T","STOP_RELEASE_RETRY").contains(fault)&&n.config.fields("table")==JsString("source_0")&&s.index==0&&b.batchSequence==1&&readArmed.compareAndSet(true,false))
            targetHeld.future.flatMap{_=>emit("READ_FAILURE_DURING_T");Future.failed[SourceBatch](new java.sql.SQLTransientConnectionException("test next-page observation failed","08006"))}(ec)
          else if(fault=="STALE_DISPOSITION" && n.config.fields("table")==JsString("source_0") && holdNextRead.get()) {
            emit("NEXT_WINDOW_READ_HELD","batchId"->JsString(b.batchId),"split"->JsString(s.id))
            held.future.map(_=>b)(ec)
          } else Future.successful(b)
        },()=>delegate.close().map{done=>if(closed.compareAndSet(false,true)){readers.decrementAndGet();emit("READER_CLOSED","split"->JsString(s.id))};done}(ec))
      }
    }
    def batchJson(b:SourceBatch):JsObject=JsObject("batchId"->JsString(b.batchId),"split"->JsString(b.partitionId),"sequence"->JsNumber(b.batchSequence),"rows"->JsArray(b.rows.map(_.parseJson)))
    val targetPools=new java.util.concurrent.ConcurrentLinkedQueue[HikariDataSource]()
    val sink=new MySQLSinkNode {
      override def createSplitDataSource(h:String,p:Int,d:String,u:String,pw:String):HikariDataSource={
        val ds=super.createSplitDataSource(h,p,d,u,pw);targetPools.add(ds)
        ds
      }
      override def commitBatch(n:Node,w:String,e:String,b:SourceBatch,rows:Vector[String],l:String=>Unit)(implicit x:ExecutionContext):Future[BatchCommitResult]={
        val zero=n.config.fields("table")==JsString("target_0")&&b.partitionId.endsWith(":0")&&b.batchSequence==0
        val inject=zero && Set("T_J_CRASH","READ_T","STOP_READ_T","STOP_RELEASE_RETRY","COORDINATOR_RESTART","CLOSE_UNKNOWN").contains(fault)&&armed.compareAndSet(true,false)
        val commit=if(inject&&fault=="CLOSE_UNKNOWN") {
          // A per-batch sink avoids another table stealing a process-wide close gate.
          val isolated=new MySQLSinkNode {
            override def createSplitDataSource(h:String,p:Int,d:String,u:String,pw:String):HikariDataSource={
              val ds=super.createSplitDataSource(h,p,d,u,pw);targetPools.add(ds)
              new HikariDataSource(){
                override def getConnection:Connection=ds.getConnection
                override def close():Unit={ds.close();emit("TARGET_CLOSE_OBSERVATION_FAILED","workflow"->JsString(w),"batchId"->JsString(b.batchId));throw new IllegalStateException("test observation failed after physical target close")}
              }
            }
          }
          isolated.commitBatch(n,w,e,b,rows,l)(x)
        }else super.commitBatch(n,w,e,b,rows,l)(x)
        commit.flatMap{result=>
          emit("T_CONFIRMED","workflow"->JsString(w),"batch"->batchJson(b),"replay"->JsBoolean(result.isInstanceOf[AlreadyCommitted]))
          if(inject&&fault!="CLOSE_UNKNOWN"){
            targetHeld.trySuccess(Done);emit("CUTPOINT","window"->JsString(fault),"batch"->batchJson(b));held.future.map(_=>result)(ec)
          }else Future.successful(result)
        }(ec)
      }
    }
    val engine=new WorkflowExecutionEngine(nodeRegistry=NodeRegistry.builtins().withSource(source).withSink(sink)) {
      override def tableScheduling=Some(runtime)
      override def cancel(id:String):Future[Done]={
        emit("CANCEL_STARTED","executionId"->JsString(id))
        super.cancel(id).andThen {case scala.util.Success(_)=>emit("CANCEL_CONFIRMED","executionId"->JsString(id))}(ec)
      }
      override def finishScheduledSnapshot(id:String,disposition:String,released:Boolean,grantId:Option[String]):Future[Done]={
        val tableZero=id==runtime.executionId("table-00","initial-0")
        val eligible=mode=="fresh" && tableZero && disposition=="ready" && released &&
          Set("STALE_DISPOSITION","STOP_RELEASE_RETRY").contains(fault)
        if(eligible && dispositionGrant.get()==null && (fault=="STOP_RELEASE_RETRY" ||
          withConnection(settings,schema)(query(_,"SELECT COUNT(*) AS n FROM pekko_table_tasks WHERE workflow_id<>'table-00' AND phase='completed'")).elements.head.asJsObject.fields("n")==JsString("15")))
          dispositionGrant.compareAndSet(null,grantId.get)
        if(eligible && grantId.contains(dispositionGrant.get())) {
          dispositionCalls.incrementAndGet() match {
            case 1 => rejectReady.set(true);super.finishScheduledSnapshot(id,disposition,released,grantId)
            case 2 if fault=="STALE_DISPOSITION" =>
              holdNextRead.set(true)
              super.finishScheduledSnapshot(id,disposition,released,grantId).flatMap { _=>
                emit("CUTPOINT","window"->JsString(fault),"releasedGrant"->JsString(grantId.get),"oldOwned"->JsBoolean(false))
                held.future
              }(ec)
            case _ => super.finishScheduledSnapshot(id,disposition,released,grantId).map { done=>
              emit("STALE_RETRY_RETURNED","grant"->JsString(grantId.get));done
            }(ec)
          }
        } else super.finishScheduledSnapshot(id,disposition,released,grantId)
      }
      override def execute(w:Workflow,context:ReliableRunContext,log:String=>Unit):Future[ExecutionResult]={
        val count=engineRuns.incrementAndGet();maxRuns.accumulateAndGet(count,math.max)
        emit("ENGINE_STARTED","workflow"->JsString(w.id),"executionId"->JsString(context.executionId),"grant"->context.schedulingGrant.map(g=>JsString(g.id)).getOrElse(JsNull))
        val wrapped=context.splitContext.map(original=>original.copy(update=update=>original.update(update).flatMap{s=>
          emit("J_CONFIRMED","workflow"->JsString(w.id),"update"->JsString(update.getClass.getSimpleName),"state"->stateJson(s))
          val cut=fault=="J_REPLY_LOST"&&w.id=="table-00"&&update.isInstanceOf[CommitSnapshotPage]&&armed.compareAndSet(true,false)
          if(cut){emit("CUTPOINT","window"->JsString(fault));held.future.map(_=>s)(ec)}else Future.successful(s)
        }(ec)))
        val result=super.execute(w,context.copy(splitContext=wrapped),m=>emit("ENGINE_LOG","workflow"->JsString(w.id),"message"->JsString(m)))
        result.andThen{case _=>engineRuns.decrementAndGet()}(ec)
      }
    }
    val region=WorkflowSharding.init(system,engine,10)
    var dispatcher:Option[org.apache.pekko.actor.typed.ActorRef[BoundedTableDispatcher.Command]]=None
    def startDispatcher():Unit={dispatcher=Some(kit.spawn(BoundedTableDispatcher(runtime,region,100.millis)));emit("DISPATCHER_STARTED")}
    if(mode=="fresh") {
      (0 until TableSchedulingExperimentSupport.TableCount).foreach(i=>Await.result(runtime.submit(TableSchedulingExperimentSupport.workflow(settings,schema,i),s"initial-$i"),30.seconds))
      emit("COLD_QUEUED","readers"->JsNumber(readers.get()),"engines"->JsNumber(engineRuns.get()))
    }
    if(fault=="HELD_REDELIVERY"&&mode=="fresh"){
      val grants=store.claim(2);emit("CUTPOINT","window"->JsString(fault),"grants"->JsArray(grants.map(g=>JsString(g.id))))
    }else startDispatcher()
    emit("PROCESS_READY","owner"->JsString(runtime.nodeId),"pid"->JsNumber(ProcessHandle.current().pid()))
    try scala.io.Source.stdin.getLines().foreach {
      case "STATE"=>
        val probe=kit.createTestProbe[ClusterShardingStats]()
        ClusterSharding(system).shardState ! GetClusterShardingStats(WorkflowSharding.TypeKey,2.seconds,probe.ref)
        val stats=probe.receiveMessage(3.seconds)
        val entities=stats.regions.values.map(_.stats.values.sum).sum
        emit("STATE","owner"->JsString(runtime.nodeId),"entities"->JsNumber(entities),"readers"->JsNumber(readers.get()),"maxReaders"->JsNumber(maxReaders.get()),
          "attached"->JsNumber(runtime.attachedCount),"maxAttached"->JsNumber(runtime.maxAttachedCount),
          "engineRuns"->JsNumber(engineRuns.get()),"maxEngineRuns"->JsNumber(maxRuns.get()),"sourcePoolsClosed"->JsBoolean(pools.iterator().asScala.forall(_.isClosed)),
          "targetPoolsClosed"->JsBoolean(targetPools.iterator().asScala.forall(_.isClosed)),
          "tasks"->withConnection(settings,schema)(query(_,"SELECT workflow_id,phase,execution_id,grant_id,owner_id,parallelism,page_bytes,source_instance,target_instance,queue_order FROM pekko_table_tasks ORDER BY workflow_id")))
      case "START_DISPATCHER"=>startDispatcher()
      case "RESTART_DISPATCHER"=>dispatcher.foreach(kit.stop(_));startDispatcher();emit("DISPATCHER_RESTARTED")
      case "RELEASE"=>held.trySuccess(Done)
      case "DROP_J"=>held.tryFailure(new java.util.concurrent.TimeoutException("test lost persisted Journal reply"))
      case "STOP_TABLE"=>
        emit("STOP_REQUESTED","executionId"->JsString(runtime.executionId("table-00","initial-0")))
        region ! org.apache.pekko.cluster.sharding.typed.ShardingEnvelope("table-00",EventSourcedWorkflowActor.Stop)
      case _=>()
    } finally {held.trySuccess(Done);dispatcher.foreach(kit.stop(_));kit.shutdownTestKit();controlPool.close()}
  }
  private def stateJson(s:SnapshotSplitState):JsObject=JsObject("planId"->JsString(s.plan.id),"rowsCommitted"->JsNumber(s.rowsCommitted),"nextIndex"->JsNumber(s.nextIndex),
    "splits"->JsArray(s.splits.map(p=>JsObject("id"->JsString(p.split.id),"done"->JsBoolean(p.completed),"attempts"->JsNumber(p.attempts),
      "sequence"->p.checkpoint.map(c=>JsNumber(c.batchSequence)).getOrElse(JsNull),"blocked"->p.blocked.map(JsString(_)).getOrElse(JsNull)))))
}
