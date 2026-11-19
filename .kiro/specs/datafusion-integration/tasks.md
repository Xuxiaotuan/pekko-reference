# 实施任务列表：DataFusion SQL查询节点集成

## 任务概述

本任务列表将DataFusion集成分解为可执行的步骤。采用Arrow Flight RPC方案，实现高性能的SQL查询节点。每个任务都包含明确的目标、实施细节和验收标准。

---

## Phase 1: DataFusion Service基础 (Week 1)

- [x] 1. 创建DataFusion Service Rust项目
  - 使用Cargo创建新项目datafusion-service
  - 添加依赖：datafusion, arrow-flight, tonic, tokio
  - 配置项目结构：src/main.rs, src/flight_server.rs, src/executor.rs
  - 创建基础的main函数和配置加载
  - _Requirements: 1.1_

- [x] 2. 实现Arrow Flight Server基础框架
  - 实现FlightService trait
  - 实现do_get方法（执行SQL查询）
  - 实现get_flight_info方法（查询元数据）
  - 实现list_flights方法（健康检查）
  - 添加基础的错误处理
  - _Requirements: 1.1, 1.2, 1.4_

- [ ]* 2.1 编写Flight Server单元测试
  - 测试服务启动和端口监听
  - 测试健康检查端点
  - 测试服务关闭和资源清理
  - _Requirements: 1.1, 1.4, 1.5_

- [x] 3. 实现SQL查询执行器
  - 创建QueryExecutor结构体
  - 实现execute_query方法
  - 集成DataFusion SessionContext
  - 实现查询结果到RecordBatch的转换
  - 添加查询超时控制
  - _Requirements: 1.2, 1.3_

- [ ]* 3.1 编写查询执行器单元测试
  - 测试简单SELECT查询
  - 测试查询超时
  - 测试SQL语法错误处理
  - **Property 8: 错误信息明确性**
  - **Validates: Requirements 1.3**

- [x] 4. 实现配置管理
  - 创建ServiceConfig结构体
  - 支持从TOML文件加载配置
  - 支持环境变量覆盖
  - 实现配置验证
  - _Requirements: 8.1, 8.2_

- [x] 5. Checkpoint - 验证DataFusion Service基础功能
  - 确保服务能够启动并监听端口
  - 确保能够执行简单的SQL查询
  - 确保所有单元测试通过
  - 询问用户是否继续

---

## Phase 2: Scala Flight Client实现 (Week 2)

- [x] 6. 添加Arrow Flight依赖
  - 在build.sbt中添加arrow-flight-core依赖
  - 添加arrow-memory-netty依赖
  - 添加grpc-netty依赖
  - 配置Arrow内存分配器
  - _Requirements: 2.1_

- [x] 7. 实现Flight Client基础类
  - 创建FlightClient类
  - 实现连接建立和关闭
  - 实现executeQuery方法
  - 实现RecordBatch流接收
  - 添加超时和错误处理
  - _Requirements: 2.1, 2.2, 2.4, 2.5_

- [ ]* 7.1 编写Flight Client单元测试
  - 测试连接建立
  - 测试查询执行
  - 测试超时处理
  - 测试连接错误处理
  - **Property 1: Arrow Flight查询传输协议一致性**
  - **Validates: Requirements 2.1, 2.2**

- [x] 8. 实现连接池
  - 创建FlightClientPool类
  - 使用Apache Commons Pool实现连接池
  - 配置最大连接数、空闲连接数
  - 实现连接健康检查
  - 实现连接超时和清理
  - _Requirements: 5.1, 5.2, 5.3, 5.5_

- [ ]* 8.1 编写连接池属性测试
  - 测试连接复用
  - 测试最大连接数限制
  - 测试连接泄漏检测
  - **Property 6: 连接池资源管理**
  - **Validates: Requirements 5.2**

- [x] 9. 实现重试机制
  - 创建RetryPolicy对象
  - 实现withRetry方法
  - 配置重试次数和退避策略
  - 区分可重试和不可重试的错误
  - _Requirements: 6.4, 6.5_

- [ ]* 9.1 编写重试机制属性测试
  - 测试网络故障重试
  - 测试重试次数限制
  - 测试不可重试错误
  - **Property 7: 网络故障自动重试**
  - **Validates: Requirements 6.4**

---

## Phase 3: 数据格式转换 (Week 2-3)

- [x] 10. 实现JSON到Arrow转换
  - 创建ArrowConverter对象
  - 实现jsonToRecordBatch方法
  - 实现Schema推断逻辑
  - 支持基本类型：int、long、double、string、boolean
  - 处理null值
  - _Requirements: 4.1_

- [ ]* 10.1 编写Schema推断属性测试
  - 测试基本类型推断
  - 测试null值处理
  - 测试类型冲突处理
  - **Property 5: Schema推断正确性**
  - **Validates: Requirements 4.1**

- [x] 11. 实现Arrow到JSON转换
  - 实现recordBatchToJson方法
  - 保持数据类型和精度
  - 处理特殊值（NaN、Infinity）
  - 优化性能（批量转换）
  - _Requirements: 4.2_

- [ ]* 11.1 编写Round-Trip属性测试
  - 测试JSON -> Arrow -> JSON一致性
  - 测试各种数据类型
  - 测试边界值
  - **Property 2: 数据格式Round-Trip一致性**
  - **Validates: Requirements 4.2**

- [x] 12. 支持复杂类型
  - 支持嵌套JSON（Struct类型）
  - 支持数组（List类型）
  - 支持Map类型
  - 添加类型转换错误处理
  - _Requirements: 4.3, 4.4, 4.5_

- [ ]* 12.1 编写复杂类型单元测试
  - 测试嵌套对象转换
  - 测试数组转换
  - 测试类型不兼容错误
  - **Property 8: 错误信息明确性**
  - **Validates: Requirements 4.5**

---

## Phase 4: SQL节点实现 (Week 3)

- [x] 13. 创建SQL节点配置模型
  - 创建SQLNodeConfig case class
  - 定义配置字段：sql、schema、batchSize、timeout
  - 实现JSON序列化/反序列化
  - 添加配置验证逻辑
  - _Requirements: 3.1, 3.5_

- [ ]* 13.1 编写配置验证属性测试
  - 测试有效配置
  - 测试无效配置（缺少字段、SQL语法错误）
  - 测试配置边界值
  - **Property 3: SQL节点配置有效性**
  - **Validates: Requirements 3.5**

- [x] 14. 实现SQL节点Transform
  - 创建SQLNode类，继承NodeTransform
  - 实现createTransform方法
  - 实现数据批处理逻辑
  - 集成FlightClient执行查询
  - 实现流式数据处理
  - _Requirements: 3.2, 3.3_

- [x] 15. 实现参数化查询
  - 支持SQL参数占位符
  - 实现参数绑定逻辑
  - 防止SQL注入
  - 添加参数类型验证
  - _Requirements: 3.4_

- [ ]* 15.1 编写SQL注入防护属性测试
  - 生成各种SQL注入尝试
  - 验证参数化查询安全性
  - 测试特殊字符处理
  - **Property 4: 参数化查询安全性**
  - **Validates: Requirements 3.4**

- [x] 16. 注册SQL节点到工作流引擎
  - 创建SQLNodeRegistry对象
  - 实现节点注册逻辑
  - 集成到WorkflowExecutionEngine
  - 更新节点类型文档
  - _Requirements: 3.1_

---

## Phase 5: 错误处理和监控 (Week 4)

- [x] 17. 实现异常类型
  - 创建ServiceUnavailableException
  - 创建SQLSyntaxException
  - 创建DataFormatException
  - 创建QueryTimeoutException
  - 添加异常信息和上下文
  - _Requirements: 6.1, 6.2, 6.3_

- [ ]* 17.1 编写错误处理属性测试
  - 测试各种错误场景
  - 验证错误信息明确性
  - 测试错误传播
  - **Property 8: 错误信息明确性**
  - **Validates: Requirements 1.3, 6.2**

- [x] 18. 实现Prometheus指标
  - 创建DataFusionMetrics对象
  - 实现datafusion_query_duration_seconds指标
  - 实现datafusion_query_total计数器
  - 实现datafusion_data_transferred_bytes计数器
  - 实现datafusion_pool_connections状态指标
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [ ]* 18.1 编写监控指标单元测试
  - 测试指标记录
  - 测试指标查询
  - 验证指标准确性
  - **Property 9: 查询执行监控完整性**
  - **Validates: Requirements 7.1, 7.2, 7.3**

- [x] 19. 实现结构化日志
  - 添加查询开始日志
  - 添加查询完成日志（包含duration、rows、bytes）
  - 添加查询失败日志（包含error、error_type）
  - 使用JSON格式
  - _Requirements: 7.1, 7.2, 7.3_

---

## Phase 6: SQL功能测试 (Week 4-5)

- [ ] 20. 实现SELECT操作测试
  - 测试投影（SELECT列）
  - 测试过滤（WHERE条件）
  - 测试排序（ORDER BY）
  - 测试限制（LIMIT）
  - _Requirements: 9.1_

- [ ]* 20.1 编写SELECT属性测试
  - 生成随机数据和查询
  - 验证查询结果正确性
  - 测试各种WHERE条件
  - **Property 11: SQL功能完整性 - SELECT操作**
  - **Validates: Requirements 9.1**

- [ ] 21. 实现聚合操作测试
  - 测试GROUP BY
  - 测试HAVING
  - 测试聚合函数（SUM、AVG、COUNT、MIN、MAX）
  - 测试多列分组
  - _Requirements: 9.2_

- [ ]* 21.1 编写聚合属性测试
  - 生成随机数据和聚合查询
  - 验证聚合结果正确性
  - 测试空结果集
  - **Property 12: SQL功能完整性 - 聚合操作**
  - **Validates: Requirements 9.2**

- [ ] 22. 实现JOIN操作测试
  - 测试INNER JOIN
  - 测试LEFT JOIN
  - 测试RIGHT JOIN
  - 测试多表JOIN
  - _Requirements: 9.3_

- [ ]* 22.1 编写JOIN属性测试
  - 生成随机数据和JOIN查询
  - 验证JOIN结果正确性
  - 测试空表JOIN
  - **Property 13: SQL功能完整性 - JOIN操作**
  - **Validates: Requirements 9.3**

- [ ] 23. 实现窗口函数测试
  - 测试ROW_NUMBER
  - 测试RANK
  - 测试LAG、LEAD
  - 测试PARTITION BY
  - _Requirements: 9.4_

- [ ]* 23.1 编写窗口函数属性测试
  - 生成随机数据和窗口函数查询
  - 验证窗口函数结果正确性
  - 测试边界情况
  - **Property 14: SQL功能完整性 - 窗口函数**
  - **Validates: Requirements 9.4**

- [ ] 24. 实现子查询测试
  - 测试嵌套子查询
  - 测试CTE（WITH子句）
  - 测试相关子查询
  - 测试EXISTS、IN子查询
  - _Requirements: 9.5_

- [ ]* 24.1 编写子查询属性测试
  - 生成随机数据和子查询
  - 验证子查询结果正确性
  - 测试复杂嵌套
  - **Property 15: SQL功能完整性 - 子查询**
  - **Validates: Requirements 9.5**

---

## Phase 7: 集成测试 (Week 5)

- [ ] 25. 编写端到端工作流测试
  - 创建包含SQL节点的完整工作流
  - 测试Source -> SQL -> Sink流程
  - 测试多个SQL节点串联
  - 测试SQL节点与其他节点混合
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 26. 编写DataFusion Service集成测试
  - 测试服务启动和连接
  - 测试查询执行
  - 测试大数据集处理
  - 测试并发查询
  - _Requirements: 1.1, 1.2, 2.3_

- [ ] 27. 编写性能测试
  - 测试10000行数据处理性能
  - 测试100万行大数据集
  - 测试并发查询性能
  - 对比原生Pekko Stream性能
  - _Requirements: 2.3_

- [ ] 28. 编写负载均衡测试
  - 部署多个DataFusion Service实例
  - 测试请求分布均匀性
  - 测试实例故障转移
  - _Requirements: 8.4_

- [ ]* 28.1 编写负载均衡属性测试
  - 生成大量查询请求
  - 验证请求分布均匀性
  - 测试实例动态增减
  - **Property 10: 负载均衡均匀性**
  - **Validates: Requirements 8.4**

---

## Phase 8: 配置和部署 (Week 6)

- [x] 29. 完善配置文件
  - 创建application.conf配置
  - 创建config.toml配置（DataFusion Service）
  - 支持环境变量覆盖
  - 添加配置验证
  - 提供dev/test/prod配置示例
  - _Requirements: 8.1, 8.2, 8.3_

- [x] 30. 创建Docker镜像
  - 创建DataFusion Service Dockerfile
  - 创建Workflow Engine Dockerfile（包含SQL节点）
  - 优化镜像大小
  - 添加健康检查
  - _Requirements: 8.1_

- [x] 31. 创建Docker Compose配置
  - 配置datafusion-service服务
  - 配置workflow-engine服务
  - 配置服务依赖关系
  - 添加健康检查和重启策略
  - _Requirements: 8.1_

- [x] 32. 创建Kubernetes部署配置
  - 创建DataFusion Service Deployment
  - 创建Service和Ingress
  - 配置资源限制
  - 配置健康检查和就绪探针
  - 支持水平扩展（HPA）
  - _Requirements: 8.4_

---

## Phase 9: 文档和示例 (Week 6)

- [ ] 33. 编写用户文档
  - 编写SQL节点使用指南
  - 编写配置说明
  - 编写部署指南
  - 编写故障排查指南
  - 提供完整的工作流示例
  - _Requirements: 所有_

- [ ] 34. 编写API文档
  - 文档化SQL节点配置格式
  - 文档化支持的SQL功能
  - 文档化错误码和错误信息
  - 提供API使用示例
  - _Requirements: 所有_

- [ ] 35. 创建示例工作流
  - 创建简单的数据过滤示例
  - 创建数据聚合示例
  - 创建数据JOIN示例
  - 创建复杂的数据分析示例
  - _Requirements: 9.1, 9.2, 9.3_

- [ ] 36. 编写性能调优指南
  - 文档化批处理大小调优
  - 文档化连接池配置
  - 文档化内存配置
  - 提供性能基准数据
  - _Requirements: 8.1, 8.2_

---

## Phase 10: 向后兼容性验证 (Week 7)

- [ ] 37. 测试现有工作流兼容性
  - 运行所有现有的工作流测试
  - 验证不使用SQL节点的工作流正常运行
  - 验证API兼容性
  - _Requirements: 10.1, 10.2_

- [ ] 38. 测试可选依赖
  - 测试DataFusion Service未部署时的行为
  - 验证不使用SQL节点的工作流不受影响
  - 验证使用SQL节点的工作流在验证阶段报错
  - _Requirements: 10.2, 10.3_

- [ ] 39. 创建迁移指南
  - 编写从v0.5到v0.6的迁移步骤
  - 编写DataFusion Service部署指南
  - 编写SQL节点迁移示例
  - 编写回滚方案
  - _Requirements: 10.4, 10.5_

- [ ] 40. Final Checkpoint - 完整验证
  - 确保所有测试通过
  - 确保文档完整
  - 确保部署配置正确
  - 确保向后兼容性
  - 项目完成

---

## 检查点

- [ ] Checkpoint 1: Phase 1-2完成后
  - 确保DataFusion Service能够启动并执行查询
  - 确保Flight Client能够连接并执行查询
  - 确保所有单元测试通过
  - 询问用户是否继续

- [ ] Checkpoint 2: Phase 3-4完成后
  - 确保数据格式转换正常工作
  - 确保SQL节点能够集成到工作流
  - 确保Round-Trip测试通过
  - 询问用户是否继续

- [ ] Checkpoint 3: Phase 5-6完成后
  - 确保错误处理完善
  - 确保监控指标正常
  - 确保所有SQL功能测试通过
  - 询问用户是否继续

- [ ] Checkpoint 4: Phase 7-8完成后
  - 确保集成测试通过
  - 确保性能达标
  - 确保部署配置正确
  - 询问用户是否进入文档阶段

- [ ] Checkpoint 5: Phase 9-10完成后
  - 确保文档完整
  - 确保向后兼容性
  - 确保所有验收标准满足
  - 项目完成

---

## 注意事项

1. **Rust开发**: 需要熟悉Rust语言和异步编程
2. **Arrow生态**: 需要理解Arrow内存模型和数据格式
3. **测试优先**: 每个功能实现后立即编写测试
4. **增量开发**: 每个任务完成后提交代码
5. **性能监控**: 持续关注性能指标

## 预估工作量

- Phase 1: 5天 (DataFusion Service基础)
- Phase 2: 5天 (Scala Flight Client)
- Phase 3: 5天 (数据格式转换)
- Phase 4: 5天 (SQL节点实现)
- Phase 5: 3天 (错误处理和监控)
- Phase 6: 5天 (SQL功能测试)
- Phase 7: 5天 (集成测试)
- Phase 8: 3天 (配置和部署)
- Phase 9: 3天 (文档和示例)
- Phase 10: 3天 (向后兼容性验证)

**总计**: 约42天（8-9周）

## 成功标准

- ✅ 所有单元测试通过
- ✅ 所有属性测试通过
- ✅ 所有集成测试通过
- ✅ SQL功能完整（SELECT、聚合、JOIN、窗口函数、子查询）
- ✅ 性能提升10倍以上（相比纯Scala实现）
- ✅ 向后兼容性保持
- ✅ 文档完整
- ✅ 部署配置正确

## 技术栈

**Rust侧**:
- DataFusion 0.40+
- Arrow Flight 50.0+
- Tonic 0.11+
- Tokio 1.35+

**Scala侧**:
- Arrow Java 15.0+
- Pekko Streams 1.0+
- Apache Commons Pool 2.12+

**部署**:
- Docker
- Docker Compose
- Kubernetes
- Prometheus + Grafana
