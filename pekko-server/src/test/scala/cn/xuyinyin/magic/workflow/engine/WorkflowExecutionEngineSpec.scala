package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import com.typesafe.config.ConfigFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicBoolean

class WorkflowExecutionEngineSpec extends STSpec {
  private var registeredSource: Option[NodeSource] = None
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty[Nothing],
    "workflow-execution-engine-spec",
    ConfigFactory.parseString(
      """pekko.actor.provider = local
        |pekko.coordinated-shutdown.exit-jvm = off""".stripMargin
    )
  )
  private implicit val ec: ExecutionContext = system.executionContext

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    try registeredSource.foreach(source => NodeRegistry.unregisterSource(source.nodeType, source))
    finally {
      registeredSource = None
      FailingSource.reset()
      super.afterEach()
    }
  }

  "WorkflowExecutionEngine" should {
    "report the failing Sink node without converting the execution to success" in {
      val engine = new WorkflowExecutionEngine()

      val result = Await.result(engine.execute(WorkflowFixtures.failingSinkWorkflow, "exec-1", _ => ()), 5.seconds)

      result.success shouldBe false
      result.nodeResults.map(_.nodeId) shouldBe Vector("source-1", "sink-1")
      result.nodeResults.map(_.status) shouldBe Vector("not_started", "failed")
    }

    "attribute an asynchronous Source failure and preserve all pipeline node results" in {
      register(FailingSource)
      val engine = new WorkflowExecutionEngine()
      FailingSource.reset()

      val result = Await.result(engine.execute(
        WorkflowFixtures.failingSourceWorkflow,
        "exec-2",
        _ => ()
      ), 5.seconds)

      result.success shouldBe false
      FailingSource.emittedFirstValue shouldBe true
      result.nodeResults.map(_.nodeId) shouldBe Vector("source-1", "transform-1", "sink-1")
      result.nodeResults.map(_.status) shouldBe Vector("failed", "unknown", "unknown")
    }

    "return ordered conservative results when validation prevents execution" in {
      val engine = new WorkflowExecutionEngine()

      val result = Await.result(engine.execute(WorkflowFixtures.branchedWorkflow, "exec-3", _ => ()), 5.seconds)

      result.success shouldBe false
      result.nodeResults.map(_.nodeId) shouldBe Vector("source-1", "transform-1", "sink-1", "sink-2")
      result.nodeResults.map(_.status) shouldBe Vector("not_started", "not_started", "not_started", "not_started")
    }

    "keep a replacement Source registered when cleanup expects the previous instance" in {
      register(FailingSource)
      NodeRegistry.registerSource(ReplacementSource)

      NodeRegistry.unregisterSource(FailingSource.nodeType, FailingSource)

      NodeRegistry.findSource(FailingSource.nodeType) shouldBe Some(ReplacementSource)
      registeredSource = Some(ReplacementSource)
    }

    "keep an equal but distinct replacement Source registered during cleanup" in {
      register(FailingSource)
      NodeRegistry.registerSource(EqualReplacementSource)
      registeredSource = Some(EqualReplacementSource)

      NodeRegistry.unregisterSource(FailingSource.nodeType, FailingSource)

      NodeRegistry.findSource(FailingSource.nodeType) shouldBe Some(EqualReplacementSource)
    }
  }

  private def register(source: NodeSource): Unit = {
    registeredSource = Some(source)
    NodeRegistry.registerSource(source)
  }

  private object FailingSource extends NodeSource {
    override val nodeType: String = "test.failing-source"
    private val emittedFirst = new AtomicBoolean(false)

    def emittedFirstValue: Boolean = emittedFirst.get
    def reset(): Unit = emittedFirst.set(false)

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      Source(List("first"))
        .map { value =>
          emittedFirst.set(true)
          value
        }
        .concat(Source.failed(new IllegalStateException("source boom")))
  }

  private object ReplacementSource extends NodeSource {
    override val nodeType: String = FailingSource.nodeType

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      Source.empty
  }

  private object EqualReplacementSource extends NodeSource {
    override val nodeType: String = FailingSource.nodeType

    override def equals(other: Any): Boolean = other match {
      case source: NodeSource => source.nodeType == nodeType
      case _ => false
    }

    override def hashCode(): Int = nodeType.hashCode

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      Source.empty
  }
}
