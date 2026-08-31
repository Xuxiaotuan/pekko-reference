package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Edge, ScheduleConfig}
import spray.json.JsObject

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

    "accept only a direct unscheduled MySQL CDC source-to-apply-sink workflow" in {
      val cdc = WorkflowFixtures.mysqlCdcWorkflow
      val source = cdc.nodes.head
      val sink = cdc.nodes.last
      val transform = WorkflowFixtures.linearWorkflow.nodes(1)
      val cdcWithTransform = cdc.copy(
        nodes = List(source, transform, sink),
        edges = List(
          Edge("source-transform", source.id, transform.id),
          Edge("transform-sink", transform.id, sink.id)
        )
      )
      val cdcWithLegacySink = cdc.copy(nodes = cdc.nodes.updated(1, sink.copy(nodeType = "console.log")))
      val legacySourceWithCdcSink = cdc.copy(nodes = cdc.nodes.updated(0, source.copy(nodeType = "sequence.numbers")))
      val scheduledCdc = cdc.copy(metadata = cdc.metadata.copy(
        schedule = Some(ScheduleConfig(enabled = true, "fixed_rate", interval = Some("1h")))
      ))

      errors(cdcWithTransform) should contain("mysql_cdc_transform_not_supported")
      errors(cdcWithLegacySink) should contain("mysql_cdc_sink_required")
      errors(legacySourceWithCdcSink) should contain("mysql_cdc_source_required")
      errors(scheduledCdc) should contain("mysql_cdc_schedule_not_supported")
      WorkflowValidator.validate(cdc).isRight shouldBe true
    }

    "leave enabled schedules valid for legacy workflows" in {
      val scheduledLegacy = WorkflowFixtures.linearWorkflow.copy(metadata = WorkflowFixtures.linearWorkflow.metadata.copy(
        schedule = Some(ScheduleConfig(enabled = true, "fixed_rate", interval = Some("1h")))
      ))

      WorkflowValidator.validate(scheduledLegacy).isRight shouldBe true
    }

    "reject inline passwords in persisted MySQL CDC definitions" in {
      val cdc = WorkflowFixtures.mysqlCdcWorkflow
      val sourceWithPassword = cdc.nodes.head.copy(config = JsObject(
        (cdc.nodes.head.config.fields - "passwordEnv") + ("password" -> spray.json.JsString("definition-secret"))
      ))
      val sinkWithPassword = cdc.nodes.last.copy(config = JsObject(
        (cdc.nodes.last.config.fields - "passwordEnv") + ("password" -> spray.json.JsString("definition-secret"))
      ))

      errors(cdc.copy(nodes = cdc.nodes.updated(0, sourceWithPassword))) should contain("mysql_cdc_inline_password_not_supported")
      errors(cdc.copy(nodes = cdc.nodes.updated(1, sinkWithPassword))) should contain("mysql_cdc_inline_password_not_supported")
    }
  }

  private def errors(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow): Vector[String] =
    WorkflowValidator.validate(workflow).fold(_.map(_.code), _ => fail("Expected validation failure"))
}
