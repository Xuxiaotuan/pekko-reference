package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import java.sql.{Connection,PreparedStatement}
import java.util.concurrent.{Semaphore,TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/** Small explicit budgets; network timeouts must also be present on the supplied JDBC URL. */
final class BootstrapJdbc(open:()=>Connection,slots:Int=2) {
  private val permits=new Semaphore(slots)
  private val fenced=new AtomicBoolean(false)
  def connection[A](f:Connection=>A):A={
    if(fenced.get())throw new CdcExecutionException(
      ExecutionFailure(ExecutionFailure.ReleaseUnknown,"bootstrap-close",releaseConfirmed=false),
      new IllegalStateException("bootstrap connection release unknown"))
    require(permits.tryAcquire(5,TimeUnit.SECONDS),"bootstrap connection budget exhausted")
    var c:Connection=null;var primary:Throwable=null;var released=false
    try {c=open();f(c)}catch {case e:Throwable=>primary=e;throw e}
    finally {
      try {if(c!=null)c.close();released=true} catch {case release:Throwable=>
        fenced.set(true)
        val failure=new CdcExecutionException(ExecutionFailure(ExecutionFailure.ReleaseUnknown,"bootstrap-close",releaseConfirmed=false),release)
        if(primary!=null)failure.addSuppressed(primary)
        throw failure
      }finally if(released)permits.release()
    }
  }
  def transaction[A](f:Connection=>A):A=connection {c=>
    c.setAutoCommit(false)
    val result=try f(c) catch {case e:Throwable=>try c.rollback()catch{case r:Throwable=>e.addSuppressed(r)};throw e}
    try c.commit() catch {case e:Throwable=>throw new CdcExecutionException(ExecutionFailure(ExecutionFailure.CommitUnknown,"bootstrap-commit"),e)}
    result
  }
  def statement[A](c:Connection,sql:String,args:Seq[Any])(f:PreparedStatement=>A):A={
    val q=c.prepareStatement(sql);q.setQueryTimeout(30)
    try {args.zipWithIndex.foreach {case(v,i)=>q.setObject(i+1,v)};f(q)}finally q.close()
  }
  def update(c:Connection,sql:String,args:Any*):Int=statement(c,sql,args)(_.executeUpdate())
  def query(c:Connection,sql:String,args:Any*):Vector[Map[String,String]]=statement(c,sql,args){q=>
    val r=q.executeQuery();try {val m=r.getMetaData;val b=Vector.newBuilder[Map[String,String]]
      while(r.next())b+=(1 to m.getColumnCount).map(i=>m.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT)->r.getString(i)).toMap
      b.result()
    }finally r.close()
  }
}
