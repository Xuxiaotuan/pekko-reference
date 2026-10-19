package cn.xuyinyin.magic.workflow.integration

import java.io.{EOFException, InputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import spray.json._

/** Test fixture, not a database driver: uncompressed, non-TLS loopback MySQL only.
  * Only the armed Sink COMMIT is affected. Authentication payloads are never logged.
  */
private[integration] final class MySQLCommitResponseProxy(host: String, upstreamPort: Int,
  observe: (String, JsObject) => Unit) extends AutoCloseable {
  import MySQLCommitResponseProxy._
  require(Set("127.0.0.1", "localhost").contains(host))
  private case class Armed(mode: String, batch: JsObject)
  private val armed = new AtomicReference[Armed]()
  private val sockets = new ConcurrentLinkedQueue[Socket]()
  private val held = new CountDownLatch(1)
  private val stopped = new AtomicBoolean(false)
  private val listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress)
  val port: Int = listener.getLocalPort
  def arm(mode: String, batch: JsObject): Unit = {
    require(Set("C0", "C1", "C2").contains(mode))
    require(armed.compareAndSet(null, Armed(mode, batch)), "proxy already armed")
  }
  def dropHeldResponse(): Unit = held.countDown()
  private def spawn(name: String)(body: => Unit): Unit = {
    val thread = new Thread(() => body, name); thread.setDaemon(true); thread.start()
  }
  spawn("test-mysql-proxy-accept") {
    while (!stopped.get()) try {
      val client = listener.accept(); val server = new Socket(host, upstreamPort)
      client.setTcpNoDelay(true); server.setTcpNoDelay(true)
      sockets.add(client); sockets.add(server)
      session(client, server)
    } catch { case NonFatal(e) => if (!stopped.get()) observe("PROXY_ERROR", JsObject("error" -> JsString(e.getClass.getSimpleName))) }
  }
  private def session(client: Socket, server: Socket): Unit = {
    val authenticated = new AtomicBoolean(false)
    val intercepted = new AtomicReference[Armed]()
    val holding = new AtomicBoolean(false)
    val connectionId = new AtomicReference[JsValue](JsNull)
    def closePair(): Unit = { closeSocket(client); closeSocket(server); sockets.remove(client); sockets.remove(server) }
    def event(kind: String, a: Armed): Unit = observe(kind, JsObject(
      "batch" -> a.batch, "mode" -> JsString(a.mode), "connectionId" -> connectionId.get()))
    spawn("test-mysql-proxy-client") {
      var first = true
      try while (!stopped.get()) {
        val packet = readPacket(client.getInputStream)
        if (first) {
          require(packet.length >= 8, "short handshake")
          val capabilities = (0 until 4).foldLeft(0L)((v,i) => v | ((packet(4+i)&255L) << (8*i)))
          require((capabilities & (0x800L | 0x20L | 0x04000000L)) == 0L, "test proxy rejects TLS/compression")
          first = false
        }
        val a = if (authenticated.get() && isCommit(packet)) armed.getAndSet(null) else null
        if (a != null) {
          intercepted.set(a)
          if (a.mode == "C0") {
            holding.set(true); closeSocket(server)
            event("PROXY_COMMIT_NOT_FORWARDED", a); event("CUTPOINT", a)
            held.await(); closePair(); returnFromThread()
          }
          event("PROXY_COMMIT_FORWARDED", a)
        }
        server.getOutputStream.write(packet); server.getOutputStream.flush()
      } catch { case _: ThreadFinished => (); case NonFatal(_) => if (!holding.get()) closePair() }
    }
    spawn("test-mysql-proxy-server") {
      var greeting = true
      try while (!stopped.get()) {
        val packet = readPacket(server.getInputStream)
        if (greeting) {
          val end = packet.indexOf(0.toByte, 5)
          if (end >= 5 && end + 4 < packet.length) connectionId.set(JsNumber((1 to 4).foldLeft(0L)((v,i) => v | ((packet(end+i)&255L) << (8*(i-1))))))
          greeting = false
        } else if (!authenticated.get() && isOk(packet)) authenticated.set(true)
        val a = intercepted.get()
        if (a != null) {
          require(isOk(packet), "armed COMMIT did not receive OK; cut is invalid")
          holding.set(true)
          event("PROXY_COMMIT_OK_HELD", a); event("CUTPOINT", a)
          held.await() // C2 drops this actual server reply; C1 is killed while it is held.
          event("PROXY_COMMIT_OK_DROPPED", a); closePair(); returnFromThread()
        }
        client.getOutputStream.write(packet); client.getOutputStream.flush()
      } catch {
        case _: ThreadFinished => ()
        case NonFatal(e) =>
          if (intercepted.get() != null && !holding.get()) observe("PROXY_INVALID_CUT", JsObject("error" -> JsString(e.getClass.getSimpleName)))
          if (!holding.get()) closePair()
      }
    }
  }
  override def close(): Unit = {
    stopped.set(true); listener.close(); held.countDown(); sockets.asScala.foreach(closeSocket)
  }
}

private[integration] object MySQLCommitResponseProxy {
  private final class ThreadFinished extends RuntimeException
  private def returnFromThread(): Nothing = throw new ThreadFinished
  private def closeSocket(s: Socket): Unit = try s.close() catch { case NonFatal(_) => () }
  def readPacket(in: InputStream): Array[Byte] = {
    def exact(n: Int): Array[Byte] = {
      val data = new Array[Byte](n); var offset = 0
      while (offset < n) { val read = in.read(data, offset, n-offset); if (read < 0) throw new EOFException("truncated MySQL frame"); offset += read }
      data
    }
    val header = exact(4)
    val length = (header(0)&255) | ((header(1)&255)<<8) | ((header(2)&255)<<16)
    require(length < 1024*1024, "test proxy packet budget exceeded")
    header ++ exact(length)
  }
  def isCommit(p: Array[Byte]): Boolean = p.length >= 5 && p(3) == 0 && p(4) == 3 &&
    new String(p.drop(5), UTF_8).trim.equalsIgnoreCase("COMMIT")
  def isOk(p: Array[Byte]): Boolean = p.length >= 5 && p(4) == 0
}
