package cn.xuyinyin.magic.connectors.mysql

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

/**
 * MySQL批量同步集成测试
 * 
 * 测试完整的MySQL同步流程，包括：
 * - ConnectorLoader动态加载
 * - NodeRegistry查找连接器
 * - WorkflowExecutionEngine执行同步
 * - 端到端数据同步验证
 * 
 * 运行方式：
 * sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest"
 * 
 * @author : Xuxiaotuan
 * @since : 2024-03-22
 */
class MySQLBatchSyncIntegrationTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  @volatile private var systemInitialized = false
  implicit lazy val system: ActorSystem[Nothing] = {
    val created = ActorSystem(Behaviors.empty, "MySQLTestSystem")
    systemInitialized = true
    created
  }
  implicit lazy val ec: ExecutionContext = system.executionContext

  override def afterAll(): Unit = {
    try {
      if (systemInitialized) {
        system.terminate()
        Await.result(system.whenTerminated, 20.seconds)
      }
    } finally super.afterAll()
  }

  // 测试数据库配置
  val dbHost = "100.82.226.63"
  val dbPort = 31765
  val dbName = "test"
  val dbUser = "root"
  val dbPass = "asd123456"

  "NodeRegistry" should "包含MySQL连接器" taggedAs(ExternalIntegration) in {
    // 验证Source连接器已注册
    val sourceOpt = cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry.findSource("mysql.query")
    sourceOpt shouldBe defined
    sourceOpt.get.getClass.getName should include("MySQLSourceNode")
    
    // 验证Sink连接器已注册
    val sinkOpt = cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry.findSink("mysql.write")
    sinkOpt shouldBe defined
    sinkOpt.get.getClass.getName should include("MySQLSinkNode")
    
    println("✅ MySQL连接器已注册")
  }

  "MySQLSourceNode" should "读取数据库数据" taggedAs(ExternalIntegration) in {
    val source = cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
      .findSource("mysql.query")
      .getOrElse(fail("MySQL Source未找到"))
    
    val config = JsObject(
      "host" -> JsString(dbHost),
      "port" -> JsNumber(dbPort),
      "database" -> JsString(dbName),
      "username" -> JsString(dbUser),
      "password" -> JsString(dbPass),
      "sql" -> JsString("SELECT id, name FROM xjwtest LIMIT 5"),
      "fetchSize" -> JsNumber(1000)
    )
    
    val node = WorkflowDSL.Node(
      id = "test-source",
      `type` = "source",
      nodeType = "mysql.query",
      label = "Test Source",
      position = WorkflowDSL.Position(100, 100),
      config = config
    )
    
    println("\n" + "="*60)
    println("测试: 读取MySQL数据")
    println("="*60)
    
    val logs = scala.collection.mutable.ListBuffer[String]()
    val sourceStream = source.createSource(node, msg => {
      logs += msg
      println(s"[SOURCE] $msg")
    })
    
    import org.apache.pekko.stream.scaladsl.Sink
    val result = Await.result(
      sourceStream.take(5).runWith(Sink.seq),
      15.seconds
    )
    
    println(s"\n✅ 成功读取 ${result.size} 行数据:")
    result.foreach(row => println(s"  📄 $row"))
    
    result.size should be > 0
    result.foreach { row =>
      val json = row.parseJson.asJsObject
      json.fields should contain key "id"
      json.fields should contain key "name"
    }
  }

  "WorkflowExecutionEngine" should "执行完整的MySQL同步workflow" taggedAs(ExternalIntegration) in {
    println("\n" + "="*60)
    println("测试: 完整批量同步 xjwtest → xjwtest1")
    println("="*60)
    
    // 创建Workflow
    val workflow = WorkflowDSL.Workflow(
      id = "test-mysql-sync",
      name = "MySQL批量同步测试",
      description = "从xjwtest表同步到xjwtest1表",
      version = "1.0",
      author = "test",
      tags = List("test", "mysql"),
      nodes = List(
        WorkflowDSL.Node(
          id = "mysql-source",
          `type` = "source",
          nodeType = "mysql.query",
          label = "MySQL Source",
          position = WorkflowDSL.Position(100, 100),
          config = JsObject(
            "host" -> JsString(dbHost),
            "port" -> JsNumber(dbPort),
            "database" -> JsString(dbName),
            "username" -> JsString(dbUser),
            "password" -> JsString(dbPass),
            "sql" -> JsString("SELECT id, name FROM xjwtest LIMIT 20"),
            "fetchSize" -> JsNumber(1000)
          )
        ),
        WorkflowDSL.Node(
          id = "mysql-sink",
          `type` = "sink",
          nodeType = "mysql.write",
          label = "MySQL Sink",
          position = WorkflowDSL.Position(400, 100),
          config = JsObject(
            "host" -> JsString(dbHost),
            "port" -> JsNumber(dbPort),
            "database" -> JsString(dbName),
            "table" -> JsString("xjwtest1"),
            "username" -> JsString(dbUser),
            "password" -> JsString(dbPass),
            "batchSize" -> JsNumber(1000),
            "mode" -> JsString("insert")
          )
        )
      ),
      edges = List(
        WorkflowDSL.Edge(
          id = "edge-1",
          source = "mysql-source",
          target = "mysql-sink"
        )
      ),
      metadata = WorkflowDSL.WorkflowMetadata(
        createdAt = java.time.Instant.now().toString,
        updatedAt = java.time.Instant.now().toString
      )
    )
    
    // 创建执行引擎
    val engine = new WorkflowExecutionEngine()
    
    val executionId = s"test-exec-${System.currentTimeMillis()}"
    val logs = scala.collection.mutable.ListBuffer[String]()
    val onLog = (msg: String) => {
      logs += msg
      println(s"[ENGINE] $msg")
    }
    
    // 执行workflow
    val startTime = System.currentTimeMillis()
    
    val result = Await.result(
      engine.execute(workflow, executionId, onLog),
      60.seconds
    )
    
    val duration = (System.currentTimeMillis() - startTime) / 1000.0
    
    println("\n" + "="*60)
    println("✅ Workflow执行完成！")
    println("="*60)
    println(s"⏱️  耗时: ${duration}s")
    println(s"📊 日志条数: ${logs.size}")
    println("="*60 + "\n")
    
    // 验证结果
    result.success shouldBe true
    logs should not be empty
    logs.exists(_.contains("MySQL Source")) shouldBe true
    logs.exists(_.contains("MySQL Sink")) shouldBe true
  }

  "MySQL同步性能测试" should "达到预期吞吐量" taggedAs(ExternalIntegration) in {
    println("\n" + "="*60)
    println("性能测试: 大量数据同步")
    println("="*60)
    
    val workflow = WorkflowDSL.Workflow(
      id = "perf-test-mysql-sync",
      name = "MySQL性能测试",
      description = "测试大量数据同步性能",
      version = "1.0",
      author = "test",
      tags = List("test", "performance"),
      nodes = List(
        WorkflowDSL.Node(
          id = "mysql-source",
          `type` = "source",
          nodeType = "mysql.query",
          label = "MySQL Source",
          position = WorkflowDSL.Position(100, 100),
          config = JsObject(
            "host" -> JsString(dbHost),
            "port" -> JsNumber(dbPort),
            "database" -> JsString(dbName),
            "username" -> JsString(dbUser),
            "password" -> JsString(dbPass),
            "sql" -> JsString("SELECT id, name FROM xjwtest"), // 全表
            "fetchSize" -> JsNumber(1000)
          )
        ),
        WorkflowDSL.Node(
          id = "mysql-sink",
          `type` = "sink",
          nodeType = "mysql.write",
          label = "MySQL Sink",
          position = WorkflowDSL.Position(400, 100),
          config = JsObject(
            "host" -> JsString(dbHost),
            "port" -> JsNumber(dbPort),
            "database" -> JsString(dbName),
            "table" -> JsString("xjwtest1"),
            "username" -> JsString(dbUser),
            "password" -> JsString(dbPass),
            "batchSize" -> JsNumber(1000),
            "mode" -> JsString("insert")
          )
        )
      ),
      edges = List(
        WorkflowDSL.Edge(
          id = "edge-1",
          source = "mysql-source",
          target = "mysql-sink"
        )
      ),
      metadata = WorkflowDSL.WorkflowMetadata(
        createdAt = java.time.Instant.now().toString,
        updatedAt = java.time.Instant.now().toString
      )
    )
    
    val engine = new WorkflowExecutionEngine()
    val executionId = s"perf-test-${System.currentTimeMillis()}"
    
    var rowCount = 0
    val onLog = (msg: String) => {
      if (msg.contains("已处理") || msg.contains("写入")) {
        rowCount += 1
        if (rowCount % 10 == 0) {
          println(s"[PERF] $msg")
        }
      }
    }
    
    val startTime = System.currentTimeMillis()
    
    val result = Await.result(
      engine.execute(workflow, executionId, onLog),
      120.seconds
    )
    
    val duration = (System.currentTimeMillis() - startTime) / 1000.0
    val throughput = if (rowCount > 0) rowCount / duration else 0
    
    println("\n" + "="*60)
    println("✅ 性能测试完成！")
    println("="*60)
    println(s"📊 同步行数: $rowCount")
    println(s"⏱️  总耗时: ${duration}s")
    println(s"🚀 吞吐量: ${throughput.toInt} 行/秒")
    println("="*60 + "\n")
    
    result.success shouldBe true
    
    // 性能断言（根据实际情况调整）
    if (rowCount > 100) {
      throughput should be > 50.0 // 至少50行/秒
    }
  }
}
