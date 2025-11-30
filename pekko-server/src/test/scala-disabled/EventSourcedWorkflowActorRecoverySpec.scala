package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.testkit.STPekkoSpec
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.duration._

/**
 * EventSourcedWorkflowActor恢复测试
 * 
 * 测试内容：
 * - 从快照恢复
 * - 从事件回放恢复
 * - 状态完整性
 * 
 * 验证：
 * - Property 4: 故障转移完整性
 * - Requirements 2.2
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
class EventSourcedWorkflowActorRecoverySpec
  extends ScalaTestWithActorTestKit(
    EventSourcedBehaviorTestKit.config.withFallback(ConfigFactory.load("application-test"))
  )
  with STPekkoSpec
  with BeforeAndAfterEach {
  
  implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
  
  // 创建执行引擎
  val executionEngine = new WorkflowExecutionEngine()(system, ec)
  
  override def afterEach(): Unit = {
    super.afterEach()
  }
  
  "EventSourcedWorkflowActor recovery" should {
    
    "recover from snapshot" in {
      val workflowId = "recovery-test-1"
      val workflow = createTestWorkflow(workflowId)
      
      // 创建Actor
      val actor = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId"
      )
      
      // 执行工作流以生成事件
      val executeProbe = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionResponse]()
      actor ! EventSourcedWorkflowActor.Execute(executeProbe.ref)
      
      val executeResponse = executeProbe.receiveMessage(5.seconds)
      executeResponse.status should (be("started") or be("running") or be("restarted"))
      
      // 等待执行完成
      Thread.sleep(2000)
      
      // 查询状态
      val statusProbe1 = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      actor ! EventSourcedWorkflowActor.GetStatus(statusProbe1.ref)
      
      val status1 = statusProbe1.receiveMessage(5.seconds)
      status1.workflowId shouldBe workflowId
      
      // 停止Actor
      testKit.stop(actor)
      
      // 重新创建Actor（模拟恢复）
      val recoveredActor = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId-recovered"
      )
      
      // 等待恢复完成
      Thread.sleep(1000)
      
      // 验证状态已恢复
      val statusProbe2 = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      recoveredActor ! EventSourcedWorkflowActor.GetStatus(statusProbe2.ref)
      
      val status2 = statusProbe2.receiveMessage(5.seconds)
      status2.workflowId shouldBe workflowId
      status2.allExecutions should not be empty
      
      log.info(s"Workflow recovered successfully: ${status2.allExecutions.size} executions")
      
      testKit.stop(recoveredActor)
    }
    
    "recover from event replay" in {
      val workflowId = "recovery-test-2"
      val workflow = createTestWorkflow(workflowId)
      
      // 创建Actor
      val actor = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId"
      )
      
      // 执行多次以生成多个事件
      for (i <- 1 to 3) {
        val executeProbe = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionResponse]()
        actor ! EventSourcedWorkflowActor.Execute(executeProbe.ref)
        executeProbe.receiveMessage(5.seconds)
        Thread.sleep(1000)
      }
      
      // 查询执行历史
      val historyProbe1 = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionHistoryResponse]()
      actor ! EventSourcedWorkflowActor.GetExecutionHistory(historyProbe1.ref)
      
      val history1 = historyProbe1.receiveMessage(5.seconds)
      val executionCount = history1.executions.size
      
      log.info(s"Original execution count: $executionCount")
      
      // 停止Actor
      testKit.stop(actor)
      
      // 重新创建Actor（从事件回放恢复）
      val recoveredActor = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId-recovered"
      )
      
      // 等待恢复完成
      Thread.sleep(1000)
      
      // 验证执行历史已恢复
      val historyProbe2 = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionHistoryResponse]()
      recoveredActor ! EventSourcedWorkflowActor.GetExecutionHistory(historyProbe2.ref)
      
      val history2 = historyProbe2.receiveMessage(5.seconds)
      
      // 验证执行次数一致
      history2.executions.size should be >= executionCount
      
      log.info(s"Recovered execution count: ${history2.executions.size}")
      
      testKit.stop(recoveredActor)
    }
    
    "maintain state integrity after recovery" in {
      val workflowId = "recovery-test-3"
      val workflow = createTestWorkflow(workflowId)
      
      // 创建Actor
      val actor = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId"
      )
      
      // 执行工作流
      val executeProbe = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionResponse]()
      actor ! EventSourcedWorkflowActor.Execute(executeProbe.ref)
      
      val executeResponse = executeProbe.receiveMessage(5.seconds)
      val executionId = executeResponse.executionId
      
      // 等待执行完成
      Thread.sleep(2000)
      
      // 获取原始状态
      val statusProbe1 = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      actor ! EventSourcedWorkflowActor.GetStatus(statusProbe1.ref)
      
      val originalStatus = statusProbe1.receiveMessage(5.seconds)
      
      // 停止Actor
      testKit.stop(actor)
      
      // 重新创建Actor
      val recoveredActor = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId-recovered"
      )
      
      // 等待恢复完成
      Thread.sleep(1000)
      
      // 获取恢复后的状态
      val statusProbe2 = testKit.createTestProbe[EventSourcedWorkflowActor.StatusResponse]()
      recoveredActor ! EventSourcedWorkflowActor.GetStatus(statusProbe2.ref)
      
      val recoveredStatus = statusProbe2.receiveMessage(5.seconds)
      
      // 验证状态完整性
      recoveredStatus.workflowId shouldBe originalStatus.workflowId
      recoveredStatus.allExecutions.size shouldBe originalStatus.allExecutions.size
      
      // 验证执行ID存在
      recoveredStatus.allExecutions.map(_.executionId) should contain(executionId)
      
      log.info(s"State integrity maintained after recovery")
      
      testKit.stop(recoveredActor)
    }
    
    "handle multiple recovery cycles" in {
      val workflowId = "recovery-test-4"
      val workflow = createTestWorkflow(workflowId)
      
      var executionCount = 0
      
      // 第一次创建和执行
      val actor1 = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId-1"
      )
      
      val executeProbe1 = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionResponse]()
      actor1 ! EventSourcedWorkflowActor.Execute(executeProbe1.ref)
      executeProbe1.receiveMessage(5.seconds)
      Thread.sleep(1000)
      executionCount += 1
      
      testKit.stop(actor1)
      
      // 第二次恢复和执行
      val actor2 = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId-2"
      )
      
      Thread.sleep(500)
      
      val executeProbe2 = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionResponse]()
      actor2 ! EventSourcedWorkflowActor.Execute(executeProbe2.ref)
      executeProbe2.receiveMessage(5.seconds)
      Thread.sleep(1000)
      executionCount += 1
      
      testKit.stop(actor2)
      
      // 第三次恢复和验证
      val actor3 = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId-3"
      )
      
      Thread.sleep(500)
      
      val historyProbe = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionHistoryResponse]()
      actor3 ! EventSourcedWorkflowActor.GetExecutionHistory(historyProbe.ref)
      
      val history = historyProbe.receiveMessage(5.seconds)
      
      // 验证所有执行都被保留
      history.executions.size should be >= executionCount
      
      log.info(s"Multiple recovery cycles completed: ${history.executions.size} executions preserved")
      
      testKit.stop(actor3)
    }
    
    "recover and continue execution" in {
      val workflowId = "recovery-test-5"
      val workflow = createTestWorkflow(workflowId)
      
      // 创建Actor并执行
      val actor1 = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId-1"
      )
      
      val executeProbe1 = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionResponse]()
      actor1 ! EventSourcedWorkflowActor.Execute(executeProbe1.ref)
      executeProbe1.receiveMessage(5.seconds)
      Thread.sleep(1000)
      
      testKit.stop(actor1)
      
      // 恢复后继续执行
      val actor2 = testKit.spawn(
        EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
        s"workflow-$workflowId-2"
      )
      
      Thread.sleep(500)
      
      // 执行新的工作流
      val executeProbe2 = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionResponse]()
      actor2 ! EventSourcedWorkflowActor.Execute(executeProbe2.ref)
      
      val executeResponse2 = executeProbe2.receiveMessage(5.seconds)
      executeResponse2.status should (be("started") or be("running") or be("restarted"))
      
      Thread.sleep(1000)
      
      // 验证可以继续执行
      val historyProbe = testKit.createTestProbe[EventSourcedWorkflowActor.ExecutionHistoryResponse]()
      actor2 ! EventSourcedWorkflowActor.GetExecutionHistory(historyProbe.ref)
      
      val history = historyProbe.receiveMessage(5.seconds)
      history.executions.size should be >= 2
      
      log.info(s"Workflow can continue execution after recovery: ${history.executions.size} total executions")
      
      testKit.stop(actor2)
    }
  }
  
  // 辅助方法：创建测试工作流
  private def createTestWorkflow(workflowId: String): WorkflowDSL.Workflow = {
    WorkflowDSL.Workflow(
      id = workflowId,
      name = s"Test Workflow $workflowId",
      description = "Test workflow for recovery testing",
      version = "1.0",
      author = "test",
      tags = List("test", "recovery"),
      nodes = List(
        WorkflowDSL.Node(
          id = "source-1",
          `type` = "source",
          nodeType = "sequence",
          label = "Sequence Source",
          position = WorkflowDSL.Position(0, 0),
          config = spray.json.JsObject(
            "start" -> spray.json.JsNumber(1),
            "end" -> spray.json.JsNumber(5)
          )
        ),
        WorkflowDSL.Node(
          id = "sink-1",
          `type` = "sink",
          nodeType = "console.log",
          label = "Console Sink",
          position = WorkflowDSL.Position(200, 0),
          config = spray.json.JsObject()
        )
      ),
      edges = List(
        WorkflowDSL.Edge(
          id = "edge-1",
          source = "source-1",
          target = "sink-1"
        )
      ),
      metadata = WorkflowDSL.WorkflowMetadata(
        createdAt = java.time.Instant.now().toString,
        updatedAt = java.time.Instant.now().toString
      )
    )
  }
}
