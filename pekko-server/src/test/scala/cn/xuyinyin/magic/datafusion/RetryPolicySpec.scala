package cn.xuyinyin.magic.datafusion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.IOException
import java.net.ConnectException

/**
 * Retry Policy测试
 */
class RetryPolicySpec extends AnyFlatSpec with Matchers {
  
  "RetryPolicy" should "identify retryable exceptions" in {
    RetryPolicy.isRetryable(new IOException("Network error")) shouldBe true
    RetryPolicy.isRetryable(new ConnectException("Connection refused")) shouldBe true
    RetryPolicy.isRetryable(new java.util.concurrent.TimeoutException()) shouldBe true
  }
  
  "RetryPolicy" should "identify non-retryable exceptions" in {
    RetryPolicy.isRetryable(new IllegalArgumentException("Invalid argument")) shouldBe false
    RetryPolicy.isRetryable(new NullPointerException()) shouldBe false
  }
  
  "RetryPolicy" should "identify UNAVAILABLE errors as retryable" in {
    val error = new RuntimeException("UNAVAILABLE: service not available")
    RetryPolicy.isRetryable(error) shouldBe true
  }
  
  "RetryStats" should "calculate success rate" in {
    val stats = RetryStats(
      totalAttempts = 100,
      successfulAttempts = 95,
      failedAttempts = 5,
      retriedAttempts = 10
    )
    
    stats.successRate shouldBe 95.0
    stats.retryRate shouldBe 10.0
  }
  
  "RetryStats" should "handle zero attempts" in {
    val stats = RetryStats(0, 0, 0, 0)
    
    stats.successRate shouldBe 0.0
    stats.retryRate shouldBe 0.0
  }
  
  "RetryStats" should "format toString correctly" in {
    val stats = RetryStats(100, 95, 5, 10)
    
    val str = stats.toString
    str should include("total=100")
    str should include("success=95")
    str should include("failed=5")
    str should include("retried=10")
    str should include("95.0%")
  }
}
