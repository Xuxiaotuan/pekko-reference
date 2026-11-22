package cn.xuyinyin.magic.workflow.integration

import java.net.{InetAddress,ServerSocket,Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentLinkedQueue,CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import spray.json._

/** Transparent loopback SDK wire gate. Never changes SQL, replies, SourceRecords, or offsets.
  * BEFORE_POINT stops binary-log-status SQL before forwarding, before SDK selects its start.
  * The no-locking production profile uses a separate factory snapshot observer.
  * Only allowlisted snapshot SQL and binary-log-status reply bytes are recorded; never auth.
  */
private[integration] final class CaptureStartupRecoverySqlProxy(host:String,upstream:Int,
  observe:(String,JsObject)=>Unit,mode:String) extends AutoCloseable {
  require(Set("127.0.0.1","localhost").contains(host))
  require(Set("NONE","BEFORE_POINT").contains(mode))
  private val server=new ServerSocket(0,16,InetAddress.getLoopbackAddress)
  private val stopped=new AtomicBoolean(false)
  private val armed=new AtomicBoolean(mode!="NONE")
  private val latch=new CountDownLatch(1)
  private val sockets=new ConcurrentLinkedQueue[Socket]()
  val port:Int=server.getLocalPort
  private def spawn(label:String)(body: => Unit):Unit={val t=new Thread(()=>body,label);t.setDaemon(true);t.start()}
  private def closeSocket(s:Socket):Unit=try s.close()catch{case NonFatal(_)=>()}
  def release():Unit=latch.countDown()
  spawn("startup-sql-accept") {
    while(!stopped.get())try {
      val client=server.accept();val upstreamSocket=new Socket(host,upstream)
      client.setTcpNoDelay(true);upstreamSocket.setTcpNoDelay(true)
      sockets.add(client);sockets.add(upstreamSocket)
      val authenticated=new AtomicBoolean(false)
      val statusQuery=new AtomicReference[String]("")
      def closePair():Unit={closeSocket(client);closeSocket(upstreamSocket);sockets.remove(client);sockets.remove(upstreamSocket)}
      spawn("startup-sql-client") {
        var first=true
        try while(!stopped.get()) {
          val p=MySQLCommitResponseProxy.readPacket(client.getInputStream)
          if(first){
            require(p.length>=8,"short handshake")
            val caps=(0 until 4).foldLeft(0L)((v,i)=>v|((p(4+i)&255L)<<(8*i)))
            require((caps&(0x800L|0x20L|0x04000000L))==0L,"TLS/compression unsupported by fixture")
            first=false
          }
          if(authenticated.get() && p.length>5 && p(3)==0 && p(4)==3) {
            val sql=new String(p.drop(5),UTF_8).trim.toUpperCase(java.util.Locale.ROOT)
            val status=sql=="SHOW MASTER STATUS" || sql=="SHOW BINARY LOG STATUS"
            statusQuery.set(if(status)sql else "")
            if(status || sql=="FLUSH TABLES WITH READ LOCK" || sql=="UNLOCK TABLES")
              observe("SDK_SQL",JsObject("sql"->JsString(sql)))
            val hit=mode=="BEFORE_POINT" && status
            if(hit && armed.compareAndSet(true,false)) {
              observe("SDK_CUTPOINT",JsObject("window"->JsString(mode),"sql"->JsString(sql),"forwarded"->JsBoolean(false)))
              latch.await()
              observe("SDK_CUTPOINT_RELEASED",JsObject("window"->JsString(mode)))
            }
          }
          upstreamSocket.getOutputStream.write(p);upstreamSocket.getOutputStream.flush()
        }catch{case NonFatal(_)=>closePair()}
      }
      spawn("startup-sql-server") {
        var greeting=true
        try while(!stopped.get()) {
          val p=MySQLCommitResponseProxy.readPacket(upstreamSocket.getInputStream)
          if(greeting)greeting=false
          else if(!authenticated.get() && MySQLCommitResponseProxy.isOk(p))authenticated.set(true)
          val sql=statusQuery.get()
          if(authenticated.get() && sql.nonEmpty)observe("SDK_POINT_REPLY_FRAME",JsObject("sql"->JsString(sql),"sequence"->JsNumber(p(3)&255),"payloadHex"->JsString(java.util.HexFormat.of().formatHex(p.drop(4)))))
          client.getOutputStream.write(p);client.getOutputStream.flush()
        }catch{case NonFatal(_)=>closePair()}
      }
    }catch{case NonFatal(e)=>if(!stopped.get())observe("SDK_PROXY_ERROR",JsObject("error"->JsString(e.getClass.getSimpleName)))}
  }
  override def close():Unit={stopped.set(true);server.close();release();sockets.asScala.foreach(closeSocket)}
}
