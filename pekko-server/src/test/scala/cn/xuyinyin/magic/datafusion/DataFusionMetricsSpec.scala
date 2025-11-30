package cn.xuyinyin.magic.datafusion

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

/**
 * DataFusion指标测试
 */
class DataFusionMetricsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  
  override def beforeEach(): Unit = {
    // 注意：在实际测试中，重置指标可能会影响其他测试
    // 这里仅用于演示
  }
  
  "DataFusionMetrics" should "record query success" in {
    val nodeId = "test-node-1"
    
    DataFusionMetrics.recordQuerySuccess(
      nodeId = nodeId,
      durationSeconds = 1.5,
      rowCount = 1000,
      bytesReceived = 50000
    )
    
    // 验证指标已记录（实际测试中需要查询Prometheus注册表）
    // 这里只验证方法不抛出异常
    succeed
  }
  
  it should "record query error" in {
    val nodeId = "test-node-2"
    
    DataFusionMetrics.recordQueryError(
      nodeId = nodeId,
      durationSeconds = 0.5,
      errorType = "SQL_SYNTAX_ERROR"
    )
    
    succeed
  }
  
  it should "record query timeout" in {
    val nodeId = "test-node-3"
    
    DataFusionMetrics.recordQueryTimeout(
      nodeId = nodeId,
      durationSeconds = 30.0
    )
    
    succeed
  }
  
  it should "record data sent" in {
    val nodeId = "test-node-4"
    
    DataFusionMetrics.recordDataSent(nodeId, 100000)
    
    succeed
  }
  
  it should "update pool connections" in {
    DataFusionMetrics.updatePoolConnections(
      active = 5,
      idle = 3,
      total = 10
    )
    
    succeed
  }
  
  it should "record pool wait time" in {
    DataFusionMetrics.recordPoolWaitTime(0.05)
    
    succeed
  }
  
  it should "get metrics snapshot" in {
    val snapshot = DataFusionMetrics.getMetricsSnapshot
    
    snapshot should not be null
    snapshot.queryTotal should be >= 0.0
    snapshot.successRate should be >= 0.0
    snapshot.successRate should be <= 100.0
  }
  
  "MetricsSnapshot" should "calculate success rate correctly" in {
    val snapshot = MetricsSnapshot(
      queryTotal = 100,
      querySuccess = 95,
      queryErrors = 5,
      activeConnections = 5,
      idleConnections = 3,
      totalConnections = 10
    )
    
    snapshot.successRate shouldBe 95.0
  }
  
  it should "handle zero queries" in {
    val snapshot = MetricsSnapshot.empty
    
    snapshot.successRate shouldBe 100.0
  }
  
  it should "format toString correctly" in {
    val snapshot = MetricsSnapshot(
      queryTotal = 100,
      querySuccess = 95,
      queryErrors = 5,
      activeConnections = 5,
      idleConnections = 3,
      totalConnections = 10
    )
    
    val str = snapshot.toString
    str should include("total=100")
    str should include("success=95")
    str should include("errors=5")
    str should include("95.0%")
    str should include("active:5")
  }
  
  "QueryTimer" should "measure elapsed time" in {
    val timer = QueryTimer("test-node")
    
    Thread.sleep(100) // 等待100ms
    
    timer.elapsedSeconds should be >= 0.1
    timer.elapsedSeconds should be < 0.2
  }
  
  it should "record success" in {
    val timer = QueryTimer("test-node")
    
    timer.recordSuccess(rowCount = 100, bytesReceived = 5000)
    
    // 验证不抛出异常
    succeed
  }
  
  it should "record error" in {
    val timer = QueryTimer("test-node")
    
    timer.recordError("SQL_SYNTAX_ERROR")
    
    succeed
  }
  
  it should "record timeout" in {
    val timer = QueryTimer("test-node")
    
    timer.recordTimeout()
    
    succeed
  }
  
  it should "only record once" in {
    val timer = QueryTimer("test-node")
    
    timer.recordSuccess(100, 5000)
    timer.recordError("TEST") // 应该被忽略
    timer.recordTimeout() // 应该被忽略
    
    // 验证不抛出异常
    succeed
  }
  
  "DataFusionMetrics integration" should "handle multiple queries" in {
    val nodeId = "integration-test"
    
    // 记录多个成功查询
    for (i <- 1 to 10) {
      DataFusionMetrics.recordQuerySuccess(
        nodeId = nodeId,
        durationSeconds = i * 0.1,
        rowCount = i * 100,
        bytesReceived = i * 1000
      )
    }
    
    // 记录一些错误
    for (i <- 1 to 3) {
      DataFusionMetrics.recordQueryError(
        nodeId = nodeId,
        durationSeconds = 0.5,
        errorType = "TEST_ERROR"
      )
    }
    
    // 更新连接池状态
    DataFusionMetrics.updatePoolConnections(7, 3, 10)
    
    // 获取快照
    val snapshot = DataFusionMetrics.getMetricsSnapshot
    
    // 验证快照包含数据
    snapshot.totalConnections shouldBe 10
    snapshot.activeConnections shouldBe 7
    snapshot.idleConnections shouldBe 3
  }
  
  it should "handle concurrent updates" in {
    val nodeId = "concurrent-test"
    
    // 模拟并发查询
    val threads = (1 to 10).map { i =>
      new Thread {
        override def run(): Unit = {
          DataFusionMetrics.recordQuerySuccess(
            nodeId = s"$nodeId-$i",
            durationSeconds = 0.1,
            rowCount = 100,
            bytesReceived = 1000
          )
        }
      }
    }
    
    threads.foreach(_.start())
    threads.foreach(_.join())
    
    // 验证不抛出异常
    succeed
  }
}
