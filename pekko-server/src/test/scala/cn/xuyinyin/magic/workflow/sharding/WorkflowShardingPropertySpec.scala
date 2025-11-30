package cn.xuyinyin.magic.workflow.sharding

import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/**
 * WorkflowSharding属性测试
 * 
 * 使用Property-Based Testing验证：
 * - Property 1: 工作流分片一致性
 * - Property 7: 哈希分片确定性
 * 
 * 验证Requirements 1.1, 3.2
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-27
 */
class WorkflowShardingPropertySpec 
  extends AnyPropSpec
  with ScalaCheckPropertyChecks
  with Matchers {
  
  // 配置属性测试参数
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 100, maxDiscardedFactor = 5.0)
  
  // workflowId生成器
  val workflowIdGen: Gen[String] = for {
    prefix <- Gen.oneOf("workflow", "wf", "job", "task", "pipeline")
    id <- Gen.choose(1, 10000)
    suffix <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
  } yield suffix match {
    case Some(s) => s"$prefix-$id-$s"
    case None => s"$prefix-$id"
  }
  
  // 分片数量生成器
  val numberOfShardsGen: Gen[Int] = Gen.choose(10, 200)
  
  /**
   * Property 1: 工作流分片一致性
   * 
   * Feature: distributed-workflow-architecture, Property 1: 工作流分片一致性
   * 
   * For any workflowId，多次查询应该总是路由到相同的Shard和节点（在没有再平衡的情况下）
   * 
   * Validates: Requirements 1.1, 3.2
   */
  property("Property 1: Workflow sharding consistency - same workflowId always routes to same shard") {
    forAll(workflowIdGen, numberOfShardsGen) { (workflowId: String, numberOfShards: Int) =>
      // 多次提取shardId应该得到相同的结果
      val shardId1 = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      val shardId2 = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      val shardId3 = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      val shardId4 = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      val shardId5 = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      
      // 验证一致性
      shardId1 shouldBe shardId2
      shardId2 shouldBe shardId3
      shardId3 shouldBe shardId4
      shardId4 shouldBe shardId5
      
      // 验证shardId在有效范围内
      val shardIdInt = shardId1.toInt
      shardIdInt should be >= 0
      shardIdInt should be < numberOfShards
    }
  }
  
  /**
   * Property 7: 哈希分片确定性
   * 
   * Feature: distributed-workflow-architecture, Property 7: 哈希分片确定性
   * 
   * For any workflowId，使用哈希分片策略时，相同的ID应该总是映射到相同的Shard
   * 
   * Validates: Requirements 3.2
   */
  property("Property 7: Hash sharding determinism - same input always produces same hash") {
    forAll(workflowIdGen) { (workflowId: String) =>
      val numberOfShards = 100
      
      // 计算哈希值
      val hash1 = workflowId.hashCode
      val hash2 = workflowId.hashCode
      
      // 验证哈希确定性
      hash1 shouldBe hash2
      
      // 验证分片ID确定性
      val shardId1 = (math.abs(hash1) % numberOfShards).toString
      val shardId2 = (math.abs(hash2) % numberOfShards).toString
      
      shardId1 shouldBe shardId2
      
      // 验证与WorkflowSharding.extractShardId一致
      val officialShardId = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      officialShardId shouldBe shardId1
    }
  }
  
  /**
   * 额外属性：负载均衡性
   * 
   * For any 大量工作流实例，它们在各个Shard上的分布应该相对均匀
   * 
   * Validates: Requirements 1.2, 5.1
   */
  property("Load balancing - workflows should be distributed relatively evenly across shards") {
    val numberOfShards = 100
    val numberOfWorkflows = 1000
    
    // 生成大量workflowId
    val workflowIds = Gen.listOfN(numberOfWorkflows, workflowIdGen).sample.get
    
    // 计算分片分布
    val shardDistribution = workflowIds
      .map(id => WorkflowSharding.extractShardId(id, numberOfShards))
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap
    
    // 计算统计信息
    val counts = shardDistribution.values.toList
    val mean = counts.sum.toDouble / counts.size
    val variance = counts.map(c => math.pow(c - mean, 2)).sum / counts.size
    val stdDev = math.sqrt(variance)
    
    // 验证标准差 < 平均值的20%（相对均匀）
    stdDev should be < (mean * 0.2)
    
    // 验证至少使用了大部分shard（至少80%）
    shardDistribution.size should be >= (numberOfShards * 0.8).toInt
  }
  
  /**
   * 额外属性：分片范围有效性
   * 
   * For any workflowId和分片数量，生成的shardId应该始终在有效范围内
   */
  property("Shard ID validity - generated shard IDs should always be within valid range") {
    forAll(workflowIdGen, numberOfShardsGen) { (workflowId: String, numberOfShards: Int) =>
      val shardId = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      val shardIdInt = shardId.toInt
      
      // 验证范围
      shardIdInt should be >= 0
      shardIdInt should be < numberOfShards
    }
  }
  
  /**
   * 额外属性：不同ID产生不同分片（大概率）
   * 
   * For any 两个不同的workflowId，它们应该有较高概率映射到不同的Shard
   */
  property("Shard diversity - different workflow IDs should likely map to different shards") {
    val numberOfShards = 100
    val numberOfPairs = 100
    
    // 生成成对的不同workflowId
    val workflowIdPairs = Gen.listOfN(numberOfPairs, 
      for {
        id1 <- workflowIdGen
        id2 <- workflowIdGen.suchThat(_ != id1)
      } yield (id1, id2)
    ).sample.get
    
    // 计算映射到不同shard的比例
    val differentShardCount = workflowIdPairs.count { case (id1, id2) =>
      val shard1 = WorkflowSharding.extractShardId(id1, numberOfShards)
      val shard2 = WorkflowSharding.extractShardId(id2, numberOfShards)
      shard1 != shard2
    }
    
    val differentShardRatio = differentShardCount.toDouble / numberOfPairs
    
    // 至少90%的不同ID应该映射到不同的shard
    differentShardRatio should be >= 0.9
  }
  
  /**
   * 额外属性：WorkflowShardingExtractor一致性
   * 
   * For any workflowId，WorkflowShardingExtractor应该与WorkflowSharding产生相同的shardId
   */
  property("Extractor consistency - WorkflowShardingExtractor should match WorkflowSharding") {
    forAll(workflowIdGen) { (workflowId: String) =>
      val numberOfShards = 100
      
      val extractor = new WorkflowShardingExtractor(numberOfShards)
      val extractorShardId = extractor.shardId(workflowId)
      val directShardId = WorkflowSharding.extractShardId(workflowId, numberOfShards)
      
      extractorShardId shouldBe directShardId
    }
  }
}
