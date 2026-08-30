package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.workflow.engine.executors.{SinkExecutor, SourceExecutor, TransformExecutor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node, Workflow}
import com.typesafe.scalalogging.Logger
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class WorkflowExecutionEngine()(implicit system: ActorSystem[_], ec: ExecutionContext) {
  private val logger = Logger(getClass)
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer

  private val sourceExecutor = new SourceExecutor()
  private val transformExecutor = new TransformExecutor()
  private val sinkExecutor = new SinkExecutor()

  def execute(workflow: Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] = {
    val startTime = System.currentTimeMillis()
    logger.info(s"Starting workflow execution: ${workflow.id}")
    onLog(s"开始执行工作流: ${workflow.name}")

    WorkflowValidator.validate(workflow) match {
      case Left(errors) =>
        val message = errors.map(_.message).mkString("; ")
        onLog(s"执行失败: $message")
        Future.successful(failedResult(message, startTime, validationFailureResults(workflow)))

      case Right(pipeline) =>
        try {
          val graph = buildExecutionGraph(pipeline, onLog)
          onLog("开始执行Pekko Stream图")
          graph.run().map { _ =>
            val duration = System.currentTimeMillis() - startTime
            onLog(s"工作流执行成功完成 (耗时: ${duration}ms)")
            logger.info(s"Workflow executed successfully: ${workflow.id} in ${duration}ms")
            ExecutionResult(
              status = "completed",
              success = true,
              message = s"Workflow executed successfully in ${duration}ms",
              rowsProcessed = None,
              duration = Some(duration),
              nodeResults = pipeline.nodes.map(node => completedNode(node, duration))
            )
          }.recover {
            case failure: NodeRuntimeFailure => runtimeFailureResult(workflow, pipeline, failure.node, failure.cause, startTime, onLog)
            case NonFatal(ex) => unknownFailureResult(workflow, pipeline, ex, startTime, onLog)
          }
        } catch {
          case failure: NodeSetupFailure =>
            val duration = System.currentTimeMillis() - startTime
            val message = s"执行失败: ${failure.cause.getMessage}"
            onLog(message)
            logger.error(s"Workflow setup failed: ${workflow.id} after ${duration}ms", failure.cause)
            Future.successful(failedResult(message, startTime, setupFailureResults(pipeline, failure.node, failure.cause, duration)))
          case NonFatal(ex) =>
            val message = s"执行异常: ${ex.getMessage}"
            onLog(message)
            logger.error(s"Workflow execution error: ${workflow.id}", ex)
            Future.successful(failedResult(message, startTime, unknownResults(pipeline, ex)))
        }
    }
  }

  private def buildExecutionGraph(pipeline: ValidatedPipeline, onLog: String => Unit): RunnableGraph[Future[Done]] = {
    onLog("开始构建Pekko Stream执行图")
    onLog(s"节点执行顺序: ${pipeline.nodes.map(node => s"${node.id}(${node.nodeType})").mkString(" -> ")}")

    val source = createSource(pipeline.source, onLog)
    val transforms = pipeline.transforms.foldLeft(Flow[String]) { (flow, node) =>
      onLog(s"添加转换: ${node.label} (${node.nodeType})")
      flow.via(createTransform(node, onLog))
    }
    val sink = createSink(pipeline.sink, onLog)

    source.via(transforms).toMat(sink)(Keep.right)
  }

  private def createSource(node: Node, onLog: String => Unit): Source[String, NotUsed] =
    try sourceExecutor.createSource(node, onLog).mapError(wrapRuntimeFailure(node))
    catch { case NonFatal(ex) => throw NodeSetupFailure(node, ex) }

  private def createTransform(node: Node, onLog: String => Unit): Flow[String, String, NotUsed] =
    try transformExecutor.createTransform(node, onLog).mapError(wrapRuntimeFailure(node))
    catch { case NonFatal(ex) => throw NodeSetupFailure(node, ex) }

  private def createSink(node: Node, onLog: String => Unit): Sink[String, Future[Done]] =
    try sinkExecutor.createSink(node, onLog).mapMaterializedValue(_.recoverWith {
      case failure: NodeRuntimeFailure => Future.failed(failure)
      case NonFatal(ex) => Future.failed(NodeRuntimeFailure(node, ex))
    })
    catch { case NonFatal(ex) => throw NodeSetupFailure(node, ex) }

  private def wrapRuntimeFailure(node: Node): PartialFunction[Throwable, Throwable] = {
    case failure: NodeRuntimeFailure => failure
    case NonFatal(ex) => NodeRuntimeFailure(node, ex)
  }

  private def failedResult(
    message: String,
    startTime: Long,
    nodeResults: Vector[NodeExecutionResult] = Vector.empty
  ): ExecutionResult = {
    val duration = System.currentTimeMillis() - startTime
    ExecutionResult(
      status = "failed",
      success = false,
      message = message,
      rowsProcessed = None,
      duration = Some(duration),
      nodeResults = nodeResults
    )
  }

  private def completedNode(node: Node, duration: Long): NodeExecutionResult =
    NodeExecutionResult(node.id, node.nodeType, "completed", duration = Some(duration))

  private def setupFailureResults(
    pipeline: ValidatedPipeline,
    failedNode: Node,
    cause: Throwable,
    duration: Long
  ): Vector[NodeExecutionResult] =
    pipeline.nodes.map { node =>
      if (node.id == failedNode.id) NodeExecutionResult(node.id, node.nodeType, "failed", Some(cause.getMessage), Some(duration))
      else NodeExecutionResult(node.id, node.nodeType, "not_started")
    }

  private def validationFailureResults(workflow: Workflow): Vector[NodeExecutionResult] =
    workflow.nodes.toVector.map(node => NodeExecutionResult(node.id, node.nodeType, "not_started"))

  private def unknownResults(
    pipeline: ValidatedPipeline,
    cause: Throwable
  ): Vector[NodeExecutionResult] =
    pipeline.nodes.map(node => NodeExecutionResult(node.id, node.nodeType, "unknown", Some(cause.getMessage)))

  private def runtimeFailureResult(
    workflow: Workflow,
    pipeline: ValidatedPipeline,
    failedNode: Node,
    cause: Throwable,
    startTime: Long,
    onLog: String => Unit
  ): ExecutionResult = {
    val duration = System.currentTimeMillis() - startTime
    val message = s"执行失败: ${cause.getMessage}"
    val failedIndex = pipeline.nodes.indexWhere(_.id == failedNode.id)
    onLog(message)
    logger.error(s"Workflow execution failed: ${workflow.id} after ${duration}ms", cause)
    ExecutionResult(
      status = "failed",
      success = false,
      message = message,
      rowsProcessed = None,
      duration = Some(duration),
      nodeResults = pipeline.nodes.zipWithIndex.map { case (node, index) =>
        if (index == failedIndex) NodeExecutionResult(node.id, node.nodeType, "failed", Some(cause.getMessage), Some(duration))
        else NodeExecutionResult(node.id, node.nodeType, "unknown")
      }
    )
  }

  private def unknownFailureResult(
    workflow: Workflow,
    pipeline: ValidatedPipeline,
    cause: Throwable,
    startTime: Long,
    onLog: String => Unit
  ): ExecutionResult = {
    val duration = System.currentTimeMillis() - startTime
    val message = s"执行失败: ${cause.getMessage}"
    onLog(message)
    logger.error(s"Workflow execution failed: ${workflow.id} after ${duration}ms", cause)
    ExecutionResult(
      status = "failed",
      success = false,
      message = message,
      rowsProcessed = None,
      duration = Some(duration),
      nodeResults = unknownResults(pipeline, cause)
    )
  }

  private final case class NodeRuntimeFailure(node: Node, cause: Throwable)
    extends RuntimeException(cause)

  private final case class NodeSetupFailure(node: Node, cause: Throwable)
    extends RuntimeException(cause)
}
