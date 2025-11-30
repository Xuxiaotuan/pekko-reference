package cn.xuyinyin.magic.datafusion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ConnectException

/**
 * DataFusion异常测试
 */
class DataFusionExceptionsSpec extends AnyFlatSpec with Matchers {
  
  "ServiceUnavailableException" should "be created with host and port" in {
    val ex = ServiceUnavailableException("localhost", 50051)
    
    ex.errorType shouldBe "SERVICE_UNAVAILABLE"
    ex.isRetryable shouldBe true
    ex.context should contain("host" -> "localhost")
    ex.context should contain("port" -> 50051)
    ex.getMessage should include("localhost:50051")
  }
  
  it should "be created with cause" in {
    val cause = new ConnectException("Connection refused")
    val ex = ServiceUnavailableException("localhost", 50051, cause)
    
    ex.getCause shouldBe cause
    ex.getMessage should include("Connection refused")
  }
  
  it should "generate JSON representation" in {
    val ex = ServiceUnavailableException("localhost", 50051)
    val json = ex.toJson
    
    json should include("SERVICE_UNAVAILABLE")
    json should include("localhost")
    json should include("50051")
    json should include("\"retryable\":true")
  }
  
  "SQLSyntaxException" should "be created with SQL" in {
    val sql = "SELECT * FORM users" // typo: FORM instead of FROM
    val ex = SQLSyntaxException(sql)
    
    ex.errorType shouldBe "SQL_SYNTAX_ERROR"
    ex.isRetryable shouldBe false
    ex.context should contain("sql" -> sql)
  }
  
  it should "be created with position" in {
    val sql = "SELECT * FROM users WHERE"
    val ex = SQLSyntaxException(sql, 25, "Unexpected end of query")
    
    ex.position shouldBe Some(25)
    ex.getMessage should include("position 25")
  }
  
  it should "truncate long SQL in context" in {
    val longSql = "SELECT * FROM users WHERE " + ("x" * 300)
    val ex = SQLSyntaxException(longSql)
    
    val sqlInContext = ex.context("sql").asInstanceOf[String]
    sqlInContext.length shouldBe 200
  }
  
  "DataFormatException" should "be created for JSON to Arrow conversion" in {
    val data = """{"invalid": json}"""
    val cause = new IllegalArgumentException("Invalid JSON")
    val ex = DataFormatException.jsonToArrow(data, cause)
    
    ex.errorType shouldBe "DATA_FORMAT_ERROR"
    ex.isRetryable shouldBe false
    ex.sourceFormat shouldBe "JSON"
    ex.targetFormat shouldBe "Arrow"
    ex.context should contain("sourceFormat" -> "JSON")
  }
  
  it should "be created for Arrow to JSON conversion" in {
    val cause = new IllegalStateException("Invalid Arrow data")
    val ex = DataFormatException.arrowToJson(cause)
    
    ex.sourceFormat shouldBe "Arrow"
    ex.targetFormat shouldBe "JSON"
  }
  
  it should "be created for schema inference" in {
    val data = """{"mixed": "types"}"""
    val cause = new IllegalArgumentException("Cannot infer schema")
    val ex = DataFormatException.schemaInference(data, cause)
    
    ex.targetFormat shouldBe "Arrow Schema"
  }
  
  "QueryTimeoutException" should "be created with timeout info" in {
    val sql = "SELECT * FROM large_table"
    val ex = QueryTimeoutException(sql, 30000, 35000)
    
    ex.errorType shouldBe "QUERY_TIMEOUT"
    ex.isRetryable shouldBe true
    ex.timeoutMs shouldBe 30000
    ex.elapsedMs shouldBe 35000
    ex.context should contain("timeoutMs" -> 30000)
    ex.context should contain("elapsedMs" -> 35000)
  }
  
  it should "include timeout in seconds in context" in {
    val sql = "SELECT * FROM large_table"
    val ex = QueryTimeoutException(sql, 30000, 35000)
    
    ex.context("timeoutSeconds") shouldBe 30.0
    ex.context("elapsedSeconds") shouldBe 35.0
  }
  
  "ConnectionPoolExhaustedException" should "be created with pool info" in {
    val ex = ConnectionPoolExhaustedException(10, 10, 5000)
    
    ex.errorType shouldBe "CONNECTION_POOL_EXHAUSTED"
    ex.isRetryable shouldBe true
    ex.maxConnections shouldBe 10
    ex.activeConnections shouldBe 10
    ex.waitTimeMs shouldBe 5000
    ex.getMessage should include("10/10")
  }
  
  "ConfigurationException" should "be created for missing config" in {
    val ex = ConfigurationException.missingConfig("datafusion.host")
    
    ex.errorType shouldBe "CONFIGURATION_ERROR"
    ex.isRetryable shouldBe false
    ex.configKey shouldBe "datafusion.host"
    ex.getMessage should include("Missing required configuration")
  }
  
  it should "be created for invalid config" in {
    val ex = ConfigurationException.invalidConfig("datafusion.port", "invalid", "Must be a number")
    
    ex.configKey shouldBe "datafusion.port"
    ex.configValue shouldBe Some("invalid")
    ex.getMessage should include("Must be a number")
  }
  
  "DataFusionExceptions" should "identify retryable exceptions" in {
    DataFusionExceptions.isRetryable(ServiceUnavailableException("localhost", 50051)) shouldBe true
    DataFusionExceptions.isRetryable(QueryTimeoutException("", 1000, 1100)) shouldBe true
    DataFusionExceptions.isRetryable(SQLSyntaxException("")) shouldBe false
    DataFusionExceptions.isRetryable(new ConnectException()) shouldBe true
  }
  
  it should "get error type from exceptions" in {
    DataFusionExceptions.getErrorType(ServiceUnavailableException("localhost", 50051)) shouldBe "SERVICE_UNAVAILABLE"
    DataFusionExceptions.getErrorType(SQLSyntaxException("")) shouldBe "SQL_SYNTAX_ERROR"
    DataFusionExceptions.getErrorType(new ConnectException()) shouldBe "CONNECTION_ERROR"
    DataFusionExceptions.getErrorType(new IllegalArgumentException()) shouldBe "INVALID_ARGUMENT"
  }
  
  it should "wrap exceptions as DataFusionException" in {
    val connectEx = new ConnectException("Connection refused")
    val wrapped = DataFusionExceptions.wrap(connectEx, "Test context")
    
    wrapped shouldBe a[DataFusionException]
    wrapped.isRetryable shouldBe true
  }
  
  it should "not double-wrap DataFusionException" in {
    val original = ServiceUnavailableException("localhost", 50051)
    val wrapped = DataFusionExceptions.wrap(original, "Test context")
    
    wrapped shouldBe original
  }
  
  "All DataFusionExceptions" should "have proper error types" in {
    val exceptions = List(
      ServiceUnavailableException("localhost", 50051),
      SQLSyntaxException("SELECT"),
      DataFormatException.jsonToArrow("{}", new Exception()),
      QueryTimeoutException("SELECT", 1000, 1100),
      ConnectionPoolExhaustedException(10, 10, 5000),
      ConfigurationException.missingConfig("key")
    )
    
    exceptions.foreach { ex =>
      ex.errorType should not be empty
      ex.errorType should not be "UNKNOWN_ERROR"
    }
  }
  
  it should "have non-empty messages" in {
    val exceptions = List(
      ServiceUnavailableException("localhost", 50051),
      SQLSyntaxException("SELECT"),
      DataFormatException.jsonToArrow("{}", new Exception("test")),
      QueryTimeoutException("SELECT", 1000, 1100),
      ConnectionPoolExhaustedException(10, 10, 5000),
      ConfigurationException.missingConfig("key")
    )
    
    exceptions.foreach { ex =>
      ex.getMessage should not be empty
    }
  }
  
  it should "have valid context" in {
    val exceptions = List(
      ServiceUnavailableException("localhost", 50051),
      SQLSyntaxException("SELECT"),
      DataFormatException.jsonToArrow("{}", new Exception()),
      QueryTimeoutException("SELECT", 1000, 1100),
      ConnectionPoolExhaustedException(10, 10, 5000),
      ConfigurationException.missingConfig("key")
    )
    
    exceptions.foreach { ex =>
      ex.context should not be null
      ex.context should not be empty
    }
  }
}
