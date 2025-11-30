package cn.xuyinyin.magic.datafusion

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

import scala.concurrent.ExecutionContext

/**
 * SQL节点注册表测试
 */
class SQLNodeRegistrySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  
  val testKit = ActorTestKit()
  implicit val system = testKit.system
  implicit val ec: ExecutionContext = system.executionContext
  
  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }
  
  "SQLNodeRegistry" should "return None when DataFusion is disabled" in {
    val config = ConfigFactory.parseString(
      """
        |datafusion {
        |  enabled = false
        |}
        |""".stripMargin)
    
    val pool = SQLNodeRegistry.createClientPoolFromConfig(config)
    pool shouldBe None
  }
  
  it should "create FlightClientPool when DataFusion is enabled" in {
    val config = ConfigFactory.parseString(
      """
        |datafusion {
        |  enabled = true
        |  host = "localhost"
        |  port = 50051
        |  pool {
        |    maxTotal = 5
        |    maxIdle = 3
        |    minIdle = 1
        |  }
        |}
        |""".stripMargin)
    
    val pool = SQLNodeRegistry.createClientPoolFromConfig(config)
    pool.isDefined shouldBe true
    
    pool.foreach(_.close())
  }
  
  it should "use default values when config is missing" in {
    val config = ConfigFactory.parseString(
      """
        |datafusion {
        |  enabled = true
        |}
        |""".stripMargin)
    
    val pool = SQLNodeRegistry.createClientPoolFromConfig(config)
    pool.isDefined shouldBe true
    
    pool.foreach(_.close())
  }
  
  it should "check if SQL node is available" in {
    val config = ConfigFactory.parseString(
      """
        |datafusion {
        |  enabled = true
        |}
        |""".stripMargin)
    
    val pool = SQLNodeRegistry.createClientPoolFromConfig(config)
    SQLNodeRegistry.isSQLNodeAvailable(pool) shouldBe true
    SQLNodeRegistry.isSQLNodeAvailable(None) shouldBe false
    
    pool.foreach(_.close())
  }
  
  it should "return correct SQL node type" in {
    SQLNodeRegistry.sqlNodeType shouldBe "sql.query"
  }
  
  it should "return supported SQL node types" in {
    val types = SQLNodeRegistry.supportedSQLNodeTypes
    types should contain("sql.query")
    types.size shouldBe 1
  }
  
  it should "validate SQL node config" in {
    val validConfig = JsObject(
      "sql" -> JsString("SELECT * FROM users"),
      "batchSize" -> JsNumber(1000)
    )
    
    val result = SQLNodeRegistry.validateSQLNodeConfig(validConfig)
    result.isRight shouldBe true
  }
  
  it should "reject invalid SQL node config" in {
    val invalidConfig = JsObject(
      "batchSize" -> JsNumber(1000) // missing sql
    )
    
    val result = SQLNodeRegistry.validateSQLNodeConfig(invalidConfig)
    result.isLeft shouldBe true
  }
  
  "SQLNodeRegistryConfig" should "load from config" in {
    val config = ConfigFactory.parseString(
      """
        |datafusion {
        |  enabled = true
        |  host = "test-host"
        |  port = 9999
        |  pool {
        |    maxTotal = 20
        |    maxIdle = 10
        |    minIdle = 5
        |  }
        |}
        |""".stripMargin)
    
    val registryConfig = SQLNodeRegistryConfig.fromConfig(config)
    
    registryConfig.enabled shouldBe true
    registryConfig.host shouldBe "test-host"
    registryConfig.port shouldBe 9999
    registryConfig.poolConfig.maxTotal shouldBe 20
    registryConfig.poolConfig.maxIdle shouldBe 10
    registryConfig.poolConfig.minIdle shouldBe 5
  }
  
  it should "use defaults when config is missing" in {
    val config = ConfigFactory.parseString("")
    
    val registryConfig = SQLNodeRegistryConfig.fromConfig(config)
    
    registryConfig.enabled shouldBe false
    registryConfig.host shouldBe "localhost"
    registryConfig.port shouldBe 50051
  }
}
