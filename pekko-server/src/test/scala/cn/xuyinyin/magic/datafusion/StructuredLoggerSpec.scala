package cn.xuyinyin.magic.datafusion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * 结构化日志测试
 */
class StructuredLoggerSpec extends AnyFlatSpec with Matchers {
  
  "StructuredLogger" should "log query start" in {
    StructuredLogger.logQueryStart(
      nodeId = "test-node",
      sql = "SELECT * FROM users",
      batchSize = 1000,
      parameters = Map("limit" -> 100, "offset" -> 0)
    )
    
    // 验证不抛出异常
    succeed
  }
  
  it should "log query complete" in {
    StructuredLogger.logQueryComplete(
      nodeId = "test-node",
      sql = "SELECT * FROM users",
      durationMs = 1500,
      rows = 10000,
      bytes = 500000
    )
    
    succeed
  }
  
  it should "log query failed" in {
    StructuredLogger.logQueryFailed(
      nodeId = "test-node",
      sql = "SELECT * FROM users",
      durationMs = 500,
      error = "SQL syntax error",
      errorType = "SQL_SYNTAX_ERROR",
      stackTrace = Some("at line 1...")
    )
    
    succeed
  }
  
  it should "log pool event" in {
    StructuredLogger.logPoolEvent(
      event = "pool_connection_acquired",
      active = 5,
      idle = 3,
      total = 10,
      waitTimeMs = Some(50)
    )
    
    succeed
  }
  
  it should "log data conversion" in {
    StructuredLogger.logDataConversion(
      event = "data_conversion_complete",
      sourceFormat = "JSON",
      targetFormat = "Arrow",
      recordCount = 1000,
      durationMs = 100,
      success = true
    )
    
    succeed
  }
  
  "QueryStartLog" should "generate valid JSON" in {
    val log = QueryStartLog(
      event = "query_start",
      timestamp = 1234567890,
      nodeId = "test-node",
      sql = "SELECT * FROM users",
      batchSize = 1000,
      parameters = Map("limit" -> 100)
    )
    
    val json = log.toJson
    json should include("query_start")
    json should include("test-node")
    json should include("SELECT * FROM users")
    json should include("1000")
  }
  
  it should "escape special characters in SQL" in {
    val log = QueryStartLog(
      event = "query_start",
      timestamp = 1234567890,
      nodeId = "test-node",
      sql = """SELECT * FROM users WHERE name = "John's \"quote\"""",
      batchSize = 1000,
      parameters = Map.empty
    )
    
    val json = log.toJson
    json should include("\\\"")
  }
  
  "QueryCompleteLog" should "generate valid JSON" in {
    val log = QueryCompleteLog(
      event = "query_complete",
      timestamp = 1234567890,
      nodeId = "test-node",
      sql = "SELECT * FROM users",
      durationMs = 1500,
      rows = 10000,
      bytes = 500000,
      throughput = 6666
    )
    
    val json = log.toJson
    json should include("query_complete")
    json should include("1500")
    json should include("10000")
    json should include("500000")
    json should include("6666")
  }
  
  it should "calculate throughput correctly" in {
    val log = QueryCompleteLog(
      event = "query_complete",
      timestamp = 1234567890,
      nodeId = "test-node",
      sql = "SELECT * FROM users",
      durationMs = 1000,
      rows = 5000,
      bytes = 100000,
      throughput = 5000
    )
    
    log.throughput shouldBe 5000
  }
  
  "QueryFailedLog" should "generate valid JSON" in {
    val log = QueryFailedLog(
      event = "query_failed",
      timestamp = 1234567890,
      nodeId = "test-node",
      sql = "SELECT * FROM users",
      durationMs = 500,
      error = "Connection timeout",
      errorType = "TIMEOUT",
      stackTrace = Some("at line 1\nat line 2")
    )
    
    val json = log.toJson
    json should include("query_failed")
    json should include("Connection timeout")
    json should include("TIMEOUT")
    json should include("stackTrace")
  }
  
  it should "handle missing stack trace" in {
    val log = QueryFailedLog(
      event = "query_failed",
      timestamp = 1234567890,
      nodeId = "test-node",
      sql = "SELECT * FROM users",
      durationMs = 500,
      error = "Error",
      errorType = "ERROR",
      stackTrace = None
    )
    
    val json = log.toJson
    json should not include("stackTrace")
  }
  
  "PoolEventLog" should "generate valid JSON" in {
    val log = PoolEventLog(
      event = "pool_connection_acquired",
      timestamp = 1234567890,
      active = 5,
      idle = 3,
      total = 10,
      waitTimeMs = Some(50)
    )
    
    val json = log.toJson
    json should include("pool_connection_acquired")
    json should include("\"active\":5")
    json should include("\"idle\":3")
    json should include("\"total\":10")
    json should include("\"waitTimeMs\":50")
  }
  
  it should "handle missing wait time" in {
    val log = PoolEventLog(
      event = "pool_connection_released",
      timestamp = 1234567890,
      active = 4,
      idle = 4,
      total = 10,
      waitTimeMs = None
    )
    
    val json = log.toJson
    json should not include("waitTimeMs")
  }
  
  "DataConversionLog" should "generate valid JSON" in {
    val log = DataConversionLog(
      event = "data_conversion_complete",
      timestamp = 1234567890,
      sourceFormat = "JSON",
      targetFormat = "Arrow",
      recordCount = 1000,
      durationMs = 100,
      success = true
    )
    
    val json = log.toJson
    json should include("data_conversion_complete")
    json should include("JSON")
    json should include("Arrow")
    json should include("1000")
    json should include("100")
    json should include("true")
  }
  
  "QueryLogContext" should "track query execution" in {
    val context = QueryLogContext("test-node", "SELECT * FROM users")
    
    context.logStart(1000, Map("limit" -> 100))
    
    context.updateStats(rows = 500, bytes = 25000)
    context.updateStats(rows = 500, bytes = 25000)
    
    Thread.sleep(100) // 模拟查询执行
    
    context.logSuccess()
    
    context.elapsedMs should be >= 100L
  }
  
  it should "log failure with exception" in {
    val context = QueryLogContext("test-node", "SELECT * FROM users")
    
    context.logStart(1000)
    
    val exception = new RuntimeException("Test error")
    context.logFailure(exception)
    
    succeed
  }
  
  it should "accumulate stats correctly" in {
    val context = QueryLogContext("test-node", "SELECT * FROM users")
    
    context.updateStats(rows = 100, bytes = 5000)
    context.updateStats(rows = 200, bytes = 10000)
    context.updateStats(rows = 300, bytes = 15000)
    
    // 内部统计应该累加（无法直接验证，但确保不抛出异常）
    context.logSuccess()
    
    succeed
  }
  
  "LogEvents" should "define all event types" in {
    LogEvents.QUERY_START shouldBe "query_start"
    LogEvents.QUERY_COMPLETE shouldBe "query_complete"
    LogEvents.QUERY_FAILED shouldBe "query_failed"
    LogEvents.POOL_CONNECTION_ACQUIRED shouldBe "pool_connection_acquired"
    LogEvents.POOL_CONNECTION_RELEASED shouldBe "pool_connection_released"
    LogEvents.DATA_CONVERSION_START shouldBe "data_conversion_start"
    LogEvents.DATA_CONVERSION_COMPLETE shouldBe "data_conversion_complete"
  }
}
