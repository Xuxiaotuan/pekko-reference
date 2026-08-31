package cn.xuyinyin.magic.workflow.nodes.sources

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters._
import scala.util.control.NonFatal
import spray.json._

final case class ResolvedKafkaTopic(topic: String, bootstrapServers: String)

trait KafkaTopicResolver {
  def resolve(config: KafkaSourceConfig)(implicit ec: ExecutionContext): Future[ResolvedKafkaTopic]
}

final class DefaultKafkaTopicResolver(httpClient: HttpClient = HttpClient.newHttpClient()) extends KafkaTopicResolver {
  override def resolve(config: KafkaSourceConfig)(implicit ec: ExecutionContext): Future[ResolvedKafkaTopic] =
    config.connection match {
      case DirectKafkaConnection(bootstrapServers) =>
        Future.successful(ResolvedKafkaTopic(config.topic, bootstrapServers))
      case GravitinoKafkaConnection(uri, metalake, catalog) =>
        val catalogPath = s"/api/metalakes/$metalake/catalogs/$catalog"
        val topicPath = s"$catalogPath/schemas/default/topics/${config.topic}"
        for {
          catalogResponse <- get(uri, catalogPath)
          bootstrapServers = catalogBootstrapServers(catalogResponse.body(), catalogPath)
          topicResponse <- get(uri, topicPath, s"topic ${config.topic}")
          _ = validateTopic(topicResponse.body(), config.topic, topicPath)
        } yield ResolvedKafkaTopic(config.topic, bootstrapServers)
    }

  private def get(baseUri: URI, path: String, resource: String = "")(implicit ec: ExecutionContext): Future[HttpResponse[String]] = {
    val request = try {
      HttpRequest.newBuilder(endpoint(baseUri, path))
        .timeout(Duration.ofSeconds(5))
        .header("Accept", "application/vnd.gravitino.v1+json")
        .GET()
        .build()
    } catch {
      case NonFatal(_) => return Future.failed(requestFailure(path, resource, "could not build request"))
    }

    val response = try {
      httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).asScala
    } catch {
      case NonFatal(_) => return Future.failed(requestFailure(path, resource, "transport failed"))
    }

    response.map { received =>
      if (received.statusCode() / 100 == 2) received
      else throw requestFailure(path, resource, s"failed with status ${received.statusCode()}")
    }.recoverWith {
      case error: IllegalStateException => Future.failed(error)
      case NonFatal(_) => Future.failed(requestFailure(path, resource, "transport failed"))
    }
  }

  private def endpoint(baseUri: URI, path: String): URI =
    new URI(baseUri.getScheme, null, baseUri.getHost, baseUri.getPort, path, null, null)

  private def catalogBootstrapServers(body: String, path: String): String = {
    val catalog = responseObject(body, "catalog", path)
    val catalogType = requiredString(catalog, "type", path)
    if (catalogType != "messaging")
      throw failure(path, "catalog type messaging required")

    val provider = requiredString(catalog, "provider", path)
    if (provider != "kafka")
      throw failure(path, "catalog provider kafka required")

    val properties = requiredObject(catalog, "properties", path)
    requiredString(properties, "bootstrap.servers", path)
  }

  private def validateTopic(body: String, expectedTopic: String, path: String): Unit = {
    val topic = responseObject(body, "topic", path)
    if (requiredRawString(topic, "name", path) != expectedTopic)
      throw failure(path, s"did not return topic $expectedTopic")
  }

  private def responseObject(body: String, key: String, path: String): JsObject =
    try {
      val response = body.parseJson.asJsObject
      val code = response.fields.get("code") match {
        case Some(JsNumber(value)) if value.isValidInt => value.toInt
        case _ => throw failure(path, "is missing integer code")
      }
      if (code != 0) throw failure(path, s"returned code $code")
      requiredObject(response, key, path)
    }
    catch {
      case error: IllegalStateException => throw error
      case NonFatal(_) => throw failure(path, s"contained an invalid $key response")
    }

  private def requiredObject(objectValue: JsObject, key: String, path: String): JsObject =
    objectValue.fields.get(key) match {
      case Some(value: JsObject) => value
      case _ => throw failure(path, s"is missing object $key")
    }

  private def requiredString(objectValue: JsObject, key: String, path: String): String =
    requiredRawString(objectValue, key, path).trim

  private def requiredRawString(objectValue: JsObject, key: String, path: String): String =
    objectValue.fields.get(key) match {
      case Some(JsString(value)) if value.trim.nonEmpty => value
      case _ => throw failure(path, s"is missing non-empty $key")
    }

  private def failure(path: String, detail: String): IllegalStateException =
    new IllegalStateException(s"Gravitino request $path $detail")

  private def requestFailure(path: String, resource: String, detail: String): IllegalStateException =
    failure(path, s"${if (resource.nonEmpty) s"$resource " else ""}$detail")
}
