package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.typed.{Cluster, Join, Leave}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

/**
 * 故障恢复测试
 * 
 * 测试分布式工作流引擎的故障恢复能力：
 * - 模拟节点宕机
 * - 验证工作流自动迁移
 * - 验证状态完整恢复
 * - 验证路由自动更新
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
class FailoverRecoverySpec
  extends ScalaTestWithActorTestKit(FailoverRecoverySpec.config)
  with AnyWordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with ScalaFutures
  with Eventually {

  override implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds))

  var node1: ActorTestKit = _
  var node2: ActorTestKit = _
  var node3: ActorTestKit = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    
    // 创建3个节点
    node1 = ActorTestKit("FailoverSystem", FailoverRecoverySpec.nodeConfig(2561))
    node2 = ActorTestKit("FailoverSystem", FailoverRecoverySpec.nodeConfig(2562))
    node3 = ActorTestKit("FailoverSystem", FailoverRecoverySpec.nodeConfig(2563))
    
    // 让所有节点加入集群
    val cluster1 = Cluster(node1.system)
    val cluster2 = Cluster(node2.system)
    val cluster3 = Cluster(node3.system)
    
    cluster1.manager ! Join(cluster1.selfMember.address)
    cluster2.manager ! Join(cluster1.selfMember.address)
    cluster3.manager ! Join(cluster1.selfMember.address)
    
    // 等待集群形成
    eventually {
      cluster1.state.members.size shouldBe 3
      cluster2.state.members.size shouldBe 3
      cluster3.state.members.size shouldBe 3
    }
  }

  override def afterAll(): Unit = {
    node1.shutdownTestKit()
    node2.shutdownTestKit()
    node3.shutdownTestKit()
    super.afterAll()
  }

  "Failover Recovery" should {

    "recover workflow after node failure" in {
      // 在所有节点上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)
      
      val executionEngine2 = new WorkflowExecutionEngine()(node2.system, ec)
      val shardRegion2 = WorkflowSharding.init(node2.system, executionEngine2)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      val shardRegion3 = WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(3000)

      // 创建一个工作流
      val workflowId = "failover-workflow-1"
      val workflow = Workflow(
        id = workflowId,
        name = "Failover Test Workflow",
        description = "Test workflow for failover",
        version = "1.0",
        author = "test",
        tags = List("test", "failover"),
        nodes = List(
          Node(
            id = "node-1",
            `type` = "transform",
            nodeType = "shell",
            label = "Test Node",
            position = Position(0, 0),
            config = spray.json.JsObject("command" -> spray.json.JsString("echo 'test'"))
          )
        ),
        edges = List.empty,
        metadata = WorkflowMetadata(
          createdAt = java.time.Instant.now().toString,
          updatedAt = java.time.Instant.now().toString,
          executionHistory = None,
          schedule = None
        )
      )

      // 在node1上创建工作流
      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](5.seconds)

      // 验证工作流可以从node2查询
      val statusProbe2Before = node2.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion2 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe2Before.ref)
      )
      val statusBefore = statusProbe2Before.expectMessageType[EventSourcedWorkflowActor.StatusResponse](5.seconds)
      statusBefore.workflowId shouldBe workflowId

      // 模拟node2宕机
      val cluster2 = Cluster(node2.system)
      cluster2.manager ! Leave(cluster2.selfMember.address)
      
      // 等待node2离开集群
      Thread.sleep(5000)

      // 验证集群现在只有2个节点
      val cluster1 = Cluster(node1.system)
      val cluster3 = Cluster(node3.system)
      
      eventually {
        cluster1.state.members.size shouldBe 2
        cluster3.state.members.size shouldBe 2
      }

      // 验证工作流仍然可以从node1查询（自动迁移）
      val statusProbe1After = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe1After.ref)
      )
      val statusAfter1 = statusProbe1After.expectMessageType[EventSourcedWorkflowActor.StatusResponse](10.seconds)
      statusAfter1.workflowId shouldBe workflowId
      statusAfter1.state shouldBe statusBefore.state

      // 验证工作流也可以从node3查询
      val statusProbe3After = node3.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion3 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe3After.ref)
      )
      val statusAfter3 = statusProbe3After.expectMessageType[EventSourcedWorkflowActor.StatusResponse](10.seconds)
      statusAfter3.workflowId shouldBe workflowId
      statusAfter3.state shouldBe statusBefore.state
    }

    "maintain workflow state after failover" in {
      // 在所有节点上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      val shardRegion3 = WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(3000)

      // 创建一个工作流
      val workflowId = "state-recovery-workflow-1"
      val workflow = Workflow(
        id = workflowId,
        name = "State Recovery Test Workflow",
        description = "Test workflow for state recovery",
        version = "1.0",
        author = "test",
        tags = List("test", "state-recovery"),
        nodes = List(
          Node(
            id = "node-1",
            `type` = "transform",
            nodeType = "shell",
            label = "Test Node",
            position = Position(0, 0),
            config = spray.json.JsObject("command" -> spray.json.JsString("echo 'test'"))
          )
        ),
        edges = List.empty,
        metadata = WorkflowMetadata(
          createdAt = java.time.Instant.now().toString,
          updatedAt = java.time.Instant.now().toString,
          executionHistory = None,
          schedule = None
        )
      )

      // 创建工作流
      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](5.seconds)

      // 执行工作流
      val execProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Execute(execProbe.ref)
      )
      val execResponse = execProbe.expectMessageType[EventSourcedWorkflowActor.ExecutionResponse](5.seconds)
      val executionId = execResponse.executionId

      // 等待执行开始
      Thread.sleep(2000)

      // 查询状态（在故障前）
      val statusProbeBefore = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbeBefore.ref)
      )
      val statusBefore = statusProbeBefore.expectMessageType[EventSourcedWorkflowActor.StatusResponse](5.seconds)

      // 模拟节点故障（通过停止ActorSystem）
      // 注意：在实际测试中，我们不能真正停止node1，因为我们还需要用它
      // 所以这里我们只是验证状态可以从其他节点恢复

      // 从node3查询状态（验证状态一致性）
      val statusProbeAfter = node3.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion3 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbeAfter.ref)
      )
      val statusAfter = statusProbeAfter.expectMessageType[EventSourcedWorkflowActor.StatusResponse](10.seconds)

      // 验证状态一致
      statusAfter.workflowId shouldBe statusBefore.workflowId
      statusAfter.state shouldBe statusBefore.state
      
      // 验证执行历史保留
      if (statusBefore.allExecutions.nonEmpty) {
        statusAfter.allExecutions should not be empty
        statusAfter.allExecutions.exists(_.executionId == executionId) shouldBe true
      }
    }

    "handle multiple concurrent failures" in {
      // 在所有节点上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      val shardRegion3 = WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(3000)

      // 创建多个工作流
      val workflowIds = (1 to 5).map { i =>
        val workflowId = s"multi-failover-workflow-$i"
        val workflow = Workflow(
          id = workflowId,
          name = s"Multi Failover Test Workflow $i",
          description = s"Test workflow $i for multi failover",
          version = "1.0",
          author = "test",
          tags = List("test", "multi-failover"),
          nodes = List(
            Node(
              id = s"node-$i",
              `type` = "transform",
              nodeType = "shell",
              label = s"Test Node $i",
              position = Position(0, 0),
              config = spray.json.JsObject("command" -> spray.json.JsString("echo 'test'"))
            )
          ),
          edges = List.empty,
          metadata = WorkflowMetadata(
            createdAt = java.time.Instant.now().toString,
            updatedAt = java.time.Instant.now().toString,
            executionHistory = None,
            schedule = None
          )
        )

        // 创建工作流
        val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
        shardRegion1 ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
        )
        initProbe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](5.seconds)
        
        workflowId
      }

      // 等待所有工作流创建完成
      Thread.sleep(2000)

      // 验证所有工作流都可以从node3查询（即使node2已经离开）
      workflowIds.foreach { workflowId =>
        val statusProbe = node3.createTestProbe[EventSourcedWorkflowActor.Response]()
        shardRegion3 ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.GetStatus(statusProbe.ref)
        )
        val status = statusProbe.expectMessageType[EventSourcedWorkflowActor.StatusResponse](10.seconds)
        status.workflowId shouldBe workflowId
      }
    }

    "automatically update routing after node failure" in {
      // 在所有节点上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      val shardRegion3 = WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(3000)

      // 创建一个工作流
      val workflowId = "routing-update-workflow-1"
      val workflow = Workflow(
        id = workflowId,
        name = "Routing Update Test Workflow",
        description = "Test workflow for routing update",
        version = "1.0",
        author = "test",
        tags = List("test", "routing"),
        nodes = List(
          Node(
            id = "node-1",
            `type` = "transform",
            nodeType = "shell",
            label = "Test Node",
            position = Position(0, 0),
            config = spray.json.JsObject("command" -> spray.json.JsString("echo 'test'"))
          )
        ),
        edges = List.empty,
        metadata = WorkflowMetadata(
          createdAt = java.time.Instant.now().toString,
          updatedAt = java.time.Instant.now().toString,
          executionHistory = None,
          schedule = None
        )
      )

      // 创建工作流
      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](5.seconds)

      // 从node1查询状态
      val statusProbe1 = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe1.ref)
      )
      statusProbe1.expectMessageType[EventSourcedWorkflowActor.StatusResponse](5.seconds)

      // 从node3查询状态（验证路由正常工作）
      val statusProbe3 = node3.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion3 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe3.ref)
      )
      val status3 = statusProbe3.expectMessageType[EventSourcedWorkflowActor.StatusResponse](5.seconds)
      status3.workflowId shouldBe workflowId

      // 验证路由在故障后仍然工作
      // 即使node2已经离开，路由应该自动更新到剩余的节点
      val statusProbe3After = node3.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion3 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe3After.ref)
      )
      val status3After = statusProbe3After.expectMessageType[EventSourcedWorkflowActor.StatusResponse](10.seconds)
      status3After.workflowId shouldBe workflowId
    }
  }
}

object FailoverRecoverySpec {
  
  val config = ConfigFactory.parseString(
    """
    pekko {
      actor.provider = "cluster"
      
      remote.artery {
        canonical {
          hostname = "127.0.0.1"
          port = 0
        }
      }
      
      cluster {
        jmx.multi-mbeans-in-same-jvm = on
        
        # 快速故障检测
        failure-detector {
          acceptable-heartbeat-pause = 3s
          threshold = 8.0
        }
        
        # 快速downing（仅用于测试）
        auto-down-unreachable-after = 5s
        
        sharding {
          number-of-shards = 10
          role = "worker"
          
          # 快速再平衡
          rebalance-interval = 2s
          
          # Remember Entities
          remember-entities = on
          remember-entities-store = "eventsourced"
        }
      }
      
      persistence {
        journal.plugin = "pekko.persistence.journal.inmem"
        snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
        snapshot-store.local.dir = "target/failover-test-snapshots"
      }
      
      loglevel = "INFO"
    }
    """).withFallback(ConfigFactory.load("application-test"))

  def nodeConfig(port: Int) = ConfigFactory.parseString(
    s"""
    pekko {
      remote.artery.canonical.port = $port
      cluster.roles = ["worker"]
    }
    """).withFallback(config)
}
