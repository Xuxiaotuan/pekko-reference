package cn.xuyinyin.magic.workflow.nodes.sources

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class KafkaCheckpointCodecSpec extends AnyWordSpecLike with Matchers {
  private val boundary = KafkaBoundaryV1(
    "events",
    "kafka:9092",
    123456789L,
    Vector(
      KafkaPartitionBoundary(1, 4, 8),
      KafkaPartitionBoundary(0, 0, 4)
    )
  )

  "KafkaCheckpointCodec" should {
    "encode a boundary with deterministic canonical JSON and decode it" in {
      KafkaCheckpointCodec.encodeBoundary(boundary) shouldBe
        """{"bootstrapServers":"kafka:9092","deadlineEpochMillis":123456789,"partitions":[{"endOffset":4,"partition":0,"startOffset":0},{"endOffset":8,"partition":1,"startOffset":4}],"topic":"events","version":1}"""

      KafkaCheckpointCodec.decodeBoundary(KafkaCheckpointCodec.encodeBoundary(boundary)).partitions.map(_.partition) shouldBe
        Vector(0, 1)
    }

    "encode and decode a cursor canonically" in {
      val cursor = KafkaCursorV1(Map(1 -> 8L, 0 -> 4L), recordsConsumed = 12L)

      KafkaCheckpointCodec.encodeCursor(cursor) shouldBe
        """{"nextOffsets":{"0":4,"1":8},"recordsConsumed":12,"version":1}"""
      KafkaCheckpointCodec.decodeCursor(KafkaCheckpointCodec.encodeCursor(cursor)) shouldBe cursor
    }

    "reject duplicate boundary partitions" in {
      val duplicate = boundary.copy(partitions = Vector(
        KafkaPartitionBoundary(0, 0, 4),
        KafkaPartitionBoundary(0, 4, 8)
      ))

      intercept[IllegalArgumentException](KafkaCheckpointCodec.encodeBoundary(duplicate))
    }

    "reject negative boundary offsets" in {
      intercept[IllegalArgumentException] {
        KafkaCheckpointCodec.encodeBoundary(boundary.copy(partitions = Vector(KafkaPartitionBoundary(0, -1, 4))))
      }
      intercept[IllegalArgumentException] {
        KafkaCheckpointCodec.decodeBoundary(
          """{"version":1,"topic":"events","bootstrapServers":"kafka:9092","deadlineEpochMillis":1,"partitions":[{"partition":0,"startOffset":-1,"endOffset":4}]}"""
        )
      }
    }

    "reject a boundary whose start offset exceeds its end offset" in {
      intercept[IllegalArgumentException] {
        KafkaCheckpointCodec.encodeBoundary(boundary.copy(partitions = Vector(KafkaPartitionBoundary(0, 5, 4))))
      }
    }

    "reject unknown boundary and cursor versions" in {
      intercept[IllegalArgumentException] {
        KafkaCheckpointCodec.decodeBoundary(
          """{"version":2,"topic":"events","bootstrapServers":"kafka:9092","deadlineEpochMillis":1,"partitions":[]}"""
        )
      }
      intercept[IllegalArgumentException] {
        KafkaCheckpointCodec.decodeCursor("""{"version":2,"nextOffsets":{},"recordsConsumed":0}""")
      }
    }

    "reject malformed and incomplete JSON" in {
      intercept[IllegalArgumentException](KafkaCheckpointCodec.decodeBoundary("not-json"))
      intercept[IllegalArgumentException](KafkaCheckpointCodec.decodeBoundary("""{"version":1}"""))
      intercept[IllegalArgumentException](KafkaCheckpointCodec.decodeCursor("""{"version":1,"recordsConsumed":0}"""))
    }

    "require exactly the boundary partition set in a cursor" in {
      val missing = KafkaCursorV1(Map(0 -> 4L), recordsConsumed = 4L)
      val extra = KafkaCursorV1(Map(0 -> 4L, 1 -> 8L, 2 -> 0L), recordsConsumed = 4L)

      intercept[IllegalArgumentException](KafkaCheckpointCodec.validateCursor(boundary, missing))
      intercept[IllegalArgumentException](KafkaCheckpointCodec.validateCursor(boundary, extra))
    }

    "reject cursor offsets beyond their frozen ends" in {
      val beyondEnd = KafkaCursorV1(Map(0 -> 5L, 1 -> 8L), recordsConsumed = 1L)

      intercept[IllegalArgumentException](KafkaCheckpointCodec.validateCursor(boundary, beyondEnd))
    }

    "reject cursor offsets before their frozen starts" in {
      val beforeStart = KafkaCursorV1(Map(0 -> 4L, 1 -> 3L), recordsConsumed = 1L)

      intercept[IllegalArgumentException](KafkaCheckpointCodec.validateCursor(boundary, beforeStart))
    }

    "reject duplicate JSON keys in nextOffsets" in {
      intercept[IllegalArgumentException] {
        KafkaCheckpointCodec.decodeCursor(
          """{"version":1,"nextOffsets":{"0":4,"0":4,"1":8},"recordsConsumed":12}"""
        )
      }
    }

    "reject negative cursor offsets and record counts" in {
      intercept[IllegalArgumentException] {
        KafkaCheckpointCodec.validateCursor(boundary, KafkaCursorV1(Map(0 -> -1L, 1 -> 8L), recordsConsumed = 0L))
      }
      intercept[IllegalArgumentException] {
        KafkaCheckpointCodec.encodeCursor(KafkaCursorV1(Map(0 -> 4L, 1 -> 8L), recordsConsumed = -1L))
      }
    }
  }
}
