package cn.xuyinyin.magic.datafusion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * DataFusion Client测试
 */
class DataFusionClientSpec extends AnyFlatSpec with Matchers {
  
  "FlightClientConfig" should "have default values" in {
    val config = FlightClientConfig.default
    
    config.host shouldBe "localhost"
    config.port shouldBe 50051
    config.maxRetries shouldBe 3
  }
  
  "FlightClientConfig" should "allow custom values" in {
    val config = FlightClientConfig(
      host = "datafusion-service",
      port = 50052,
      maxRetries = 5
    )
    
    config.host shouldBe "datafusion-service"
    config.port shouldBe 50052
    config.maxRetries shouldBe 5
  }
  
  // 注意：以下测试需要DataFusion Service运行
  // 在实际环境中应该使用mock或testcontainers
  
  "DataFusionClient" should "be created with default config" in {
    val client = DataFusionClient()
    client should not be null
    client.close()
  }
  
  "DataFusionClient" should "be created with custom config" in {
    val config = FlightClientConfig(host = "localhost", port = 50051)
    val client = DataFusionClient(config)
    client should not be null
    client.close()
  }
}
