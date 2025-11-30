import cn.xuyinyin.magic.datafusion._
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

object TestIntegrationSimple {
  
  implicit val ec: ExecutionContext = ExecutionContext.global
  
  def main(args: Array[String]): Unit = {
    println("🚀 开始DataFusion集成测试...")
    println()
    
    // 创建客户端
    val config = FlightClientConfig.default
    val client = DataFusionClient(config)
    
    try {
      // 测试1: 健康检查
      println("测试1: 健康检查")
      val healthFuture = client.healthCheck()
      val isHealthy = Await.result(healthFuture, 10.seconds)
      println(s"  结果: ${if (isHealthy) "✅ 通过" else "❌ 失败"}")
      println()
      
      // 测试2: 简单查询
      println("测试2: 简单SELECT查询")
      val sql1 = "SELECT * FROM users"
      val result1 = Await.result(client.executeQuery(sql1), 10.seconds)
      println(s"  成功: ${result1.success}")
      println(s"  行数: ${result1.data.size}")
      println(s"  耗时: ${result1.execution_time_ms}ms")
      println()
      
      // 测试3: WHERE查询
      println("测试3: WHERE条件查询")
      val sql2 = "SELECT * FROM users WHERE age > 30"
      val result2 = Await.result(client.executeQuery(sql2), 10.seconds)
      println(s"  成功: ${result2.success}")
      println(s"  行数: ${result2.data.size}")
      println()
      
      // 测试4: 聚合查询
      println("测试4: COUNT聚合")
      val sql3 = "SELECT COUNT(*) as total FROM users"
      val result3 = Await.result(client.executeQuery(sql3), 10.seconds)
      println(s"  成功: ${result3.success}")
      if (result3.success && result3.data.nonEmpty) {
        println(s"  总数: ${result3.data.head.get("total")}")
      }
      println()
      
      // 测试5: 错误处理
      println("测试5: 错误处理（无效SQL）")
      val sql4 = "SELECT * FROM invalid syntax"
      val result4 = Await.result(client.executeQuery(sql4), 10.seconds)
      println(s"  成功: ${result4.success}")
      println(s"  错误消息: ${result4.message.take(100)}")
      println()
      
      println("✅ 所有测试完成！")
      
    } catch {
      case e: Exception =>
        println(s"❌ 测试失败: ${e.getMessage}")
        e.printStackTrace()
    } finally {
      client.close()
      println("✅ 客户端已关闭")
    }
  }
}
