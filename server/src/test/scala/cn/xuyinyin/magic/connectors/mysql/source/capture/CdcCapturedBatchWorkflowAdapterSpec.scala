package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.cdc.checkpoint.{BatchId,SourceBatch,SourceCursor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CdcCapturedBatchWorkflowAdapterSpec extends AnyWordSpec with Matchers {
  "CDC batch compatibility" should {
    "preserve the original batch identity, cursor bytes, rows and acknowledgement capability" in {
      val partition="mysql-cdc:capture-1"
      val cursor=CdcCaptureCursor("mysql.capture.groups.v1","{\"version\":1,\"sequence\":17}","{\"schema\":\"é\"}")
      val batch=CdcCapturedBatch("source",partition,3,
        MySQLCdcGroup.hash(s"execution|source|$partition|3"),cursor,Vector("group-1","group-2"))
      val expected=SourceBatch("source",partition,3,BatchId.sha256("execution","source",partition,3),
        SourceCursor(cursor.kind,cursor.value,cursor.upperBound),batch.rows,Some("consumer"))
      CdcCapturedBatchWorkflowAdapter.toWorkflow(batch,Some("consumer")) shouldBe expected
      CdcCapturedBatchWorkflowAdapter.cursorFromWorkflow(expected.cursor) shouldBe cursor
      CdcCapturedBatchWorkflowAdapter.toWorkflow(batch).acknowledgementId shouldBe None
    }
    "match legacy batch hashes for Unicode and boundary sequence numbers" in {
      for {
        (execution,node,partition) <- Vector(("执行-一","源节点","mysql-cdc:表"),("","", ""),("exec|1","源","p"))
        sequence <- Vector(Long.MinValue,-1L,0L,1L,Long.MaxValue)
      } MySQLCdcGroup.hash(s"$execution|$node|$partition|$sequence") shouldBe
        BatchId.sha256(execution,node,partition,sequence)
    }
  }
}
