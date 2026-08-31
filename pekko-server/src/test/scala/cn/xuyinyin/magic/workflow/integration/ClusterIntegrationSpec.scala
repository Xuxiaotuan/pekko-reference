package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * 集群集成测试
 * 
 * 测试3节点集群的以下功能：
 * - 集群启动和节点加入
 * - 分片实体路由和工作流初始化
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
  var shardRegion1: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]] = _
  var shardRegion2: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]] = _
  var shardRegion3: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]] = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    
    // 创建3个节点
    node1 = ActorTestKit(ClusterIntegrationSpec.systemName, ClusterIntegrationSpec.nodeConfig)
    node2 = ActorTestKit(ClusterIntegrationSpec.systemName, ClusterIntegrationSpec.nodeConfig)
    node3 = ActorTestKit(ClusterIntegrationSpec.systemName, ClusterIntegrationSpec.nodeConfig)
    
    // 让所有节点加入集群
    val cluster1 = Cluster(node1.system)
    val cluster2 = Cluster(node2.system)
    val cluster3 = Cluster(node3.system)
    
    cluster1.manager ! Join(cluster1.selfMember.address)
    cluster2.manager ! Join(cluster1.selfMember.address)
    cluster3.manager ! Join(cluster1.selfMember.address)
    
    // 等待集群形成
    eventually {
      Seq(cluster1, cluster2, cluster3).foreach { cluster =>
        cluster.state.members should have size 3
        cluster.state.members.iterator.map(_.status).toSet shouldBe Set(MemberStatus.Up)
      }
    }

    implicit val ec1 = node1.system.executionContext
    shardRegion1 = WorkflowSharding.init(node1.system, new WorkflowExecutionEngine()(node1.system, ec1))(ec1)
    implicit val ec2 = node2.system.executionContext
    shardRegion2 = WorkflowSharding.init(node2.system, new WorkflowExecutionEngine()(node2.system, ec2))(ec2)
    implicit val ec3 = node3.system.executionContext
    shardRegion3 = WorkflowSharding.init(node3.system, new WorkflowExecutionEngine()(node3.system, ec3))(ec3)
  }

  override def afterAll(): Unit = {
    val nodes = Seq(node1, node2, node3).filter(_ != null)
    nodes.foreach(_.system.terminate())
    try nodes.foreach(node => Await.result(node.system.whenTerminated, 20.seconds))
    finally super.afterAll()
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
        member.status shouldBe MemberStatus.Up
      }
    }

    "route and initialize workflow entities through cluster sharding" in {
      // 创建多个工作流
      val workflows = (1 to 10).map { i =>
        val workflowId = ClusterIntegrationSpec.workflowId(s"workflow-$i")
        val workflow = ClusterIntegrationSpec.workflow(workflowId, s"Test Workflow $i")

        // 发送Initialize命令
        val probe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
        shardRegion1 ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.Initialize(workflow, probe.ref)
        )

        // 等待响应
        probe.expectMessage(EventSourcedWorkflowActor.InitializeResponse(workflowId, "initialized"))
        
        workflowId
      }

      workflows.size shouldBe 10
    }

    "execute workflows successfully" in {
      // 创建一个工作流
      val workflowId = ClusterIntegrationSpec.workflowId("exec-workflow")
      val workflow = ClusterIntegrationSpec.workflow(workflowId, "Execution Test Workflow")

      // 初始化工作流
      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessage(EventSourcedWorkflowActor.InitializeResponse(workflowId, "initialized"))

      // 执行工作流
      val execProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Execute(execProbe.ref)
      )

      // 验证执行已被准确启动
      val response = execProbe.expectMessageType[EventSourcedWorkflowActor.ExecutionResponse](5.seconds)
      response.executionId should not be empty
      response.status shouldBe "started"

      // 通过公开状态协议有界轮询，验证异步 sequence -> map -> console 执行真正完成
      eventually {
        val status = Await.result(workflowStatus(shardRegion1, workflowId), 2.seconds)
        status.workflowId shouldBe workflowId
        status.state shouldBe "completed"
        status.currentExecution shouldBe None
        val completed = status.allExecutions.find(_.executionId == response.executionId)
          .getOrElse(fail(s"completed execution ${response.executionId} was absent from public status history: $status"))
        completed.status shouldBe "completed"
        completed.endTime should not be empty
      }
    }

    "query workflow status successfully" in {
      // 创建一个工作流
      val workflowId = ClusterIntegrationSpec.workflowId("status-workflow")
      val workflow = ClusterIntegrationSpec.workflow(workflowId, "Status Test Workflow")

      // 初始化工作流
      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessage(EventSourcedWorkflowActor.InitializeResponse(workflowId, "initialized"))

      // 查询状态
      val statusProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.GetStatus(statusProbe.ref)
      )

      // 验证状态响应
      val statusResponse = statusProbe.expectMessageType[EventSourcedWorkflowActor.StatusResponse](5.seconds)
      statusResponse.workflowId shouldBe workflowId
    }

    "handle workflow queries from different nodes" in {
      // 在node1上创建工作流
      val workflowId = ClusterIntegrationSpec.workflowId("multi-node-workflow")
      val workflow = ClusterIntegrationSpec.workflow(workflowId, "Multi-Node Test Workflow")

      val initProbe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
      shardRegion1 ! ShardingEnvelope(
        workflowId,
        EventSourcedWorkflowActor.Initialize(workflow, initProbe.ref)
      )
      initProbe.expectMessage(EventSourcedWorkflowActor.InitializeResponse(workflowId, "initialized"))

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

  private def workflowStatus(
    region: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]],
    workflowId: String
  ): scala.concurrent.Future[EventSourcedWorkflowActor.StatusResponse] = {
    val timeout: Timeout = Timeout(2.seconds)
    region.ask[EventSourcedWorkflowActor.StatusResponse](replyTo =>
      ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetStatus(replyTo))
    )(timeout, node1.system.scheduler)
  }
}

object ClusterIntegrationSpec {
  private val runId = java.util.UUID.randomUUID().toString
  val systemName: String = s"ClusterSystem-$runId"

  def workflowId(prefix: String): String = s"$prefix-$runId"

  def workflow(id: String, name: String): Workflow =
    WorkflowFixtures.linearWorkflow.copy(id = id, name = name)
  
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
          remember-entities = off
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

  val nodeConfig = ConfigFactory.parseString(
    """
    pekko {
      remote.artery.canonical.port = 0
      cluster.roles = ["worker"]
    }
    """).withFallback(config)
}
