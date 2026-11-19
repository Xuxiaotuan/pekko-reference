package cn.xuyinyin.magic.cdc.model

import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.JsObject

class CdcNodeModelSpec extends AnyWordSpec with Matchers {
  "CDC node adapter" should {
    "round trip a legacy node without changing its persisted fields" in {
      val legacy = WorkflowDSL.Node("n", "sink", "mysql.cdc.apply", "target",
        WorkflowDSL.Position(1.5, 2.5), JsObject("table" -> spray.json.JsString("orders")), None)
      CdcNodeAdapter.toLegacy(CdcNodeAdapter.fromLegacy(legacy)) shouldBe legacy
    }
  }
}
