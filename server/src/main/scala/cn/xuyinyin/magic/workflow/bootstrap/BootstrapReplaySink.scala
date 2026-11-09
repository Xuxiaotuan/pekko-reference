package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.connectors.mysql.sink.{MySQLCdcApplyConfig,MySQLCdcApplySinkNode}
import cn.xuyinyin.magic.cdc.{MySQLCdcGroup,MySQLCdcGroupProtocol}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CdcCapturedBatch,CdcCapturedBatchWorkflowAdapter}
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCommitResult,SourceBatch}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.Node
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import java.sql.{Connection,DriverManager}
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}
import scala.concurrent.{ExecutionContext,Future}
import scala.util.Failure
import spray.json._

/** Uses the existing group ledger/DML transaction unchanged, but requires close confirmation.
  * A completed Future alone is insufficient because the legacy node suppresses close failures.
  */
final class BootstrapReplaySink(open:MySQLCdcApplyConfig=>Connection=BootstrapReplaySink.open) {
  /** The workflow node and checkpoint types end at this compatibility adapter. */
  private[magic] def bind(node:Node):CdcReplaySink = new CdcReplaySink {
    override def commitBatch(subscriptionId:String,executionId:String,batch:CdcCapturedBatch,log:String=>Unit)
      (implicit ec:ExecutionContext):Future[Unit] =
      BootstrapReplaySink.this.commitBatch(node,subscriptionId,executionId,
        CdcCapturedBatchWorkflowAdapter.toWorkflow(batch),batch.rows,log).map(_=>())
  }
  private[magic] def bind(config:MySQLCdcApplyConfig):CdcReplaySink = new CdcReplaySink {
    override def commitBatch(subscriptionId:String,executionId:String,batch:CdcCapturedBatch,log:String=>Unit)
      (implicit ec:ExecutionContext):Future[Unit] =
      execute(Right(config),subscriptionId,executionId,CdcCapturedBatchWorkflowAdapter.toWorkflow(batch),batch.rows,log).map(_=>())
  }
  def commitBatch(node:Node,workflow:String,execution:String,batch:SourceBatch,rows:Vector[String],log:String=>Unit)
    (implicit ec:ExecutionContext):Future[BatchCommitResult]=execute(Left(node),workflow,execution,batch,rows,log)
  private def execute(target:Either[Node,MySQLCdcApplyConfig],workflow:String,execution:String,batch:SourceBatch,
    rows:Vector[String],log:String=>Unit)(implicit ec:ExecutionContext):Future[BatchCommitResult]={
    val opened=new AtomicBoolean(false);val released=new AtomicBoolean(false);val closeError=new AtomicReference[Throwable]()
    val delegate=new MySQLCdcApplySinkNode {
      override protected[magic] def beforeTargetWrite(c:Connection,config:MySQLCdcApplyConfig,bootstrap:String,b:SourceBatch):Unit={
        val identity=b.cursor.upperBound.parseJson.asJsObject.fields
        val schema=identity.get("schemaFingerprint").collect {case JsString(value)=>value}
          .filter(_.matches("[a-f0-9]{64}")).getOrElse(throw new IllegalArgumentException("bootstrap replay schema missing"))
        val protocol=MySQLCdcGroupProtocol.parse(identity.getOrElse("groupProtocol",throw new IllegalArgumentException("bootstrap replay protocol missing")))
        require(protocol.replicationId==bootstrap,"bootstrap replay subscription changed")
        val q=c.prepareStatement("SELECT bootstrap_id,table_name,schema_id FROM pekko_bootstrap_targets WHERE target_key=? FOR UPDATE")
        q.setQueryTimeout(30)
        try {q.setString(1,MySQLCdcGroup.hash(config.table));val r=q.executeQuery();try {
          require(r.next() && r.getString(1)==bootstrap && r.getString(2)==config.table && r.getString(3)==schema && !r.next(),
            "bootstrap replay target claim missing or changed")
        }finally r.close()}finally q.close()
      }
      override def openConnection(config:MySQLCdcApplyConfig):Connection={
        val c=open(config);opened.set(true)
        Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
          def invoke(proxy:Any,m:Method,args:Array[AnyRef]):AnyRef={
            try {val result=m.invoke(c,Option(args).getOrElse(Array.empty[AnyRef]):_*)
              if(m.getName=="close")released.set(true)
              result
            }catch {case e:InvocationTargetException=>
              if(m.getName=="close")closeError.compareAndSet(null,e.getCause)
              throw e.getCause}
          }
        }).asInstanceOf[Connection]
      }
    }
    val committed=target match {
      case Left(node)=>delegate.commitBatch(node,workflow,execution,batch,rows,log)
      case Right(config)=>delegate.commitBatchConfigured(config,workflow,execution,batch,rows,log)
    }
    committed.transform {result=>
      if(opened.get() && (!released.get() || closeError.get()!=null))Failure(new CdcExecutionException(
        ExecutionFailure(ExecutionFailure.ReleaseUnknown,"bootstrap-replay-close",Some(batch.batchId),releaseConfirmed=false),
        Option(closeError.get()).getOrElse(new IllegalStateException("replay connection close not confirmed"))))
      else result
    }(ExecutionContext.parasitic)
  }
}
object BootstrapReplaySink {
  private def open(config:MySQLCdcApplyConfig):Connection=DriverManager.getConnection(
    s"jdbc:mysql://${config.host}:${config.port}/${config.database}?useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=30000",
    config.username,config.password)
}
