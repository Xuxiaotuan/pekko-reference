package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchCommitResult, SourceBatch}
import cn.xuyinyin.magic.workflow.engine.executors.{SinkExecutor, SourceExecutor, TransformExecutor}
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node, Workflow}
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, CheckpointedNodeSource}
import com.typesafe.scalalogging.Logger
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector}
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
  private lazy val jdbcBlockingEc: ExecutionContext =
    system.dispatchers.lookup(DispatcherSelector.fromConfig("pekko.workflow.jdbc-dispatcher"))

  def execute(workflow: Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
    executeInternal(workflow, executionId, None, onLog)

  def execute(workflow: Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] =
    executeInternal(workflow, runContext.executionId, Some(runContext), onLog)

  private def executeInternal(
    workflow: Workflow,
    executionId: String,
    runContext: Option[ReliableRunContext],
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    val startTime = System.currentTimeMillis()
    logger.info(s"Starting workflow execution: ${workflow.id}")
    onLog(s"开始执行工作流: ${workflow.name}")

    WorkflowValidator.validate(workflow) match {
      case Left(errors) =>
        val message = errors.map(_.message).mkString("; ")
        onLog(s"执行失败: $message")
        Future.successful(failedResult(message, startTime, validationFailureResults(workflow)))

      case Right(pipeline) =>
        checkpointedSource(Some(pipeline.source)) match {
          case Some(source) => checkpointedSink(Some(pipeline.sink)) match {
            case Some(sink) => runContext match {
              case Some(context) => executeReliable(workflow, pipeline, source, sink, context, startTime, onLog)
              case None => capabilityFailure(
                workflow,
                pipeline,
                s"Reliable source ${pipeline.source.nodeType} requires ReliableRunContext for sink ${pipeline.sink.nodeType}",
                startTime,
                onLog
              )
            }
            case None =>
              val message = runContext.fold(
                s"Reliable source ${pipeline.source.nodeType} requires checkpoint-aware sink ${pipeline.sink.nodeType}"
              )(_ =>
                s"ReliableRunContext capability loss: sink ${pipeline.sink.nodeType} is not a checkpoint-aware sink for source ${pipeline.source.nodeType}"
              )
              capabilityFailure(workflow, pipeline, message, startTime, onLog)
          }
          case None => runContext match {
            case Some(_) => capabilityFailure(
              workflow,
              pipeline,
              s"ReliableRunContext capability loss: source ${pipeline.source.nodeType} is not a checkpoint-aware source for sink ${pipeline.sink.nodeType}",
              startTime,
              onLog
            )
            case None => executeLegacy(workflow, pipeline, startTime, onLog)
          }
        }
    }
  }

  private def executeLegacy(
    workflow: Workflow,
    pipeline: ValidatedPipeline,
    startTime: Long,
    onLog: String => Unit
  ): Future[ExecutionResult] =
    try {
      val graph = buildExecutionGraph(pipeline, onLog)
      onLog("开始执行Pekko Stream图")
      graph.run().map(_ => completedResult(workflow, pipeline, None, startTime, onLog)).recover {
        case failure: NodeRuntimeFailure => runtimeFailureResult(workflow, pipeline, failure.node, failure.cause, startTime, onLog)
        case NonFatal(ex) => unknownFailureResult(workflow, pipeline, ex, startTime, onLog)
      }
    } catch {
      case failure: NodeSetupFailure => setupFailureResult(workflow, pipeline, failure, startTime, onLog)
      case NonFatal(ex) =>
        val message = s"执行异常: ${ex.getMessage}"
        onLog(message)
        logger.error(s"Workflow execution error: ${workflow.id}", ex)
        Future.successful(failedResult(message, startTime, unknownResults(pipeline, ex)))
    }

  private def executeReliable(
    workflow: Workflow,
    pipeline: ValidatedPipeline,
    source: CheckpointedNodeSource,
    sink: CheckpointedNodeSink,
    runContext: ReliableRunContext,
    startTime: Long,
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    val execution = for {
      _ <- nodeFuture(pipeline.sink)(sink.validateReady(pipeline.sink, onLog)(jdbcBlockingEc))
      boundary <- runContext.boundary match {
        case Some(existing) => Future.successful(existing)
        case None =>
          nodeFuture(pipeline.source)(source.discoverBoundary(pipeline.source, onLog)(jdbcBlockingEc))
            .flatMap(boundary => workflowFuture(runContext.initializeBoundary(boundary)).map(_ => boundary))
      }
      rowsProcessed <- {
        val resumeFrom = runContext.checkpoints
          .filter(checkpoint => checkpoint.sourceNodeId == pipeline.source.id && checkpoint.partitionId == boundary.partitionId)
          .sortBy(_.batchSequence)
          .lastOption
        val transforms = createTransforms(pipeline.transforms, onLog)
        val batches = try {
          source.createBatches(pipeline.source, runContext.executionId, boundary, resumeFrom, onLog)(jdbcBlockingEc)
            .mapError(wrapRuntimeFailure(pipeline.source))
        } catch {
          case NonFatal(ex) => Source.failed(NodeRuntimeFailure(pipeline.source, ex))
        }
        batches
          .mapAsync(1)(batch => processBatch(workflow, pipeline, sink, transforms, runContext, batch, onLog))
          .runFold(0L)(_ + _)
      }
    } yield rowsProcessed

    execution
      .map(rows => completedResult(workflow, pipeline, reportedRowsProcessed(runContext, rows), startTime, onLog))
      .recover {
        case failure: NodeRuntimeFailure => runtimeFailureResult(workflow, pipeline, failure.node, failure.cause, startTime, onLog)
        case failure: NodeSetupFailure => runtimeFailureResult(workflow, pipeline, failure.node, failure.cause, startTime, onLog)
        case NonFatal(ex) => unknownFailureResult(workflow, pipeline, ex, startTime, onLog)
      }
  }

  private def reportedRowsProcessed(runContext: ReliableRunContext, rows: Long): Option[Int] =
    Option.when(runContext.checkpoints.isEmpty && rows.isValidInt)(rows.toInt)

  private def processBatch(
    workflow: Workflow,
    pipeline: ValidatedPipeline,
    sink: CheckpointedNodeSink,
    transforms: Flow[String, String, NotUsed],
    runContext: ReliableRunContext,
    batch: SourceBatch,
    onLog: String => Unit
  ): Future[Long] =
    Source(batch.rows)
      .via(transforms)
      .runWith(Sink.seq)
      .map(_.toVector)
      .flatMap(rows => nodeFuture(pipeline.sink)(
        sink.commitBatch(pipeline.sink, workflow.id, runContext.executionId, batch, rows, onLog)(jdbcBlockingEc)
      ))
      .flatMap { result =>
        val committed = checkpoint(result)
        workflowFuture(runContext.checkpointCommitted(committed)).map(_ => committed.targetRowsWritten)
      }

  private def checkpoint(result: BatchCommitResult): BatchCheckpoint = result match {
    case cn.xuyinyin.magic.workflow.checkpoint.Committed(value) => value
    case cn.xuyinyin.magic.workflow.checkpoint.AlreadyCommitted(value) => value
  }

  private def createTransforms(nodes: Vector[Node], onLog: String => Unit): Flow[String, String, NotUsed] =
    nodes.foldLeft(Flow[String]) { (flow, node) =>
      onLog(s"添加转换: ${node.label} (${node.nodeType})")
      flow.via(createTransform(node, onLog))
    }

  private def nodeFuture[A](node: Node)(operation: => Future[A]): Future[A] =
    try operation.recoverWith {
      case failure: NodeRuntimeFailure => Future.failed(failure)
      case NonFatal(ex) => Future.failed(NodeRuntimeFailure(node, ex))
    } catch {
      case NonFatal(ex) => Future.failed(NodeRuntimeFailure(node, ex))
    }

  private def workflowFuture[A](operation: => Future[A]): Future[A] =
    try operation catch { case NonFatal(ex) => Future.failed(ex) }

  private def checkpointedSource(node: Option[Node]): Option[CheckpointedNodeSource] =
    node.flatMap(value => NodeRegistry.findSource(value.nodeType)).collect { case source: CheckpointedNodeSource => source }

  private def checkpointedSink(node: Option[Node]): Option[CheckpointedNodeSink] =
    node.flatMap(value => NodeRegistry.findSink(value.nodeType)).collect { case sink: CheckpointedNodeSink => sink }

  private def capabilityFailure(
    workflow: Workflow,
    pipeline: ValidatedPipeline,
    message: String,
    startTime: Long,
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    onLog(s"执行失败: $message")
    logger.error(s"Workflow capability validation failed: ${workflow.id}: $message")
    Future.successful(failedResult(message, startTime, validationFailureResults(workflow)))
  }

  private def completedResult(
    workflow: Workflow,
    pipeline: ValidatedPipeline,
    rowsProcessed: Option[Int],
    startTime: Long,
    onLog: String => Unit
  ): ExecutionResult = {
    val duration = System.currentTimeMillis() - startTime
    onLog(s"工作流执行成功完成 (耗时: ${duration}ms)")
    logger.info(s"Workflow executed successfully: ${workflow.id} in ${duration}ms")
    ExecutionResult(
      status = "completed",
      success = true,
      message = s"Workflow executed successfully in ${duration}ms",
      rowsProcessed = rowsProcessed,
      duration = Some(duration),
      nodeResults = pipeline.nodes.map(node => completedNode(node, duration))
    )
  }

  private def setupFailureResult(
    workflow: Workflow,
    pipeline: ValidatedPipeline,
    failure: NodeSetupFailure,
    startTime: Long,
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    val duration = System.currentTimeMillis() - startTime
    val message = s"执行失败: ${failure.cause.getMessage}"
    onLog(message)
    logger.error(s"Workflow setup failed: ${workflow.id} after ${duration}ms", failure.cause)
    Future.successful(failedResult(message, startTime, setupFailureResults(pipeline, failure.node, failure.cause, duration)))
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
