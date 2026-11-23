# CDC Runtime 可读性重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在不改变 CDC 协议和持久化格式的前提下，降低主流程阅读成本。

**Architecture:** 先提取 Kernel 的 Capture 注册、Table Session 构建和 Runtime 生命周期边界，再用显式状态对象替代 Graph Runtime 的双 Option 模式。Runner 和 Reader 的算法保持原样。

**Tech Stack:** Scala 2.13、Pekko Streams、Pekko Persistence JDBC、MySQL、SBT。

## Global Constraints

- 保留 LOW/HIGH/F、T/J/D、Journal 序列化和 persistenceId。
- 不改变数据库表结构和现有运行入口。
- 保留全部实验材料和失败证据。

## Tasks

### Task 1: 主流程协作者

- [x] 提取连接角色选择和身份校验的窄接口 `CdcJdbcConnections`。
- [x] 提取 Table Session 构建到 `CdcTableSessionFactory`。
- [x] 编译并运行主回归。

### Task 2: Graph 生命周期

- [x] 将 Graph 生命周期判断和物化控制收进 `TableGraphController`。
- [x] 保留 `TableCdcGraphRuntime` 对外接口，避免调用方改动。
- [x] 回归 Graph、恢复和关闭测试。

### Task 3: 命名和阅读路径

- [x] 为 Runner 的状态转换方法补充稳定的阶段命名。
- [x] 为 Graph 使用的 Reader 订阅与 ACK 能力补充 `TableGraphCapture` 窄接口。
- [x] 检查无未使用代码和无关改动。

### Task 4: 最终验证

- [ ] 运行 `pekko-server/Test/test`。
- [ ] 检查 git diff、工作区和 persistence 兼容测试。
- [ ] 记录未重构部分和未验证边界。
