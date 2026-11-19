# 实施任务列表：分布式工作流引擎架构重构

## 任务概述

本任务列表将分布式工作流引擎的实施分解为可执行的步骤。每个任务都包含明确的目标、实施细节和验收标准。

---

## Phase 1: 基础设施准备（Week 1）

- [x] 1. 添加Cluster Sharding依赖和配置
  - 在build.sbt中确认pekko-cluster-sharding依赖
  - 在application.conf中添加Sharding配置
  - 配置分片数量、角色限制、Passivation等参数
  - _Requirements: 3.1, 3.2_

- [x] 2. 创建WorkflowSharding核心组件
  - 创建WorkflowSharding.scala
  - 实现Entity初始化逻辑
  - 配置EntityTypeKey和分片策略
  - 实现默认的哈希分片策略
  - _Requirements: 1.1, 3.2_

- [x] 2.1 编写WorkflowSharding单元测试
  - 测试Entity创建和路由
  - 测试分片策略的确定性
  - 测试消息路由的正确性
  - **Property 1: 工作流分片一致性**
  - **Property 7: 哈希分片确定性**
  - **Validates: Requirements 1.1, 3.2**

- [x] 3. 增强EventSourcedWorkflowActor
  - 添加Initialize命令支持
  - 配置快照策略（每100个事件）
  - 添加RecoveryCompleted信号处理
  - 添加SnapshotCompleted信号处理
  - _Requirements: 2.2_

- [x]* 3.1 编写EventSourcedWorkflowActor恢复测试
  - 测试从快照恢复
  - 测试从事件回放恢复
  - 测试状态完整性
  - **Property 4: 故障转移完整性**
  - **Validates: Requirements 2.2**

---

## Phase 2: WorkflowSupervisor重构（Week 2）

- [x] 4. 重构WorkflowSupervisor为Sharding代理
  - 移除本地Actor引用管理
  - 接收ShardRegion作为构造参数
  - 重构CreateWorkflow使用ShardingEnvelope
  - 重构ExecuteWorkflow使用ShardingEnvelope
  - 重构GetWorkflowStatus使用ShardingEnvelope
  - _Requirements: 1.3, 1.4, 4.1, 4.2, 4.3_

- [ ]* 4.1 编写WorkflowSupervisor路由测试
  - 测试创建工作流的路由
  - 测试执行工作流的路由
  - 测试查询状态的路由
  - **Property 3: 路由透明性**
  - **Validates: Requirements 4.1, 4.2, 4.3**

- [x] 5. 更新PekkoGuardian集成Sharding
  - 在PekkoGuardian中初始化WorkflowSharding
  - 获取ShardRegion引用
  - 将ShardRegion传递给WorkflowSupervisor
  - 保持现有的SchedulerManager集成
  - _Requirements: 1.1_

---

## Phase 3: HTTP API适配（Week 2）

- [x] 6. 重构WorkflowRoutes使用Sharding
  - 更新POST /api/v1/workflows使用ShardingEnvelope
  - 更新POST /api/v1/workflows/{id}/execute使用ShardingEnvelope
  - 更新GET /api/v1/workflows/{id}/status使用ShardingEnvelope
  - 保持响应格式不变
  - _Requirements: 4.1, 4.2, 4.3, 7.1_

- [ ]* 6.1 编写HTTP API集成测试
  - 测试创建工作流API
  - 测试执行工作流API
  - 测试查询状态API
  - 验证响应格式兼容性
  - **Property 16: API兼容性**
  - **Validates: Requirements 7.1**

- [x] 7. 添加超时和错误处理
  - 配置合理的Ask超时（5秒）
  - 处理TimeoutException返回明确错误
  - 处理EntityNotFound返回404
  - 添加重试逻辑（可选）
  - _Requirements: 4.5_

---

## Phase 4: 监控和可观测性（Week 3）

- [x] 8. 实现分片监控API
  - 添加GET /api/v1/cluster/shards端点
  - 返回每个Shard的位置和Entity数量
  - 添加GET /api/v1/cluster/stats端点
  - 返回集群总体统计信息
  - _Requirements: 6.1, 6.2, 6.5_

- [ ]* 8.1 编写监控API测试
  - 测试Shard分布查询
  - 测试集群统计查询
  - 测试工作流位置查询
  - **Property 10: 状态查询准确性**
  - **Property 12: 位置查询正确性**
  - **Validates: Requirements 6.1, 6.2, 6.5**

- [x] 9. 添加Prometheus指标
  - 实现workflow_entity_count指标
  - 实现workflow_routing_latency_seconds指标
  - 实现workflow_failover_total指标
  - 实现workflow_rebalance_total指标
  - 暴露/metrics端点
  - _Requirements: 6.1_

- [x] 10. 实现事件记录
  - 记录工作流迁移事件
  - 记录分片再平衡事件
  - 记录故障转移事件
  - 使用结构化日志格式
  - _Requirements: 6.3, 6.4_

---

## Phase 5: 测试和验证（Week 3-4）

- [x] 11. 编写集成测试
  - 测试3节点集群启动
  - 测试工作流创建和分布
  - 测试工作流执行
  - 测试状态查询
  - _Requirements: 1.2, 1.3, 1.4_

- [ ]* 11.1 编写负载均衡属性测试
  - 创建1000个工作流实例
  - 验证分布均匀性（标准差 < 20%）
  - **Property 2: 负载均衡性**
  - **Validates: Requirements 1.2, 5.1**

- [x] 12. 编写故障恢复测试
  - 模拟节点宕机
  - 验证工作流自动迁移
  - 验证状态完整恢复
  - 验证路由自动更新
  - _Requirements: 2.1, 2.2, 2.5_

- [ ]* 12.1 编写故障转移属性测试
  - 随机杀死运行工作流的节点
  - 验证工作流在其他节点重启
  - 验证状态通过Event Sourcing恢复
  - **Property 4: 故障转移完整性**
  - **Property 5: 执行连续性**
  - **Validates: Requirements 2.1, 2.2, 2.4**

- [x] 13. 编写性能测试
  - 测试单节点吞吐量基准
  - 测试3节点吞吐量
  - 验证线性扩展性（至少2.4倍）
  - 测试并发1000个工作流
  - _Requirements: 8.1, 8.2_

- [ ]* 13.1 编写性能属性测试
  - 测试不同节点数量的吞吐量
  - 验证线性扩展性（0.8N倍）
  - 测试工作流数量对延迟的影响
  - **Property 13: 线性扩展性**
  - **Property 14: 性能隔离性**
  - **Validates: Requirements 8.1, 8.3**

---

## Phase 6: 配置和文档（Week 4）

- [x] 14. 完善配置管理
  - 添加环境变量支持
  - 添加配置验证
  - 编写配置文档
  - 提供不同环境的配置示例（dev/test/prod）
  - _Requirements: 3.1, 3.2_

- [x] 15. 编写部署文档
  - 编写单节点部署指南
  - 编写多节点集群部署指南
  - 编写Kubernetes部署指南
  - 编写故障排查指南
  - _Requirements: 所有_

- [x] 16. 编写迁移指南
  - 编写从v0.4到v0.5的迁移步骤
  - 编写数据迁移脚本（如需要）
  - 编写回滚方案
  - 编写常见问题FAQ
  - _Requirements: 7.4_

---

## Phase 7: 灰度发布和切换（Week 5）

- [ ] 17. 实现特性开关
  - 添加workflow.use-sharding配置项
  - 实现双模式支持（旧架构/新架构）
  - 添加运行时切换能力
  - 添加监控和告警
  - _Requirements: 所有_

- [ ] 18. 10%流量灰度测试
  - 配置10%流量使用Sharding
  - 监控关键指标（延迟、错误率、吞吐量）
  - 验证功能正确性
  - 收集性能数据
  - _Requirements: 所有_

- [ ] 19. 50%流量灰度测试
  - 配置50%流量使用Sharding
  - 持续监控关键指标
  - 对比新旧架构性能
  - 验证故障转移能力
  - _Requirements: 所有_

- [ ] 20. 100%流量切换
  - 配置100%流量使用Sharding
  - 监控24小时确保稳定
  - 准备回滚方案
  - 删除旧代码（可选）
  - _Requirements: 所有_

---

## Phase 8: 优化和改进（持续）

- [ ] 21. 性能调优
  - 调优分片数量
  - 调优Passivation配置
  - 调优快照频率
  - 优化网络序列化
  - _Requirements: 8.1, 8.3_

- [ ] 22. 监控和告警完善
  - 配置Grafana仪表板
  - 配置AlertManager告警规则
  - 设置关键指标阈值
  - 编写告警响应手册
  - _Requirements: 6.1, 6.2_

- [ ] 23. 文档和培训
  - 更新README
  - 更新架构文档
  - 编写运维手册
  - 进行团队培训
  - _Requirements: 所有_

---

## 检查点

- [ ] Checkpoint 1: Phase 1-2完成后
  - 确保所有单元测试通过
  - 确保编译无警告
  - 确保基础功能可用
  - 询问用户是否继续

- [ ] Checkpoint 2: Phase 3-4完成后
  - 确保HTTP API正常工作
  - 确保监控指标正常
  - 确保集成测试通过
  - 询问用户是否继续

- [ ] Checkpoint 3: Phase 5完成后
  - 确保所有测试通过
  - 确保性能达标
  - 确保故障恢复正常
  - 询问用户是否进入灰度发布

- [ ] Checkpoint 4: Phase 7完成后
  - 确保100%流量稳定运行
  - 确保无重大问题
  - 确认是否删除旧代码
  - 项目完成

---

## 注意事项

1. **测试优先**：每个功能实现后立即编写测试
2. **增量开发**：每个任务完成后提交代码，不要积累太多变更
3. **向后兼容**：确保每个阶段都不破坏现有功能
4. **监控先行**：在切换流量前确保监控到位
5. **准备回滚**：每个阶段都要有回滚方案

## 预估工作量

- Phase 1: 5天
- Phase 2: 5天
- Phase 3: 3天
- Phase 4: 5天
- Phase 5: 7天
- Phase 6: 3天
- Phase 7: 7天
- Phase 8: 持续

**总计**: 约35天（7周）

## 成功标准

- ✅ 所有单元测试通过
- ✅ 所有集成测试通过
- ✅ 所有属性测试通过
- ✅ 性能测试达标（线性扩展性 >= 0.8N）
- ✅ 故障恢复测试通过（< 15秒）
- ✅ 100%流量稳定运行7天无重大问题
- ✅ 代码审查通过
- ✅ 文档完整
