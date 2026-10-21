# 单表 Split 提交前整体审查

基线 `9881fa26a75d625cb96ff44589ba67ea1a939aab` 加本次实现，最终实验的 237 个源码/资源/构建文件见 [源码摘要](source-manifest.json)。实验发生在提交前；Git 提交、推送与源码验证分别记录，不将提交或推送当作运行证明。没有部署生产服务。

## 审查发现与修复

发现一项阻断问题：下一次 Source 读取失败时，Pekko 流可以先失败，而前一页 `mapAsync(1)` 内已发起的目标提交及 Journal Future 仍在运行。原 Split 路径只等待 Source 关闭，就可能提前重试或退出。

先用与真实 Source 相同的 `BatchSourceSession.resource` 固定门闩复现：挂起目标 Future，等待下一次读取失败，执行器却提前结束；断言实际失败。修复后，在接纳页时登记完整 T→J Future，流结束后先排空该 Future，再处理关闭、失败分类、重试或退出。片内并发为 1，保留最后一个接纳的 Future 足以覆盖此前页；目标/J 的失败不会被源失败覆盖，关闭不确定继续保留 Engine fence。未改旧 CDC 恢复协议。

独立源码复审确认上述路径已修复，未发现其他阻断项；最终结论同时依赖下面实际验证，不能只凭审查意见。RED、接口接线编译失败、Java 版本不符的构建失败及 GREEN 日志分别保留在证据包 run/，不将设施失败算作协议故障。

## 恢复、释放和兼容边界

- T 与批次账本、摘要同事务；J 只记录实际确认的片内页。页序号、边界、配置指纹与恢复预算在同 execution 冻结。已完成片不重开，失败片从自己的 J 恢复；目标已提交而 J 未保存时由目标账本判定重放。
- 新准入在取消或释放不确定后关闭，已接纳 T→J 仍须排空；Source 释放观察不确定不能授权替代 Reader。查询和 Socket 超时只覆盖相应 JDBC 路径，不承诺所有故障的固定恢复时限。
- 新状态是 WorkflowState 的可选字段，旧持久化类型、事件、Checkpoint、persistenceId 保留。18 份旧 CBOR 按原 manifest 解码并重编码一致，新非空状态/事件往返及真实 SnapshotStore/Journal 恢复通过。新事件写入后不承诺旧二进制降级，也没有混合版本升级实验证据。
- 快照 D 为 N/A；没有把页进度伪装成 binlog offset，也没有更改 CDC 的 T→J→原始回调前缀 ACK。

## 实际验证

| 项目 | 结果与归属 |
|---|---|
| 修复后完整后端 | 516/516，88 套件，0 失败、0 中止；Java 17，显式 4 GiB 堆 |
| 修复后局部测试 | 57/57，6 套件；覆盖 Source、Sink、状态机、执行器、契约和新 CBOR |
| 构建 | compile/packageBin 通过；运行类、显式 SQL 资源和旧状态类存在；新实验类不进入 JAR |
| 修复后真实快照 | 22/22 达到预期：16 项收敛、6 项保留上下文暂停；所有收敛项做全量六字段对账 |
| 原 CDC 真实结果 | 沿用首次实验的 27 项原始证据，本次未重跑；10 自动收敛、7 安全暂停、7 历史窗口收敛、3 释放/Stop 检查 |
| 历史保全 | 181 个原有实验文件摘要未变，旧 C2 失败证据保留；首次 Split 原始证据包未改写 |

相对首次实验，生产代码只追加 Split 执行器的排空修复；另外三份变化是局部测试和真实实验设施。原 CDC 使用的生产代码与其真实实验版本字节相同，这只支持沿用对应有限场景证据，不等于本轮重新运行 CDC。

| 场景 | 分类 | 源/目标行数 | 相同键字段差异 | 最大恢复次数 |
|---|---|---:|---:|---:|
| BASELINE | converged | 65/65 | 0 | 0 |
| CHANGED | safe_pause | 65/60 | 1 | 1 |
| COMMIT_ABSENT | converged | 65/65 | 0 | 1 |
| COMMIT_LOST | converged | 65/65 | 0 | 1 |
| EMPTY | converged | 0/0 | 0 | 0 |
| EOF | converged | 65/65 | 0 | 1 |
| EOF_J_LOST | converged | 65/65 | 0 | 0 |
| J_LOST | converged | 65/65 | 0 | 1 |
| PAGE_BYTES | safe_pause | 65/0 | 0 | 0 |
| PLAN | converged | 65/65 | 0 | 0 |
| QUERY_TIMEOUT | converged | 65/65 | 0 | 1 |
| READ_T | converged | 65/65 | 0 | 1 |
| REGISTER_BEFORE | converged | 65/65 | 0 | 0 |
| REJECT_J | safe_pause | 65/60 | 0 | 0 |
| RETRY_EXHAUSTED | safe_pause | 65/60 | 0 | 3 |
| SNAPSHOT_RECOVERY | converged | 65/65 | 0 | 1 |
| STOP_READ_T | converged | 65/65 | 0 | 1 |
| STOP_T | converged | 65/65 | 0 | 1 |
| TARGET_CLOSE | safe_pause | 65/6 | 0 | 0 |
| T_J | converged | 65/65 | 0 | 1 |
| WIDE_PAGE | safe_pause | 65/57 | 0 | 0 |
| WINDOWS | converged | 65/65 | 0 | 0 |

新增 READ_T 在真实 MySQL 首批已 COMMIT 后挂起提交结果，等待下一页实际读取后注入读取观测失败。切点目标账本已存在、指定片 J 未推进，也未写重试/暂停；放行后先保存首批 J，再持久化 Retry，并最终 65 行、六字段一致、65 条插入审计。STOP_READ_T 在同一切点请求 Stop，放行前 owner 仍能响应状态查询；放行后排空 J、确认 Source 关闭与 owner 退出，再用新 JVM 恢复同一 execution，最终逐字段一致。

这里的目标事务已 COMMIT 且 JDBC 连接/池已经关闭，门闩挂起的是其返回结果；没有把它称为“真实打开中的事务资源排空”实验。源故障是测试设施在实际读取后的可控注入，不是 MySQL 自然发生的网络故障。局部 Promise 测试另行验证未完成目标 Future 不会被流终止越过。

其他 20 个窗口沿用首次实验定义，在最终冻结源码上重新运行。数据仍为静态单表 65 行、六字段（EMPTY 为 0 行），涵盖负数/稀疏/大整数键、DECIMAL、NULL/空白、Unicode 和微秒时间；不是任意 MySQL 类型或规模证明。6 项暂停保留缺行、字段差异和失败上下文，没有伪称数据已经一致。MySQL 为 8.4.11，Pekko 1.1.3、Connector/J 8.0.33。

## 证据与复核

[结果](result.json)、[源码摘要](source-manifest.json)、[原始证据包](evidence.tar.gz)、[逐文件摘要](archive-manifest.json)、[独立核验器](verify.py)、[SHA256SUMS](SHA256SUMS)、[JAR 检查](package-check.json)、[历史保全](historical-artifact-check.json)、[数据库设置观察](mysql-observed-settings.json)、[清理结果](cleanup.json)。证据包含本次 22 个随机测试库的 SQL dump、实际 Journal CBOR、目标账本、审计、逐字段数据及固定窗口记录；原 27 项 CDC 证据逐字节复制并注明归属，首次旧源码及失败材料仍见 [首次实现](../2026-10-21-snapshot-splits/README.md)。私有设置及实际凭据未打包。

```sh
python3 docs/experiments/2026-10-21-snapshot-splits-review/verify.py
python3 docs/experiments/2026-10-21-snapshot-splits/verify.py --archived-source
```

核验器重新比较字段，解析保存的实际 Journal CBOR 与 T，检查新门闩切点无提前 J/Retry，并核对源码及历史材料；不连接数据库，不替代真实实验。

## 未验证

限于单 owner、静态源与固定 Schema、单表 mysql.snapshot→mysql.write；不支持据此宣称在线增量快照、全量到 binlog 切换、DDL、多 owner fencing、网络分区、源实例替换、跨表资源预算、70 万表容量、硬 JVM RSS 上限、任意 MySQL 类型、永久阻塞 JDBC close 或断电恢复。没有新增暂停后的人工继续/放弃 API；计划/枚举元数据回复丢失的自动恢复仍未全覆盖。目标按页可见，不是全表原子发布。
