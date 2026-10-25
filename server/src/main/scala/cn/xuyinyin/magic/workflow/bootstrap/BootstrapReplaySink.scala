package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.connectors.mysql.sink.{MySQLCdcApplyConfig,MySQLCdcApplySinkNode}
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCommitResult,SourceBatch}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.Node
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import java.sql.{Connection,DriverManager}
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}
import scala.concurrent.{ExecutionContext,Future}
import scala.util.Failure

/** Uses the existing group ledger/DML transaction unchanged, but requires close confirmation.
  * A completed Future alone is insufficient because the legacy node suppresses close failures.
  */
final class BootstrapReplaySink(open:MySQLCdcApplyConfig=>Connection=BootstrapReplaySink.open) {
  def commitBatch(node:Node,workflow:String,execution:String,batch:SourceBatch,rows:Vector[String],log:String=>Unit)
    (implicit ec:ExecutionContext):Future[BatchCommitResult]={
    val opened=new AtomicBoolean(false);val released=new AtomicBoolean(false);val closeError=new AtomicReference[Throwable]()
    val delegate=new MySQLCdcApplySinkNode {
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
    delegate.commitBatch(node,workflow,execution,batch,rows,log).transform {result=>
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
