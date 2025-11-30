package cn.xuyinyin.magic.workflow.sharding

import cn.xuyinyin.magic.testkit.STPekkoSpec
import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowActor}
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration._

/**
 * WorkflowSharding单元测试
 * 
 * 测试内容：
 * - Entity创建和路由
 * - 分片策略的确定性
 * - 消息路由的正确性
 * 
 * 验证：
 * - Property 1: 工作流分片一致性
 * - Property 7: 哈希分片确定性
 * - Requirements 1.1, 3.2
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-27
 */
class WorkflowShardingSpec 
  extends ScalaTestWithActorTestKit(ConfigFactory.load("application-test"))
  with STPekkoSpec
  with BeforeAndAfterAll {
  
  implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
  
  // 创建执行引擎
  val executionEngine = new WorkflowExecutionEngine()(system, ec)
  
  override def beforeAll(): Unit = {
    super.beforeAll()
    // 加入集群
    val cluster = Cluster(system)
    cluster.manager ! Join(cluster.selfMember.address)
    
    // 等待集群就绪
    Thread.sleep(2000)
  }
  
  "WorkflowSharding" should {
    
    "initialize successfully and return ShardRegion" in {
      val shardRegion = WorkflowSharding.init(system, executionEngine, numberOfShards = 10)
      
      shardRegion should not be null
      log.info("WorkflowSharding initialized successfully")
    }
    
    "route messages to correct entity" in {
      val shardRegion = WorkflowSharding.init(system, executionEngine, numberOfShards = 10)
      val workflowId = "test-workflow-1"
      
      // 创建测试工作流
      val workflow = createTestWorkflow(workflowId)
      
      // 发送Execute命令
      val probe = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionResponse]()
      
      shardRegion ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Execute(probe.ref)
      )
      
      // 验证收到响应
      val response = probe.receiveMessage(5.seconds)
      response.status should (be("started") or be("running") or be("restarted"))
      
      log.info(s"Message routed successfully to entity: $workflowId")
    }
    
    "route multiple messages to same entity" in {
      val shardRegion = WorkflowSharding.init(system, executionEngine, numberOfShards = 10)
      val workflowId = "test-workflow-2"
      
      // 发送多个消息到同一个workflowId
      val probe1 = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      val probe2 = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      val probe3 = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      
      shardRegion ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(probe1.ref)
      )
      
      shardRegion ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(probe2.ref)
      )
      
      shardRegion ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(probe3.ref)
      )
      
      // 验证所有消息都被路由到同一个Entity
      val response1 = probe1.receiveMessage(5.seconds)
      val response2 = probe2.receiveMessage(5.seconds)
      val response3 = probe3.receiveMessage(5.seconds)
      
      response1.workflowId shouldBe workflowId
      response2.workflowId shouldBe workflowId
      response3.workflowId shouldBe workflowId
      
      log.info(s"Multiple messages routed to same entity: $workflowId")
    }
    
    "extract correct entityId from envelope" in {
      val workflowId = "test-workflow-3"
      val probe = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      val envelope = ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(probe.ref)
      )
      
      val extractedId = WorkflowSharding.extractEntityId(envelope)
      extractedId shouldBe workflowId
    }
    
    "extract deterministic shardId from entityId" in {
      val workflowId = "test-workflow-4"
      val numberOfShards = 10
      
      // 多次提取应该得到相同的shardId
      val shardId1 = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      val shardId2 = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      val shardId3 = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      
      shardId1 shouldBe shardId2
      shardId2 shouldBe shardId3
      
      // shardId应该在有效范围内
      val shardIdInt = shardId1.toInt
      shardIdInt should be >= 0
      shardIdInt should be < numberOfShards
      
      log.info(s"Deterministic shardId for $workflowId: $shardId1")
    }
    
    "distribute workflows across shards" in {
      val numberOfShards = 10
      val workflowIds = (1 to 100).map(i => s"workflow-$i")
      
      // 计算每个workflow的shardId
      val shardDistribution = workflowIds
        .map(id => WorkflowSharding.extractShardId(id, numberOfShards))
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap
      
      // 验证至少使用了多个shard
      shardDistribution.size should be > 1
      
      // 验证分布相对均匀（没有一个shard占比超过30%）
      val maxCount = shardDistribution.values.max
      val avgCount = 100.0 / numberOfShards
      maxCount.toDouble should be < (avgCount * 1.5)
      
      log.info(s"Workflows distributed across ${shardDistribution.size} shards")
      log.info(s"Distribution: $shardDistribution")
    }
    
    "handle different workflow IDs correctly" in {
      val shardRegion = WorkflowSharding.init(system, executionEngine, numberOfShards = 10)
      
      val workflowIds = List("wf-alpha", "wf-beta", "wf-gamma")
      
      workflowIds.foreach { workflowId =>
        val probe = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
        
        shardRegion ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.GetStatus(probe.ref)
        )
        
        val response = probe.receiveMessage(5.seconds)
        response.workflowId shouldBe workflowId
      }
      
      log.info(s"Successfully handled ${workflowIds.size} different workflow IDs")
    }
  }
  
  "WorkflowShardingExtractor" should {
    
    "extract entityId correctly" in {
      val extractor = new WorkflowShardingExtractor(10)
      val workflowId = "test-workflow-5"
      val probe = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      val envelope = ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetStatus(probe.ref))
      
      val entityId = extractor.entityIdGeneric(envelope)
      entityId shouldBe workflowId
    }
    
    "extract shardId deterministically" in {
      val extractor = new WorkflowShardingExtractor(10)
      val workflowId = "test-workflow-6"
      
      val shardId1 = extractor.shardId(workflowId)
      val shardId2 = extractor.shardId(workflowId)
      
      shardId1 shouldBe shardId2
    }
    
    "unwrap message correctly" in {
      val extractor = new WorkflowShardingExtractor(10)
      val probe = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      val command = EventSourcedWorkflowActor.GetStatus(probe.ref)
      val envelope = ShardingEnvelope("test-workflow-7", command)
      
      val unwrapped = extractor.unwrapMessageGeneric(envelope)
      unwrapped shouldBe command
    }
  }
  
  // 辅助方法：创建测试工作流
  private def createTestWorkflow(workflowId: String): WorkflowDSL.Workflow = {
    WorkflowDSL.Workflow(
      id = workflowId,
      name = s"Test Workflow $workflowId",
      description = "Test workflow for sharding",
      version = "1.0",
      author = "test",
      tags = List("test"),
      nodes = List(
        WorkflowDSL.Node(
          id = "source-1",
          `type` = "source",
          nodeType = "sequence",
          label = "Sequence Source",
          position = WorkflowDSL.Position(0, 0),
          config = spray.json.JsObject(
            "start" -> spray.json.JsNumber(1),
            "end" -> spray.json.JsNumber(10)
          )
        ),
        WorkflowDSL.Node(
          id = "sink-1",
          `type` = "sink",
          nodeType = "console.log",
          label = "Console Sink",
          position = WorkflowDSL.Position(200, 0),
          config = spray.json.JsObject()
        )
      ),
      edges = List(
        WorkflowDSL.Edge(
          id = "edge-1",
          source = "source-1",
          target = "sink-1"
        )
      ),
      metadata = WorkflowDSL.WorkflowMetadata(
        createdAt = java.time.Instant.now().toString,
        updatedAt = java.time.Instant.now().toString
      )
    )
  }
}
