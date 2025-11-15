# Pekko Workflow

一个基于 Pekko Actor 和 Pekko Stream 的分布式工作流引擎学习项目。

**当前版本**: v0.3 | **状态**: 学习探索阶段 | **最后更新**: 2024-11-15

---

## 项目简介

这是一个用于学习和探索现代工作流引擎设计的个人项目。通过实践 Actor 模型、Stream 处理、分布式系统等概念，深入理解工作流编排的核心原理。

### 学习目标

- 理解和实践 Actor 模型在分布式系统中的应用
- 掌握 Pekko Stream 的流式数据处理
- 探索工作流 DSL 的设计与实现
- 学习前后端联调和可视化开发
- 为未来引入 DataFusion 和 Arrow 做技术储备

### 技术栈

- **后端**: Scala 2.13 + Pekko (Actor + Stream + HTTP)
- **前端**: React + React Flow + TypeScript
- **构建**: SBT
- **JDK**: 11+

---

## 快速导航

**快速开始** → [plan/QUICKSTART.md](plan/QUICKSTART.md)  
**API使用** → [plan/API_USAGE.md](plan/API_USAGE.md)  
**开发指南** → [plan/DEVELOPMENT.md](plan/DEVELOPMENT.md)  
**前端使用** → [plan/FRONTEND_GUIDE.md](plan/FRONTEND_GUIDE.md)  

**Actor架构** → [ACTOR_HIERARCHY.md](ACTOR_HIERARCHY.md)  
**性能优化** → [OPTIMIZATION_SUMMARY.md](OPTIMIZATION_SUMMARY.md)  

---

## 系统架构

### 当前架构 (v0.3)

```
┌─────────────────────────────────────────────────────────────┐
│                      Pekko Workflow                         │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  前端层 (React + React Flow)                        │    │
│  │  - 可视化DAG编辑                                    │    │
│  │  - 拖拽式节点连接                                  │    │
│  │  - 实时执行监控                                    │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │ HTTP/REST                       │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  API层 (Pekko HTTP)                                 │    │
│  │  - RESTful API                                       │    │
│  │  - 工作流管理                                        │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │ Actor Messages                  │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  Actor层 (分布式执行)                               │    │
│  │  PekkoGuardian                                       │    │
│  │      ↓                                               │    │
│  │  WorkflowSupervisor                                  │    │
│  │      ↓                                               │    │
│  │  WorkflowActor × N                                   │    │
│  │  - 隔离执行 | 容错恢复 | 并发控制                  │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                 │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  执行引擎 (WorkflowExecutionEngine)                 │    │
│  │  - DAG解析与验证                                    │    │
│  │  - 拓扑排序                                         │    │
│  │  - Stream图构建                                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │ Pekko Stream                    │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  Stream层 (流式处理)                                │    │
│  │  Source → Flow → Flow → Sink                        │    │
│  │  - 自动背压                                         │    │
│  │  - 异步执行                                         │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                 │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  节点层 (可扩展)                                    │    │
│  │  Source(7) + Transform(5) + Sink(5)                 │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

## 已完成功能

### Week 1: 集群基础
- ✅ Pekko Cluster 分布式集群
- ✅ Leader 选举机制
- ✅ 节点健康检查
- ✅ HTTP API 基础框架

### Week 2: 工作流系统
- ✅ WorkflowDSL 定义语言
- ✅ Actor 模型执行系统
  - WorkflowSupervisor (管理所有工作流)
  - WorkflowActor (单个工作流生命周期)
- ✅ WorkflowExecutionEngine 执行引擎
  - DAG 验证与拓扑排序
  - Pekko Stream 图构建
  - 执行时长统计
- ✅ 17种基础节点实现
- ✅ React Flow 可视化编辑器
- ✅ 前后端完整联调

### 支持的节点类型

**Source (数据源)**
- random.numbers - 随机数生成
- sequence.numbers - 序列生成
- file.csv - CSV文件读取
- file.text - 文本文件读取
- memory.collection - 内存数据
- sql.query - SQL查询
- kafka.consumer - Kafka消费

**Transform (转换)**
- filter.condition - 条件过滤
- map.transform - 映射转换
- aggregate.sum - 聚合求和
- data.clean - 数据清洗
- data.transform - 数据转换

**Sink (输出)**
- console.log - 控制台输出
- file.text - 文件输出
- aggregate.count - 计数聚合
- kafka.producer - Kafka生产
- file.transfer - 文件传输

### 性能表现

- 执行延迟: 23ms/100条数据
- 并发: 支持多工作流同时执行
- 内存: ~500MB (JVM)
- 成功率: 100%

---

## 学习路线图

### Phase 1: 基础架构 (已完成 ✅)

**目标**: 理解 Actor 模型和 Stream 处理

- ✅ Pekko Cluster 集群搭建
- ✅ Actor 生命周期管理
- ✅ Stream 流式处理
- ✅ 工作流 DSL 设计
- ✅ 可视化编辑器集成

**收获**:
- 理解了 Actor 隔离执行的优势
- 掌握了 Pekko Stream 的背压机制
- 学会了前后端联调

---

### Phase 2: 功能完善 (进行中 📍)

**目标**: 补充实用节点，完善错误处理

**Week 3-4**: 节点扩展
- [ ] HTTP 请求节点
- [ ] PostgreSQL 读写节点
- [ ] Redis 缓存节点
- [ ] 错误重试机制
- [ ] 单元测试框架

**Week 5-8**: 调度系统
- [ ] Cron 定时调度
- [ ] 工作流执行历史
- [ ] 简单监控面板
- [ ] 实现3个典型场景

**Week 9-12**: 稳定性
- [ ] 错误恢复机制
- [ ] 资源限制
- [ ] Docker 部署
- [ ] 文档完善

---

### Phase 3: 技术探索 (6个月计划)

**目标**: 学习高性能计算引擎

**探索方向**:
- [ ] **DataFusion 学习**
  - Rust 计算引擎集成
  - SQL 查询优化
  - 向量化执行
  
- [ ] **Arrow 数据传输**
  - 零拷贝数据传输
  - 列式存储
  - Arrow IPC 协议

- [ ] **复杂工作流**
  - 条件分支
  - 循环执行
  - 子工作流

**为什么选择这个方向**:
- DataFusion 是现代化的查询引擎，值得学习
- Arrow 的零拷贝理念很有启发性
- Rust + Scala 的混合开发是个有趣的挑战

---

### Phase 4: 深入优化 (12个月计划)

**目标**: 深入理解性能优化

**学习重点**:
- [ ] 分布式调度策略
- [ ] 数据分区与并行
- [ ] 性能监控与分析
- [ ] 智能资源分配

**企业级特性探索**:
- [ ] 权限管理 (RBAC)
- [ ] 多租户设计
- [ ] 审计日志
- [ ] 监控告警

---

## 技术演进思路

### 当前技术栈 (v0.3)
```
后端: Scala + Pekko
前端: React + React Flow
存储: 内存临时存储
```

### 近期计划 (v1.0 - 3个月)
```
+ PostgreSQL (持久化)
+ Redis (缓存)
+ Prometheus + Grafana (监控)
+ 单元测试 (60%覆盖)
```

### 中期探索 (v2.0 - 6个月)
```
+ DataFusion (Rust计算引擎)
+ Arrow (零拷贝传输)
+ 向量化执行
```

### 远期学习 (v3.0 - 12个月)
```
+ 智能调度
+ 性能自动调优
+ 云原生部署
```

---

## 项目结构

```
pekko-reference/
├── pekko-server/                     # 后端服务
│   └── src/main/scala/cn/xuyinyin/magic/
│       ├── core/                     # 核心系统
│       │   ├── cluster/              # 集群管理
│       │   └── config/               # 配置
│       ├── workflow/                 # 工作流系统
│       │   ├── model/                # DSL定义
│       │   ├── actors/               # Actor系统
│       │   ├── engine/               # 执行引擎
│       │   ├── nodes/                # 节点实现
│       │   └── scheduler/            # 调度
│       └── api/                      # HTTP API
│           └── http/routes/
│
├── xxt-ui/                           # 前端
│   ├── src/
│   │   ├── pages/
│   │   └── components/
│   └── package.json
│
├── plan/                             # 文档
│   ├── QUICKSTART.md
│   ├── API_USAGE.md
│   ├── DEVELOPMENT.md
│   └── FRONTEND_GUIDE.md
│
└── docs/
    ├── ACTOR_HIERARCHY.md
    └── OPTIMIZATION_SUMMARY.md
```

---

## 学习笔记

### Week 1-2 收获

**Actor 模型**:
- 理解了 Actor 的隔离性带来的容错优势
- 学会了使用 Supervisor 管理多个 Actor
- 掌握了消息传递的异步编程模式

**Pekko Stream**:
- 理解了背压机制的重要性
- 学会了 Source/Flow/Sink 的组合
- 掌握了流式处理的基本模式

**前端集成**:
- React Flow 的使用
- 前后端通信
- 实时日志展示

**性能优化**:
- 减少了33%的节点遍历
- 使用函数式编程避免可变状态
- 添加了执行时长统计

### 遇到的问题

1. **节点类型判断不一致**
   - 问题: 前端和后端对节点类型的判断不统一
   - 解决: 统一使用 `nodeType` 字段
   - 学到: API 设计要统一，减少歧义

2. **Actor 生命周期管理**
   - 问题: 工作流停止后 Actor 如何清理
   - 解决: 使用 Behavior.stopped
   - 学到: Actor 的生命周期管理很重要

3. **Stream 执行时机**
   - 问题: 何时启动 Stream 执行
   - 解决: 在 Actor 中通过消息触发
   - 学到: 异步编程需要仔细设计

---

## 参考资料

### 学习资源

- [Pekko 官方文档](https://pekko.apache.org/)
- [React Flow 文档](https://reactflow.dev/)
- [DataFusion 文档](https://arrow.apache.org/datafusion/)
- [Apache Arrow 文档](https://arrow.apache.org/)

### 类似项目

- Airflow - Python 工作流平台
- Prefect - 现代化工作流引擎
- Temporal - 分布式编排引擎
- Dagster - 数据编排平台

**为什么重新造轮子**:
- 学习目的，理解核心原理
- 探索 Actor 模型在工作流中的应用
- 实践 Scala + Rust 混合开发
- 学习现代化数据处理技术

---

## 如何参与

这是一个开放的学习项目，欢迎：

- 🐛 提出问题和建议
- 📖 完善文档
- 🔧 贡献代码
- 💡 分享想法

### 贡献指南

1. Fork 项目
2. 创建特性分支
3. 提交代码
4. 发起 Pull Request

详细开发指南：[plan/DEVELOPMENT.md](plan/DEVELOPMENT.md)

---

## 许可证

MIT License - 查看 [LICENSE](LICENSE) 文件

---

## 联系方式

如有问题或建议，欢迎通过 Issue 联系。

---

**当前阶段**: Phase 1 完成，Phase 2 进行中  
**学习进度**: 基础架构 ✅ | 功能完善 📍 | 技术探索 📋  
**最后更新**: 2024-11-15

这是一个持续学习的过程，感谢关注！
