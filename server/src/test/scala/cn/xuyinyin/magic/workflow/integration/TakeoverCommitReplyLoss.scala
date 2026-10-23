package cn.xuyinyin.magic.workflow.integration

import java.sql.{Connection,SQLException}
import java.lang.reflect.{Proxy,InvocationTargetException}
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** Drops only the client reply, after the real MySQL COMMIT has returned successfully. */
private[integration] final class TakeoverCommitReplyLoss(delegate:DataSource,marker:String) extends DataSource {
  val armed=new AtomicBoolean(false)
  val injected=new AtomicBoolean(false)
  def getConnection:Connection=wrap(delegate.getConnection)
  def getConnection(user:String,password:String):Connection=wrap(delegate.getConnection(user,password))
  private def wrap(c:Connection):Connection={
    var matched=false
    Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),(_,method,args)=>{
      if(method.getName=="prepareStatement" && args!=null && args.headOption.exists(v=>v.isInstanceOf[String] && v.toString.contains(marker)))matched=true
      try {
        val result=method.invoke(c,Option(args).getOrElse(Array.empty[AnyRef]):_*)
        if(method.getName=="commit" && matched && armed.compareAndSet(true,false)) {
          injected.set(true);throw new SQLException("test: real control commit reply lost","08007")
        }
        result
      }catch{case e:InvocationTargetException=>throw e.getCause}
    }).asInstanceOf[Connection]
  }
  def getLogWriter:java.io.PrintWriter=delegate.getLogWriter
  def setLogWriter(v:java.io.PrintWriter):Unit=delegate.setLogWriter(v)
  def setLoginTimeout(v:Int):Unit=delegate.setLoginTimeout(v)
  def getLoginTimeout:Int=delegate.getLoginTimeout
  def getParentLogger:java.util.logging.Logger=delegate.getParentLogger
  def unwrap[T](c:Class[T]):T=delegate.unwrap(c)
  def isWrapperFor(c:Class[_]):Boolean=delegate.isWrapperFor(c)
}
