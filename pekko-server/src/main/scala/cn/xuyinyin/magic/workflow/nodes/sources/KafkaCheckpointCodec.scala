package cn.xuyinyin.magic.workflow.nodes.sources

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import spray.json._

import scala.util.control.NonFatal

final case class KafkaPartitionBoundary(partition: Int, startOffset: Long, endOffset: Long)

final case class KafkaBoundaryV1(
  topic: String,
  bootstrapServers: String,
  deadlineEpochMillis: Long,
  partitions: Vector[KafkaPartitionBoundary]
)

final case class KafkaCursorV1(nextOffsets: Map[Int, Long], recordsConsumed: Long)

object KafkaCheckpointCodec {
  val CursorKind: String = "kafka.offsets.v1"

  private val Version = 1
  private val DuplicateKeyMapper = new ObjectMapper()
    .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

  def encodeBoundary(boundary: KafkaBoundaryV1): String = {
    validateBoundary(boundary)
    canonicalJson(JsObject(
      "version" -> JsNumber(Version),
      "topic" -> JsString(boundary.topic),
      "bootstrapServers" -> JsString(boundary.bootstrapServers),
      "deadlineEpochMillis" -> JsNumber(boundary.deadlineEpochMillis),
      "partitions" -> JsArray(boundary.partitions.sortBy(_.partition).map { partition =>
        JsObject(
          "partition" -> JsNumber(partition.partition),
          "startOffset" -> JsNumber(partition.startOffset),
          "endOffset" -> JsNumber(partition.endOffset)
        )
      })
    ))
  }

  def decodeBoundary(json: String): KafkaBoundaryV1 =
    parse(json, "boundary") { root =>
      requireKeys(root, Set("version", "topic", "bootstrapServers", "deadlineEpochMillis", "partitions"), "boundary")
      requireVersion(root, "boundary")
      val boundary = KafkaBoundaryV1(
        topic = requiredString(root, "topic", "boundary"),
        bootstrapServers = requiredString(root, "bootstrapServers", "boundary"),
        deadlineEpochMillis = requiredLong(root, "deadlineEpochMillis", "boundary"),
        partitions = requiredArray(root, "partitions", "boundary").elements.zipWithIndex.map {
          case (value, index) => decodePartition(value, s"boundary.partitions[$index]")
        }.toVector
      )
      validateBoundary(boundary)
      boundary.copy(partitions = boundary.partitions.sortBy(_.partition))
    }

  def encodeCursor(cursor: KafkaCursorV1): String = {
    validateCursorShape(cursor)
    canonicalJson(JsObject(
      "version" -> JsNumber(Version),
      "nextOffsets" -> JsObject(cursor.nextOffsets.map { case (partition, offset) =>
        partition.toString -> JsNumber(offset)
      }),
      "recordsConsumed" -> JsNumber(cursor.recordsConsumed)
    ))
  }

  def decodeCursor(json: String): KafkaCursorV1 =
    parse(json, "cursor") { root =>
      requireKeys(root, Set("version", "nextOffsets", "recordsConsumed"), "cursor")
      requireVersion(root, "cursor")
      val offsets = requiredObject(root, "nextOffsets", "cursor").fields.map {
        case (partitionText, value) =>
          val partition = parsePartition(partitionText, "cursor.nextOffsets")
          partition -> requiredLongValue(value, s"cursor.nextOffsets.$partitionText")
      }
      val cursor = KafkaCursorV1(offsets, requiredLong(root, "recordsConsumed", "cursor"))
      validateCursorShape(cursor)
      cursor
    }

  def validateCursor(boundary: KafkaBoundaryV1, cursor: KafkaCursorV1): Unit = {
    validateBoundary(boundary)
    validateCursorShape(cursor)
    val boundaryPartitions = boundary.partitions.map(_.partition).toSet
    val cursorPartitions = cursor.nextOffsets.keySet
    require(cursorPartitions == boundaryPartitions,
      s"cursor partitions ${cursorPartitions.toVector.sorted} must exactly match boundary partitions ${boundaryPartitions.toVector.sorted}")
    boundary.partitions.foreach { partition =>
      val offset = cursor.nextOffsets(partition.partition)
      require(offset >= partition.startOffset,
        s"cursor offset for partition ${partition.partition} is before start offset ${partition.startOffset}")
      require(offset <= partition.endOffset,
        s"cursor offset for partition ${partition.partition} exceeds end offset ${partition.endOffset}")
    }
  }

  private def validateBoundary(boundary: KafkaBoundaryV1): Unit = {
    require(boundary.topic.nonEmpty, "boundary topic must not be empty")
    require(boundary.bootstrapServers.nonEmpty, "boundary bootstrapServers must not be empty")
    val partitions = boundary.partitions.map(_.partition)
    require(partitions.distinct.size == partitions.size, "boundary contains duplicate partitions")
    boundary.partitions.foreach { partition =>
      require(partition.partition >= 0, s"boundary partition must be non-negative: ${partition.partition}")
      require(partition.startOffset >= 0, s"boundary start offset must be non-negative: ${partition.startOffset}")
      require(partition.endOffset >= 0, s"boundary end offset must be non-negative: ${partition.endOffset}")
      require(partition.startOffset <= partition.endOffset,
        s"boundary start offset ${partition.startOffset} exceeds end offset ${partition.endOffset}")
    }
  }

  private def validateCursorShape(cursor: KafkaCursorV1): Unit = {
    require(cursor.recordsConsumed >= 0, s"cursor recordsConsumed must be non-negative: ${cursor.recordsConsumed}")
    cursor.nextOffsets.foreach { case (partition, offset) =>
      require(partition >= 0, s"cursor partition must be non-negative: $partition")
      require(offset >= 0, s"cursor offset must be non-negative: $offset")
    }
  }

  private def decodePartition(value: JsValue, context: String): KafkaPartitionBoundary = {
    val objectValue = asObject(value, context)
    requireKeys(objectValue, Set("partition", "startOffset", "endOffset"), context)
    val partition = requiredInt(objectValue, "partition", context)
    val startOffset = requiredLong(objectValue, "startOffset", context)
    val endOffset = requiredLong(objectValue, "endOffset", context)
    KafkaPartitionBoundary(partition, startOffset, endOffset)
  }

  private def parsePartition(text: String, context: String): Int = {
    try {
      require(text.matches("(?:0|[1-9][0-9]*)"), s"$context contains a non-canonical partition key: $text")
      val partition = text.toInt
      require(partition >= 0, s"$context partition must be non-negative: $partition")
      partition
    } catch {
      case error: IllegalArgumentException => throw error
      case NonFatal(error) => throw new IllegalArgumentException(s"$context contains an invalid partition key: $text", error)
    }
  }

  private def parse[A](json: String, kind: String)(decode: JsObject => A): A = {
    try {
      rejectDuplicateJsonKeys(json, kind)
      decode(asObject(json.parseJson, kind))
    }
    catch {
      case error: IllegalArgumentException => throw error
      case NonFatal(error) => throw new IllegalArgumentException(s"malformed $kind JSON", error)
    }
  }

  private def rejectDuplicateJsonKeys(json: String, kind: String): Unit = {
    try DuplicateKeyMapper.readTree(json)
    catch {
      case error: IllegalArgumentException => throw error
      case NonFatal(error) => throw new IllegalArgumentException(s"malformed $kind JSON", error)
    }
  }

  private def asObject(value: JsValue, context: String): JsObject = value match {
    case objectValue: JsObject => objectValue
    case _ => throw new IllegalArgumentException(s"$context must be a JSON object")
  }

  private def requireKeys(value: JsObject, expected: Set[String], context: String): Unit = {
    val actual = value.fields.keySet
    require(actual == expected, s"$context fields must be exactly ${expected.toVector.sorted.mkString(", ")}")
  }

  private def requireVersion(value: JsObject, context: String): Unit = {
    require(requiredInt(value, "version", context) == Version, s"unsupported $context version")
  }

  private def requiredString(value: JsObject, field: String, context: String): String = value.fields.get(field) match {
    case Some(JsString(result)) => result
    case _ => throw new IllegalArgumentException(s"$context.$field must be a string")
  }

  private def requiredLong(value: JsObject, field: String, context: String): Long =
    value.fields.get(field).map(requiredLongValue(_, s"$context.$field")).getOrElse(
      throw new IllegalArgumentException(s"$context.$field is required"))

  private def requiredLongValue(value: JsValue, context: String): Long = value match {
    case JsNumber(number) =>
      try number.toLongExact
      catch {
        case NonFatal(error) => throw new IllegalArgumentException(s"$context must be an integer Long", error)
      }
    case _ => throw new IllegalArgumentException(s"$context must be an integer Long")
  }

  private def requiredInt(value: JsObject, field: String, context: String): Int = {
    val number = requiredLong(value, field, context)
    try Math.toIntExact(number)
    catch {
      case NonFatal(error) => throw new IllegalArgumentException(s"$context.$field must be an Int", error)
    }
  }

  private def requiredArray(value: JsObject, field: String, context: String): JsArray = value.fields.get(field) match {
    case Some(array: JsArray) => array
    case _ => throw new IllegalArgumentException(s"$context.$field must be an array")
  }

  private def requiredObject(value: JsObject, field: String, context: String): JsObject = value.fields.get(field) match {
    case Some(objectValue: JsObject) => objectValue
    case _ => throw new IllegalArgumentException(s"$context.$field must be an object")
  }

  private def canonicalJson(value: JsValue): String = value match {
    case JsObject(fields) =>
      fields.toVector.sortBy(_._1).map { case (key, child) =>
        JsString(key).compactPrint + ":" + canonicalJson(child)
      }.mkString("{", ",", "}")
    case JsArray(elements) => elements.map(canonicalJson).mkString("[", ",", "]")
    case scalar => scalar.compactPrint
  }
}
