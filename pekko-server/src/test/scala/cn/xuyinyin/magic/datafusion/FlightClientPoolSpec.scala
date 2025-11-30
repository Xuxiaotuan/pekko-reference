package cn.xuyinyin.magic.datafusion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Flight Client Pool测试
 */
class FlightClientPoolSpec extends AnyFlatSpec with Matchers {
  
  "FlightClientPoolConfig" should "have default values" in {
    val config = FlightClientPoolConfig()
    
    config.maxTotal shouldBe 20
    config.maxIdle shouldBe 10
    config.minIdle shouldBe 2
    config.maxWaitMillis shouldBe 5000
  }
  
  "FlightClientPoolConfig" should "allow custom values" in {
    val config = FlightClientPoolConfig(
      maxTotal = 50,
      maxIdle = 25,
      minIdle = 5
    )
    
    config.maxTotal shouldBe 50
    config.maxIdle shouldBe 25
    config.minIdle shouldBe 5
  }
  
  "FlightClientPool" should "be created with default config" in {
    val clientConfig = FlightClientConfig.default
    val pool = FlightClientPool(clientConfig)
    
    pool should not be null
    
    val stats = pool.getStats
    stats.maxTotal shouldBe 20
    
    pool.close()
  }
  
  "FlightClientPool" should "provide pool statistics" in {
    val clientConfig = FlightClientConfig.default
    val poolConfig = FlightClientPoolConfig(maxTotal = 10, maxIdle = 5, minIdle = 1)
    val pool = FlightClientPool(clientConfig, poolConfig)
    
    val stats = pool.getStats
    stats.maxTotal shouldBe 10
    stats.numActive shouldBe 0
    
    pool.close()
  }
  
  "PoolStats" should "calculate utilization percentage" in {
    val stats = PoolStats(numActive = 5, numIdle = 3, numWaiters = 0, maxTotal = 10)
    
    stats.utilizationPercent shouldBe 50.0
  }
  
  "PoolStats" should "format toString correctly" in {
    val stats = PoolStats(numActive = 5, numIdle = 3, numWaiters = 2, maxTotal = 10)
    
    val str = stats.toString
    str should include("active=5")
    str should include("idle=3")
    str should include("waiters=2")
    str should include("max=10")
    str should include("50.0%")
  }
}
