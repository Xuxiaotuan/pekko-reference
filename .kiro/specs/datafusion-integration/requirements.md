# 需求文档：DataFusion SQL查询节点集成

## 简介

当前工作流引擎支持基本的数据源（Source）、转换（Transform）和数据汇（Sink）节点，但缺乏高性能的SQL查询和数据分析能力。本需求旨在通过集成Apache Arrow DataFusion，为工作流引擎添加强大的SQL查询节点，实现：

- 高性能向量化SQL查询执行
- 零拷贝的Arrow格式数据传输
- 支持复杂的数据转换和聚合
- 可扩展的分布式查询能力

## 术语表

- **DataFusion**: Apache Arrow项目的SQL查询引擎，使用Rust实现，支持向量化执行
- **Arrow Flight**: Apache Arrow的高性能RPC协议，基于gRPC，支持零拷贝数据传输
- **RecordBatch**: Arrow的列式数据批次，是DataFusion的基本数据单元
- **SQLNode**: 新增的工作流节点类型，用于执行SQL查询
- **DataFusion Service**: 独立的Rust服务，提供Arrow Flight接口，执行SQL查询
- **Flight Client**: Scala客户端，通过Arrow Flight协议与DataFusion Service通信
- **向量化执行**: 使用SIMD指令批量处理数据，提高查询性能

## 需求

### 需求 1：DataFusion Service基础架构

**用户故事**：作为系统架构师，我希望有一个独立的DataFusion服务，能够接收SQL查询请求并返回结果，以便与工作流引擎解耦并独立扩展。

#### 验收标准

1. WHEN DataFusion Service启动 THEN 系统应该初始化Arrow Flight Server并监听指定端口
2. WHEN 接收到SQL查询请求 THEN 系统应该使用DataFusion执行查询并返回Arrow RecordBatch
3. WHEN 查询执行失败 THEN 系统应该返回明确的错误信息和错误类型
4. WHEN 服务健康检查 THEN 系统应该返回服务状态和版本信息
5. WHEN 服务关闭 THEN 系统应该优雅地关闭所有连接并清理资源

### 需求 2：Arrow Flight通信协议

**用户故事**：作为工作流引擎开发者，我希望通过标准的Arrow Flight协议与DataFusion Service通信，以便实现高性能的数据传输。

#### 验收标准

1. WHEN 发送SQL查询 THEN 系统应该使用Arrow Flight的DoGet方法传输查询
2. WHEN 接收查询结果 THEN 系统应该以Arrow RecordBatch流的形式接收数据
3. WHEN 传输大数据集 THEN 系统应该支持流式传输，避免内存溢出
4. WHEN 网络连接中断 THEN 系统应该能够检测并返回连接错误
5. WHEN 查询超时 THEN 系统应该取消查询并返回超时错误

### 需求 3：SQL查询节点实现

**用户故事**：作为工作流设计者，我希望在工作流中添加SQL查询节点，能够对数据流执行SQL转换和分析。

#### 验收标准

1. WHEN 创建SQL节点 THEN 系统应该支持配置SQL查询语句和参数
2. WHEN SQL节点接收数据 THEN 系统应该将数据转换为Arrow格式并发送给DataFusion Service
3. WHEN SQL节点执行查询 THEN 系统应该将查询结果转换回Pekko Stream格式
4. WHEN SQL查询包含参数 THEN 系统应该支持参数化查询，防止SQL注入
5. WHEN SQL节点配置无效 THEN 系统应该在工作流验证阶段报错

### 需求 4：数据格式转换

**用户故事**：作为系统开发者，我希望能够在Pekko Stream的String/JSON格式和Arrow RecordBatch格式之间高效转换，以便无缝集成SQL节点。

#### 验收标准

1. WHEN 将JSON数据转换为Arrow THEN 系统应该自动推断Schema或使用预定义Schema
2. WHEN 将Arrow数据转换为JSON THEN 系统应该保持数据类型和精度
3. WHEN 处理嵌套JSON结构 THEN 系统应该支持转换为Arrow的Struct类型
4. WHEN 处理数组类型 THEN 系统应该支持转换为Arrow的List类型
5. WHEN 数据格式不兼容 THEN 系统应该返回明确的转换错误

### 需求 5：连接池和资源管理

**用户故事**：作为系统管理员，我希望Flight Client能够复用连接并管理资源，以便提高性能并避免资源泄漏。

#### 验收标准

1. WHEN 创建Flight Client THEN 系统应该初始化连接池并配置最大连接数
2. WHEN 执行查询 THEN 系统应该从连接池获取连接，使用后归还
3. WHEN 连接空闲超时 THEN 系统应该自动关闭空闲连接
4. WHEN 连接池耗尽 THEN 系统应该等待可用连接或返回超时错误
5. WHEN 系统关闭 THEN 系统应该关闭所有连接并释放资源

### 需求 6：错误处理和重试

**用户故事**：作为工作流运维人员，我希望SQL节点能够处理各种错误情况并自动重试，以便提高系统的可靠性。

#### 验收标准

1. WHEN DataFusion Service不可用 THEN 系统应该返回服务不可用错误
2. WHEN SQL语法错误 THEN 系统应该返回SQL解析错误和错误位置
3. WHEN 查询执行超时 THEN 系统应该取消查询并返回超时错误
4. WHEN 网络临时故障 THEN 系统应该自动重试最多3次
5. WHEN 重试全部失败 THEN 系统应该记录错误并标记工作流执行失败

### 需求 7：性能监控和指标

**用户故事**：作为系统运维人员，我希望能够监控SQL节点的性能指标，以便优化查询和排查问题。

#### 验收标准

1. WHEN SQL节点执行查询 THEN 系统应该记录查询执行时间
2. WHEN 数据传输 THEN 系统应该记录传输的数据量（行数和字节数）
3. WHEN 查询失败 THEN 系统应该记录失败次数和失败原因
4. WHEN 查询Prometheus指标 THEN 系统应该暴露datafusion_query_duration_seconds指标
5. WHEN 查询Prometheus指标 THEN 系统应该暴露datafusion_query_total计数器

### 需求 8：配置和部署

**用户故事**：作为系统部署人员，我希望能够灵活配置DataFusion Service的部署方式，以便适应不同的环境需求。

#### 验收标准

1. WHEN 配置DataFusion Service地址 THEN 系统应该支持通过配置文件或环境变量指定
2. WHEN 配置连接超时 THEN 系统应该支持自定义连接和查询超时时间
3. WHEN 配置TLS THEN 系统应该支持启用TLS加密通信
4. WHEN 部署多个DataFusion Service实例 THEN 系统应该支持负载均衡
5. WHEN DataFusion Service版本升级 THEN 系统应该能够平滑升级不影响工作流执行

### 需求 9：SQL功能支持

**用户故事**：作为数据分析师，我希望SQL节点能够支持常用的SQL功能，以便进行复杂的数据分析。

#### 验收标准

1. WHEN 执行SELECT查询 THEN 系统应该支持投影、过滤、排序、限制等基本操作
2. WHEN 执行聚合查询 THEN 系统应该支持GROUP BY、HAVING、聚合函数（SUM、AVG、COUNT等）
3. WHEN 执行JOIN查询 THEN 系统应该支持INNER JOIN、LEFT JOIN、RIGHT JOIN
4. WHEN 执行窗口函数 THEN 系统应该支持ROW_NUMBER、RANK、LAG、LEAD等窗口函数
5. WHEN 执行子查询 THEN 系统应该支持嵌套子查询和CTE（WITH子句）

### 需求 10：向后兼容性

**用户故事**：作为系统维护者，我希望添加SQL节点不会破坏现有的工作流功能，确保平滑升级。

#### 验收标准

1. WHEN 现有工作流不使用SQL节点 THEN 系统应该正常执行不受影响
2. WHEN DataFusion Service未部署 THEN 不使用SQL节点的工作流应该正常执行
3. WHEN 工作流包含SQL节点但Service不可用 THEN 系统应该在验证阶段报错
4. WHEN 升级到新版本 THEN 现有的工作流定义应该保持兼容
5. WHEN 回滚到旧版本 THEN 不包含SQL节点的工作流应该能够正常执行
