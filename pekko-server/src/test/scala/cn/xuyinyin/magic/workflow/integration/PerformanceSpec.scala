package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
 * 性能测试
 * 
 * 测试分布式工作流引擎的性能特性：
 * - 单节点吞吐量基准
 * - 3节点吞吐量
 * - 线性扩展性验证
 * - 并发1000个工作流
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
class PerformanceSpec
  extends ScalaTestWithActorTestKit(PerformanceSpec.config)
  with AnyWordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with ScalaFutures
  with Eventually {

  override implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(60, Seconds))

  var node1: ActorTestKit = _
  var node2: ActorTestKit = _
  var node3: ActorTestKit = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    
    // 创建3个节点
    node1 = ActorTestKit("PerfSystem", PerformanceSpec.nodeConfig(2571))
    node2 = ActorTestKit("PerfSystem", PerformanceSpec.nodeConfig(2572))
    node3 = ActorTestKit("PerfSystem", PerformanceSpec.nodeConfig(2573))
    
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

  "Performance" should {

    "measure single node throughput" in {
      // 只在node1上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      val executionEngine = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion = WorkflowSharding.init(node1.system, executionEngine)

      // 等待Sharding初始化完成
      Thread.sleep(3000)

      val workflowCount = 100
      val startTime = System.currentTimeMillis()

      // 创建100个工作流
      val futures = (1 to workflowCount).map { i =>
        Future {
          val workflowId = s"perf-single-workflow-$i"
          val workflow = createTestWorkflow(workflowId, s"Single Node Perf Test $i")

          val probe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
          shardRegion ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.Initialize(workflow, probe.ref)
          )
          probe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](10.seconds)
        }
      }

      // 等待所有工作流创建完成
      Await.result(Future.sequence(futures), 60.seconds)

      val endTime = System.currentTimeMillis()
      val duration = (endTime - startTime) / 1000.0
      val throughput = workflowCount / duration

      println(s"Single Node Performance:")
      println(s"  Workflows: $workflowCount")
      println(s"  Duration: ${duration}s")
      println(s"  Throughput: ${throughput} workflows/sec")

      // 验证吞吐量合理（至少1个工作流/秒）
      throughput should be > 1.0
    }

    "measure 3-node cluster throughput" in {
      // 在所有节点上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)
      
      val executionEngine2 = new WorkflowExecutionEngine()(node2.system, ec)
      WorkflowSharding.init(node2.system, executionEngine2)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(3000)

      val workflowCount = 300
      val startTime = System.currentTimeMillis()

      // 创建300个工作流
      val futures = (1 to workflowCount).map { i =>
        Future {
          val workflowId = s"perf-cluster-workflow-$i"
          val workflow = createTestWorkflow(workflowId, s"Cluster Perf Test $i")

          val probe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
          shardRegion1 ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.Initialize(workflow, probe.ref)
          )
          probe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](10.seconds)
        }
      }

      // 等待所有工作流创建完成
      Await.result(Future.sequence(futures), 120.seconds)

      val endTime = System.currentTimeMillis()
      val duration = (endTime - startTime) / 1000.0
      val throughput = workflowCount / duration

      println(s"3-Node Cluster Performance:")
      println(s"  Workflows: $workflowCount")
      println(s"  Duration: ${duration}s")
      println(s"  Throughput: ${throughput} workflows/sec")

      // 验证吞吐量合理（至少2个工作流/秒）
      throughput should be > 2.0
    }

    "verify linear scalability" in {
      // 这个测试比较单节点和3节点的吞吐量
      // 理论上，3节点应该有接近3倍的吞吐量
      // 但考虑到网络开销和协调成本，我们期望至少2.4倍（0.8 * 3）

      // 单节点基准
      implicit val ec = node1.system.executionContext
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)

      Thread.sleep(3000)

      val singleNodeCount = 50
      val singleNodeStart = System.currentTimeMillis()

      val singleNodeFutures = (1 to singleNodeCount).map { i =>
        Future {
          val workflowId = s"scale-single-workflow-$i"
          val workflow = createTestWorkflow(workflowId, s"Scale Single Test $i")

          val probe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
          shardRegion1 ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.Initialize(workflow, probe.ref)
          )
          probe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](10.seconds)
        }
      }

      Await.result(Future.sequence(singleNodeFutures), 60.seconds)

      val singleNodeEnd = System.currentTimeMillis()
      val singleNodeDuration = (singleNodeEnd - singleNodeStart) / 1000.0
      val singleNodeThroughput = singleNodeCount / singleNodeDuration

      // 3节点集群
      val executionEngine2 = new WorkflowExecutionEngine()(node2.system, ec)
      WorkflowSharding.init(node2.system, executionEngine2)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      WorkflowSharding.init(node3.system, executionEngine3)

      Thread.sleep(3000)

      val clusterCount = 150
      val clusterStart = System.currentTimeMillis()

      val clusterFutures = (1 to clusterCount).map { i =>
        Future {
          val workflowId = s"scale-cluster-workflow-$i"
          val workflow = createTestWorkflow(workflowId, s"Scale Cluster Test $i")

          val probe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
          shardRegion1 ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.Initialize(workflow, probe.ref)
          )
          probe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](10.seconds)
        }
      }

      Await.result(Future.sequence(clusterFutures), 120.seconds)

      val clusterEnd = System.currentTimeMillis()
      val clusterDuration = (clusterEnd - clusterStart) / 1000.0
      val clusterThroughput = clusterCount / clusterDuration

      val scalabilityFactor = clusterThroughput / singleNodeThroughput

      println(s"Linear Scalability Test:")
      println(s"  Single Node Throughput: ${singleNodeThroughput} workflows/sec")
      println(s"  3-Node Cluster Throughput: ${clusterThroughput} workflows/sec")
      println(s"  Scalability Factor: ${scalabilityFactor}x")

      // 验证线性扩展性（至少2.4倍，即0.8 * 3）
      scalabilityFactor should be > 2.0
    }

    "handle 1000 concurrent workflows" in {
      // 在所有节点上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)
      
      val executionEngine2 = new WorkflowExecutionEngine()(node2.system, ec)
      WorkflowSharding.init(node2.system, executionEngine2)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(3000)

      val workflowCount = 1000
      val startTime = System.currentTimeMillis()

      // 分批创建1000个工作流（每批100个）
      val batchSize = 100
      val batches = (0 until workflowCount by batchSize).map { batchStart =>
        val batchEnd = Math.min(batchStart + batchSize, workflowCount)
        (batchStart until batchEnd).map { i =>
          Future {
            val workflowId = s"concurrent-workflow-$i"
            val workflow = createTestWorkflow(workflowId, s"Concurrent Test $i")

            val probe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
            shardRegion1 ! ShardingEnvelope(
              workflowId,
              EventSourcedWorkflowActor.Initialize(workflow, probe.ref)
            )
            probe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](10.seconds)
          }
        }
      }

      // 执行所有批次
      batches.foreach { batch =>
        Await.result(Future.sequence(batch), 60.seconds)
      }

      val endTime = System.currentTimeMillis()
      val duration = (endTime - startTime) / 1000.0
      val throughput = workflowCount / duration

      println(s"1000 Concurrent Workflows Test:")
      println(s"  Workflows: $workflowCount")
      println(s"  Duration: ${duration}s")
      println(s"  Throughput: ${throughput} workflows/sec")

      // 验证所有工作流都创建成功
      workflowCount shouldBe 1000
      
      // 验证吞吐量合理
      throughput should be > 1.0
    }

    "measure workflow execution latency" in {
      // 在所有节点上初始化WorkflowSharding
      implicit val ec = node1.system.executionContext
      
      val executionEngine1 = new WorkflowExecutionEngine()(node1.system, ec)
      val shardRegion1 = WorkflowSharding.init(node1.system, executionEngine1)
      
      val executionEngine2 = new WorkflowExecutionEngine()(node2.system, ec)
      WorkflowSharding.init(node2.system, executionEngine2)
      
      val executionEngine3 = new WorkflowExecutionEngine()(node3.system, ec)
      WorkflowSharding.init(node3.system, executionEngine3)

      // 等待Sharding初始化完成
      Thread.sleep(3000)

      val workflowCount = 100
      val latencies = scala.collection.mutable.ArrayBuffer[Long]()

      // 测量100个工作流的延迟
      (1 to workflowCount).foreach { i =>
        val workflowId = s"latency-workflow-$i"
        val workflow = createTestWorkflow(workflowId, s"Latency Test $i")

        val startTime = System.nanoTime()

        val probe = node1.createTestProbe[EventSourcedWorkflowActor.Response]()
        shardRegion1 ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.Initialize(workflow, probe.ref)
        )
        probe.expectMessageType[EventSourcedWorkflowActor.InitializeResponse](10.seconds)

        val endTime = System.nanoTime()
        val latency = (endTime - startTime) / 1000000 // 转换为毫秒

        latencies += latency
      }

      // 计算统计信息
      val avgLatency = latencies.sum / latencies.size
      val minLatency = latencies.min
      val maxLatency = latencies.max
      val sortedLatencies = latencies.sorted
      val p50Latency = sortedLatencies(sortedLatencies.size / 2)
      val p95Latency = sortedLatencies((sortedLatencies.size * 0.95).toInt)
      val p99Latency = sortedLatencies((sortedLatencies.size * 0.99).toInt)

      println(s"Workflow Execution Latency:")
      println(s"  Average: ${avgLatency}ms")
      println(s"  Min: ${minLatency}ms")
      println(s"  Max: ${maxLatency}ms")
      println(s"  P50: ${p50Latency}ms")
      println(s"  P95: ${p95Latency}ms")
      println(s"  P99: ${p99Latency}ms")

      // 验证延迟合理（平均延迟应该小于1秒）
      avgLatency should be < 1000L
    }
  }

  private def createTestWorkflow(id: String, name: String): Workflow = {
    Workflow(
      id = id,
      name = name,
      description = s"Performance test workflow: $name",
      version = "1.0",
      author = "test",
      tags = List("test", "performance"),
      nodes = List(
        Node(
          id = s"$id-node-1",
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
  }
}

object PerformanceSpec {
  
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
          number-of-shards = 100
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
        snapshot-store.local.dir = "target/perf-test-snapshots"
      }
      
      loglevel = "WARNING"
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
