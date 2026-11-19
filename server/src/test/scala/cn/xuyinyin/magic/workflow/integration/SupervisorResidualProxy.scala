package cn.xuyinyin.magic.workflow.integration

import java.net.{ServerSocket,Socket}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.util.control.NonFatal

/** Test-only transparent TCP relay. Keeps original server sockets after worker EOF until the harness releases them.
  * It sends no SQL, creates no replacement owner/session, and never supplies an isolation proof.
  */
private[integration] final class SupervisorResidualProxy(host:String,targetPort:Int) extends AutoCloseable {
  private val server=new ServerSocket(0,32,java.net.InetAddress.getLoopbackAddress)
  val port:Int=server.getLocalPort
  private val retaining=new AtomicBoolean(true)
  private val running=new AtomicBoolean(true)
  private val channels=mutable.ArrayBuffer.empty[(Socket,Socket,Boolean)]
  private def quietly(s:Socket):Unit=try s.close()catch{case NonFatal(_)=>()}
  private def copy(input:Socket,output:Socket,held:Boolean):Unit={
    val bytes=new Array[Byte](8192)
    try {
      val in=input.getInputStream;val out=output.getOutputStream
      var count=in.read(bytes)
      while(count>=0 && running.get()){
        if(count>0){out.write(bytes,0,count);out.flush()};count=in.read(bytes)
      }
    }catch{case NonFatal(_)=>()}
    finally if(!held || !retaining.get()){quietly(input);quietly(output)}
  }
  private val accept=new Thread(()=>{
    while(running.get())try {
      val client=server.accept();client.setTcpNoDelay(true)
      val upstream=new Socket();upstream.connect(new java.net.InetSocketAddress(host,targetPort),5000);upstream.setTcpNoDelay(true)
      val held=retaining.get()
      val admitted=channels.synchronized {if(channels.size>=64)false else {channels+=((client,upstream,held));true}}
      if(!admitted){quietly(client);quietly(upstream);throw new IllegalStateException("bounded residual relay exhausted")}
      Vector((client,upstream),(upstream,client)).foreach { case(in,out)=>
        val t=new Thread(()=>copy(in,out,held),"test-residual-relay");t.setDaemon(true);t.start()
      }
    }catch{case NonFatal(e)=>if(running.get())CdcJournalWindowSupport.emit("RESIDUAL_RELAY_ERROR","errorClass"->spray.json.JsString(e.getClass.getName))}
  },"test-residual-accept")
  accept.setDaemon(true);accept.start()
  def releaseHeld():Unit={
    retaining.set(false)
    channels.synchronized {channels.filter(_._3).foreach{case(a,b,_)=>quietly(a);quietly(b)};channels.filterInPlace(!_._3)}
  }
  def close():Unit={
    running.set(false);server.close();channels.synchronized {channels.foreach{case(a,b,_)=>quietly(a);quietly(b)};channels.clear()};accept.join(2000)
  }
}
