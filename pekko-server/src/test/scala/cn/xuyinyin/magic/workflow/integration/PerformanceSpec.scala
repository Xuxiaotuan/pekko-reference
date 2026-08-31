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

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
 * 性能测试
 * 
 * 测试3节点分片集群的工作流初始化确认性能：
 * - 预热阶段初始化确认吞吐量
 * - 集群初始化确认吞吐量
 * - 不同初始化请求负载下的持续吞吐量
 * - 分批完成1000个初始化确认
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
  var shardRegion1: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]] = _
  var shardRegion2: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]] = _
  var shardRegion3: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]] = _

  private implicit def testExecutionContext: scala.concurrent.ExecutionContext = node1.system.executionContext

  override def beforeAll(): Unit = {
    super.beforeAll()
    
    // 创建3个节点
    node1 = ActorTestKit(PerformanceSpec.systemName, PerformanceSpec.nodeConfig)
    node2 = ActorTestKit(PerformanceSpec.systemName, PerformanceSpec.nodeConfig)
    node3 = ActorTestKit(PerformanceSpec.systemName, PerformanceSpec.nodeConfig)
    
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

    implicit val ec = node1.system.executionContext
    shardRegion1 = WorkflowSharding.init(node1.system, new WorkflowExecutionEngine()(node1.system, ec))(ec)
    initializeThreeNodeSharding()
  }

  override def afterAll(): Unit = {
    val nodes = Seq(node1, node2, node3).filter(_ != null)
    nodes.foreach(_.system.terminate())
    try nodes.foreach(node => Await.result(node.system.whenTerminated, 20.seconds))
    finally super.afterAll()
  }

  "Performance" should {

    "measure initial 3-node workflow initialization acknowledgement throughput" in {
      val initializationCount = 100
      val initializationStartTime = System.currentTimeMillis()

      // 发送100个工作流初始化请求
      val initializationFutures = (1 to initializationCount).map { i =>
        val workflowId = PerformanceSpec.workflowId(s"perf-single-workflow-$i")
        initialize(shardRegion1, workflowId, s"3-Node Warm-up Initialization Test $i")
      }

      // 等待所有初始化确认
      Await.result(Future.sequence(initializationFutures), 60.seconds)

      val initializationEndTime = System.currentTimeMillis()
      val initializationDuration = (initializationEndTime - initializationStartTime) / 1000.0
      val initializationThroughput = initializationCount / initializationDuration

      println(s"3-Node Warm-up Initialization ACK Performance:")
      println(s"  Initialization ACKs: $initializationCount")
      println(s"  Initialization ACK Duration: ${initializationDuration}s")
      println(s"  Initialization ACK Throughput: ${initializationThroughput} acknowledgements/sec")

      // 验证初始化确认吞吐量合理（至少1个/秒）
      initializationThroughput should be > 1.0
    }

    "measure 3-node workflow initialization acknowledgement throughput" in {
      val initializationCount = 300
      val initializationStartTime = System.currentTimeMillis()

      // 发送300个工作流初始化请求
      val initializationFutures = (1 to initializationCount).map { i =>
        val workflowId = PerformanceSpec.workflowId(s"perf-cluster-workflow-$i")
        initialize(shardRegion1, workflowId, s"Cluster Initialization Test $i")
      }

      // 等待所有初始化确认
      Await.result(Future.sequence(initializationFutures), 120.seconds)

      val initializationEndTime = System.currentTimeMillis()
      val initializationDuration = (initializationEndTime - initializationStartTime) / 1000.0
      val initializationThroughput = initializationCount / initializationDuration

      println(s"3-Node Initialization ACK Performance:")
      println(s"  Initialization ACKs: $initializationCount")
      println(s"  Initialization ACK Duration: ${initializationDuration}s")
      println(s"  Initialization ACK Throughput: ${initializationThroughput} acknowledgements/sec")

      // 验证初始化确认吞吐量合理（至少2个/秒）
      initializationThroughput should be > 2.0
    }

    "sustain workflow initialization acknowledgement throughput as 3-node request load scales" in {
      initializeThreeNodeSharding()

      val smallRequestCount = 50
      val smallRequestStart = System.currentTimeMillis()

      val smallInitializationFutures = (1 to smallRequestCount).map { i =>
        val workflowId = PerformanceSpec.workflowId(s"scale-small-workflow-$i")
        initialize(shardRegion1, workflowId, s"Scale Small Test $i")
      }

      Await.result(Future.sequence(smallInitializationFutures), 60.seconds)

      val smallRequestEnd = System.currentTimeMillis()
      val smallRequestDuration = (smallRequestEnd - smallRequestStart) / 1000.0
      val smallInitializationThroughput = smallRequestCount / smallRequestDuration

      val largeRequestCount = 150
      val largeRequestStart = System.currentTimeMillis()

      val largeInitializationFutures = (1 to largeRequestCount).map { i =>
        val workflowId = PerformanceSpec.workflowId(s"scale-large-workflow-$i")
        initialize(shardRegion1, workflowId, s"Scale Large Test $i")
      }

      Await.result(Future.sequence(largeInitializationFutures), 120.seconds)

      val largeRequestEnd = System.currentTimeMillis()
      val largeRequestDuration = (largeRequestEnd - largeRequestStart) / 1000.0
      val largeInitializationThroughput = largeRequestCount / largeRequestDuration

      println(s"3-Node Initialization ACK Load Test:")
      println(s"  50-request Initialization ACK Throughput: ${smallInitializationThroughput} acknowledgements/sec")
      println(s"  150-request Initialization ACK Throughput: ${largeInitializationThroughput} acknowledgements/sec")

      smallInitializationThroughput should be > 1.0
      largeInitializationThroughput should be > 1.0
    }

    "handle 1000 workflow initialization acknowledgements in batches of 100" in {
      initializeThreeNodeSharding()

      val initializationCount = 1000
      val initializationStartTime = System.currentTimeMillis()

      // 分批发送1000个初始化请求（每批100个）
      val initializationBatchSize = 100
      val submittedInitializationCount = new AtomicInteger(0)
      val acknowledgedInitializationCount = new AtomicInteger(0)
      val awaitedInitializationCount = new AtomicInteger(0)
      val inFlightInitializationCount = new AtomicInteger(0)
      val maxObservedInFlight = new AtomicInteger(0)

      def updateMaxObserved(candidate: Int): Unit = {
        var observed = maxObservedInFlight.get()
        while (candidate > observed && !maxObservedInFlight.compareAndSet(observed, candidate)) {
          observed = maxObservedInFlight.get()
        }
      }

      def trackedInitialize(workflowId: String, name: String): Future[EventSourcedWorkflowActor.InitializeResponse] = {
        val initializationFuture = initialize(shardRegion1, workflowId, name)
        val submitted = submittedInitializationCount.incrementAndGet()
        val currentInFlight = inFlightInitializationCount.incrementAndGet()
        updateMaxObserved(currentInFlight)
        (submitted - awaitedInitializationCount.get()) should be <= initializationBatchSize
        currentInFlight should be <= initializationBatchSize

        initializationFuture.andThen { case result =>
          if (result.isSuccess) acknowledgedInitializationCount.incrementAndGet()
          inFlightInitializationCount.decrementAndGet()
        }(node1.system.executionContext)
      }

      (0 until initializationCount by initializationBatchSize).foreach { batchStart =>
        val batchEnd = Math.min(batchStart + initializationBatchSize, initializationCount)
        val batchInitializationFutures = (batchStart until batchEnd).map { i =>
          val workflowId = PerformanceSpec.workflowId(s"batched-workflow-$i")
          trackedInitialize(workflowId, s"Batched Initialization Test $i")
        }

        val batchAcknowledgements = Await.result(Future.sequence(batchInitializationFutures), 60.seconds)
        awaitedInitializationCount.addAndGet(batchAcknowledgements.size)
      }

      val initializationEndTime = System.currentTimeMillis()
      val initializationDuration = (initializationEndTime - initializationStartTime) / 1000.0
      val initializationThroughput = initializationCount / initializationDuration

      println(s"1000 Batched Workflow Initialization ACK Test:")
      println(s"  Initialization ACKs: $initializationCount")
      println(s"  Initialization ACK Duration: ${initializationDuration}s")
      println(s"  Initialization ACK Throughput: ${initializationThroughput} acknowledgements/sec")
      println(s"  Max Observed In-Flight Initialization ACKs: ${maxObservedInFlight.get()}")

      // 验证真实 Future 生命周期未超过批次上限，且所有请求均已完成
      submittedInitializationCount.get() shouldBe initializationCount
      acknowledgedInitializationCount.get() shouldBe initializationCount
      awaitedInitializationCount.get() shouldBe initializationCount
      inFlightInitializationCount.get() shouldBe 0
      maxObservedInFlight.get() should be > 0
      maxObservedInFlight.get() should be <= initializationBatchSize
      
      // 验证初始化确认吞吐量合理
      initializationThroughput should be > 1.0
    }

    "measure workflow initialization acknowledgement latency" in {
      initializeThreeNodeSharding()

      val initializationCount = 100
      val initializationLatencies = scala.collection.mutable.ArrayBuffer[Long]()

      // 测量100个工作流初始化确认的延迟
      (1 to initializationCount).foreach { i =>
        val workflowId = PerformanceSpec.workflowId(s"latency-workflow-$i")

        val initializationStartTime = System.nanoTime()
        Await.result(initialize(shardRegion1, workflowId, s"Initialization Latency Test $i"), 10.seconds)

        val initializationEndTime = System.nanoTime()
        val initializationLatency = (initializationEndTime - initializationStartTime) / 1000000 // 转换为毫秒

        initializationLatencies += initializationLatency
      }

      // 计算统计信息
      val averageInitializationLatency = initializationLatencies.sum / initializationLatencies.size
      val minimumInitializationLatency = initializationLatencies.min
      val maximumInitializationLatency = initializationLatencies.max
      val sortedInitializationLatencies = initializationLatencies.sorted
      val p50InitializationLatency = sortedInitializationLatencies(sortedInitializationLatencies.size / 2)
      val p95InitializationLatency = sortedInitializationLatencies((sortedInitializationLatencies.size * 0.95).toInt)
      val p99InitializationLatency = sortedInitializationLatencies((sortedInitializationLatencies.size * 0.99).toInt)

      println(s"Workflow Initialization ACK Latency:")
      println(s"  Average: ${averageInitializationLatency}ms")
      println(s"  Min: ${minimumInitializationLatency}ms")
      println(s"  Max: ${maximumInitializationLatency}ms")
      println(s"  P50: ${p50InitializationLatency}ms")
      println(s"  P95: ${p95InitializationLatency}ms")
      println(s"  P99: ${p99InitializationLatency}ms")

      // 验证初始化确认延迟合理（平均延迟应该小于1秒）
      averageInitializationLatency should be < 1000L
    }
  }

  private def initialize(
    region: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]],
    workflowId: String,
    name: String
  ): Future[EventSourcedWorkflowActor.InitializeResponse] = {
    val timeout: Timeout = Timeout(10.seconds)
    region
      .ask[EventSourcedWorkflowActor.InitializeResponse](replyTo =>
        ShardingEnvelope(workflowId, EventSourcedWorkflowActor.Initialize(PerformanceSpec.workflow(workflowId, name), replyTo))
      )(timeout, node1.system.scheduler)
      .map { response =>
        require(response == EventSourcedWorkflowActor.InitializeResponse(workflowId, "initialized"), s"unexpected initialize response: $response")
        response
      }(node1.system.executionContext)
  }

  private def initializeThreeNodeSharding(): Unit = synchronized {
    if (shardRegion2 == null) {
      implicit val ec2 = node2.system.executionContext
      shardRegion2 = WorkflowSharding.init(node2.system, new WorkflowExecutionEngine()(node2.system, ec2))(ec2)
    }
    if (shardRegion3 == null) {
      implicit val ec3 = node3.system.executionContext
      shardRegion3 = WorkflowSharding.init(node3.system, new WorkflowExecutionEngine()(node3.system, ec3))(ec3)
    }
  }
}

object PerformanceSpec {
  private val runId = java.util.UUID.randomUUID().toString
  val systemName: String = s"PerfSystem-$runId"

  def workflowId(prefix: String): String = s"$prefix-$runId"

  def workflow(id: String, name: String): Workflow =
    WorkflowFixtures.linearWorkflow.copy(id = id, name = name, description = s"Initialization benchmark workflow: $name")
  
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
          
          remember-entities = off
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

  val nodeConfig = ConfigFactory.parseString(
    """
    pekko {
      remote.artery.canonical.port = 0
      cluster.roles = ["worker"]
    }
    """).withFallback(config)
}
