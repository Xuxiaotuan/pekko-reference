# Pekko CDC / Workflow 管理界面

当前产品保留分页摘要、完整 Workflow DSL JSON 创建、请求执行、近期历史、已有调度的暂停/恢复及真实服务/集群状态。URL、HTTP DTO 和未知执行请求沿用 requestId 的语义不变。执行接受不代表执行完成；摘要不能当完整定义编辑。

## 目录

- `src/app`：布局；`src/App.tsx` 组合路由。
- `src/features/workflows`：工作流页面、API、DTO、执行身份和按钮。
- `src/features/schedules`：已有计划列表与暂停/恢复。
- `src/features/cluster`：集群观测和服务状态页面。
- `src/shared/api`：HTTP client、错误格式化及存活/就绪 API。
- `tests`：真实本地 HTTP stub 上的契约与 requestId 测试。
- `examples/legacy`：退役页面、编辑器、旧类型和文档，逐字节保存，不进入产品 TypeScript/Vite/lint 源集。

## 构建与验证

Java 17 启动后端：仓库根目录运行 `sbt runServer`。UI 使用现有 Yarn Classic v1 锁文件安装；npm 只用于运行脚本，不生成第二份依赖锁。

```sh
cd console
yarn install --frozen-lockfile
npm test
npm run build
npm run lint
npm run dev
```

默认代理 `http://localhost:8080`，可用 `PEKKO_API_TARGET=http://127.0.0.1:<port>` 覆盖。生产静态站点需为 `/api` 和 `/health` 配置反向代理；Vite 开发代理不是部署方案。

产品 URL：`/workflows`、`/workflows/new/import`、`/workflows/:id`、`/history/:workflowId`、`/schedules`、`/dashboard`、`/cluster`。列表没有全局总数/排序/快照分页保证；错误时保留的旧结果不代表本次采集成功。暂停调度不停止当前执行。

当前根依赖只声明实际产品需要的 React/Router、Ant Design 和 Axios；测试显式声明已验证的 esbuild 版本。旧演示依赖不再作为产品直接依赖。历史界面并非已实现的 SQL、任意 DAG 或吞吐监控能力，见[归档](examples/legacy/README.md)。

[原 B 阶段联调证据](../docs/experiments/2026-10-19-stage-b-console/README.md)保留原始源码与边界；[当前架构](../docs/architecture/README.md)区分源码、局部验证和端到端证据。本轮目录迁移不是全浏览器 UI 回归或生产部署证明。
