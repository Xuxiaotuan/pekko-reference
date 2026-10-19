# Pekko CDC / Workflow 管理界面

当前 B 阶段支持：分页摘要、完整 Workflow DSL JSON 创建、请求执行、最近最多 50 条执行记录，以及已有调度计划的暂停/恢复。
不提供既有定义编辑/删除、日志、完整 Journal 浏览、SQL 执行或任意 DAG。

```bash
# Java 17：仓库根目录启动后端
sbt runServer

# 另一个终端，已有前端依赖的环境
cd xxt-ui
npm run dev
npm test
npm run build
```

默认开发代理指向 `http://localhost:8080`；隔离联调可用
`PEKKO_API_TARGET=http://127.0.0.1:<backend-port> npm run dev -- --host 127.0.0.1 --port <ui-port>`。
生产静态站点必须为 `/api`、`/health` 配置指向后端的反向代理，Vite 的开发代理不是生产部署。

- 列表：`/workflows`，消费 `{page,pageSize,items}`；没有全局总数/排序/快照保证。
- 创建：`/workflows/new/import`；201 仅表示定义已保存，不自动执行、初始化目标或注册调度。
- 摘要：`/workflows/:id`；不能当完整定义读取或编辑。
- 执行：202 是接受；未知回复按工作流在当前标签页会话保留 requestId，刷新/导航后重试仍复用。
  标签页关闭、独立标签页或其他用户不共享此身份；后端去重保留上限未改变。
- 调度：`/schedules`；暂停计划不停止当前执行。新建/编辑仍使用已有后端 API，本页不提供入口。
- 监控：`/dashboard` 和 `/cluster`；错误/未采集明确显示未知，不用 0 冒充测量值。

[本轮真实联调、HTTP 故障证据与未验证范围](../docs/experiments/2026-10-19-stage-b-console/README.md)。
这不是生产部署、真实 MySQL CDC 恢复或 70 万任务容量证明。

---

以下为 B 阶段前的历史说明，含当时演示能力，不作为当前可用功能清单。

# XXT-UI

XXT - Pekko DataFusion Arrow 前端展示层

## 🚀 功能特性

- **仪表盘**: 系统总览和关键指标展示
- **SQL控制台**: 在线SQL查询编辑器，支持语法高亮和自动补全
- **任务监控**: 实时监控任务执行状态和统计信息
- **集群状态**: 查看集群节点信息和健康状态
- **DAG编辑器**: 可视化任务流程编排（规划中）

## 📦 技术栈

- **框架**: React 18 + TypeScript
- **构建工具**: Vite 5
- **UI库**: Ant Design 5
- **路由**: React Router 6
- **状态管理**: Zustand
- **HTTP客户端**: Axios
- **代码编辑器**: Monaco Editor
- **图表**: Recharts

## 🛠️ 开发指南

### 环境要求

- Node.js >= 18
- npm >= 9

### 安装依赖

```bash
cd xxt-ui
npm install
```

### 启动开发服务器

```bash
npm run dev
```

访问 http://localhost:3000

### 构建生产版本

```bash
npm run build
```

### 预览生产构建

```bash
npm run preview
```

## 📁 项目结构

```
xxt-ui/
├── public/                 # 静态资源
├── src/
│   ├── api/               # API客户端和服务
│   │   ├── client.ts      # Axios配置
│   │   └── services.ts    # API接口定义
│   ├── components/        # 可复用组件
│   │   └── Layout/        # 布局组件
│   ├── pages/             # 页面组件
│   │   ├── Dashboard.tsx       # 仪表盘
│   │   ├── SQLConsole.tsx      # SQL控制台
│   │   ├── TaskMonitor.tsx     # 任务监控
│   │   ├── ClusterStatus.tsx   # 集群状态
│   │   └── DAGEditor.tsx       # DAG编辑器
│   ├── types/             # TypeScript类型定义
│   ├── App.tsx            # 根组件
│   └── main.tsx           # 入口文件
├── index.html
├── package.json
├── tsconfig.json
└── vite.config.ts
```

## 🔌 API集成

前端通过Vite代理将 `/api` 请求转发到后端服务（默认 `http://localhost:8080`）。

### 主要API端点

- `GET /api/v1/status` - API状态
- `POST /api/v1/tasks` - 提交任务
- `GET /api/v1/tasks/statistics` - 任务统计
- `GET /api/v1/cluster/status` - 集群状态
- `POST /api/v1/sql/execute` - 执行SQL查询
- `GET /monitoring/metrics` - 系统指标
- `GET /health` - 健康检查

## 🎨 UI组件

### 核心页面

1. **Dashboard (仪表盘)**
   - 系统状态卡片
   - 实时指标图表
   - 快速操作入口

2. **SQL Console (SQL控制台)**
   - Monaco编辑器集成
   - SQL语法高亮
   - 查询结果表格展示
   - 查询历史记录

3. **Task Monitor (任务监控)**
   - 任务列表视图
   - 任务状态筛选
   - 实时统计图表
   - 任务详情查看

4. **Cluster Status (集群状态)**
   - 节点列表展示
   - Leader信息
   - 节点健康状态
   - 资源使用情况

5. **DAG Editor (DAG编辑器)**
   - 可视化流程设计（规划中）
   - 拖拽式任务编排（规划中）
   - 依赖关系管理（规划中）

## 🔧 配置说明

### Vite配置 (vite.config.ts)

```typescript
export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',  // 后端服务地址
        changeOrigin: true,
      },
    },
  },
})
```

## 📝 开发规范

- 使用 TypeScript 严格模式
- 遵循 ESLint 规则
- 使用 Prettier 格式化代码
- 组件使用函数式写法 + Hooks
- 类型定义统一放在 `types/` 目录

## 🚧 待开发功能

- [ ] DAG可视化编辑器完整实现
- [ ] 实时数据推送 (WebSocket)
- [ ] 用户认证和权限管理
- [ ] 国际化支持
- [ ] 暗色主题切换
- [ ] 数据血缘可视化
- [ ] 性能监控面板

## 📄 许可证

MIT License
