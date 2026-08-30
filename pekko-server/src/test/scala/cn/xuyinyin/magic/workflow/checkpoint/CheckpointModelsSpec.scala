package cn.xuyinyin.magic.workflow.checkpoint

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, NodeSink}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, SerializationTestKit}
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.{ExecutionContext, Future}

class CheckpointModelsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {
  "checkpoint models" should {
    "round-trip snapshot boundaries and durable checkpoints" in {
      val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("18446744073709551615"))
      val checkpoint = BatchCheckpoint(
        "source-1", "pk-range-0", 7L,
        BatchId.sha256("execution-1", "source-1", "pk-range-0", 7L),
        SourceCursor("mysql.numeric-pk", "1009", "18446744073709551615"),
        sourceRowsScanned = 10L,
        targetRowsWritten = 8L
      )
      val serialization = new SerializationTestKit(system)

      serialization.verifySerialization(boundary) shouldBe boundary
      serialization.verifySerialization(checkpoint) shouldBe checkpoint
    }

    "produce a stable lowercase SHA-256 batch identity for one execution sequence" in {
      val first = BatchId.sha256("execution-1", "source-1", "pk-range-0", 7L)
      val repeated = BatchId.sha256("execution-1", "source-1", "pk-range-0", 7L)
      val next = BatchId.sha256("execution-1", "source-1", "pk-range-0", 8L)

      first shouldBe repeated
      first should fullyMatch regex "[0-9a-f]{64}"
      next should not be first
    }

    "require the blocking dispatcher when a checkpointed sink commits a batch" in {
      val sink: CheckpointedNodeSink = new NodeSink with CheckpointedNodeSink {
        override val nodeType: String = "checkpointed-test"

        override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
          (implicit ec: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore

        override def validateReady(node: WorkflowDSL.Node, onLog: String => Unit)
          (implicit blockingEc: ExecutionContext): Future[Done] = Future.successful(Done)

        override def commitBatch(
          node: WorkflowDSL.Node,
          workflowId: String,
          executionId: String,
          batch: SourceBatch,
          transformedRows: Vector[String],
          onLog: String => Unit
        )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] =
          Future.failed(new UnsupportedOperationException("not exercised by this signature test"))
      }

      sink shouldBe a[CheckpointedNodeSink]
    }
  }
}
