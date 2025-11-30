package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding
import spray.json._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

/**
 * 集群集成测试
 * 
 * 测试3节点集群的以下功能：
 * - 集群启动和节点加入
 * - 工作流创建和分布
 * - 工作流执行
 * - 状态查询
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
class ClusterIntegrationSpec
  extends ScalaTestWithActorTestKit(ClusterIntegrationSpec.config)
  with AnyWordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with ScalaFutures
  with Eventually {

  override implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  var node1: ActorTestKit = _
  var node2: ActorTestKit = _
  var node3: ActorTestKit = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    
    // 创建3个节点
    node1 = ActorTestKit("ClusterSystem", ClusterIntegrationSpec.nodeConfig(2551))
    node2 = ActorTestKit("ClusterSystem", ClusterIntegrationSpec.nodeConfig(2552))
    node3 = ActorTestKit("ClusterSystem", ClusterIntegrationSpec.nodeConfig(2553))
    
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

  "Cluster Integration" should {

    "start 3-node cluster successfully" in {
      val cluster1 = Cluster(node1.system)
      val cluster2 = Cluster(node2.system)
      val cluster3 = Cluster(node3.system)

      // 验证所有节点都看到3个成员
      cluster1.state.members.size shouldBe 3
      cluster2.state.members.size shouldBe 3
      cluster3.state.members.size shouldBe 3

      // 验证所有节点都是Up状态
      cluster1.state.members.foreach { member =>
        member.status.toString shouldBe "Up"
      }
    }

    "distribute workflows across nodes" in {
      // 在node1上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)

      // 在node2上初始化WorkflowSharding
      val executionEngine2 = new WorkflowExecutionEngine()(node2.system, ec)
      val shardRegion2 = WorkflowSharding.init(node2.system, executionEngine2)

      // 在node3上初始化WorkflowSharding
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      val shardRegion3 = WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(2000)

      // 创建多个工作流
      val workflows = (1 to 10).map { i =>
        val workflowId = s"workflow-$i"
        val workflow = Workflow(
          id = workflowId,
          name = s"Test Workflow $i",
          description = s"Test workflow $i",
          version = "1.0",
          author = "test",
          tags = List("test", "integration"),
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

        // 发送Initialize命令
        val probe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
        shardRegion1 ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.Initialize(workflow, probe.ref)
        )

        // 等待响应
        probe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](5.seconds)
        
        workflowId
      }

      workflows.size shouldBe 10
    }

    "execute workflows successfully" in {
      // 在node1上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      val executionEngine = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion = WorkflowSharding.init(node1.system, executionEngine)

      // 等待Sharding初始化完成
      Thread.sleep(2000)

      // 创建一个工作流
      val workflowId = "exec-workflow-1"
      val workflow = Workflow(
        id = workflowId,
        name = "Execution Test Workflow",
        description = "Execution test workflow",
        version = "1.0",
        author = "test",
        tags = List("test", "execution"),
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

      // 初始化工作流
      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](5.seconds)

      // 执行工作流
      val execProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Execute(execProbe.ref)
      )

      // 等待执行完成
      val response = execProbe.expectMessageType[EventSourcedWorkflowActor.ExecutionResponse](5.seconds)
      response.executionId should not be empty
    }

    "query workflow status successfully" in {
      // 在node1上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      val executionEngine = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion = WorkflowSharding.init(node1.system, executionEngine)

      // 等待Sharding初始化完成
      Thread.sleep(2000)

      // 创建一个工作流
      val workflowId = "status-workflow-1"
      val workflow = Workflow(
        id = workflowId,
        name = "Status Test Workflow",
        description = "Status test workflow",
        version = "1.0",
        author = "test",
        tags = List("test", "status"),
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

      // 初始化工作流
      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](5.seconds)

      // 查询状态
      val statusProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe.ref)
      )

      // 验证状态响应
      val statusResponse = statusProbe.expectMessageType[EventSourcedWorkflowActor.StatusResponse](5.seconds)
      statusResponse.workflowId shouldBe workflowId
    }

    "handle workflow queries from different nodes" in {
      // 在所有节点上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)
      
      val executionEngine2 = new WorkflowExecutionEngine()(node2.system, ec)
      val shardRegion2 = WorkflowSharding.init(node2.system, executionEngine2)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      val shardRegion3 = WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(2000)

      // 在node1上创建工作流
      val workflowId = "multi-node-workflow-1"
      val workflow = Workflow(
        id = workflowId,
        name = "Multi-Node Test Workflow",
        description = "Multi-node test workflow",
        version = "1.0",
        author = "test",
        tags = List("test", "multi-node"),
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

      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](5.seconds)

      // 从node2查询状态
      val statusProbe2 = node2.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion2 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe2.ref)
      )
      val statusResponse2 = statusProbe2.expectMessageType[EventSourcedWorkflowActor.StatusResponse](5.seconds)
      statusResponse2.workflowId shouldBe workflowId

      // 从node3查询状态
      val statusProbe3 = node3.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion3 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe3.ref)
      )
      val statusResponse3 = statusProbe3.expectMessageType[EventSourcedWorkflowActor.StatusResponse](5.seconds)
      statusResponse3.workflowId shouldBe workflowId
    }
  }
}

object ClusterIntegrationSpec {
  
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
        
        sharding {
          number-of-shards = 10
          role = "worker"
        }
      }
      
      persistence {
        journal.plugin = "pekko.persistence.journal.inmem"
        snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
        snapshot-store.local.dir = "target/test-snapshots"
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
