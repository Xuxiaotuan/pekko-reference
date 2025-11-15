# 🚀 Pekko Workflow

基于 **Pekko Actor + Pekko Stream** 的分布式工作流引擎。支持可视化DAG编辑、Actor模型隔离执行、流式数据处理。

**当前版本**: v0.3 | **状态**: Week 2完成 ✅ | **最后更新**: 2024-11-15

---

## 📋 快速导航

**快速开始** → [plan/QUICKSTART.md](plan/QUICKSTART.md)  
**API使用** → [plan/API_USAGE.md](plan/API_USAGE.md)  
**开发指南** → [plan/DEVELOPMENT.md](plan/DEVELOPMENT.md)  
**前端使用** → [plan/FRONTEND_GUIDE.md](plan/FRONTEND_GUIDE.md)  

**详细进度** → [WEEK2_PROGRESS.md](WEEK2_PROGRESS.md)  
**Actor架构** → [ACTOR_HIERARCHY.md](ACTOR_HIERARCHY.md)  
**性能优化** → [OPTIMIZATION_SUMMARY.md](OPTIMIZATION_SUMMARY.md)  

---

## 🏗️ 系统架构

### 核心架构

```
┌─────────────────────────────────────────────────────────────┐
│                   🚀 Pekko Workflow 架构                    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  🎨 前端层 (React + React Flow)                     │    │
│  │  - 可视化DAG编辑                                     │    │
│  │  - 拖拽式节点连接                                   │    │
│  │  - 实时执行监控                                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │ HTTP/REST                       │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  📡 API层 (Pekko HTTP)                              │    │
│  │  - RESTful API                                       │    │
│  │  - 工作流管理                                        │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │ Actor Messages                  │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  🎭 Actor层                                          │    │
│  │  PekkoGuardian                                       │    │
│  │      ↓                                               │    │
│  │  WorkflowSupervisor (管理所有工作流)                 │    │
│  │      ↓                                               │    │
│  │  WorkflowActor × N (独立生命周期)                    │    │
│  │      ├─ Execute (执行)                               │    │
│  │      ├─ Pause (暂停)                                 │    │
│  │      ├─ Resume (恢复)                                │    │
│  │      └─ Stop (停止)                                  │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                 │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  ⚙️ 执行层 (WorkflowExecutionEngine)                │    │
│  │  1. 验证工作流 (检查节点类型、环路、连接)            │    │
│  │  2. 拓扑排序 (确定执行顺序)                          │    │
│  │  3. 构建执行图                                       │    │
│  │     ├─ Source节点  → Pekko Stream Source            │    │
│  │     ├─ Transform节点 → Pekko Stream Flow             │    │
│  │     └─ Sink节点   → Pekko Stream Sink               │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │ Pekko Stream                    │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  🌊 Stream层 (Pekko Stream)                         │    │
│  │  Source → Flow → Flow → ... → Sink                  │    │
│  │  - 自动背压 (Back-pressure)                          │    │
│  │  - 流式处理 (Streaming)                              │    │
│  │  - 异步执行 (Async)                                  │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                 │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  📦 节点层 (可扩展实现)                              │    │
│  │  - Source: 7种数据源                                 │    │
│  │  - Transform: 5种转换                                │    │
│  │  - Sink: 5种输出                                     │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘

数据流向: 用户 → 前端 → API → Actor → 执行引擎 → Stream → 节点
```

### 技术栈

| 层级 | 核心技术 | 状态 | 说明 |
|------|----------|------|------|
| 前端层 | React + React Flow | ✅ 已实现 | 可视化编辑器 |
| API层 | Pekko HTTP | ✅ 已实现 | RESTful API |
| Actor层 | Pekko Actor | ✅ 已实现 | 工作流生命周期管理 |
| 执行层 | WorkflowExecutionEngine | ✅ 已实现 | DAG解析与编排 |
| Stream层 | Pekko Stream | ✅ 已实现 | 流式数据处理 |
| 节点层 | Source/Transform/Sink | ✅ 已实现 | 17种节点类型 |

### 项目结构

```
pekko-reference/
├── pekko-server/                     # 后端服务
│   └── src/main/scala/cn/xuyinyin/magic/
│       ├── core/                     # 核心系统 ⭐
│       │   ├── cluster/              # 集群管理
│       │   └── config/               # 配置管理
│       ├── workflow/                 # 工作流系统 ⭐⭐⭐
│       │   ├── model/                # WorkflowDSL
│       │   ├── actors/               # WorkflowSupervisor + WorkflowActor
│       │   ├── engine/               # 执行引擎 + Executors
│       │   ├── nodes/                # 节点实现 (Source/Transform/Sink)
│       │   └── scheduler/            # 调度系统
│       └── api/                      # HTTP API ⭐
│           └── http/routes/          # WorkflowRoutes
├── xxt-ui/                           # 前端 ⭐
│   ├── src/pages/                    # React页面
│   └── src/components/               # 组件
├── plan/                             # 详细文档 📚
│   ├── QUICKSTART.md                 # 快速开始
│   ├── API_USAGE.md                  # API使用
│   ├── DEVELOPMENT.md                # 开发指南
│   └── FRONTEND_GUIDE.md             # 前端使用
└── docs/                             # 专题文档
    ├── WEEK2_PROGRESS.md
    ├── ACTOR_HIERARCHY.md
    └── OPTIMIZATION_SUMMARY.md
```

---

## ✅ 已完成功能 (Week 1 + Week 2)

### Week 1: 集群基础
- ✅ Pekko Cluster集群架构
- ✅ PekkoGc组件（全局GC）
- ✅ 多节点支持与Leader选举
- ✅ JDK 11兼容性
- ✅ HTTP API与健康检查
- ✅ 高可用设计

### Week 2: 工作流系统
- ✅ **WorkflowDSL** - 完整的工作流定义语言
- ✅ **Actor模型** - WorkflowSupervisor + WorkflowActor
- ✅ **执行引擎** - DAG解析、拓扑排序、Stream构建
- ✅ **节点系统** - 17种可扩展节点（7 Source + 5 Transform + 5 Sink）
- ✅ **可视化编辑器** - React Flow集成，拖拽式编辑
- ✅ **模块化重构** - 5层清晰架构，20文件重组
- ✅ **性能优化** - 遍历优化33%，函数式风格
- ✅ **前后端联调** - 完整工作流创建-执行-监控闭环

### 支持的节点类型

**Source节点（7种）**
- `random.numbers` - 随机数生成器
- `sequence.numbers` - 序列生成器  
- `file.csv` - CSV文件读取
- `file.text` - 文本文件读取
- `memory.collection` - 内存集合
- `sql.query` - SQL查询
- `kafka.consumer` - Kafka消费者

**Transform节点（5种）**
- `filter.condition` - 条件过滤
- `map.transform` - 映射转换
- `aggregate.sum` - 聚合求和
- `data.clean` - 数据清洗
- `data.transform` - 数据转换

**Sink节点（5种）**
- `console.log` - 控制台输出
- `file.text` - 文件输出
- `aggregate.count` - 聚合计数
- `kafka.producer` - Kafka生产者
- `file.transfer` - 文件传输

### 性能指标

- ⚡️ 执行速度: **23ms/100条数据**
- 📊 流式处理: Pekko Stream自动背压
- ✅ 成功率: **100%**
- 🔄 并发支持: 多工作流同时执行

---

## 🚧 未完成功能 (规划中)

### Week 3: 功能扩展
- [ ] **更多节点类型**
  - HTTP请求节点
  - 数据库读写节点 (PostgreSQL, MongoDB)
  - JSON/XML解析节点
  - 时间窗口聚合节点
  - Redis缓存节点

- [ ] **复杂工作流支持**
  - 多Source合并
  - 条件分支（if-else）
  - 循环执行（for-each）
  - 子工作流调用
  - 并行分支执行

- [ ] **调度系统完善**
  - Cron定时执行
  - 事件触发
  - 依赖触发（上游工作流完成）
  - 手动触发增强

### Week 4: 性能与监控
- [ ] **性能优化**
  - 独立分支并行执行
  - 数据分区并行处理
  - 背压控制优化
  - 资源限制与管理

- [ ] **监控增强**
  - 实时性能指标
  - 数据流量统计
  - 节点执行时长分析
  - 错误率统计
  - Grafana仪表板

- [ ] **错误恢复**
  - 自动重试机制
  - 断点续传
  - 工作流回滚
  - 失败通知

### Week 5: 前端完善
- [ ] **交互优化**
  - 节点图标和动画
  - 批量操作（复制、删除）
  - 撤销/重做栈优化
  - 节点搜索功能

- [ ] **功能增强**
  - 工作流模板库
  - 导入/导出工作流
  - 工作流版本管理
  - 协作功能

- [ ] **监控面板**
  - 实时日志流
  - 性能图表
  - 执行历史
  - 错误追踪

### Week 6+: 企业特性
- [ ] **测试覆盖**
  - 单元测试（目标80%覆盖率）
  - 集成测试
  - 端到端测试
  - 性能基准测试

- [ ] **文档完善**
  - Swagger/OpenAPI文档
  - 用户操作手册
  - 视频教程
  - 最佳实践

- [ ] **企业功能**
  - 权限管理（RBAC）
  - 审计日志
  - 多租户支持
  - 数据加密
  - SSO集成

### 长期规划
- [ ] **分布式增强**
  - 跨集群工作流
  - 数据分区策略
  - 智能负载均衡

- [ ] **智能化**
  - 工作流推荐
  - 性能预测
  - 异常检测
  - 自动调优

- [ ] **生态集成**
  - Spark集成
  - Flink集成
  - Airflow兼容
  - 云原生部署

---

## 📊 项目统计

### 代码量
- **总代码行数**: ~2700+行
- **Scala代码**: ~2200行
- **TypeScript代码**: ~500行
- **文档**: 11篇专题文档

### 架构
- **模块数**: 6个核心模块
- **文件数**: 20+个Scala文件
- **节点类型**: 17种
- **API端点**: 8个

### 性能
- **编译时间**: ~6秒
- **启动时间**: ~5秒
- **执行延迟**: 23ms/100条数据
- **内存占用**: ~500MB (JVM)

---

## 🎯 下一步计划

### 本周重点 (Week 3)

1. **HTTP请求节点** - 支持REST API调用
2. **条件分支** - 支持if-else逻辑
3. **Cron调度** - 定时执行工作流
4. **监控面板** - 实时性能监控

### 近期目标 (1个月)

- 节点类型扩展到30+
- 支持复杂工作流（分支、循环）
- 完善监控和告警系统
- 单元测试覆盖率达到70%

### 长期愿景 (6个月)

- 企业级权限管理
- 多租户支持
- 云原生部署方案
- 智能化工作流推荐

---

## 🤝 贡献指南

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 开启 Pull Request

详细开发指南：[plan/DEVELOPMENT.md](plan/DEVELOPMENT.md)

---

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

---

**开发状态**: Week 2完成 ✅  
**当前版本**: v0.3  
**最后更新**: 2024-11-15

查看详细进度：[WEEK2_PROGRESS.md](WEEK2_PROGRESS.md)
