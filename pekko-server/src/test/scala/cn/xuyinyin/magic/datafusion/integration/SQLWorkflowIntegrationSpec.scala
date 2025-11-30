package cn.xuyinyin.magic.datafusion.integration

import cn.xuyinyin.magic.datafusion._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.transforms.SQLQueryNode
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * SQL工作流集成测试
 * 
 * 测试SQL节点在完整工作流中的集成
 * 测试Source -> SQL Transform -> Sink的完整数据流
 * 
 * 前置条件：DataFusion服务必须在localhost:50051运行
 */
class SQLWorkflowIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  
  val testKit = ActorTestKit()
  implicit val system: org.apache.pekko.actor.typed.ActorSystem[Nothing] = testKit.system
  implicit val ec: ExecutionContext = system.executionContext
  
  var pool: FlightClientPool = _
  
  override def beforeAll(): Unit = {
    val clientConfig = FlightClientConfig.default
    val poolConfig = FlightClientPoolConfig(maxTotal = 5)
    pool = FlightClientPool(clientConfig, poolConfig)
    println("✅ FlightClientPool已初始化")
  }
  
  override def afterAll(): Unit = {
    pool.close()
    testKit.shutdownTestKit()
    println("✅ 测试资源已清理")
  }
  
  "SQL Workflow" should "execute simple query in stream" in {
    val config = SQLNodeConfig(
      sql = "SELECT * FROM users WHERE age > 30",
      batchSize = 10
    )
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-1",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Filter Users",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, _ => ())
    
    // 创建输入流（虽然SQL查询不使用输入，但需要触发流）
    val input = Source.single("""{"trigger": "start"}""")
    
    val resultFuture = input
      .via(transform)
      .runWith(Sink.seq)
    
    val output = Await.result(resultFuture, 10.seconds)
    
    output should not be empty
    
    // 解析第一个结果
    val firstResult = output.head.parseJson.asJsObject
    firstResult.fields should contain key "success"
    
    val success = firstResult.fields("success").asInstanceOf[JsBoolean].value
    success shouldBe true
    
    println(s"✅ 工作流执行成功，返回 ${output.size} 个结果")
  }
  
  it should "handle parameterized queries" in {
    val config = SQLNodeConfig(
      sql = "SELECT * FROM users WHERE age > {{min_age}}",
      parameters = Map("min_age" -> 28),
      batchSize = 10
    )
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-2",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Parameterized Query",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, _ => ())
    
    val input = Source.single("""{"trigger": "start"}""")
    
    val resultFuture = input
      .via(transform)
      .runWith(Sink.seq)
    
    val output = Await.result(resultFuture, 10.seconds)
    
    output should not be empty
    
    // 验证参数化查询成功执行
    val firstResult = output.head.parseJson.asJsObject
    val success = firstResult.fields("success").asInstanceOf[JsBoolean].value
    success shouldBe true
    
    println(s"✅ 参数化查询执行成功")
  }
  
  it should "chain multiple SQL nodes" in {
    // 第一个SQL节点：查询所有用户
    val config1 = SQLNodeConfig(
      sql = "SELECT name, age FROM users",
      batchSize = 10
    )
    
    val node1 = SQLQueryNode(pool, config1)
    
    val workflowNode1 = WorkflowDSL.Node(
      id = "sql-1",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Get All Users",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform1 = node1.createTransform(workflowNode1, _ => ())
    
    // 第二个SQL节点：统计用户数
    val config2 = SQLNodeConfig(
      sql = "SELECT COUNT(*) as total FROM users",
      batchSize = 10
    )
    
    val node2 = SQLQueryNode(pool, config2)
    
    val workflowNode2 = WorkflowDSL.Node(
      id = "sql-2",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Count Users",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform2 = node2.createTransform(workflowNode2, _ => ())
    
    // 串联两个SQL节点
    val input = Source.single("""{"trigger": "start"}""")
    
    val resultFuture = input
      .via(transform1)
      .via(transform2)
      .runWith(Sink.seq)
    
    val output = Await.result(resultFuture, 15.seconds)
    
    output should not be empty
    
    println(s"✅ 多SQL节点串联成功，返回 ${output.size} 个结果")
  }
  
  it should "process batches correctly" in {
    val config = SQLNodeConfig(
      sql = "SELECT * FROM users",
      batchSize = 2  // 小批次测试
    )
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-batch",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Batch Query",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, _ => ())
    
    // 创建多个输入触发批处理
    val input = Source(List(
      """{"batch": 1}""",
      """{"batch": 2}"""
    ))
    
    val resultFuture = input
      .via(transform)
      .runWith(Sink.seq)
    
    val output = Await.result(resultFuture, 10.seconds)
    
    output should not be empty
    
    println(s"✅ 批处理执行成功，处理了 ${output.size} 批")
  }
  
  it should "handle errors in workflow" in {
    val config = SQLNodeConfig(
      sql = "SELECT * FROM non_existent_table",  // 故意使用不存在的表
      batchSize = 10
    )
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-error",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Error Query",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, _ => ())
    
    val input = Source.single("""{"trigger": "start"}""")
    
    val resultFuture = input
      .via(transform)
      .runWith(Sink.seq)
    
    val output = Await.result(resultFuture, 10.seconds)
    
    output should not be empty
    
    // 验证错误被正确处理
    val firstResult = output.head.parseJson.asJsObject
    val success = firstResult.fields("success").asInstanceOf[JsBoolean].value
    success shouldBe false
    
    println(s"✅ 错误处理正确: ${firstResult.fields("message")}")
  }
  
  it should "integrate with Source and Sink" in {
    val config = SQLNodeConfig(
      sql = "SELECT name, age FROM users ORDER BY age DESC LIMIT 3",
      batchSize = 10
    )
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-integration",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Top 3 Users",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, _ => ())
    
    // 完整的Source -> Transform -> Sink流程
    val source = Source(List(
      """{"request": "top_users"}"""
    ))
    
    val sink = Sink.fold[Seq[String], String](Seq.empty)(_ :+ _)
    
    val resultFuture = source
      .via(transform)
      .runWith(sink)
    
    val output = Await.result(resultFuture, 10.seconds)
    
    output should not be empty
    
    // 解析结果
    val result = output.head.parseJson.asJsObject
    val success = result.fields("success").asInstanceOf[JsBoolean].value
    success shouldBe true
    
    val data = result.fields("data").asInstanceOf[JsArray].elements
    data should have size 3
    
    println(s"✅ Source->Transform->Sink集成成功，返回 ${data.size} 条记录")
  }
  
  it should "process large datasets efficiently" in {
    val config = SQLNodeConfig(
      sql = "SELECT * FROM users",
      batchSize = 100
    )
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-performance",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Performance Test",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, _ => ())
    
    val startTime = System.currentTimeMillis()
    
    // 创建多个并发请求
    val input = Source((1 to 10).map(i => s"""{"request": $i}"""))
    
    val resultFuture = input
      .via(transform)
      .runWith(Sink.seq)
    
    val output = Await.result(resultFuture, 30.seconds)
    
    val duration = System.currentTimeMillis() - startTime
    
    output should not be empty
    output should have size 10
    
    // 验证性能：10个请求应该在合理时间内完成
    duration should be < 5000L  // 5秒内完成
    
    println(s"✅ 性能测试通过: 处理10个请求耗时 ${duration}ms")
  }
  
  it should "handle concurrent queries correctly" in {
    val config = SQLNodeConfig(
      sql = "SELECT COUNT(*) as total FROM users",
      batchSize = 10
    )
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-concurrent",
      `type` = "transform",
      nodeType = "sql.query",
      label = "Concurrent Query",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, _ => ())
    
    // 创建多个并发流
    val futures = (1 to 5).map { i =>
      val input = Source.single(s"""{"query": $i}""")
      input.via(transform).runWith(Sink.seq)
    }
    
    // 等待所有查询完成
    val results = futures.map(f => Await.result(f, 15.seconds))
    
    // 验证所有查询都成功
    results.foreach { output =>
      output should not be empty
      val result = output.head.parseJson.asJsObject
      val success = result.fields("success").asInstanceOf[JsBoolean].value
      success shouldBe true
    }
    
    println(s"✅ 并发查询测试通过: ${results.size} 个查询全部成功")
  }
}
