# MySQL批量同步测试进展

> **测试时间**: 2026年3月22日 19:30  
> **目标**: xjwtest → xjwtest1 批量同步

---

## ✅ 已完成

### 1. 数据库信息确认
- 主机: `100.82.226.63:31765`
- 数据库: `test`
- 源表: `xjwtest` (id, name)
- 目标表: `xjwtest1`

### 2. Workflow配置创建
- 文件: `.tasks/mysql-sync-workflow.json`
- 源节点: `mysql.query` ✅
- 目标节点: `mysql.write` ✅  
- 正确格式: 添加了`type`, `position`, edge `id`

### 3. Workflow API测试

#### ✅ 创建Workflow成功
```bash
curl -X POST "http://localhost:8080/api/v1/workflows" \
  -H "Content-Type: application/json" \
  -d @.tasks/mysql-sync-workflow.json

# 响应
{
  "message": "Workflow created successfully via Sharding",
  "scheduleStatus": "Immediate task - execute manually",
  "workflowId": "mysql-xjwtest-sync"
}
```

#### ✅ 启动执行成功
```bash
curl -X POST "http://localhost:8080/api/v1/workflows/mysql-xjwtest-sync/execute"

# 响应
{
  "executionId": "exec_1774179022782",
  "message": "Workflow execution started via Sharding",
  "status": "started",
  "workflowId": "mysql-xjwtest-sync"
}
```

---

## ⚠️ 遇到的问题

### 问题1: 执行历史为空

```bash
curl -s "http://localhost:8080/api/v1/workflows/mysql-xjwtest-sync/executions"
# 返回: []
```

**可能原因**:
1. 执行过程中出错但没有记录
2. Event Sourcing数据未正确持久化
3. API路由问题

**下一步调试**:
- 检查服务端日志
- 直接验证数据库是否有数据同步

---

## 🔍 下一步计划

### 立即调试（今晚）

1. **检查服务日志**
   ```bash
   # 查看Pekko服务日志，找错误信息
   ```

2. **验证数据库**
   ```bash
   # 直接连接MySQL检查数据
   bash .tasks/verify-sync.sh
   ```

3. **简化测试**
   - 先用内置的测试节点验证流程
   - 然后再用真实MySQL连接器

### 调试方向

#### 方向1: 检查MySQL连接器加载
```bash
# 查看启动日志确认
grep "MySQL.*连接器" logs/*.log
```

#### 方向2: 测试简单workflow
创建一个不依赖外部数据库的workflow：
```json
{
  "nodes": [
    {"nodeType": "random.numbers"},  // 内置随机数生成
    {"nodeType": "console.log"}      // 控制台输出
  ]
}
```

#### 方向3: 调试WorkflowExecutionEngine
- 添加更详细的日志
- 检查Stream执行流程
- 验证错误处理

---

## 📋 测试脚本

### 完整测试脚本
- `.tasks/test-mysql-sync.sh` - API测试脚本
- `.tasks/verify-sync.sh` - 数据验证脚本

### 手动测试步骤

```bash
# 1. 创建workflow
curl -X POST "http://localhost:8080/api/v1/workflows" \
  -H "Content-Type: application/json" \
  -d @.tasks/mysql-sync-workflow.json

# 2. 执行workflow
curl -X POST "http://localhost:8080/api/v1/workflows/mysql-xjwtest-sync/execute"

# 3. 查看状态
curl http://localhost:8080/api/v1/workflows/mysql-xjwtest-sync/status | jq .

# 4. 查看执行历史
curl http://localhost:8080/api/v1/workflows/mysql-xjwtest-sync/executions | jq .

# 5. 直接验证数据库
bash .tasks/verify-sync.sh
```

---

## 💡 技术分析

### 当前架构
```
HTTP API
  ↓
WorkflowSupervisor (Cluster Sharding)
  ↓
EventSourcedWorkflowActor
  ↓
WorkflowExecutionEngine
  ↓
NodeRegistry.findSource("mysql.query")
  ↓
MySQLSourceNode (动态注册的真实连接器)
  ↓
Pekko Stream
  ↓
MySQLSinkNode
```

### 可能的断点
1. **WorkflowSupervisor** - 是否正确创建Actor？
2. **WorkflowExecutionEngine** - Stream是否正确执行？
3. **NodeRegistry** - 是否找到真实连接器？
4. **MySQLSourceNode** - 是否成功连接数据库？
5. **MySQLSinkNode** - 是否成功写入数据？

---

## 🎯 预期vs实际

### 预期流程
1. ✅ 创建workflow
2. ✅ 启动执行
3. ❓ Stream读取xjwtest
4. ❓ Stream写入xjwtest1
5. ❌ 记录执行历史

### 实际情况
1. ✅ API响应正常
2. ✅ Execution ID生成
3. ❓ 执行状态未知
4. ❌ 执行历史为空

---

## 📝 今天的成果总结

### 已验证可用
- ✅ MySQL连接器动态注册机制
- ✅ Workflow HTTP API
- ✅ JSON格式正确性

### 需要继续调试
- ⚠️ Workflow执行流程
- ⚠️ 执行历史记录
- ⚠️ 日志输出
- ⚠️ 错误处理

---

## 🚀 明天计划

### 上午：调试和修复
1. 查看服务日志找出执行失败原因
2. 修复执行历史记录问题
3. 添加更详细的日志输出

### 下午：验证和优化
1. 成功完成xjwtest → xjwtest1同步
2. 测试不同数据量（100行、1000行、10000行）
3. 性能测试和优化

### 晚上：开始CDC
1. 研究Debezium集成
2. 设计MySQLCDCSourceNode
3. 创建CDC测试计划

---

**当前状态**: Workflow已创建和启动，但执行情况未知，需要调试。

**建议**: 先通过服务日志和数据库验证确认实际情况，再进行针对性修复。
