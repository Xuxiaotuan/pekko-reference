package cn.xuyinyin.magic.workflow.nodes.sources

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

class GravitinoTopicResolverSpec extends AnyWordSpec with Matchers {
  private val catalogPath = "/api/metalakes/pekko/catalogs/bigdata-kafka"
  private val topicPath = s"$catalogPath/schemas/default/topics/events"

  private val directConfig = KafkaSourceConfig(
    "events",
    DirectKafkaConnection("kafka:9092"),
    KafkaOffsetReset.Earliest,
    chunkSize = 10,
    maxRecords = 50,
    maxDuration = 2.seconds
  )

  private def gravitinoConfig(uri: URI): KafkaSourceConfig =
    directConfig.copy(connection = GravitinoKafkaConnection(uri, "pekko", "bigdata-kafka"))

  "DefaultKafkaTopicResolver" should {
    "return direct brokers without HTTP" in {
      val resolver = new DefaultKafkaTopicResolver()

      Await.result(resolver.resolve(directConfig), 2.seconds) shouldBe
        ResolvedKafkaTopic("events", "kafka:9092")
    }

    "load a Kafka catalog and topic from Gravitino" in withServer(
      catalogJson = catalog(),
      topicJson = topic(),
      expectBothPaths = true
    ) { uri =>
      val resolver = new DefaultKafkaTopicResolver()

      Await.result(resolver.resolve(gravitinoConfig(uri)), 2.seconds) shouldBe
        ResolvedKafkaTopic("events", "kafka:9092")
    }

    "reject invalid Gravitino catalog and topic responses" in {
      failureFor(catalogJson = catalog(code = 1003)).getMessage should include("code 1003")
      failureFor(topicJson = topic(code = 1003)).getMessage should include("code 1003")
      failureFor(catalogJson = catalog(catalogType = "relational")).getMessage should include("type messaging")
      failureFor(catalogJson = catalog(provider = "hive")).getMessage should include("provider kafka")
      failureFor(catalogJson = catalog(properties = JsObject.empty)).getMessage should include("bootstrap.servers")
      failureFor(topicJson = topic(name = "other-events")).getMessage should include("topic events")
      failureFor(topicJson = topic(name = "events ")).getMessage should include("topic events")
      failureFor(topicStatus = 404).getMessage should include("topic events")
      failureFor(catalogStatus = 503).getMessage should include("503")
    }
  }

  private def catalog(
    code: Int = 0,
    catalogType: String = "messaging",
    provider: String = "kafka",
    properties: JsObject = JsObject("bootstrap.servers" -> JsString("kafka:9092"))
  ): String =
    JsObject(
      "code" -> JsNumber(code),
      "catalog" -> JsObject(
        "name" -> JsString("bigdata-kafka"),
        "type" -> JsString(catalogType),
        "provider" -> JsString(provider),
        "properties" -> properties
      )
    ).compactPrint

  private def topic(name: String = "events", code: Int = 0): String =
    JsObject(
      "code" -> JsNumber(code),
      "topic" -> JsObject(
        "name" -> JsString(name),
        "properties" -> JsObject.empty
      )
    ).compactPrint

  private def failureFor(
    catalogJson: String = catalog(),
    topicJson: String = topic(),
    catalogStatus: Int = 200,
    topicStatus: Int = 200
  ): IllegalStateException =
    withServer(catalogJson, topicJson, catalogStatus, topicStatus) { uri =>
      intercept[IllegalStateException] {
        Await.result(new DefaultKafkaTopicResolver().resolve(gravitinoConfig(uri)), 2.seconds)
      }
    }

  private def withServer[T](
    catalogJson: String,
    topicJson: String,
    catalogStatus: Int = 200,
    topicStatus: Int = 200,
    expectBothPaths: Boolean = false
  )(body: URI => T): T = {
    val requestedPaths = new ConcurrentLinkedQueue[String]()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        val path = exchange.getRequestURI.getPath
        requestedPaths.add(path)
        val (status, response) = path match {
          case `catalogPath` => catalogStatus -> catalogJson
          case `topicPath` => topicStatus -> topicJson
          case _ => 404 -> "{}"
        }
        val bytes = response.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length)
        try exchange.getResponseBody.write(bytes)
        finally exchange.close()
      }
    })
    server.start()

    try {
      val result = body(URI.create(s"http://127.0.0.1:${server.getAddress.getPort}"))
      if (expectBothPaths)
        requestedPaths.asScala.toVector shouldBe Vector(catalogPath, topicPath)
      result
    } finally server.stop(0)
  }
}
