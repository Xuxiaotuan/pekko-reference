package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.nodes.base.{NodeSink, NodeSource}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import spray.json.{JsObject, JsString}

import scala.concurrent.{ExecutionContext, Future}

class WorkflowPlanCompilerSpec extends STSpec {
  private val registry = NodeRegistry.builtins()

  "WorkflowPlanCompiler" should {
    "compile CDC as a distinct checkpointed plan without starting its connectors" in {
      val plan = new WorkflowPlanCompiler(registry).compile(WorkflowFixtures.mysqlCdcWorkflow).toOption.get
      plan shouldBe a[MySqlCdcPlan]
      plan.resumable shouldBe true
      plan.pipeline.transforms shouldBe empty
      plan.task shouldBe ExecutionTask("mysql-cdc", "mysql-cdc")
      plan.pipeline.source.config.fields("passwordEnv") shouldBe JsString("MYSQL_CDC_PASSWORD")
      plan.pipeline.source.config.fields should not contain "password"
    }

    "remove presentation and historical metadata from the runtime description" in {
      val workflow = WorkflowFixtures.linearWorkflow
      val changed = workflow.copy(
        author = "changed", description = "changed", tags = List("changed"),
        nodes = workflow.nodes.map(_.copy(position = Position(987, 654), style = Some(JsObject("color" -> JsString("red"))))),
        edges = workflow.edges.map(_.copy(animated = Some(true), style = Some(JsObject("width" -> JsString("wide"))))),
        metadata = workflow.metadata.copy(executionHistory = Some(List(ExecutionRecord("old", "then", None, "failed", None))))
      )
      val compiler = new WorkflowPlanCompiler(registry)
      val original = compiler.compile(workflow).toOption.get
      val updated = compiler.compile(changed).toOption.get
      original shouldBe a[RowStreamPlan]
      updated shouldBe original
      val connector = updated.pipeline.source.connectorNode
      connector.position shouldBe Position(0, 0)
      connector.style shouldBe None
      connector.config shouldBe workflow.nodes.head.config
    }

    "classify a checkpointed auxiliary task separately from CDC" in {
      val workflow = WorkflowFixtures.mysqlCdcWorkflow.copy(nodes = WorkflowFixtures.mysqlCdcWorkflow.nodes.map { node =>
        node.copy(nodeType = if (node.`type` == "source") "mysql.snapshot" else "mysql.write")
      })
      val plan = new WorkflowPlanCompiler(registry).compile(workflow).toOption.get
      plan shouldBe a[CheckpointedLinearPlan]
      plan.resumable shouldBe true
    }

    "bind the current registry instances instead of inferring capabilities from builtin names" in {
      val source = legacySource("mysql.cdc")
      val sink = legacySink("mysql.cdc.apply")
      val compiler = new WorkflowPlanCompiler(registry.withSource(source).withSink(sink))
      val plan = compiler.compile(WorkflowFixtures.mysqlCdcWorkflow).toOption.get.asInstanceOf[RowStreamPlan]
      plan.resumable shouldBe false
      plan.source should be theSameInstanceAs source
      plan.sink should be theSameInstanceAs sink
      new WorkflowPlanCompiler(registry).compile(WorkflowFixtures.mysqlCdcWorkflow).toOption.get shouldBe a[MySqlCdcPlan]
    }

    "reject a checkpoint source with a row-only sink without opening either adapter" in {
      val compiler = new WorkflowPlanCompiler(registry.withSink(legacySink("mysql.cdc.apply")))
      compiler.compile(WorkflowFixtures.mysqlCdcWorkflow).left.toOption.get shouldBe a[CheckpointSinkRequired]
    }

    "keep a row source paired with a checkpoint sink on the existing row path" in {
      val compiler = new WorkflowPlanCompiler(registry.withSource(legacySource("mysql.cdc")))
      val plan = compiler.compile(WorkflowFixtures.mysqlCdcWorkflow).toOption.get
      plan shouldBe a[RowStreamPlan]
      plan.resumable shouldBe false
    }

    "retain structural validation errors before resolving a runtime plan" in {
      val compiler = new WorkflowPlanCompiler(registry)
      val errors = compiler.compile(WorkflowFixtures.branchedWorkflow).left.toOption.get.asInstanceOf[InvalidWorkflowPlan]
      errors.errors.map(_.code) should contain("branch_not_supported")
    }

    "reject CDC transforms even when the graph is a valid linear pipeline" in {
      val cdc = WorkflowFixtures.mysqlCdcWorkflow
      val transform = WorkflowFixtures.linearWorkflow.nodes.find(_.`type` == "transform").get
      val changed = cdc.copy(nodes = List(cdc.nodes.head, transform, cdc.nodes.last), edges = List(
        Edge("a", cdc.nodes.head.id, transform.id), Edge("b", transform.id, cdc.nodes.last.id)))
      val errors = new WorkflowPlanCompiler(registry).compile(changed).left.toOption.get.asInstanceOf[InvalidWorkflowPlan]
      errors.errors.map(_.code) should contain("mysql_cdc_transform_not_supported")
    }
  }

  private def legacySource(kind: String): NodeSource = new NodeSource {
    override val nodeType: String = kind
    override def createSource(node: Node, onLog: String => Unit): Source[String, NotUsed] =
      throw new AssertionError("compilation must not open a source")
  }

  private def legacySink(kind: String): NodeSink = new NodeSink {
    override val nodeType: String = kind
    override def createSink(node: Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] =
      throw new AssertionError("compilation must not open a sink")
  }
}
