package cn.xuyinyin.magic.datafusion

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * DataFusion集成测试
 * 
 * 测试Scala客户端与Rust DataFusion服务之间的完整通信流程
 * 
 * 前置条件：DataFusion服务必须在localhost:50051运行
 */
class DataFusionIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  
  implicit val ec: ExecutionContext = ExecutionContext.global
  
  var client: DataFusionClient = _
  
  override def beforeAll(): Unit = {
    val config = FlightClientConfig.default
    client = DataFusionClient(config)
    println("✅ DataFusion客户端已初始化")
  }
  
  override def afterAll(): Unit = {
    client.close()
    println("✅ DataFusion客户端已关闭")
  }
  
  "DataFusion Service" should "be reachable and healthy" in {
    val healthFuture = client.healthCheck()
    val isHealthy = Await.result(healthFuture, 10.seconds)
    
    isHealthy shouldBe true
    println("✅ 健康检查通过")
  }
  
  it should "execute simple SELECT query" in {
    val sql = "SELECT * FROM users"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 5
    
    // 验证返回的数据包含预期的用户
    val names = response.data.map(row => row("name").asInstanceOf[JsString].value)
    names should contain allOf ("Alice", "Bob", "Charlie", "Diana", "Eve")
    
    println(s"✅ 查询返回 ${response.data.size} 行数据")
  }
  
  it should "execute SELECT with WHERE clause" in {
    val sql = "SELECT * FROM users WHERE age > 30"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 3  // Alice(30), Charlie(35), Eve(32)
    
    // 验证所有返回的用户年龄都大于30
    response.data.foreach { row =>
      val age = row("age").asInstanceOf[JsNumber].value.intValue
      age should be > 30
    }
    
    println(s"✅ WHERE条件查询返回 ${response.data.size} 行")
  }
  
  it should "execute SELECT with ORDER BY" in {
    val sql = "SELECT name, age FROM users ORDER BY age DESC"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 5
    
    // 验证结果按年龄降序排列
    val ages = response.data.map(row => row("age").asInstanceOf[JsNumber].value.intValue)
    ages shouldEqual ages.sorted(Ordering[Int].reverse)
    
    // 第一个应该是Charlie(35)
    val firstName = response.data.head("name").asInstanceOf[JsString].value
    firstName shouldBe "Charlie"
    
    println(s"✅ ORDER BY查询正确排序: ${ages.mkString(", ")}")
  }
  
  it should "execute SELECT with LIMIT" in {
    val sql = "SELECT * FROM users LIMIT 3"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 3
    
    println(s"✅ LIMIT查询返回 ${response.data.size} 行")
  }
  
  it should "execute COUNT aggregation" in {
    val sql = "SELECT COUNT(*) as total FROM users"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 1
    
    val count = response.data.head("total").asInstanceOf[JsNumber].value.longValue
    count shouldBe 5
    
    println(s"✅ COUNT查询返回: $count")
  }
  
  it should "execute multiple aggregations" in {
    val sql = "SELECT AVG(age) as avg_age, MAX(age) as max_age, MIN(age) as min_age FROM users"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 1
    
    val row = response.data.head
    val avgAge = row("avg_age").asInstanceOf[JsNumber].value.doubleValue
    val maxAge = row("max_age").asInstanceOf[JsNumber].value.intValue
    val minAge = row("min_age").asInstanceOf[JsNumber].value.intValue
    
    avgAge shouldBe 30.0 +- 0.1  // (30+25+35+28+32)/5 = 30
    maxAge shouldBe 35
    minAge shouldBe 25
    
    println(s"✅ 聚合查询: AVG=$avgAge, MAX=$maxAge, MIN=$minAge")
  }
  
  it should "handle invalid SQL gracefully" in {
    val sql = "SELECT * FROM invalid syntax"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe false
    response.data shouldBe empty
    response.message should include("failed")
    
    println(s"✅ 无效SQL错误处理: ${response.message}")
  }
  
  it should "handle non-existent table gracefully" in {
    val sql = "SELECT * FROM non_existent_table"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe false
    response.data shouldBe empty
    
    println(s"✅ 不存在的表错误处理: ${response.message}")
  }
  
  it should "retrieve query schema" in {
    val sql = "SELECT id, name, age FROM users"
    val schemaFuture = client.getQuerySchema(sql)
    val response = Await.result(schemaFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 1
    
    val schemaInfo = response.data.head
    schemaInfo should contain key "columns"
    
    val columns = schemaInfo("columns").asInstanceOf[JsArray].elements
    columns should have size 3
    
    // 验证列名
    val columnNames = columns.map { col =>
      col.asJsObject.fields("name").asInstanceOf[JsString].value
    }
    columnNames should contain allOf ("id", "name", "age")
    
    println(s"✅ Schema查询返回 ${columns.size} 列: ${columnNames.mkString(", ")}")
  }
  
  it should "convert data types correctly" in {
    val sql = "SELECT id, name, age, email FROM users WHERE id = 1"
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 1
    
    val row = response.data.head
    
    // 验证BIGINT类型
    row("id") shouldBe a[JsNumber]
    val id = row("id").asInstanceOf[JsNumber].value.longValue
    id shouldBe 1
    
    // 验证VARCHAR类型
    row("name") shouldBe a[JsString]
    val name = row("name").asInstanceOf[JsString].value
    name shouldBe "Alice"
    
    // 验证INT类型
    row("age") shouldBe a[JsNumber]
    val age = row("age").asInstanceOf[JsNumber].value.intValue
    age shouldBe 30
    
    // 验证VARCHAR类型
    row("email") shouldBe a[JsString]
    val email = row("email").asInstanceOf[JsString].value
    email shouldBe "alice@example.com"
    
    println(s"✅ 数据类型转换正确: id=$id, name=$name, age=$age, email=$email")
  }
  
  it should "execute complex query with multiple conditions" in {
    val sql = """
      SELECT name, age 
      FROM users 
      WHERE age >= 28 AND age <= 32 
      ORDER BY age ASC
    """
    val resultFuture = client.executeQuery(sql)
    val response = Await.result(resultFuture, 10.seconds)
    
    response.success shouldBe true
    response.data should have size 3  // Diana(28), Alice(30), Eve(32)
    
    val names = response.data.map(row => row("name").asInstanceOf[JsString].value)
    names shouldBe Seq("Diana", "Alice", "Eve")
    
    println(s"✅ 复杂查询返回: ${names.mkString(", ")}")
  }
}
