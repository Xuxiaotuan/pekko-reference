# 🎯 工作流API使用指南

## 工作流管理

### 1. 创建简单工作流（随机数→控制台）

```bash
curl -X POST http://localhost:8080/api/v1/workflows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "随机数测试",
    "nodes": [
      {
        "id": "source_1",
        "nodeType": "random.numbers",
        "label": "随机数生成器",
        "config": {
          "count": 100,
          "min": 1,
          "max": 100
        }
      },
      {
        "id": "sink_1",
        "nodeType": "console.log",
        "label": "控制台输出",
        "config": {
          "limit": 100
        }
      }
    ],
    "edges": [
      { "source": "source_1", "target": "sink_1" }
    ]
  }'

# 响应: {"workflowId": "wf_xxx", "message": "Workflow created"}
```

### 2. 执行工作流

```bash
curl -X POST http://localhost:8080/api/v1/workflows/{workflowId}/execute

# 执行日志:
# 开始执行工作流: 随机数测试
# 验证通过: 2个节点, 1条边
#   - Source节点: 1 个 (random.numbers)
#   - Sink节点: 1 个 (console.log)
# 节点执行顺序: source_1 -> sink_1
# 数据源: source_1 (random.numbers)
# 生成随机数: 100 个 (范围: 1-100)
# [输出100行随机数...]
# 工作流执行成功完成 (耗时: 23ms) ⚡️
```

### 3. 查询工作流列表

```bash
curl http://localhost:8080/api/v1/workflows

# 响应: 所有工作流列表
```

### 4. 查询工作流状态

```bash
curl http://localhost:8080/api/v1/workflows/{workflowId}

# 响应示例:
# {
#   "id": "wf_xxx",
#   "name": "随机数测试",
#   "status": "completed",
#   "nodes": [...],
#   "edges": [...]
# }
```

### 5. 更新工作流

```bash
curl -X PUT http://localhost:8080/api/v1/workflows/{workflowId} \
  -H "Content-Type: application/json" \
  -d '{
    "name": "更新后的名称",
    "nodes": [...],
    "edges": [...]
  }'
```

### 6. 删除工作流

```bash
curl -X DELETE http://localhost:8080/api/v1/workflows/{workflowId}
```

## 复杂工作流示例

### 示例1：数据过滤和聚合

```bash
# 工作流: 随机数 → 过滤(>50) → 求和 → 控制台
curl -X POST http://localhost:8080/api/v1/workflows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "数据处理流程",
    "nodes": [
      {"id": "s1", "nodeType": "random.numbers", "config": {"count": 100}},
      {"id": "t1", "nodeType": "filter.condition", "config": {"condition": ">50"}},
      {"id": "t2", "nodeType": "aggregate.sum", "config": {}},
      {"id": "k1", "nodeType": "console.log", "config": {}}
    ],
    "edges": [
      {"source": "s1", "target": "t1"},
      {"source": "t1", "target": "t2"},
      {"source": "t2", "target": "k1"}
    ]
  }'
```

### 示例2：文件处理

```bash
# 工作流: CSV文件 → 转换 → 输出文件
curl -X POST http://localhost:8080/api/v1/workflows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "文件处理",
    "nodes": [
      {
        "id": "s1", 
        "nodeType": "file.csv",
        "config": {
          "path": "/data/input.csv",
          "skipHeader": true
        }
      },
      {
        "id": "t1",
        "nodeType": "map.transform",
        "config": {
          "operation": "toUpperCase"
        }
      },
      {
        "id": "k1",
        "nodeType": "file.text",
        "config": {
          "path": "/data/output.txt"
        }
      }
    ],
    "edges": [
      {"source": "s1", "target": "t1"},
      {"source": "t1", "target": "k1"}
    ]
  }'
```

### 示例3：SQL查询处理

```bash
# 工作流: SQL查询 → 数据清洗 → MySQL输出
curl -X POST http://localhost:8080/api/v1/workflows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SQL数据处理",
    "nodes": [
      {
        "id": "s1",
        "nodeType": "sql.query",
        "config": {
          "query": "SELECT * FROM users WHERE age > 18",
          "database": "production"
        }
      },
      {
        "id": "t1",
        "nodeType": "data.clean",
        "config": {
          "removeNull": true,
          "trim": true
        }
      },
      {
        "id": "k1",
        "nodeType": "mysql.sink",
        "config": {
          "table": "users_clean",
          "database": "warehouse"
        }
      }
    ],
    "edges": [
      {"source": "s1", "target": "t1"},
      {"source": "t1", "target": "k1"}
    ]
  }'
```

## 支持的节点类型

### Source节点（数据源）

| 节点类型 | 说明 | 配置参数 |
|---------|------|----------|
| `random.numbers` | 随机数生成器 | count, min, max |
| `sequence.numbers` | 序列数字生成器 | start, end, step |
| `file.csv` | CSV文件读取 | path, skipHeader, delimiter |
| `file.text` | 文本文件读取 | path, encoding |
| `memory.collection` | 内存集合 | data |
| `sql.query` | SQL查询 | query, database, connection |
| `kafka.consumer` | Kafka消费者 | topic, groupId, bootstrap |

### Transform节点（数据转换）

| 节点类型 | 说明 | 配置参数 |
|---------|------|----------|
| `filter.condition` | 条件过滤 | condition |
| `map.transform` | 映射转换 | operation, expression |
| `aggregate.sum` | 聚合求和 | field |
| `aggregate.count` | 聚合计数 | - |
| `data.clean` | 数据清洗 | removeNull, trim |
| `data.transform` | 数据转换 | rules |

### Sink节点（数据输出）

| 节点类型 | 说明 | 配置参数 |
|---------|------|----------|
| `console.log` | 控制台输出 | limit |
| `file.text` | 文件输出 | path, append |
| `aggregate.count` | 聚合计数输出 | - |
| `mysql.sink` | MySQL输出 | table, database, batchSize |
| `kafka.producer` | Kafka生产者 | topic, bootstrap |
| `file.transfer` | 文件传输 | source, target |

## 执行日志示例

### 成功执行

```
[WorkflowExecutionEngine] - Starting workflow execution: wf_1763216624811
[WorkflowExecutionEngine] - 开始执行工作流: 随机数测试
[WorkflowExecutionEngine] - 验证工作流定义
[WorkflowExecutionEngine] - 验证通过: 2个节点, 1条边
[WorkflowExecutionEngine]   - Source节点: 1 个 (random.numbers)
[WorkflowExecutionEngine]   - Sink节点: 1 个 (console.log)
[WorkflowExecutionEngine] - 开始构建Pekko Stream执行图
[WorkflowExecutionEngine] - 节点执行顺序: source_1(random.numbers) -> sink_1(console.log)
[WorkflowExecutionEngine] - 数据源: source_1 (random.numbers)
[WorkflowExecutionEngine] - 生成随机数: 100 个 (范围: 1-100)
[输出100行随机数...]
[WorkflowExecutionEngine] - 工作流执行成功完成 (耗时: 23ms) ⚡️
[WorkflowExecutionEngine] - Workflow executed successfully: wf_1763216624811 in 23ms
```

### 性能指标

- ⚡️ 执行速度: 23ms/100条数据
- 📊 流式处理: Pekko Stream自动背压
- ✅ 成功率: 100%
- 🔄 并发支持: 多工作流同时执行

## 错误处理

### 验证失败

```json
{
  "error": "工作流必须至少有一个数据源节点",
  "supportedSourceTypes": ["random.numbers", "file.csv", ...]
}
```

### 节点类型不支持

```json
{
  "error": "不支持的节点类型: unknown.type",
  "supportedTypes": [...]
}
```

### 环路检测

```json
{
  "error": "工作流包含环路",
  "cycle": ["node1", "node2", "node1"]
}
```

## 监控和调试

### 查看集群状态

```bash
curl http://localhost:8080/monitoring/cluster/status
```

### 查看系统指标

```bash
curl http://localhost:8080/monitoring/metrics
```

### 查看健康状态

```bash
curl http://localhost:8080/health
```
