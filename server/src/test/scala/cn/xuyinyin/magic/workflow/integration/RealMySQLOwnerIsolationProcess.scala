package cn.xuyinyin.magic.workflow.integration

import CdcJournalWindowSupport._
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
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.typed.{Cluster,Join}
import spray.json._
import java.sql.Connection
import java.lang.reflect.{Proxy,InvocationTargetException}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Await,ExecutionContext,Future,Promise}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Real worker effects; gates and the candidate's manual start command are laboratory facilities only. */
object RealMySQLOwnerIsolationProcess {
  def main(args:Array[String]):Unit = {
    require(args.length==4,"schema, mode, scenario and gate are required")
    val schema=args(0);val mode=args(1);val scenario=args(2);val gate=args(3)
    require(sys.env.get("RUN_MYSQL_OWNER_ISOLATION").contains("1"))
    val s=Settings.environment();validateSchema(schema)
    val owner=java.util.UUID.randomUUID().toString
    def url(role:String)=s.url(schema)+s"&connectionAttributes=probe_owner:$owner,probe_role:$role"
    val config=ConfigFactory.parseMap(Map[String,AnyRef](
      "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
      "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
      "pekko-persistence-jdbc.shared-databases.slick.db.url"->url("journal"),
      "pekko-persistence-jdbc.shared-databases.slick.db.user"->s.user,
      "pekko-persistence-jdbc.shared-databases.slick.db.password"->s.password,
      "pekko.cluster.sharding.rebalance-interval"->"1h",
      "pekko.workflow.event-sourcing.snapshot-every"->Int.box(1000)
    ).asJava).withFallback(ConfigFactory.load("application-multinode-test"))
    val kit=ActorTestKit("owner-probe-"+ProcessHandle.current().pid(),config)
    implicit val system:org.apache.pekko.actor.typed.ActorSystem[_]=kit.system
    implicit val ec:ExecutionContext=system.executionContext
    val cluster=Cluster(system);cluster.manager ! Join(cluster.selfMember.address)
    awaitCondition("worker cluster up",30.seconds){cluster.selfMember.status==MemberStatus.Up}
    val release=Promise[Done]();val armed=new AtomicBoolean(mode=="fresh")
    val pools=new java.util.concurrent.ConcurrentLinkedQueue[HikariDataSource]()
    def pool(role:String):HikariDataSource = {
      val c=new HikariConfig();c.setJdbcUrl(url(role)+"&socketTimeout=120000&connectTimeout=5000")
      c.setUsername(s.user);c.setPassword(s.password);c.setMaximumPoolSize(if(role=="control")2 else 1)
      c.setMinimumIdle(0);c.setConnectionTimeout(10000)
      val ds=new HikariDataSource(c);pools.add(ds);ds
    }
    val store=new TableSchedulingStore(pool("control"),SchedulingLimits(2,1,4,1,24576,16))
    val runtime=new TableSchedulingRuntime(store,owner)(system.dispatchers.lookup(org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("pekko.workflow.jdbc-dispatcher")))
    def identity(b:SourceBatch):JsObject=JsObject("batchId"->JsString(b.batchId),"split"->JsString(b.partitionId),"sequence"->JsNumber(b.batchSequence),"rows"->JsArray(b.rows.map(_.parseJson)))
    def cut(kind:String,b:SourceBatch):Unit=emit("CUTPOINT","window"->JsString(kind),"batch"->identity(b))
    val source=new MySQLSnapshotSourceNode {
      override def createSplitDataSource(h:String,p:Int,d:String,u:String,pw:String):HikariDataSource=pool("source")
      override def openSplit(n:Node,e:String,p:SnapshotSplitPlanState,split:SnapshotSplit,cp:Option[BatchCheckpoint],log:String=>Unit,closeEc:ExecutionContext)
        (implicit x:ExecutionContext):BatchSourceSession = {
        emit("READER_OPEN","split"->JsString(split.id),"resume"->cp.map(v=>JsString(v.cursor.value)).getOrElse(JsNull))
        val session=super.openSplit(n,e,p,split,cp,log,closeEc)(x)
        BatchSourceSession(session.source.mapAsync(1){b=>
          emit("PAGE_READ","batch"->identity(b))
          if(Set("SIGSTOP_READ","KILL_READ","OBSERVER_DENIED").contains(scenario)&&armed.compareAndSet(true,false)) {
            cut("READ_BEFORE_T",b);release.future.map(_=>b)(x)
          } else Future.successful(b)
        },()=>session.close().map{v=>emit("READER_CLOSED","split"->JsString(split.id));v}(x))
      }
    }
    val sink=new MySQLSinkNode {
      override def createSplitDataSource(h:String,p:Int,d:String,u:String,pw:String):HikariDataSource=pool("target")
      override def commitBatch(n:Node,w:String,e:String,b:SourceBatch,rows:Vector[String],log:String=>Unit)(implicit x:ExecutionContext):Future[BatchCommitResult] = {
        val inject=Set("KILL_TARGET_TX","KILL_DB_TARGET_WAIT").contains(scenario)&&armed.compareAndSet(true,false)
        val delegate=if(inject)new MySQLSinkNode {
          override def createSplitDataSource(h:String,p:Int,d:String,u:String,pw:String):HikariDataSource = {
            val ds=pool("target")
            new HikariDataSource() {
              override def getConnection:Connection = {
                val cn=ds.getConnection
                Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),(_,method,a)=>{
                  if(method.getName=="commit") {
                    val id=query(cn,"SELECT CONNECTION_ID() AS id").elements.head.asJsObject.fields("id")
                    emit("TARGET_TRANSACTION_OPEN","connectionId"->id,"batch"->identity(b))
                    cut(scenario,b)
                    if(scenario=="KILL_DB_TARGET_WAIT") {
                      val st=cn.prepareStatement("SELECT GET_LOCK(?,90)");st.setString(1,gate)
                      try {val rs=st.executeQuery();try require(rs.next()&&rs.getInt(1)==1) finally rs.close()} finally st.close()
                      executeSql(cn,s"DO RELEASE_LOCK('$gate')")
                    } else Await.result(release.future,90.seconds)
                  }
                  try method.invoke(cn,Option(a).getOrElse(Array.empty[AnyRef]):_*) catch {case e:InvocationTargetException=>throw e.getCause}
                }).asInstanceOf[Connection]
              }
              override def close():Unit=ds.close()
            }
          }
        } else this
        val written=if(inject)delegate.commitBatch(n,w,e,b,rows,log)(x) else super.commitBatch(n,w,e,b,rows,log)(x)
        written.flatMap { result=>
          emit("T_CONFIRMED","batch"->identity(b),"replay"->JsBoolean(result.isInstanceOf[AlreadyCommitted]))
          if(scenario=="KILL_T_BEFORE_J"&&armed.compareAndSet(true,false)){cut(scenario,b);release.future.map(_=>result)(x)}
          else Future.successful(result)
        }(x)
      }
    }
    val engine=new WorkflowExecutionEngine(nodeRegistry=NodeRegistry.builtins().withSource(source).withSink(sink)) {
      override def tableScheduling=Some(runtime)
      override def execute(w:Workflow,c:ReliableRunContext,log:String=>Unit):Future[ExecutionResult] = {
        emit("ENGINE_STARTED","executionId"->JsString(c.executionId),"grant"->c.schedulingGrant.map(g=>JsString(g.id)).getOrElse(JsNull))
        val wrapped=c.splitContext.map(original=>original.copy(update=u=>{
          val waitBefore=scenario=="KILL_DB_JOURNAL_WAIT"&&u.isInstanceOf[CommitSnapshotPage]&&armed.compareAndSet(true,false)
          val begin=if(waitBefore){emit("BEFORE_JOURNAL_PAGE");release.future}else Future.successful(Done)
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
    var started=false
    def startWork():Unit={
      require(!started);started=true
      val region=WorkflowSharding.init(system,engine,10)
      kit.spawn(BoundedTableDispatcher(runtime,region,100.millis))
      emit("WORKFLOW_DISPATCH_ENABLED","testOperatorCommand"->JsBoolean(mode!="fresh"))
    }
    if(mode=="fresh") {Await.result(runtime.submit(OwnerIsolationProbeSupport.workflow(s,schema),"isolation-one"),20.seconds);startWork()}
    emit("WORKER_READY","owner"->JsString(owner),"pid"->JsNumber(ProcessHandle.current().pid()),
      "startInstant"->JsString(ProcessHandle.current().info().startInstant().get().toString),"mode"->JsString(mode))
    try scala.io.Source.stdin.getLines().foreach {
      case "RELEASE"=>release.trySuccess(Done)
      case "START"=>startWork()
      case "TRY_OLD_BIND"=>
        val t=store.get("table-00").get
        emit("OLD_BIND_RESULT","allowed"->JsBoolean(Await.result(runtime.bind(TableGrant(t,t.grantId.get)),10.seconds)))
      case "STATE"=>emit("STATE","owner"->JsString(owner),"tasks"->withConnection(s,schema)(query(_,"SELECT workflow_id,phase,execution_id,grant_id,owner_id FROM pekko_table_tasks")))
      case _=>()
    } finally {release.trySuccess(Done);kit.shutdownTestKit();pools.iterator().asScala.foreach(_.close())}
  }
}
