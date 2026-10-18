# MySQL批量同步集成测试指南

> **目的**: 验证MySQL批量同步功能的正确性和性能

---

## 📋 测试文件说明

### MySQLBatchSyncIntegrationTest.scala ⭐

**位置**: `pekko-server/src/test/scala/cn/xuyinyin/magic/connectors/mysql/MySQLBatchSyncIntegrationTest.scala`

**特点**:
- ✅ 简单直接，易于理解
- ✅ 快速验证功能
- ✅ 完整的端到端测试
- ✅ 实时输出同步进度

**包含的测试**:
1. **ConnectorLoader测试** - 验证MySQL连接器能被正确加载和注册
2. **读取数据测试** - 验证MySQLSourceNode能正确连接和读取
3. **完整Workflow执行** - 通过WorkflowExecutionEngine执行端到端同步
4. **性能测试** - 测试大量数据同步的吞吐量

**输出示例**:
```
============================================================
测试3: 完整批量同步测试
============================================================
[SYNC] 连接MySQL: 100.82.226.63:31765/test
[SYNC] 执行查询: SELECT id, name FROM xjwtest
📊 已处理: 100 行
📊 已处理: 200 行
...
============================================================
✅ 同步完成！
============================================================
📊 同步行数: 500
⏱️  耗时: 2.5s
🚀 吞吐量: 200 行/秒
============================================================
```

**测试架构**:
```
MySQLBatchSyncIntegrationTest (pekko-server)
    ↓
ConnectorLoader (动态加载)
    ↓
NodeRegistry (查找连接器)
    ↓
WorkflowExecutionEngine (执行引擎)
    ↓
MySQLSourceNode + MySQLSinkNode (pekko-connectors)
    ↓
真实MySQL数据库
```

---

## 🚀 快速开始

### 方式1: 使用测试脚本 ⭐ 推荐

```bash
cd /Users/xujiawei/magic/scala-workbench/pekko-reference

# 运行测试脚本
bash .tasks/run-mysql-tests.sh

# 选择:
# 1 - 快速集成测试 (推荐)
# 2 - 完整单元测试
# 3 - 运行所有测试
```

---

### 方式2: 使用SBT命令

#### 运行完整集成测试（推荐）
```bash
sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest -- -z 执行完整"
```

#### 运行性能测试
```bash
sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest -- -z 性能测试"
```

#### 运行所有MySQL测试
```bash
sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest"
```

#### 运行单个测试用例
```bash
# 只运行连接器加载测试
sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest -- -z 加载MySQL"
```

---

## 📊 测试数据库配置

所有测试使用以下数据库配置：

```scala
host = "100.82.226.63"
port = 31765
database = "test"
username = "root"
password = "asd123456"

源表: xjwtest (id, name)
目标表: xjwtest1 (id, name)
```

---

## ✅ 预期结果

### 成功的测试输出

```
[info] MySQLBatchSyncTest:
[info] - should 连接数据库并读取数据 ✅
[info] - should 写入数据到MySQL ✅
[info] - should 完整执行xjwtest->xjwtest1同步 ✅
[info] Run completed in 15 seconds.
[info] Total number of tests run: 3
[info] Suites: completed 1, aborted 0
[info] Tests: succeeded 3, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
```

---

## 🔍 调试和排错

### 问题1: 数据库连接失败

**症状**: 
```
java.sql.SQLException: Communications link failure
```

**解决**:
1. 检查数据库是否可访问
2. 验证防火墙设置
3. 确认用户名和密码正确

```bash
# 测试数据库连接
mysql -h 100.82.226.63 -P 31765 -u root -pasd123456 test -e "SELECT 1"
```

---

### 问题2: 测试超时

**症状**:
```
java.util.concurrent.TimeoutException
```

**解决**:
1. 增加测试超时时间
2. 检查网络延迟
3. 减少测试数据量

---

### 问题3: 表不存在

**症状**:
```
Table 'test.xjwtest' doesn't exist
```

**解决**:
```bash
# 运行测试数据SQL
mysql -h 100.82.226.63 -P 31765 -u root -pasd123456 test < .tasks/test-data.sql
```

---

## 📝 测试最佳实践

### 1. 每次测试前清理目标表
```sql
TRUNCATE TABLE xjwtest1;
```

### 2. 准备足够的测试数据
```sql
-- 插入1000行测试数据
INSERT INTO xjwtest (name) 
SELECT CONCAT('用户_', seq) 
FROM (SELECT @row := @row + 1 AS seq FROM 
  (SELECT 0 UNION SELECT 1 UNION SELECT 2 UNION SELECT 3) t1,
  (SELECT 0 UNION SELECT 1 UNION SELECT 2 UNION SELECT 3) t2,
  (SELECT @row := 0) r) numbers
LIMIT 1000;
```

### 3. 性能测试
```bash
# 先测试少量数据
sbt "project pekkoConnectors" "testOnly *MySQLBatchSyncTest -- -z 读取"

# 再测试大量数据
sbt "project pekkoConnectors" "testOnly *MySQLBatchSyncTest -- -z 完整"
```

---

## 🎯 下一步

测试通过后，你可以：

1. **验证数据一致性**
   ```bash
   bash .tasks/verify-sync.sh
   ```

2. **性能优化**
   - 调整batchSize参数
   - 调整fetchSize参数
   - 测试不同数据量

3. **开始CDC开发**
   - 集成Debezium
   - 实现实时同步

---

## 📞 需要帮助？

如果测试失败或有疑问：
1. 查看测试输出的详细日志
2. 检查数据库连接和数据
3. 运行单个测试用例定位问题

---

**立即开始测试！** 🚀

```bash
bash .tasks/run-mysql-tests.sh
```
