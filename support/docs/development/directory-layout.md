# 目录布局

仓库只保留 `common`、`capture`、`worker` 三个源码目录。`common` 是共享 CDC Core，后两个是可部署应用。构建产物和 IDE 元数据不属于项目目录，默认被 `.gitignore` 排除。

```text
pekko-reference/
├── common/                         # pekko-server 兼容项目，共享 CDC Core
│   ├── src/main/scala/.../cdc/     # 协议、状态、恢复、Journal 和生命周期
│   ├── src/main/scala/.../connectors/mysql/
│   ├── src/main/resources/         # 配置和数据库 schema
│   └── src/test/                   # 单测、集成测试和历史兼容夹具
├── capture/                        # pekko-cdc-capture，单 Capture 入口
└── worker/                         # pekko-cdc-worker，至少两个 Worker 入口
├── support/
│   ├── deploy/
│   │   ├── kubernetes/cdc-runtime/ # 当前 Capture/Worker 清单
│   │   ├── kubesphere/             # 构建、镜像和部署流水线
│   │   └── local/                  # 本地 Compose 配置
│   ├── docs/
│   │   ├── architecture/           # 架构和数据流说明
│   │   ├── guides/                 # 配置、部署和运行指南
│   │   ├── articles/               # 对外技术文章
│   │   ├── development/            # 设计、计划和目录约定
│   │   ├── experiments/            # 实验原始材料、失败证据和对账结果
│   │   └── archive/                # 历史实现和迁移说明
│   └── scripts/                    # 构建、检查和实验辅助脚本
├── .tasks/                         # 用户保留的任务记录，不参与生产编译
├── build.sbt                       # SBT 聚合入口
└── README.md                       # 项目入口
```

## 生产边界

Capture 只持有 SharedMySQLCapture 和一个 Reader。Worker 通过 Control DB 的 TaskLease 竞争任务，并为每张表独立 materialize TableCdcGraph。生产部署只使用 `capture` 和 `worker`，两者都依赖 `common`，`common` 不单独部署。

`common` 的 SBT 项目 ID 仍是 `pekko-server`，所以既有 `sbt 'pekko-server/test'` 等命令继续兼容。物理源码目录是 `common`，不再保留旧的 `pekko-server` 符号链接。

## 兼容与历史边界

`common/src/test/examples/legacy-console` 只保存旧接口测试夹具。旧 Workflow 的历史类型、迁移样本和失败记录归档在 `support/docs/archive`、`support/docs/experiments` 或测试源码中，不进入 Capture/Worker 的生产入口。

`support/docs/experiments` 是证据目录，不能因为后续实验通过而覆盖或删除先前失败材料。文章中的源码链接应指向 `common`、`capture` 或 `worker` 的当前路径，历史版本链接单独标注提交号。

## 本地生成物

以下目录会在本机构建或打开 IDE 后重新生成，不纳入仓库：

```text
target/                 # 根、common、capture、worker、project 的构建输出
.idea/ .metals/ .bsp/   # IDE 和 Metals 元数据
.openidea/ .superpowers/
console/node_modules/   # 旧前端的本地依赖，仅用于历史试验
__pycache__/ logs/
```

实验原始材料应写入 `support/docs/experiments`，不要把结果保存到 `target`，否则清理构建目录会丢失证据。
