package cn.xuyinyin.magic.server

import cn.xuyinyin.magic.server.http.HttpServerLifecycle
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.server.Directives._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.concurrent.duration._

class HttpServerLifecycleSpec extends AnyWordSpec with Matchers {
  "HTTP lifecycle" should {
    "reject a drain deadline that cannot finish inside its shutdown phase" in {
      val invalid=ConfigFactory.parseString("http.shutdown-grace=10s\npekko.coordinated-shutdown.phases.service-requests-done.timeout=10s").withFallback(ConfigFactory.load())
      intercept[IllegalArgumentException] {HttpServerLifecycle.shutdownGrace(invalid)}
      HttpServerLifecycle.shutdownGrace(ConfigFactory.load()) shouldBe 5.seconds
    }

    "unbind new connections and drain an admitted request during coordinated shutdown" in {
      implicit val system:ActorSystem[Nothing]=ActorSystem[Nothing](Behaviors.empty[Nothing],"http-lifecycle",
        ConfigFactory.parseString("pekko.actor.provider=local\nhttp.host=127.0.0.1\nhttp.port=0\nhttp.shutdown-grace=2s").withFallback(ConfigFactory.load("application-test")))
      implicit val ec:ExecutionContext=system.executionContext
      val started=Promise[Done]();val response=Promise[HttpResponse]();val socket=new Socket
      try {
        val binding=Await.result(HttpServerLifecycle.bind(system,path("slow"){get {started.trySuccess(Done);complete(response.future)}}),5.seconds)
        socket.connect(binding.localAddress,1000);socket.setSoTimeout(3000)
        socket.getOutputStream.write("GET /slow HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));socket.getOutputStream.flush()
        Await.result(started.future,3.seconds)
        val shutdown=CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason)
        Await.result(binding.whenTerminationSignalIssued,3.seconds)
        shutdown.isCompleted shouldBe false
        val fresh=new Socket
        try intercept[java.io.IOException] {fresh.connect(new InetSocketAddress("127.0.0.1",binding.localAddress.getPort),200)} finally fresh.close()
        response.success(HttpResponse(entity="drained"))
        val reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream,StandardCharsets.UTF_8))
        reader.readLine() should include("200 OK")
        val headers=Iterator.continually(reader.readLine()).takeWhile(line=>line != null && line.nonEmpty).toVector
        headers.exists(_.equalsIgnoreCase("Content-Length: 7")) shouldBe true
        val body=new Array[Char](7);var count=0
        while(count<body.length) { val n=reader.read(body,count,body.length-count);assert(n>0,"response body was truncated");count+=n }
        new String(body) shouldBe "drained"
        Await.result(shutdown,5.seconds)
      } finally {response.trySuccess(HttpResponse());socket.close();system.terminate();Await.ready(system.whenTerminated,5.seconds)}
    }
  }
}
