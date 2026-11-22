package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import spray.json._

class SnapshotSplitContractSpec extends STSpec {
  "split snapshots" should {
    "reject an opt-in split source without an explicit quiescent-data contract" in {
      val original = WorkflowFixtures.mysqlCdcWorkflow
      val workflow = original.copy(nodes = original.nodes.map { n =>
        if (n.`type` == "source") n.copy(nodeType = "mysql.snapshot", config = JsObject(n.config.fields + ("splitRows" -> JsNumber(4))))
        else n.copy(nodeType = "mysql.write")
      })
      new WorkflowPlanCompiler(NodeRegistry.builtins()).compile(workflow).isLeft shouldBe true
    }
  }
}
