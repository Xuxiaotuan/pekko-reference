# PekkoSync MVP: MySQL → MySQL 实时同步方案

> **目标**: 最小可行产品，专注MySQL到MySQL同步，实时界面监控每条数据

---

## 🎯 MVP功能范围

### 核心功能（必须）

✅ **MySQL Source** - 读取源MySQL数据  
✅ **MySQL Sink** - 写入目标MySQL  
✅ **实时监控** - 界面显示每条同步的数据  
✅ **同步统计** - 成功/失败计数、速率、延迟  
✅ **错误处理** - 失败重试、死信队列  

### 暂不实现（未来版本）

❌ 其他数据库（PostgreSQL、MongoDB等）  
❌ CDC实时增量（先做全量批量）  
❌ 复杂转换（先做简单字段映射）  
❌ 集群部署（先单节点）  

---

## 🏗️ 架构设计

### 数据流图

```
┌─────────────────────────────────────────────────────────────┐
│                        Web UI (React)                        │
│  ┌────────────────────┐  ┌─────────────────────────────┐   │
│  │  任务配置面板       │  │  实时监控面板                │   │
│  │  - 源MySQL配置     │  │  - 数据流动画                │   │
│  │  - 目标MySQL配置   │  │  - 实时数据预览（滚动）      │   │
│  │  - 字段映射        │  │  - 同步速率图表              │   │
│  │  - 启动/停止       │  │  - 成功/失败统计             │   │
│  └────────────────────┘  └─────────────────────────────┘   │
└────────────────┬────────────────────────┬───────────────────┘
                 │ REST API               │ WebSocket
                 │                        │
┌────────────────▼────────────────────────▼───────────────────┐
│                    Pekko HTTP Server                         │
│  ┌──────────────────────┐  ┌─────────────────────────────┐ │
│  │  SyncJobRoutes       │  │  MonitoringWebSocket        │ │
│  │  - 创建任务          │  │  - 实时推送同步数据          │ │
│  │  - 查询状态          │  │  - 推送统计指标              │ │
│  │  - 停止任务          │  │  - 推送错误信息              │ │
│  └──────────────────────┘  └─────────────────────────────┘ │
└────────────────┬───────────────────────────────────────────┘
                 │
┌────────────────▼───────────────────────────────────────────┐
│              SyncJobActor (管理单个同步任务)                │
│  ┌─────────────────────────────────────────────────────┐  │
│  │  Pekko Stream Pipeline                               │  │
│  │                                                       │  │
│  │  MySQLSource → Transform → Monitor → MySQLSink      │  │
│  │      ↓             ↓          ↓          ↓           │  │
│  │   读取数据      字段映射    实时上报    写入数据     │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                             │
│  State:                                                     │
│  - totalRecords: Long      (总记录数)                      │
│  - successCount: Long      (成功数)                        │
│  - failedCount: Long       (失败数)                        │
│  - currentRate: Double     (当前速率 行/秒)                │
│  - lastRecord: Option[Record] (最后一条数据)               │
└─────────────────────────────────────────────────────────────┘
                 │
      ┌──────────┴──────────┐
      │                     │
┌─────▼──────┐      ┌───────▼──────┐
│ Source     │      │ Target       │
│ MySQL      │      │ MySQL        │
└────────────┘      └──────────────┘
```

---

## 💻 核心实现

### 1. 同步任务Actor

```scala
package cn.xuyinyin.pekkosync.sync

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, ActorContext}
import org.apache.pekko.stream.scaladsl.{Source, Sink, Flow}
import org.apache.pekko.stream.Materializer

object SyncJobActor {
  
  // 命令
  sealed trait Command
  case class Start(config: MySQLSyncConfig) extends Command
  case class Stop() extends Command
  case class GetStatus(replyTo: ActorRef[SyncStatus]) extends Command
  
  // 内部消息
  private case class RecordSynced(record: DataRecord, success: Boolean) extends Command
  private case class UpdateMetrics(
    successDelta: Int, 
    failedDelta: Int, 
    rate: Double
  ) extends Command
  
  // 同步状态
  case class SyncStatus(
    state: String,              // "running" | "stopped" | "failed"
    totalRecords: Long,
    successCount: Long,
    failedCount: Long,
    currentRate: Double,        // 行/秒
    lastRecord: Option[DataRecord],
    errorMessage: Option[String]
  )
  
  def apply(
    monitorActor: ActorRef[MonitoringActor.Command]
  ): Behavior[Command] = {
    Behaviors.setup { ctx =>
      idle(ctx, monitorActor)
    }
  }
  
  private def idle(
    ctx: ActorContext[Command],
    monitorActor: ActorRef[MonitoringActor.Command]
  ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Start(config) =>
        ctx.log.info(s"Starting sync job: ${config.jobName}")
        
        // 启动同步Pipeline
        val killSwitch = startSyncPipeline(ctx, config, monitorActor)
        
        running(
          ctx,
          monitorActor,
          config,
          killSwitch,
          SyncStatus(
            state = "running",
            totalRecords = 0,
            successCount = 0,
            failedCount = 0,
            currentRate = 0.0,
            lastRecord = None,
            errorMessage = None
          )
        )
      
      case GetStatus(replyTo) =>
        replyTo ! SyncStatus(
          state = "idle",
          totalRecords = 0,
          successCount = 0,
          failedCount = 0,
          currentRate = 0.0,
          lastRecord = None,
          errorMessage = None
        )
        Behaviors.same
      
      case _ =>
        Behaviors.same
    }
  }
  
  private def running(
    ctx: ActorContext[Command],
    monitorActor: ActorRef[MonitoringActor.Command],
    config: MySQLSyncConfig,
    killSwitch: KillSwitch,
    status: SyncStatus
  ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Stop() =>
        ctx.log.info("Stopping sync job")
        killSwitch.shutdown()
        
        idle(ctx, monitorActor)
      
      case RecordSynced(record, success) =>
        val newStatus = if (success) {
          status.copy(
            successCount = status.successCount + 1,
            lastRecord = Some(record)
          )
        } else {
          status.copy(
            failedCount = status.failedCount + 1
          )
        }
        
        // 推送到监控Actor
        monitorActor ! MonitoringActor.RecordUpdate(record, success)
        
        running(ctx, monitorActor, config, killSwitch, newStatus)
      
      case UpdateMetrics(successDelta, failedDelta, rate) =>
        val newStatus = status.copy(
          totalRecords = status.totalRecords + successDelta + failedDelta,
          successCount = status.successCount + successDelta,
          failedCount = status.failedCount + failedDelta,
          currentRate = rate
        )
        
        // 推送指标到监控Actor
        monitorActor ! MonitoringActor.MetricsUpdate(
          totalRecords = newStatus.totalRecords,
          successCount = newStatus.successCount,
          failedCount = newStatus.failedCount,
          rate = rate
        )
        
        running(ctx, monitorActor, config, killSwitch, newStatus)
      
      case GetStatus(replyTo) =>
        replyTo ! status
        Behaviors.same
      
      case _ =>
        Behaviors.same
    }
  }
  
  /**
   * 启动同步Pipeline
   */
  private def startSyncPipeline(
    ctx: ActorContext[Command],
    config: MySQLSyncConfig,
    monitorActor: ActorRef[MonitoringActor.Command]
  )(implicit mat: Materializer): KillSwitch = {
    
    import org.apache.pekko.stream.scaladsl.Keep
    import org.apache.pekko.stream.KillSwitches
    import scala.concurrent.duration._
    
    val sourceConnector = new MySQLSourceConnector()
    val sinkConnector = new MySQLSinkConnector()
    
    // 创建Source
    val source = sourceConnector.createSource(config.sourceConfig)
    
    // 创建Sink
    val sink = sinkConnector.createSink(config.sinkConfig)
    
    // 构建Pipeline
    val (killSwitch, future) = source
      // 1. 字段映射（如果需要）
      .map { record =>
        applyFieldMapping(record, config.fieldMapping)
      }
      
      // 2. 批量处理（提升性能）
      .grouped(config.batchSize.getOrElse(1000))
      
      // 3. 统计指标（每秒）
      .groupedWithin(Int.MaxValue, 1.second)
      .map { batch =>
        val successCount = batch.size
        val rate = successCount.toDouble
        
        ctx.self ! UpdateMetrics(successCount, 0, rate)
        batch.flatten
      }
      .mapConcat(identity)
      
      // 4. 实时上报每条数据
      .map { record =>
        ctx.self ! RecordSynced(record, success = true)
        record
      }
      
      // 5. 错误处理
      .recover {
        case ex: Exception =>
          ctx.log.error("Sync error", ex)
          // 返回空记录，标记为失败
          DataRecord.empty
      }
      
      // 6. KillSwitch（用于优雅停止）
      .viaMat(KillSwitches.single)(Keep.right)
      
      // 7. 写入目标
      .toMat(sink)(Keep.both)
      .run()
    
    // 处理完成
    future._2.onComplete { result =>
      ctx.self ! Stop()
    }(ctx.executionContext)
    
    killSwitch
  }
  
  /**
   * 字段映射
   */
  private def applyFieldMapping(
    record: DataRecord,
    mapping: Map[String, String]
  ): DataRecord = {
    if (mapping.isEmpty) {
      record
    } else {
      val newValues = mapping.map { case (sourceField, targetField) =>
        targetField -> record.values.get(sourceField)
      }
      record.copy(values = newValues)
    }
  }
}

/**
 * 同步配置
 */
case class MySQLSyncConfig(
  jobName: String,
  sourceConfig: MySQLSourceConfig,
  sinkConfig: MySQLSinkConfig,
  fieldMapping: Map[String, String] = Map.empty,  // source -> target字段映射
  batchSize: Option[Int] = Some(1000)
)

case class MySQLSourceConfig(
  host: String,
  port: Int = 3306,
  database: String,
  table: String,
  username: String,
  password: String,
  query: Option[String] = None,  // 自定义SQL查询
  fetchSize: Int = 1000
)

case class MySQLSinkConfig(
  host: String,
  port: Int = 3306,
  database: String,
  table: String,
  username: String,
  password: String,
  writeMode: String = "insert",  // insert | upsert
  batchSize: Int = 1000
)
```

---

### 2. 实时监控Actor（推送到WebSocket）

```scala
package cn.xuyinyin.pekkosync.monitoring

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete
import spray.json._

object MonitoringActor {
  
  sealed trait Command
  
  // 接收来自SyncJobActor的更新
  case class RecordUpdate(record: DataRecord, success: Boolean) extends Command
  case class MetricsUpdate(
    totalRecords: Long,
    successCount: Long,
    failedCount: Long,
    rate: Double
  ) extends Command
  
  // WebSocket连接管理
  case class RegisterClient(
    clientId: String,
    queue: SourceQueueWithComplete[String]
  ) extends Command
  case class UnregisterClient(clientId: String) extends Command
  
  def apply(): Behavior[Command] = {
    running(Map.empty)
  }
  
  private def running(
    clients: Map[String, SourceQueueWithComplete[String]]
  ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case RegisterClient(clientId, queue) =>
        println(s"WebSocket client connected: $clientId")
        running(clients + (clientId -> queue))
      
      case UnregisterClient(clientId) =>
        println(s"WebSocket client disconnected: $clientId")
        running(clients - clientId)
      
      case RecordUpdate(record, success) =>
        // 推送单条数据到所有WebSocket客户端
        val message = RecordMessage(
          messageType = "record",
          record = record,
          success = success,
          timestamp = System.currentTimeMillis()
        ).toJson.compactPrint
        
        broadcast(clients, message)
        Behaviors.same
      
      case MetricsUpdate(total, success, failed, rate) =>
        // 推送统计指标
        val message = MetricsMessage(
          messageType = "metrics",
          totalRecords = total,
          successCount = success,
          failedCount = failed,
          rate = rate,
          timestamp = System.currentTimeMillis()
        ).toJson.compactPrint
        
        broadcast(clients, message)
        Behaviors.same
    }
  }
  
  private def broadcast(
    clients: Map[String, SourceQueueWithComplete[String]],
    message: String
  ): Unit = {
    clients.values.foreach { queue =>
      queue.offer(message)
    }
  }
}

// WebSocket消息格式
case class RecordMessage(
  messageType: String,
  record: DataRecord,
  success: Boolean,
  timestamp: Long
)

case class MetricsMessage(
  messageType: String,
  totalRecords: Long,
  successCount: Long,
  failedCount: Long,
  rate: Double,
  timestamp: Long
)

// JSON序列化
object MonitoringJsonProtocol extends DefaultJsonProtocol {
  implicit val dataRecordFormat = jsonFormat3(DataRecord.apply)
  implicit val recordMessageFormat = jsonFormat4(RecordMessage.apply)
  implicit val metricsMessageFormat = jsonFormat6(MetricsMessage.apply)
}
```

---

### 3. WebSocket路由

```scala
package cn.xuyinyin.pekkosync.api

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.stream.scaladsl.{Source, Flow, Sink}
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.actor.typed.ActorRef
import java.util.UUID

class MonitoringWebSocketRoutes(
  monitoringActor: ActorRef[MonitoringActor.Command]
)(implicit mat: Materializer) {
  
  val routes = path("ws" / "monitor") {
    get {
      handleWebSocketMessages(monitorWebSocketFlow())
    }
  }
  
  private def monitorWebSocketFlow(): Flow[Message, Message, Any] = {
    val clientId = UUID.randomUUID().toString
    
    // 创建Source队列（用于推送消息到客户端）
    val (queue, source) = Source
      .queue[String](bufferSize = 100, OverflowStrategy.dropHead)
      .preMaterialize()
    
    // 注册客户端
    monitoringActor ! MonitoringActor.RegisterClient(clientId, queue)
    
    // 输入流（接收客户端消息，暂时忽略）
    val incomingMessages: Sink[Message, Any] =
      Sink.foreach[Message] {
        case TextMessage.Strict(text) =>
          println(s"Received from client: $text")
        case _ => // ignore
      }
    
    // 输出流（推送到客户端）
    val outgoingMessages: Source[Message, Any] =
      source
        .map(text => TextMessage(text))
        .watchTermination() { (_, done) =>
          done.onComplete { _ =>
            // 连接断开时注销
            monitoringActor ! MonitoringActor.UnregisterClient(clientId)
          }(mat.executionContext)
        }
    
    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }
}
```

---

### 4. REST API路由

```scala
package cn.xuyinyin.pekkosync.api

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import scala.concurrent.duration._
import spray.json._
import DefaultJsonProtocol._

class SyncJobRoutes(
  syncJobActor: ActorRef[SyncJobActor.Command]
)(implicit system: ActorSystem[_]) {
  
  implicit val timeout: Timeout = 5.seconds
  import system.executionContext
  
  val routes = pathPrefix("api" / "v1" / "sync") {
    concat(
      // 创建并启动同步任务
      path("jobs" / "start") {
        post {
          entity(as[MySQLSyncConfig]) { config =>
            syncJobActor ! SyncJobActor.Start(config)
            complete(StatusCodes.OK -> "Sync job started")
          }
        }
      },
      
      // 停止同步任务
      path("jobs" / "stop") {
        post {
          syncJobActor ! SyncJobActor.Stop()
          complete(StatusCodes.OK -> "Sync job stopped")
        }
      },
      
      // 查询任务状态
      path("jobs" / "status") {
        get {
          val statusFuture = syncJobActor.ask(SyncJobActor.GetStatus)
          complete(statusFuture)
        }
      }
    )
  }
}

// JSON序列化
object SyncJobJsonProtocol extends DefaultJsonProtocol {
  implicit val mysqlSourceConfigFormat = jsonFormat8(MySQLSourceConfig.apply)
  implicit val mysqlSinkConfigFormat = jsonFormat7(MySQLSinkConfig.apply)
  implicit val mysqlSyncConfigFormat = jsonFormat5(MySQLSyncConfig.apply)
  implicit val syncStatusFormat = jsonFormat7(SyncJobActor.SyncStatus.apply)
}
```

---

## 🎨 前端实时监控界面

### React组件设计

```typescript
// src/pages/MySQLSyncMonitor.tsx

import React, { useState, useEffect, useRef } from 'react';
import { Card, Statistic, Row, Col, Table, Tag, Progress } from 'antd';
import { Line } from '@ant-design/charts';

interface DataRecord {
  schema: any;
  values: Record<string, any>;
  metadata: any;
}

interface RecordMessage {
  messageType: 'record';
  record: DataRecord;
  success: boolean;
  timestamp: number;
}

interface MetricsMessage {
  messageType: 'metrics';
  totalRecords: number;
  successCount: number;
  failedCount: number;
  rate: number;
  timestamp: number;
}

type WebSocketMessage = RecordMessage | MetricsMessage;

export const MySQLSyncMonitor: React.FC = () => {
  const [metrics, setMetrics] = useState({
    totalRecords: 0,
    successCount: 0,
    failedCount: 0,
    rate: 0,
  });
  
  const [recentRecords, setRecentRecords] = useState<DataRecord[]>([]);
  const [rateHistory, setRateHistory] = useState<Array<{time: string, rate: number}>>([]);
  
  const wsRef = useRef<WebSocket | null>(null);
  
  useEffect(() => {
    // 连接WebSocket
    const ws = new WebSocket('ws://localhost:8080/ws/monitor');
    
    ws.onopen = () => {
      console.log('WebSocket connected');
    };
    
    ws.onmessage = (event) => {
      const message: WebSocketMessage = JSON.parse(event.data);
      
      if (message.messageType === 'record') {
        // 收到单条数据
        const recordMsg = message as RecordMessage;
        
        // 更新最近记录列表（保留最新100条）
        setRecentRecords(prev => {
          const newRecords = [recordMsg.record, ...prev];
          return newRecords.slice(0, 100);
        });
        
      } else if (message.messageType === 'metrics') {
        // 收到统计指标
        const metricsMsg = message as MetricsMessage;
        
        setMetrics({
          totalRecords: metricsMsg.totalRecords,
          successCount: metricsMsg.successCount,
          failedCount: metricsMsg.failedCount,
          rate: metricsMsg.rate,
        });
        
        // 更新速率历史（用于图表）
        setRateHistory(prev => {
          const time = new Date(metricsMsg.timestamp).toLocaleTimeString();
          const newHistory = [...prev, { time, rate: metricsMsg.rate }];
          return newHistory.slice(-60); // 保留最近60秒
        });
      }
    };
    
    ws.onerror = (error) => {
      console.error('WebSocket error:', error);
    };
    
    ws.onclose = () => {
      console.log('WebSocket disconnected');
    };
    
    wsRef.current = ws;
    
    return () => {
      ws.close();
    };
  }, []);
  
  // 成功率
  const successRate = metrics.totalRecords > 0
    ? (metrics.successCount / metrics.totalRecords * 100).toFixed(2)
    : 0;
  
  return (
    <div style={{ padding: 24 }}>
      <h1>MySQL 同步实时监控</h1>
      
      {/* 统计卡片 */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="总记录数"
              value={metrics.totalRecords}
              valueStyle={{ color: '#3f8600' }}
            />
          </Card>
        </Col>
        
        <Col span={6}>
          <Card>
            <Statistic
              title="成功"
              value={metrics.successCount}
              valueStyle={{ color: '#52c41a' }}
              suffix={
                <Tag color="success">{successRate}%</Tag>
              }
            />
          </Card>
        </Col>
        
        <Col span={6}>
          <Card>
            <Statistic
              title="失败"
              value={metrics.failedCount}
              valueStyle={{ color: '#cf1322' }}
            />
          </Card>
        </Col>
        
        <Col span={6}>
          <Card>
            <Statistic
              title="同步速率"
              value={metrics.rate.toFixed(0)}
              suffix="行/秒"
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
      </Row>
      
      {/* 进度条 */}
      <Card title="同步进度" style={{ marginBottom: 24 }}>
        <Progress
          percent={parseFloat(successRate)}
          status="active"
          strokeColor={{
            '0%': '#108ee9',
            '100%': '#87d068',
          }}
        />
      </Card>
      
      {/* 速率图表 */}
      <Card title="同步速率趋势" style={{ marginBottom: 24 }}>
        <Line
          data={rateHistory}
          xField="time"
          yField="rate"
          smooth
          height={200}
          xAxis={{
            label: {
              autoRotate: true,
              autoHide: true,
            }
          }}
          yAxis={{
            title: {
              text: '行/秒',
            }
          }}
        />
      </Card>
      
      {/* 实时数据流（滚动显示） */}
      <Card title="实时数据流（最新100条）">
        <div style={{ maxHeight: 400, overflow: 'auto' }}>
          <Table
            dataSource={recentRecords}
            pagination={false}
            size="small"
            rowKey={(_, index) => index}
            columns={[
              {
                title: '时间',
                dataIndex: ['metadata', 'timestamp'],
                width: 180,
                render: (ts: number) => new Date(ts).toLocaleString(),
              },
              {
                title: '数据',
                dataIndex: 'values',
                render: (values: Record<string, any>) => (
                  <pre style={{ margin: 0, fontSize: 12 }}>
                    {JSON.stringify(values, null, 2)}
                  </pre>
                ),
              },
            ]}
          />
        </div>
      </Card>
    </div>
  );
};
```

---

## 🚀 MVP实现步骤

### Week 1: 核心功能

#### Day 1-2: MySQL连接器

```bash
# 创建项目结构
mkdir -p sync-core/src/main/scala/cn/xuyinyin/pekkosync/{sync,monitoring,connector}

# 实现MySQLSourceConnector（使用HikariCP）
# 实现MySQLSinkConnector（批量写入）
```

#### Day 3-4: 同步Actor

```bash
# 实现SyncJobActor
# 实现MonitoringActor
# 集成Pekko Streams Pipeline
```

#### Day 5-6: WebSocket监控

```bash
# 实现WebSocket路由
# 实现REST API
# 测试实时推送
```

#### Day 7: 前端界面

```bash
# 实现React监控组件
# WebSocket连接
# 实时数据展示
```

---

## 🧪 测试场景

### 场景1: 小数据集（1000行）

```sql
-- 源表
CREATE TABLE source_users (
  id INT PRIMARY KEY,
  name VARCHAR(100),
  email VARCHAR(100),
  created_at TIMESTAMP
);

-- 目标表（相同结构）
CREATE TABLE target_users (
  id INT PRIMARY KEY,
  name VARCHAR(100),
  email VARCHAR(100),
  created_at TIMESTAMP
);
```

**预期**:
- 界面实时显示1000条数据
- 每条数据都能在表格中看到
- 统计准确（1000成功，0失败）

---

### 场景2: 中等数据集（10万行）

**预期**:
- 速率图表显示实时吞吐量
- 最近100条数据滚动显示
- 成功率统计准确

---

### 场景3: 字段映射

```json
{
  "fieldMapping": {
    "id": "user_id",
    "name": "user_name",
    "email": "user_email"
  }
}
```

**预期**:
- 数据正确映射
- 界面显示映射后的字段名

---

## 📊 性能目标（MVP）

| 指标 | 目标 |
|------|------|
| 吞吐量 | 5000-10000 行/秒 |
| WebSocket延迟 | < 100ms |
| 内存占用 | < 1GB |
| CPU占用 | < 50% (4核) |

---

## ✅ MVP验收标准

### 功能验收

- [ ] 能成功连接源MySQL和目标MySQL
- [ ] 能读取源表全部数据
- [ ] 能写入目标表（insert模式）
- [ ] 界面能实时看到每条同步的数据
- [ ] 统计指标准确（总数、成功、失败、速率）
- [ ] 速率图表实时更新
- [ ] 错误能被捕获并显示

### 性能验收

- [ ] 10万行数据 < 20秒同步完成
- [ ] WebSocket推送延迟 < 100ms
- [ ] 内存稳定（无泄漏）

---

## 🎯 下一步（MVP后）

### Version 1.1

- ✅ Upsert模式（INSERT ON DUPLICATE KEY UPDATE）
- ✅ 断点续传（保存Offset）
- ✅ 错误重试（失败重试3次）

### Version 1.2

- ✅ PostgreSQL连接器
- ✅ CDC实时增量（Debezium）

### Version 1.3

- ✅ 多任务并行
- ✅ 任务调度（Cron）

---

**这就是PekkoSync MVP方案！专注MySQL→MySQL，实时监控每条数据！** 🚀
