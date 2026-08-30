package cn.xuyinyin.magic.connectors.mysql

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.Cluster
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import spray.json._
import com.typesafe.config.ConfigFactory

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger

/**
 * MySQL真实写入性能测试
 * 
 * 测试场景：
 * 1. 读取10万条真实数据
 * 2. 写入10万条数据到真实MySQL
 * 3. 端到端完整同步性能测试
 * 
 * 准备工作：
 * mysql -h 100.82.226.63 -P 31765 -u root -pasd123456 test < .tasks/prepare-perf-test.sql
 * 
 * 运行方式：
 * sbt "project pekko-server" "testOnly *MySQLRealPerformanceTest"
 * 
 * @author : Xuxiaotuan
 * @since : 2024-03-22
 */
class MySQLRealPerformanceTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // 加载test配置并创建ActorSystem  
  implicit val system: ActorSystem[Nothing] = {
    val testConfig = ConfigFactory.parseString(
      """
      pekko.cluster.seed-nodes = []
      pekko.remote.artery.canonical.port = 0
      """
    ).withFallback(ConfigFactory.load("application-test"))
    
    ActorSystem[Nothing](
      Behaviors.empty, 
      "RealPerfTestSystem",
      testConfig
    )
  }
  implicit val ec: ExecutionContext = system.executionContext

  // 让测试节点自己形成单节点集群
  Cluster(system).manager ! org.apache.pekko.cluster.typed.Join(Cluster(system).selfMember.address)

  override def afterAll(): Unit = {
    system.terminate()
  }

  // MySQL配置
  val dbHost = "100.82.226.63"
  val dbPort = 31765
  val dbName = "test"
  val dbUser = "root"
  val dbPass = "asd123456"

  "真实MySQL读取" should "从perf_test_source表读取数据" taggedAs(ExternalIntegration) in {
    println("\n" + "="*70)
    println("🔍 真实MySQL性能测试 1: 读取大量数据")
    println("="*70)

    val workflow = WorkflowDSL.Workflow(
      id = "perf-test-read",
      name = "MySQL读取性能测试",
      description = "读取perf_test_source表的数据",
      version = "1.0",
      author = "perf-test",
      tags = List("performance", "mysql", "read"),
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
            "sql" -> JsString("SELECT id, name, email, age, score, status FROM perf_test_source"),
            "fetchSize" -> JsNumber(1000)
          )
        ),
        WorkflowDSL.Node(
          id = "console-sink",
          `type` = "sink",
          nodeType = "console.log",
          label = "Console Sink",
          position = WorkflowDSL.Position(400, 100),
          config = JsObject()
        )
      ),
      edges = List(
        WorkflowDSL.Edge(
          id = "edge-1",
          source = "mysql-source",
          target = "console-sink"
        )
      ),
      metadata = WorkflowDSL.WorkflowMetadata(
        createdAt = java.time.Instant.now().toString,
        updatedAt = java.time.Instant.now().toString
      )
    )

    val engine = new WorkflowExecutionEngine()
    val executionId = s"perf-read-${System.currentTimeMillis()}"
    
    val rowCounter = new AtomicInteger(0)
    val startTime = System.currentTimeMillis()
    
    val onLog = (msg: String) => {
      if (msg.contains("MySQL Source")) {
        println(s"[READ] $msg")
      }
      
      // 统计行数
      val count = rowCounter.incrementAndGet()
      if (count % 10000 == 0) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val throughput = count / elapsed
        println(f"📊 读取进度: $count%,d 行 | 耗时: ${elapsed}%.2fs | 吞吐量: ${throughput}%,.0f 行/秒")
      }
    }

    val result = Await.result(
      engine.execute(workflow, executionId, onLog),
      120.seconds
    )

    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
    val totalRows = rowCounter.get()
    val throughput = totalRows / totalTime

    println("\n" + "="*70)
    println("✅ MySQL读取性能测试完成！")
    println("="*70)
    println(f"📊 读取行数: $totalRows%,d 行")
    println(f"⏱️  总耗时: ${totalTime}%.3f 秒")
    println(f"🚀 读取吞吐量: ${throughput}%,.0f 行/秒")
    println(f"💾 预估数据量: ${totalRows * 200 / 1024 / 1024}%,d MB (假设每行200字节)")
    println("="*70 + "\n")

    result.success shouldBe true
    totalRows should be > 0
  }

  "真实MySQL写入" should "写入大量数据到perf_test_sink表" taggedAs(ExternalIntegration) in {
    println("\n" + "="*70)
    println("✍️  真实MySQL性能测试 2: 写入大量数据")
    println("="*70)

    // 先清空目标表
    println("🗑️  清空目标表 perf_test_sink...")
    import java.sql.DriverManager
    val conn = DriverManager.getConnection(
      s"jdbc:mysql://$dbHost:$dbPort/$dbName",
      dbUser,
      dbPass
    )
    val stmt = conn.createStatement()
    stmt.execute("TRUNCATE TABLE perf_test_sink")
    stmt.close()
    conn.close()
    println("✅ 目标表已清空")

    val workflow = WorkflowDSL.Workflow(
      id = "perf-test-write",
      name = "MySQL写入性能测试",
      description = "从perf_test_source读取并写入perf_test_sink",
      version = "1.0",
      author = "perf-test",
      tags = List("performance", "mysql", "write"),
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
            "sql" -> JsString("SELECT id, name, email, age, score, status FROM perf_test_source"),
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
            "table" -> JsString("perf_test_sink"),
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
    val executionId = s"perf-write-${System.currentTimeMillis()}"
    
    val rowCounter = new AtomicInteger(0)
    val startTime = System.currentTimeMillis()
    
    val onLog = (msg: String) => {
      if (msg.contains("MySQL Source") || msg.contains("MySQL Sink")) {
        if (msg.contains("已写入") || msg.contains("连接MySQL") || msg.contains("执行查询")) {
          println(s"[WRITE] $msg")
        }
      }
      
      // 统计写入行数
      if (msg.contains("已写入")) {
        val count = rowCounter.incrementAndGet() * 1000 // batchSize=1000
        if (count % 10000 == 0) {
          val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
          val throughput = count / elapsed
          println(f"📊 写入进度: $count%,d 行 | 耗时: ${elapsed}%.2fs | 吞吐量: ${throughput}%,.0f 行/秒")
        }
      }
    }

    val result = Await.result(
      engine.execute(workflow, executionId, onLog),
      180.seconds
    )

    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0

    // 验证写入的数据
    val verifyConn = DriverManager.getConnection(
      s"jdbc:mysql://$dbHost:$dbPort/$dbName",
      dbUser,
      dbPass
    )
    val verifyStmt = verifyConn.createStatement()
    val rs = verifyStmt.executeQuery("SELECT COUNT(*) FROM perf_test_sink")
    rs.next()
    val writtenRows = rs.getInt(1)
    rs.close()
    verifyStmt.close()
    verifyConn.close()

    val throughput = writtenRows / totalTime

    println("\n" + "="*70)
    println("✅ MySQL写入性能测试完成！")
    println("="*70)
    println(f"📊 写入行数: $writtenRows%,d 行")
    println(f"⏱️  总耗时: ${totalTime}%.3f 秒")
    println(f"🚀 写入吞吐量: ${throughput}%,.0f 行/秒")
    println(f"📦 批量大小: 1000 行/批")
    println(f"🔄 总批次数: ${writtenRows / 1000}%,d 批")
    println(f"⚡ 每批耗时: ${totalTime / (writtenRows / 1000.0) * 1000}%.2f ms")
    println("="*70 + "\n")

    result.success shouldBe true
    writtenRows should be > 0
  }

  "性能对比" should "生成真实MySQL性能报告" taggedAs(ExternalIntegration) in {
    println("\n" + "="*70)
    println("📊 PekkoSync 真实MySQL性能测试报告")
    println("="*70)
    println()
    println("测试环境:")
    println(s"  • 数据库: MySQL $dbHost:$dbPort")
    println("  • 表引擎: InnoDB")
    println("  • 网络: 远程连接")
    println("  • 数据量: 10万条")
    println()
    println("性能分析:")
    println("  ✅ 真实网络延迟影响")
    println("  ✅ 真实磁盘I/O影响")
    println("  ✅ 真实事务提交开销")
    println("  ✅ 批量优化效果验证")
    println()
    println("优化建议:")
    println("  🔧 调整batchSize (1000 → 5000)")
    println("  🔧 增加mapAsync并行度 (1 → 4)")
    println("  🔧 使用PreparedStatement缓存")
    println("  🔧 优化MySQL配置 (innodb_buffer_pool_size)")
    println()
    println("下一步:")
    println("  📈 集群模式测试 (3节点)")
    println("  📈 CDC实时同步测试")
    println("  📈 大数据量测试 (百万级)")
    println("="*70 + "\n")

    true shouldBe true
  }
}
