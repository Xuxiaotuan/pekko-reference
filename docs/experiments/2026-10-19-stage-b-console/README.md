# B 阶段：API 契约与最小管理界面

2026-10-19。基于 `dca7d25cfcf7747d7d08231259c3353331755b84` 的现有工作区实施。
A 阶段和前轮未提交工作保留，本轮没有暂存、提交、推送、发布。

已完成的范围是分页摘要、完整 DSL 创建、请求执行、近期历史、已有调度计划的暂停/恢复，以及真实/未知状态展示。
生产后端只改 `ClusterRoutes.scala` 和 `HttpRoutes.scala`：移除固定观测值。
Actor、Scheduler、Engine、CDC Source/Assembler/Bridge/Sink、持久化事件、T/J/D、组账本与 ACK 协议没有修改。

## 接口与页面行为

- API client 保留 AxiosResponse，仅 API 适配层读取 data。列表、摘要、定义提交、执行接受、历史和调度分别使用 DTO。
- 列表使用 `GET /api/v1/workflows?page=…&pageSize=…`，显示 workflowId/revision/status；不伪造总数、总页数或全局排序。
  满页允许下一页，空页/不足一页后禁用下一页。页大小变化回到第一页，取消旧请求并忽略迟到结果。
  刷新失败明确标识旧结果；首次失败与真实空页分别展示。
- `/workflows/new/import` 提交完整 JSON，201 只表示定义已保存；不自动执行或注册调度。
  服务端结构/能力校验错误直接展示。原画布编辑器逐字节归档于 `xxt-ui/examples/legacy`，不参与生产构建。
- 摘要接口仍是摘要；不新增定义读取或更新 API。ID 为 `new` 的合法工作流可以打开 `/workflows/new` 摘要。
- 执行使用真实 `/execute?requestId=…`；202 只显示已接受。明确的重复执行/已有执行 409 分别提示。
  未知响应保留按 workflowId 存储在 sessionStorage 的请求 ID，刷新摘要、页面往返或本标签页重载仍可复用。
  202、明确的执行 409、400/404 后核对并清理该 ID；408、5xx 和网络未知结果保留，不自动重试。
- 近期历史使用 `/api/history/:id`，明确最多最近 50 条已记录执行；不是完整 Journal 审计。
  节点时间、处理量不存在时显示“未记录”，不补成零。执行终态与请求接受分开展示。
- 调度页面只列出现有计划并暂停/恢复；后端已有增删改 API 不变。
  暂停调度不等于停止正在执行的任务。独立调度 API 尚未检查工作流类型，本次没有补此后端约束。
- SQL、通用 DAG、已有定义编辑等入口从导航及可执行路由撤下，直接深链也显示不可用。
  旧 SQL/DAG/TaskMonitor 示例源码仍保留。活跃页面不再调用不存在的 task statistics、node-types、update/delete/logs 或 execute-es。

## 本轮真实验证

| 检查 | 实际结果 | 层级与限制 |
|---|---|---|
| 前端 HTTP 契约与身份测试 | 5/5 通过 | Node 测试调用实际适配层和 loopback HTTP fixture；浏览器存储另有真实 UI 验证 |
| 后端契约/历史/调度/能力路由 | 11/11 通过 | 4 个套件；新增能力测试修复前 4 项失败，修复后通过 |
| 完整后端 `test` | 422 通过，67 套件完成、2 中止；退出 1 | `MysqlSqlParserSpec` 缺 GeneratedMessageV3；`ComprehensiveCalciteTest` 初始化失败；完整门仍为 evidence_incomplete |
| 前端 `npm run build` | TypeScript、Vite 构建通过 | Vite CJS API 弃用与大 chunk 警告保留；不是生产部署 |
| 后端 packageBin、Universal/stage | 通过 | 联调启动实际 stage 产物，非只运行路由替身 |
| 真实后端 HTTP | 201/202/400/404/409/503 及分页响应已记录 | 新建隔离 file-H2 JDBC Journal、loopback 临时端口；不是 MySQL CDC 实验 |
| 浏览器正常链路 | 分页→完整定义创建→摘要→执行接受→实际终态/节点结果；调度暂停/恢复；真实健康与集群成员 | DOM 快照包括修复前观察与最终复查，不把快照数当独立通过测试数 |
| 浏览器 HTTP 故障联调 | 首次/刷新列表失败、历史/调度失败、集群/健康采集失败均不冒充空数据/在线；迟到旧页不覆盖新页 | 测试代理注入 503、延迟响应；不是实际数据库宕机 |
| 实际接受后 HTTP 回复丢失 | 后端 202 被代理替换为 504；跨刷新/历史导航后沿用 ID，后端返回同 executionId 的 409 | 验证前端确认身份；不是 T/J/D 的故障恢复证明 |
| 保全 | 258 个受保护文件逐字节一致，七组历史 SHA256SUMS 通过 | 包含所有既有实验材料和除两处 HTTP 文件外的生产后端；前轮测试设施保持不变 |
| 独立审查 | 两个 P2 已修正并复核，未发现剩余重要问题 | 修复了 requestId 卸载丢失和 `new` 路由冲突；审查不代替实测 |

原始 HTTP 请求/回复和最终源于真实后端的状态保存在 archive。
浏览器创建了 `stage-b-ui` 与 `new` 两个完整定义，另四个有限工作流由测试脚本准备。
正常链路使用真实 Sequence Source→Console Sink，Journal 为隔离 H2，不将此升级为真实 MySQL→Sink 一致性结果。

## 兼容变化

这是有意改变响应含义的接口修正，不能视为纯前端改动：

- `/api/v1/cluster/stats` 的真实成员/Leader 字段保留；sharding 中的计数变为 null，增加 availability/reason。
- `/api/v1/cluster/shards` 不再生成 100 条假分片分布，shards/计数为 null，并说明未采集。
- `/api/v1/cluster/workflow/:id/location` 不再声称任意实体在当前节点，位置/分片为 null，并说明未采集。
- `/monitoring/metrics` 改为 HTTP 503 JSON unavailable；原先 128 MB、15% 等固定文本撤下。
- `/metrics` 真实 Prometheus 出口保持原样，本轮没有为所有业务 Gauge 证明采集完整性。
- 创建入口从冲突的 `/workflows/new` 移到 `/workflows/new/import`；前者现在可用于合法 ID 摘要。

仓库外客户端若依赖旧的数值字段或静态文本，需要适配 null、availability 和 503；仓库外调用方未做流量调查。
当前前端已同步类型与调用路径。源码/接口调整并没有改变 Sharding 分配或恢复协议。

## 证据与复核

- [result.json](result.json)：检查结果与未验证范围。
- [evidence.tar.gz](evidence.tar.gz)：构建/测试日志、真实 HTTP 请求与回复、受控故障代理日志、浏览器 DOM 快照、启动配置及源码快照。
- [source-manifest.json](source-manifest.json)：本轮实际源码版本与 SHA256。
- [preservation.json](preservation.json)、[historical-checksums.json](historical-checksums.json)：保护范围及旧材料完整性。
- [console.png](console.png)：真实联调页面截图；数据是本轮隔离测试工作流。

```bash
cd docs/experiments/2026-10-19-stage-b-console
shasum -a 256 -c SHA256SUMS
python3 verify.py
```

重新运行前端契约检查：`cd xxt-ui && npm test && npm run build`。
测试 runner 使用项目已有 Vite 的 esbuild 安装与 Node 内置 test；没有新增依赖或下载测试框架。
Node 18 缺少 sessionStorage/crypto 时只在单测环境提供存储替身，真实持久行为以浏览器验证为准。
本轮实际使用 Node 25.4.0、Java 17.0.14。Vite 开发代理支持 `PEKKO_API_TARGET`，默认仍是 localhost:8080。

## 未验证与保留边界

- 本次没有重新跑真实 MySQL 八窗口；A 阶段七收敛、C2 FAIL_TERMINAL 的历史结论原样保留。
  生产 CDC 协议源码及打包依赖没有修改，不把本次 HTTP 确认实验称为 C2 修复。
- 分页仍枚举 Journal ID 并 ask Actor，可能激活实体；深页成本、稳定跨页快照、全局查询视图与 70 万任务容量均未解决。
- sessionStorage 只覆盖同一标签页会话，不跨用户/独立标签页共享；关闭标签页后不能声称身份仍保留。
  服务器本身的请求去重保留上限没有改变。
- 没有新增工作流更新/删除、运行停止/恢复、完整定义读取、全量 Journal 浏览、通用调度创建 UI。
- 没有新增 DDL、多 owner、共享 Reader、生产高可用、滚动升级或生产部署证据。
