package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures

class WorkflowValidatorSpec extends STSpec {
  "WorkflowValidator" should {
    "return the only connected Source to Sink path" in {
      val result = WorkflowValidator.validate(WorkflowFixtures.linearWorkflow).fold(
        errors => fail(errors.map(_.message).mkString(", ")),
        identity
      )

      result.transforms.map(_.id) shouldBe Vector("transform-1")
    }

    "reject branches and disconnected nodes" in {
      errors(WorkflowFixtures.branchedWorkflow) should contain("branch_not_supported")
      errors(WorkflowFixtures.disconnectedWorkflow) should contain("disconnected_node")
    }

    "reject cycles, merges, and unknown edge endpoints" in {
      errors(WorkflowFixtures.cyclicWorkflow) should contain("cycle_not_supported")
      errors(WorkflowFixtures.mergedWorkflow) should contain("merge_not_supported")
      errors(WorkflowFixtures.unknownEndpointWorkflow) should contain("unknown_endpoint")
    }

    "reject executor-advertised transform types without implementation" in {
      errors(WorkflowFixtures.unimplementedTransformWorkflow) should contain("unsupported_node_type")
    }

    "return validation errors in a stable order" in {
      errors(WorkflowFixtures.multiErrorWorkflow) shouldBe Vector(
        "unknown_endpoint",
        "branch_not_supported",
        "merge_not_supported"
      )
    }
  }

  private def errors(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow): Vector[String] =
    WorkflowValidator.validate(workflow).fold(_.map(_.code), _ => fail("Expected validation failure"))
}
