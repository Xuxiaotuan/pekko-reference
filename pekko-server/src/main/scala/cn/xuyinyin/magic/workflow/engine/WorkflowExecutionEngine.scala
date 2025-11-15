package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.executors.{SourceExecutor, TransformExecutor, SinkExecutor}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.{Done, NotUsed}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable

/**
 * 工作流执行引擎
 * 
 * 基于Pekko Stream实现真实的DSL执行
 * 使用独立的Executor处理不同类型节点，易于扩展
 * 
 * 架构：
 * - SourceExecutor: 处理所有数据源节点
 * - TransformExecutor: 处理所有转换节点
 * - SinkExecutor: 处理所有数据汇节点
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class WorkflowExecutionEngine(implicit system: ActorSystem[_], ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  implicit val materializer: Materializer = SystemMaterializer(system).materializer
  
  // 独立的执行器 - 易于扩展和测试
  private val sourceExecutor = new SourceExecutor()
  private val transformExecutor = new TransformExecutor()
  private val sinkExecutor = new SinkExecutor()
  
  // 节点类型判断辅助方法
  private def isSourceNode(node: WorkflowDSL.Node): Boolean = 
    sourceExecutor.supportedTypes.contains(node.nodeType)
    
  private def isTransformNode(node: WorkflowDSL.Node): Boolean = 
    transformExecutor.supportedTypes.contains(node.nodeType)
    
  private def isSinkNode(node: WorkflowDSL.Node): Boolean = 
    sinkExecutor.supportedTypes.contains(node.nodeType)
  
  /**
   * 节点分类结果
   */
  private case class NodeClassification(
    sources: List[WorkflowDSL.Node],
    transforms: List[WorkflowDSL.Node],
    sinks: List[WorkflowDSL.Node]
  )
  
  /**
   * 对节点进行分类（一次遍历）
   */
  private def classifyNodes(nodes: List[WorkflowDSL.Node]): NodeClassification = {
    val (sources, rest1) = nodes.partition(isSourceNode)
    val (transforms, sinks) = rest1.partition(isTransformNode)
    NodeClassification(sources, transforms, sinks)
  }
  
  /**
   * 执行工作流
   */
  def execute(
    workflow: WorkflowDSL.Workflow,
    executionId: String,
    onLog: String => Unit
  ): Future[ExecutionResult] = {
    
    val startTime = System.currentTimeMillis()
    logger.info(s"Starting workflow execution: ${workflow.id}")
    onLog(s"开始执行工作流: ${workflow.name}")
    
    try {
      // 1. 验证工作流
      validateWorkflow(workflow, onLog)
      
      // 2. 构建执行图
      val graph = buildExecutionGraph(workflow, onLog)
      
      // 3. 执行图
      onLog("开始执行Pekko Stream图")
      val result = graph.run()
      
      // 4. 处理结果
      result.map { done =>
        val duration = System.currentTimeMillis() - startTime
        onLog(s"工作流执行成功完成 (耗时: ${duration}ms)")
        logger.info(s"Workflow executed successfully: ${workflow.id} in ${duration}ms")
        ExecutionResult(
          status = "completed",
          success = true,
          message = s"Workflow executed successfully in ${duration}ms",
          rowsProcessed = None,
          duration = Some(duration)
        )
      }.recover { case ex: Throwable =>
        val duration = System.currentTimeMillis() - startTime
        val errorMsg = s"执行失败: ${ex.getMessage}"
        onLog(errorMsg)
        logger.error(s"Workflow execution failed: ${workflow.id} after ${duration}ms", ex)
        ExecutionResult(
          status = "failed",
          success = false,
          message = errorMsg,
          rowsProcessed = None,
          duration = Some(duration)
        )
      }
      
    } catch {
      case ex: Throwable =>
        val duration = System.currentTimeMillis() - startTime
        val errorMsg = s"执行异常: ${ex.getMessage}"
        onLog(errorMsg)
        logger.error(s"Workflow execution error: ${workflow.id} after ${duration}ms", ex)
        Future.successful(ExecutionResult(
          status = "failed",
          success = false,
          message = errorMsg,
          rowsProcessed = None,
          duration = Some(duration)
        ))
    }
  }
  
  /**
   * 验证工作流
   */
  private def validateWorkflow(workflow: WorkflowDSL.Workflow, onLog: String => Unit): Unit = {
    onLog("验证工作流定义")
    
    if (workflow.nodes.isEmpty) {
      throw new IllegalArgumentException("工作流没有节点")
    }
    
    // 一次遍历完成节点分类（性能优化）
    val classification = classifyNodes(workflow.nodes)
    
    if (classification.sources.isEmpty) {
      throw new IllegalArgumentException(
        s"工作流必须至少有一个数据源节点。\n支持的Source类型: ${sourceExecutor.supportedTypes.mkString(", ")}"
      )
    }
    
    if (classification.sinks.isEmpty) {
      throw new IllegalArgumentException(
        s"工作流必须至少有一个数据汇节点。\n支持的Sink类型: ${sinkExecutor.supportedTypes.mkString(", ")}"
      )
    }
    
    // 检查环路
    if (hasCycle(workflow.nodes, workflow.edges)) {
      throw new IllegalArgumentException("工作流包含环路")
    }
    
    // 输出详细的验证信息
    onLog(s"验证通过: ${workflow.nodes.length}个节点, ${workflow.edges.length}条边")
    onLog(s"  - Source节点: ${classification.sources.length} 个 (${classification.sources.map(_.nodeType).mkString(", ")})")
    onLog(s"  - Transform节点: ${classification.transforms.length} 个" + 
      (if (classification.transforms.nonEmpty) s" (${classification.transforms.map(_.nodeType).mkString(", ")})" else ""))
    onLog(s"  - Sink节点: ${classification.sinks.length} 个 (${classification.sinks.map(_.nodeType).mkString(", ")})")
  }
  
  /**
   * 检测环路
   */
  private def hasCycle(nodes: List[WorkflowDSL.Node], edges: List[WorkflowDSL.Edge]): Boolean = {
    val graph = edges.groupBy(_.source).mapValues(_.map(_.target).toSet)
    val visited = mutable.Set.empty[String]
    val recStack = mutable.Set.empty[String]
    
    def dfs(nodeId: String): Boolean = {
      visited.add(nodeId)
      recStack.add(nodeId)
      
      val neighbors = graph.getOrElse(nodeId, Set.empty)
      for (neighbor <- neighbors) {
        if (!visited.contains(neighbor)) {
          if (dfs(neighbor)) return true
        } else if (recStack.contains(neighbor)) {
          return true
        }
      }
      
      recStack.remove(nodeId)
      false
    }
    
    nodes.exists(node => !visited.contains(node.id) && dfs(node.id))
  }
  
  /**
   * 构建执行图
   */
  private def buildExecutionGraph(
    workflow: WorkflowDSL.Workflow,
    onLog: String => Unit
  ): RunnableGraph[Future[Done]] = {
    
    onLog("开始构建Pekko Stream执行图")
    
    // 拓扑排序
    val sortedNodes = topologicalSort(workflow.nodes, workflow.edges)
    onLog(s"节点执行顺序: ${sortedNodes.map(n => s"${n.id}(${n.nodeType})").mkString(" -> ")}")
    
    // 找到Source节点
    val sourceNode = sortedNodes.find(isSourceNode)
      .getOrElse(throw new IllegalArgumentException("未找到数据源节点"))
    
    onLog(s"数据源: ${sourceNode.label} (${sourceNode.nodeType})")
    
    // 使用SourceExecutor创建Source
    val source: Source[String, NotUsed] = sourceExecutor.createSource(sourceNode, onLog)
    
    // 构建Transform链
    val transformNodes = sortedNodes.filter(isTransformNode)
    val transformFlow = if (transformNodes.isEmpty) {
      Flow[String]
    } else {
      transformNodes.foldLeft(Flow[String]) { (flow, node) =>
        onLog(s"添加转换: ${node.label} (${node.nodeType})")
        val transform = transformExecutor.createTransform(node, onLog)
        flow.via(transform)
      }
    }
    
    // 找到Sink节点
    val sinkNode = sortedNodes.find(isSinkNode)
      .getOrElse(throw new IllegalArgumentException("未找到数据汇节点"))
    
    onLog(s"数据汇: ${sinkNode.label} (${sinkNode.nodeType})")
    
    // 使用SinkExecutor创建Sink
    val sink: Sink[String, Future[Done]] = sinkExecutor.createSink(sinkNode, onLog)
    
    // 组装完整的图
    source
      .via(transformFlow)
      .toMat(sink)(Keep.right)
  }
  
  /**
   * 拓扑排序
   */
  private def topologicalSort(
    nodes: List[WorkflowDSL.Node],
    edges: List[WorkflowDSL.Edge]
  ): List[WorkflowDSL.Node] = {
    val graph = edges.groupBy(_.source).mapValues(_.map(_.target).toSet)
    val inDegree = nodes.map { node =>
      node.id -> edges.count(_.target == node.id)
    }.toMap
    
    val queue = mutable.Queue(nodes.filter(n => inDegree(n.id) == 0): _*)
    val result = mutable.ListBuffer[WorkflowDSL.Node]()
    val currentDegree = mutable.Map(inDegree.toSeq: _*)
    
    while (queue.nonEmpty) {
      val node = queue.dequeue()
      result += node
      
      graph.getOrElse(node.id, Set.empty).foreach { targetId =>
        currentDegree(targetId) = currentDegree(targetId) - 1
        if (currentDegree(targetId) == 0) {
          val targetNode = nodes.find(_.id == targetId).get
          queue.enqueue(targetNode)
        }
      }
    }
    
    if (result.size != nodes.size) {
      throw new IllegalArgumentException("工作流包含环路，无法拓扑排序")
    }
    
    result.toList
  }
}

/**
 * 执行结果
 */
case class ExecutionResult(
  status: String,
  success: Boolean,
  message: String,
  rowsProcessed: Option[Int],
  duration: Option[Long] = None  // 执行时长（毫秒）
)
