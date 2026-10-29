package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

class CaptureAckProofSpec extends AnyWordSpec with Matchers {
  "bounded wake identifiers" should {
    "distinguish attempts and captures while rejecting tampered markers" in {
      val ids=(0 until CaptureWake.MaxAttempts).map(n=>CaptureWake.operation("capture-a","boot-123",n))
      ids.distinct.size shouldBe CaptureWake.MaxAttempts
      CaptureWake.nextAvailable(n=>n<3) shouldBe Some(3)
      CaptureWake.nextAvailable(_=>true) shouldBe None
      val generation=CaptureWake.generation("boot-123",1)
      CaptureWake.decode(ids(1),CaptureWake.table("capture-a"),generation) shouldBe Some("capture-a"->"boot-123")
      CaptureWake.decode(ids(1),CaptureWake.table("capture-b"),generation) shouldBe None
      CaptureWake.decode(ids(0),CaptureWake.table("capture-a"),generation) shouldBe None
      CaptureWake.decode(ids(1),CaptureWake.table("capture-a"),"boot-123.01") shouldBe None
      intercept[IllegalArgumentException](CaptureWake.generation("boot-123",CaptureWake.MaxAttempts))
    }
  }
  "B barrier ACK coverage" should {
    "verify parent and reference digests and distinguish covered from replayable" in {
      val offset=JsObject("file"->JsString("binlog.000001"),"pos"->JsNumber(100))
      def ref(seq:Int,digest:String)=JsObject("sequence"->JsNumber(seq),"digest"->JsString(digest))
      def ack(digest:String,parent:JsValue,refs:Vector[JsValue],at:JsValue=offset):Map[String,String]=Map(
        "kind"->"ACK","digest"->digest,"table_name"->MySQLCdcGroup.hash(MySQLCdcGroup.canonical(at)),
        "payload"->JsObject("offset"->at,"parent"->parent,"references"->JsArray(refs)).compactPrint)
      val barrier=Map("kind"->"BARRIER","digest"->"barrier-digest")
      val frames=Map(1L->ack("a1",JsNull,Vector.empty),2L->barrier,
        3L->ack("a3",ref(1,"a1"),Vector.empty),4L->ack("a4",ref(3,"a3"),Vector(ref(2,"barrier-digest"))))
      def inspect(head:Long,source:Map[Long,Map[String,String]]=frames)=
        CaptureAckProof.inspect(head,1L,2L,MySQLCdcGroup.canonical(offset),n=>source(n))
      val covered=inspect(4);covered.anchorInChain shouldBe true;covered.barrierReferenced shouldBe true
      val replay=inspect(3);replay.anchorInChain shouldBe true;replay.barrierReferenced shouldBe false
      val corrupted=frames.updated(4L,ack("a4",ref(3,"a3"),Vector(ref(2,"wrong"))))
      intercept[IllegalArgumentException](inspect(4,corrupted))
      val alien=frames.updated(3L,ack("a3",JsNull,Vector.empty))
      inspect(3,alien).anchorInChain shouldBe false
      val moved=frames.updated(3L,ack("a3",ref(1,"a1"),Vector.empty,JsObject("file"->JsString("binlog.000001"),"pos"->JsNumber(101))))
      intercept[IllegalArgumentException](inspect(3,moved))
    }
  }
}
