# 共享 MySQL 捕获阶段一：动态订阅、持久化 L 与独立表级恢复

本阶段实现一个业务 MySQL 实例的共享捕获、动态 B/K 订阅和持久化日志 L。捕获就绪不等于初始化完成；本轮没有实现 LOW/HIGH 全量初始化，也没有增加共享 Reader 自动接管。原有单表 CDC、静态快照及持久化事件类型保留。

## 实现及运行入口

生产实现位于 `server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/`：

- `SharedMySQLCapture`：显式启动和关闭的共享 Engine；源控制表 marker；原始回调的完整覆盖与前缀确认。
- `MySQLCaptureStore`：InnoDB 订阅、完整 Schema 描述、不可变 L 帧和 ACK 证明链。各事务在捕获域根行串行化；单 Store 最多四个操作连接。
- `CapturedLogSource`：`mysql.cdc.log` Source，按订阅从 L 有界读组，使用 `mysql.capture.groups.v1` cursor；目标仍走现有完整组账本和 Actor Checkpoint。

使用方先初始化 Store，再显式管理共享会话，并通过 `NodeRegistry.builtins().withSource(new CapturedLogSource(store,database))` 注入。新增功能没有隐式启动全库任务，没有增加 UI/REST 订阅管理或自动 owner 接管。订阅控制 API 是 `subscribe`/`unsubscribe`；`B_PENDING` 可通过 Store 显式取消。K 停止新增捕获，目标消费者的排空/关闭仍由工作流生命周期管理。

`initializedTarget=true` 是操作方对可信基线的显式声明，不是系统已经完成快照的证据。生产使用者不能仅因 `CAPTURE_READY` 就设置它。

## 新旧进度边界

旧单表模式继续使用 `T → J → 原始 ACK → D`。新增模式为：

```text
完整组 / Schema / B-K → L 持久化及覆盖证明 → 原始连续前缀 ACK → SDK flush → 实际 D
                     └→ 分表分页读取 → 目标事务 T → Actor Journal J
```

B/K 的区间是 `(B,K]`。B、订阅状态与 BARRIER 在同一状态事务提交。控制 marker 使用唯一 operationId、独占源事务，并由 Reader 的事务 END 核实；JDBC 元数据不是 B 的位置依据。订阅行与 B/K 帧双向核对；登记丢失不能让 Reader 将后续业务行当作未选中表跳过。原始 ACK 不由表 Actor 调用。主键 d/c 跨回调时先让回调返回，完整组持久化后按原始前缀确认。完整组是物理行事件的恢复单位，不是整个源事务；本轮同事务多行输入的终值对账，不证明目标的源事务原子可见或跨表提交顺序。

ACK 帧记录父 ACK 和依赖帧的序号/摘要；实际 D 必须有覆盖证明，引用缺失或内容冲突即停止。它不是签名，也不能抵御拥有状态库任意写权限的攻击者。取消后重新订阅获得新 generation/B，不自动补齐停订空档。

## 环境与前置基线

- macOS、本地独立 worker JVM，JDK 17.0.14，Scala 2.13.12、Pekko 1.1.3、Debezium 3.6.1.Final、MySQL Connector/J 8.0.33。
- 两个独占的原生 MySQL 9.5.0 实验实例：源 `127.0.0.1:13365`，状态/目标/Actor Journal `127.0.0.1:13366`。现有 `3306` 服务未修改。测试使用私有随机凭据，不提交凭据。
- 每个场景独立随机 Schema；七张源表和七张目标表，每表四行、每行六字段。测试先复制已知基线，再建立 B；这不是在线初始化实验。
- R01 七表均发生实际变更；其他用例按实际操作表解释覆盖，不能用未变更的夹具扩张多表恢复样本数。
- 源要求 ROW/FULL、稳定 server UUID；当前选中表要求一个主键。活动表 Schema 变化、DROP、TRUNCATE 走暂停，不包含目标 DDL 演进。

### 已观察到的部署反例

最初源和状态共用物理实例，虽然状态 Schema 不在捕获表路由中，真实事务元数据仍交付了状态写引起的零行 BEGIN/END；ACK 后写 Offset，再产生新事务元数据，构成反馈循环。原始失败保存在 `failed-wire-01/`。当前生产 profile 明确拒绝源与状态 server UUID 相同，并核对 Store 实际 UUID/数据库与 owner 状态连接一致；业务源仍是单实例，但需要独立状态基础设施。不能将“不同 Schema”描述为足够隔离。

## 故障矩阵及验收口径

R01—R16 展开为 31 个用例。下表中的“通过”指各自断言成立；安全暂停不等于目标已经与源收敛。

| 编号 | 固定切点 / 输入 | 验收事实 |
|---|---|---|
| R01 | 五表捕获后动态加两表，同一 Reader | 七表实际变更，28 行 × 6 字段对账；新增订阅 initialized=false |
| R02 | 只有持久订阅意图，源 marker 尚未写 | 新 JVM 使用同一请求补建 B，继续应用 |
| R03 / late-commit / late-rollback | 源 marker COMMIT 回复丢失 / 延迟提交 / 延迟回滚 | 保留真实事务结果，同一 operationId 重试；恢复 A/F 对账 |
| R04 | B 的 L 事务未提交 | 不提前确认；恢复时重新处理 marker |
| R05 | B 的 L 已提交，回复丢失 | 同一 B 被识别，不生成替代区间 |
| R06 | B 已落 L，原始 ACK 尚未开始 | 硬停后重放并继续，B 不变 |
| R07 | marker 所在原始回调返回，后续 END 尚未抵达 | 没有因等待 ACK 阻止后续回调；崩溃后装配完成 |
| R08 | 同事务多行更新、同一行多次更新、PK d/c 跨回调 | 实际原始记录、完整组及全字段对账 |
| R09 | 锁住真实 Offset 行，L/T/J 前进而 D 落后，1→128 重批 | 新 JVM 从实际 D 恢复，历史组幂等，继续应用 |
| R10 / mid | PK 两原始 ACK 之间 / markProcessed 返回后 | 完整组先落 L；崩溃后恢复并对账 |
| R11 / rollback | B 的 L COMMIT 延迟提交 / 延迟回滚 | 在事务结果不确定时停止；新 JVM 核实恢复 |
| R12 | 缺失表 Schema 的订阅 | 拒绝该订阅，原表仍前进 |
| R12-ddl / drop / truncate | 活动表 Schema 改变、删除、清空 | 捕获安全暂停；分别保留结构/缺表/终值证据 |
| R13 / reject | 目标 T 已提交而 J 未持久化 / J 明确拒绝 | 前者重放并收敛；后者保留上下文直接 blocked，不消耗自动重试 |
| R14 / capacity | A 的 T/J 被阻止，B 继续 / L 容量耗尽 | 前者 B 独立提交后 A 追平；后者捕获暂停并保留差异 |
| R15 | B_PENDING 取消、K、停订空档、新 generation | 不复活已取消意图；K 后不入旧订阅；新 B 不补空档，允许目标差异 |
| R16 | 重复 Reader、实际 D 丢失 | 重复启动失败，原 Reader 可继续；缺 D 不从 latest 恢复 |
| R16-history / log / lineage / source / registry | History、被引用的 GROUP、状态谱系、源 UUID 不匹配、删除订阅登记 | 启动拒绝，保留证据 |
| R16-binlog | D 被固定在所需旧日志前，确认 worker 退出后归档并清除专用源旧 binlog | SDK 报必需日志不可用并暂停，不跳 latest；私有归档位置保留 |

正常收敛用例等待目标逐字段一致，且 J 引用的 L GROUP 追上已消费进度。负例不适用这一条件。每个用例结束后确认所拥有的 worker 退出、对应源/状态 Schema 无残留连接；不是只凭 kill 指令成功推断释放。

## 取证、失败材料与复算

最终运行清单见 [result.json](result.json)，共享矩阵复算见 [verification.json](verification.json)，原路径回归复算见 [regression-verification.json](regression-verification.json)。[原始证据归档](evidence.tar.gz)、[逐文件清单](artifact-manifest.json)、[源码清单](source-manifest.json)和 [SHA256SUMS](SHA256SUMS)保留可核对的字节。运行时 HEAD 仍为 `cc1c43d`，新增实现尚在工作区；以源码清单和源码快照识别实际测试字节，不能将后续提交 SHA 冒充运行 HEAD。

证据包含 B/Schema/L、原始 SourceRecord（完整 offset、key、headers）、原始 ACK 调用、实际持久 D、目标组账本 T、真实序列化及解码 Journal J、各字段对账、进程与连接清理。最终矩阵与所有开发失败尝试分开保留。早期字符串 CAS 闸门没有命中、构建前置失败、真实校验测试由红转绿均不计为最终恢复通过。

`verify.py` 只读复算帧摘要和父链/引用、B 绑定、实际 D 覆盖、J cursor、原始 ACK 前缀、全字段和清理。它不连接数据库，不是额外一轮端到端实验。新增矩阵没有将旧实验的目标审计触发器移植到七张表；本轮不能据终值相同宣称任意外部副作用恰好一次。

复跑前需用专用实例恢复私有环境，设置 JDK17，并提供包含 `user`/`password` 的私有 JSON。账号需要访问两个专用端口，并能创建测试数据库和表；在源13365预建空库 `pekko_test_shared_wire`，在状态13366预建空库 `pekko_test_shared_state`，均使用 utf8mb4/InnoDB。随机场景库及 Journal/目标表由测试创建。源实例还需启用 binlog、ROW/FULL 和唯一 server UUID；本次使用独占 server-id 20261025/20261026。不能将这些准备动作指向业务实例。

```sql
-- 仅在专用源13365执行
CREATE DATABASE IF NOT EXISTS pekko_test_shared_wire CHARACTER SET utf8mb4;
-- 仅在专用状态13366执行
CREATE DATABASE IF NOT EXISTS pekko_test_shared_state CHARACTER SET utf8mb4;
```

完成环境准备后运行：

```sh
python3 docs/experiments/2026-10-25-shared-capture/run.py unit --settings-file /private/lab.json
python3 docs/experiments/2026-10-25-shared-capture/run.py matrix --settings-file /private/lab.json
python3 docs/experiments/2026-10-25-shared-capture/run.py regression --settings-file /private/lab.json
```

完整矩阵最后一个用例会操作专用 `13365` 的 binlog，先归档再清除；只能在隔离实验实例运行，不能转向业务环境。用户名需有夹具建表、binlog 操作及状态取证权限。失败证据保留，不自动删除实验 Schema。归档中的数据库日志原文保存在本机私有目录，不提交可能携带认证信息的 binlog 二进制。

## 未验证边界

LOW/HIGH 全量转流、窗口回填、共享域自动接管、DDL 演进、任意类型与多主键、任意历史恢复点、D 落后并跨越多次订阅前 DDL 时的恢复可用性、GC/保留期、跨实例源切换、70 万表 Schema/吞吐/内存容量仍为 `evidence_incomplete`。命名锁仅防重复启动，不能隔离失去控制但仍存活的旧 owner。SDK 队列、保留回调和单页读取预算不是全进程内存上界；`maxLogBytes` 是 payload/resume 加固定开销的记账预算，不是数据库物理磁盘硬限制。当前全库捕获也处理未选中表的事务元数据，其 ACK 日志成本不会简单随已订阅表数缩减；同步 JDBC 建连和网络超时仍依赖调用方连接配置。

原有恢复回归仍运行旧 `mysql.cdc` 入口，支持兼容回归结论；不能据此宣称新增 `mysql.cdc.log` 的目标 COMMIT 回复丢失、自动重试耗尽等分支已经逐项端到端验证。共享模式本轮的目标侧实测为已列出的 T/J 缺口和明确拒绝。

本次不扩大旧 guardian/systemd 探针或静态快照接管的适用范围，不删除旧 C2 失败或其他实验。相关设计文章见[系列入口](../../articles/cdc-design/README.md)。


## 最终审查修正

新视角审查发现四项缺口，均保留失败用例后修正：B/K 对登记的反向校验；按订阅封存 Schema 读取旧区间；实际 L 连接与 owner 状态库绑定；源控制连接关闭未知后阻断后续命令。相应真实 Store/Reader 测试由红转绿，关闭未知用例使用真实连接加故障代理和受控 Engine，不冒充端到端关闭实验。新增 R16-registry 对登记删除后的真实 JVM 重启做拒绝检查。

源码保留同步 JDBC 连接配置责任、历史校验成本和无 GC 的限制；这些没有因保护校验修正而消失。被取消表后来 ALTER 时，旧 L 仍使用原 SCHEMA 帧恢复，新的订阅才使用当前 Schema；活动表变化仍暂停。


## 关键取舍

- 源与状态使用不同物理实例：避免实测反馈循环；代价是增加状态基础设施，不能按单库部署计算成本。
- 停止后的订阅使用封存 Schema：保持旧区间可排空；代价是保留历史 Schema，未来清理必须同时考虑旧 J。
- 关闭无法确认时粘性暂停：不继续增加未知资源；代价是可能需要运维解除，当前没有自动接管或人工恢复 API。
- 测试采用可信基线夹具：隔离本阶段捕获/恢复问题；代价是不能据此宣称新增表完成在线全量初始化。


### 采样顺序的修正

第一次最终 31 项运行的测试断言通过，但新增独立核验在 R10 的 `baseline.json` 中发现 Actor 已报告 cursor、较早导出的 Journal 尚未包含该事件。它们是同一次 STATE 调用内的不同观察时点，不能当成互相矛盾的原子快照。此尝试不作为最终完整证据；保留原文件和核验失败。测试探针改为先获取 Actor 已确认进度，再导出 Journal，使后者至少覆盖前者的确认前缀；仍不声称 T/J/D 跨存储原子读取。随后同一最终源码串行重跑。


## 已完成的检查

- 最终相关测试：52/52；最终共享矩阵：31/31。最终源码清单摘要为 `310fabe7d526c290659a7e96fb82d3aa996d8b7cebe4bc8756211c279925264c`（清单文件的 SHA256，316 个源码/资源/构建文件），单位测试和真实矩阵一致。
- 独立保存证据复算：31/31，含 J 所覆盖的组在 T 中的内容和 Schema 指纹。R01 七表 28 行、168 个字段零差异；负例按暂停/区间空档分别展示，不并入全量收敛结论。
- 后端全库原命令：1GiB 堆 OOM；3GiB 时590项通过，但旧 `ActorSpec` 在百万 testProbe 清理时超过10秒而中止，完整测试门仍为 `evidence_incomplete`。
- 显式排除该旧压力套件的后端检查：589/589；package、Universal/stage、Docker/stage 和布局检查通过。该排除没有修改生产或旧测试代码，不能称为原命令全绿。
- 同源码的原有真实27项恢复回归、最终材料清单及归档结果见 `result.json`。原有 [C2 失败材料](../2026-10-19-recovery-windows/README.md)没有替换或删除。


只读重查归档无需启动数据库：将 `evidence.tar.gz` 解压到独立目录，然后将下面的 `/tmp/capture-recheck` 替换为该目录。

```sh
python3 docs/experiments/2026-10-25-shared-capture/verify.py /tmp/capture-recheck/target/shared-capture-runners/matrix-all-1792924046957449000 --repo /tmp/capture-recheck
python3 docs/experiments/2026-10-25-shared-capture/verify-regressions.py /tmp/capture-recheck/target/shared-capture-runners/regression-all-1792924249466298000 --repo /tmp/capture-recheck
```

归档保留所有开发尝试的源码快照、日志、用例数据和类文件摘要；去掉重复的开发类 JAR，只保留一份三组最终运行字节一致的类 JAR，见 [冻结运行时核对](frozen-runtime-check.json)。外部依赖的226个 JAR 保留版本文件名与摘要，见 [runtime-manifest.json](runtime-manifest.json)，不重复打包 Maven 缓存。源 binlog 二进制留在私有目录，公开 [归档校验](private-binlog-archive.json)。实验结束确认两实例无残留探针连接和事务后干净关闭，见 [lab-cleanup.json](lab-cleanup.json)。
