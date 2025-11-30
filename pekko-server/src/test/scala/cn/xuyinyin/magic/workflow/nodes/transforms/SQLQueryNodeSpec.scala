package cn.xuyinyin.magic.workflow.nodes.transforms

import cn.xuyinyin.magic.datafusion._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * SQL查询节点测试
 */
class SQLQueryNodeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  
  val testKit = ActorTestKit()
  implicit val system: org.apache.pekko.actor.typed.ActorSystem[Nothing] = testKit.system
  implicit val ec: ExecutionContext = system.executionContext
  
  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }
  
  "SQLQueryNode" should "be created with valid config" in {
    val config = SQLNodeConfig(
      sql = "SELECT * FROM table",
      batchSize = 1000,
      timeout = 30.seconds
    )
    
    val clientConfig = FlightClientConfig.default
    val poolConfig = FlightClientPoolConfig(maxTotal = 5)
    val pool = FlightClientPool(clientConfig, poolConfig)
    
    val node = SQLQueryNode(pool, config)
    node.nodeType shouldBe "sql.query"
    
    pool.close()
  }
  
  it should "validate config on transform creation" in {
    val invalidConfig = SQLNodeConfig(
      sql = "", // empty SQL
      batchSize = 1000
    )
    
    val clientConfig = FlightClientConfig.default
    val poolConfig = FlightClientPoolConfig(maxTotal = 5)
    val pool = FlightClientPool(clientConfig, poolConfig)
    
    val node = SQLQueryNode(pool, invalidConfig)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-1",
      `type` = "transform",
      nodeType = "sql.query",
      label = "SQL Query",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    an[IllegalArgumentException] should be thrownBy {
      node.createTransform(workflowNode, _ => ())
    }
    
    pool.close()
  }
  
  it should "bind parameters correctly" in {
    val config = SQLNodeConfig(
      sql = "SELECT * FROM table WHERE id = {{id}} AND name = {{name}}",
      parameters = Map("id" -> 123, "name" -> "test")
    )
    
    val boundSql = config.bindParameters()
    boundSql should include("123")
    boundSql should include("'test'")
    boundSql should not include("{{id}}")
    boundSql should not include("{{name}}")
  }
  
  it should "create transform flow" in {
    val config = SQLNodeConfig(
      sql = "SELECT * FROM table",
      batchSize = 10
    )
    
    val clientConfig = FlightClientConfig.default
    val poolConfig = FlightClientPoolConfig(maxTotal = 5)
    val pool = FlightClientPool(clientConfig, poolConfig)
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-1",
      `type` = "transform",
      nodeType = "sql.query",
      label = "SQL Query",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, _ => ())
    transform should not be null
    
    pool.close()
  }
  
  "SQLQueryNode.fromNode" should "parse node config correctly" in {
    val nodeConfig = JsObject(
      "sql" -> JsString("SELECT * FROM users"),
      "batchSize" -> JsNumber(2000),
      "timeout" -> JsNumber(45)
    )
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-1",
      `type` = "transform",
      nodeType = "sql.query",
      label = "SQL Query",
      position = WorkflowDSL.Position(0, 0),
      config = nodeConfig
    )
    
    val clientConfig = FlightClientConfig.default
    val poolConfig = FlightClientPoolConfig(maxTotal = 5)
    val pool = FlightClientPool(clientConfig, poolConfig)
    
    val result = SQLQueryNode.fromNode(workflowNode, pool)
    result.isRight shouldBe true
    
    val node = result.right.get
    node.nodeType shouldBe "sql.query"
    
    pool.close()
  }
  
  it should "fail on invalid config" in {
    val nodeConfig = JsObject(
      "batchSize" -> JsNumber(1000) // missing sql
    )
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-1",
      `type` = "transform",
      nodeType = "sql.query",
      label = "SQL Query",
      position = WorkflowDSL.Position(0, 0),
      config = nodeConfig
    )
    
    val clientConfig = FlightClientConfig.default
    val poolConfig = FlightClientPoolConfig(maxTotal = 5)
    val pool = FlightClientPool(clientConfig, poolConfig)
    
    val result = SQLQueryNode.fromNode(workflowNode, pool)
    result.isLeft shouldBe true
    
    pool.close()
  }
  
  // 注意：以下测试需要DataFusion Service运行
  // 在实际环境中应该使用mock或testcontainers
  
  "SQLQueryNode integration" should "process data through transform" ignore {
    // 这个测试需要实际的DataFusion Service
    // 标记为ignore，在集成测试环境中启用
    
    val config = SQLNodeConfig(
      sql = "SELECT 1 as id, 'test' as name",
      batchSize = 10
    )
    
    val clientConfig = FlightClientConfig.default
    val poolConfig = FlightClientPoolConfig(maxTotal = 5)
    val pool = FlightClientPool(clientConfig, poolConfig)
    
    val node = SQLQueryNode(pool, config)
    
    val workflowNode = WorkflowDSL.Node(
      id = "sql-1",
      `type` = "transform",
      nodeType = "sql.query",
      label = "SQL Query",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject()
    )
    
    val transform = node.createTransform(workflowNode, println)
    
    val input = Source(List(
      """{"input": "data1"}""",
      """{"input": "data2"}"""
    ))
    
    val result = input
      .via(transform)
      .runWith(Sink.seq)
    
    val output = Await.result(result, 10.seconds)
    output should not be empty
    
    pool.close()
  }
}
