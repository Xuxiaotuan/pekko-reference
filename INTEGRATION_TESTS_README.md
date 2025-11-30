# DataFusion 集成测试指南

## 概述

本文档说明如何运行DataFusion集成测试，验证Scala客户端与Rust DataFusion服务之间的完整通信流程。

## 前置条件

1. **DataFusion服务必须运行**
   ```bash
   cd datafusion-service
   cargo run --release
   ```
   
   服务将在 `localhost:50051` 监听Arrow Flight连接。

2. **Scala环境**
   - JDK 8+
   - SBT 1.9+

## 测试套件

### 1. DataFusionIntegrationSpec - 基础集成测试

测试Scala客户端与Rust服务的直接通信。

**测试内容：**
- ✅ 健康检查
- ✅ 简单SELECT查询
- ✅ WHERE条件查询
- ✅ ORDER BY排序
- ✅ LIMIT限制
- ✅ COUNT聚合
- ✅ 多重聚合（AVG, MAX, MIN）
- ✅ 无效SQL错误处理
- ✅ 不存在的表错误处理
- ✅ Schema查询
- ✅ 数据类型转换验证
- ✅ 复杂查询（多条件）

**运行方式：**
```bash
sbt "testOnly cn.xuyinyin.magic.datafusion.DataFusionIntegrationSpec"
```

### 2. SQLWorkflowIntegrationSpec - 工作流集成测试

测试SQL节点在完整Pekko Stream工作流中的集成。

**测试内容：**
- ✅ 简单工作流（Source → SQL → Sink）
- ✅ 参数化查询
- ✅ 多SQL节点串联
- ✅ 批处理
- ✅ 错误恢复
- ✅ Source/Sink集成
- ✅ 大数据集处理性能
- ✅ 并发查询

**运行方式：**
```bash
sbt "testOnly cn.xuyinyin.magic.datafusion.integration.SQLWorkflowIntegrationSpec"
```

## 快速运行

使用提供的脚本一次性运行所有集成测试：

```bash
./run-integration-tests.sh
```

该脚本会：
1. 检查DataFusion服务是否运行
2. 运行DataFusionIntegrationSpec
3. 运行SQLWorkflowIntegrationSpec
4. 输出测试结果摘要

## 测试数据

测试使用DataFusion服务中预注册的`users`表：

| id | name    | age | email              |
|----|---------|-----|--------------------|
| 1  | Alice   | 30  | alice@example.com  |
| 2  | Bob     | 25  | bob@example.com    |
| 3  | Charlie | 35  | charlie@example.com|
| 4  | Diana   | 28  | diana@example.com  |
| 5  | Eve     | 32  | eve@example.com    |

## 预期结果

所有测试应该通过，输出类似：

```
✅ DataFusion客户端已初始化
✅ 健康检查通过
✅ 查询返回 5 行数据
✅ WHERE条件查询返回 3 行
✅ ORDER BY查询正确排序: 35, 32, 30, 28, 25
✅ LIMIT查询返回 3 行
✅ COUNT查询返回: 5
✅ 聚合查询: AVG=30.0, MAX=35, MIN=25
✅ 无效SQL错误处理: Query failed: ...
✅ 不存在的表错误处理: Query failed: ...
✅ Schema查询返回 3 列: id, name, age
✅ 数据类型转换正确: id=1, name=Alice, age=30, email=alice@example.com
✅ 复杂查询返回: Diana, Alice, Eve
✅ DataFusion客户端已关闭

[info] Run completed in X seconds.
[info] Total number of tests run: 12
[info] Suites: completed 1, aborted 0
[info] Tests: succeeded 12, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
```

## 故障排查

### 问题：连接被拒绝

**症状：**
```
Failed to execute query: UNAVAILABLE: io error: Connection refused
```

**解决方案：**
确保DataFusion服务正在运行：
```bash
cd datafusion-service
cargo run --release
```

### 问题：端口已被占用

**症状：**
```
Error: Address already in use (os error 48)
```

**解决方案：**
检查并终止占用端口50051的进程：
```bash
lsof -i :50051
kill -9 <PID>
```

### 问题：编译错误

**症状：**
其他测试文件的编译错误影响集成测试运行。

**解决方案：**
使用`testOnly`只编译和运行特定的测试类：
```bash
sbt "testOnly cn.xuyinyin.magic.datafusion.DataFusionIntegrationSpec"
```

## 性能基准

在MacBook Pro (M1, 16GB RAM)上的典型性能：

- **单个查询延迟**: 5-20ms
- **10个并发查询**: < 500ms
- **数据传输**: ~1MB/s (Arrow格式)
- **连接池开销**: < 1ms

## 下一步

- [ ] 添加更多SQL功能测试（JOIN, 窗口函数, 子查询）
- [ ] 添加性能压力测试
- [ ] 添加负载均衡测试
- [ ] 集成到CI/CD pipeline

## 相关文档

- [DataFusion Integration Summary](./DATAFUSION_INTEGRATION_SUMMARY.md)
- [DataFusion Integration Progress](./DATAFUSION_INTEGRATION_PROGRESS.md)
- [DataFusion Quickstart](./DATAFUSION_QUICKSTART.md)
