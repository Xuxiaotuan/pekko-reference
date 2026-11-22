# CDC 双槽与并行 Runner 验收

状态：A 的物理隔离、TCP 接入和 Python 编排验证已通过；整体 CDC/Scala 候选版本验收仍为 `evidence_incomplete`，由统一集成负责人汇总。未提交、未推送、未修改生产实现或旧 bootstrap runner/材料。

本轮新增工具见 `scripts/experiments/cdc-lab-pool/README.md`。受额外单项授权，仅调整 `SharedCaptureReaderSpec.scala` 两处源连接 host/port：读取 `CDC_WINDOW_MYSQL_HOST/PORT`，默认仍为 `127.0.0.1/13365`；断言及被测协议不变。Scala 编译/冻结归统一负责人及 C，A 未另起 sbt 或重复跑完整 Scala 矩阵。

## 最终真实双槽 Scala 定点验收

C 释放 b 后，复用其 candidate4 的 228 个已冻结 classpath 文件，逐文件核对并复制到新的私有 `scala-final-candidate-1`。当前 source manifest 与 candidate4 完全相同，没有再运行 sbt；`scala-final-candidate-lineage.json` 记录来源、artifact 相等、生产/测试/全量源码与工具摘要。公开候选清单和源码归档为 `scala-final-candidate-1.json` / `scala-final-candidate-1-sources.zip`。

`scala-dual-slot-attempt-2` 使用当前 runner、逐 case 两实例 TCP 身份准入、池级租约与预算，在 a/b 各执行一份 `SharedCaptureReaderSpec`，JDK17、`-Xmx512m`、UTC。两份 suite 各 **2/2**，合计 **4/4** 成功，失败/取消均为 0；两个 JVM 均 exit 0，实际 JVM 执行区间重叠 **2.969483 秒**。每 case 的 runner/result/slot-receipt、开始结束与子进程清理记录分开保存。C 的五个实验 schema 在 source/state 的表清单及 CHECKSUM 前后完全一致。没有 purge、drop、修改 C 材料或生产代码，也未操作旧 3306/13365/13366。

本次验收也暴露了明确的释放边界：**两份 runner attempt 均为 125，不能把 Scala 4/4 说成整个后置验收通过。** JVM exit0、进程组清理确认后，source 各残留一条本次新建的 Binlog Dump GTID 服务端记录，socket 已 CLOSED、事务为空；wrapper 的 5 秒后置检查未归零，因此保留失败。`scala-after-attempt-2-live-cleanup.json` 的再次只读检查仍能看到它们。原计划按精确身份清理，但复核时 a 目标已自然消失，严格断言拒绝执行任何 KILL，失败材料保留为 `scala-final-exact-dump-cleanup.json`。

随后在本轮 Java 退出约 149 秒时的独占只读观察，四实例用户连接与事务均为 0、实验进程组成员为 0，a/b 租约均释放，见 `scala-final-natural-cleanup.json`。**未发送 KILL、未重启实例；精确自然释放耗时未知，149 秒是观察时刻，不能说释放恰好耗时 149 秒。** 内部 event_scheduler 单独记录，不算用户实验连接。当前 run_jobs 的进程组清理/释放证明不等于 MySQL 服务端连接立即消失；连续故障实验重用槽前须检查这个边界。本轮不修生产关闭逻辑。

首次 `scala-dual-slot-attempt-1` 因验收 wrapper 把内部 event_scheduler 误算为用户连接而在 Scala 启动前拒绝；原始失败保留，修正只把内部线程单独记录，没有放宽真实用户连接和事务检查。汇总是 `scala-dual-slot-summary.json`：Scala 4/4 及并行已证明，5 秒内自然释放未通过，后续最终清理与租约释放已确认。最终 `scala-final-credential-scan.json` 对 111 个文件及 ZIP 解压内容比较两槽实际密码，零匹配；Python 语法检查与 diff 检查通过。

## 独立 review 后的两项修复

之前版本仍有两个编排缺口：run_jobs 未逐 case 实时验证两实例身份；max_parallel 仅限制单次调用。原有测试与 smoke 不能证明这两项边界，不能把历史通过扩大为修复后双槽通过。

- `unit-admission-budget-red.log` 保留 3 个真实红例：第二个 case 在 fake state UUID 已改变后仍执行；人工租约未占池预算；两个独立 Python runner 同时申请 a/b 且 max_parallel=1 时都成功。修复后最新 `unit-admission-budget-green-final-2.log` 为 **15/15** 通过。MySQL transport 在纯 Python 测试中明确模拟，worker、子进程、flock 和租约文件系统操作均实际执行。
- `Pool.verify_slot` 在每 case Popen 前重新读取 source/state TCP UUID/port/datadir；result 记录 `instance_admission.instances.<role>.actual/expected/matches`，失败不派发，不依赖此前 tcp_check。单测第一 case 改变模拟 state UUID，第二 case 实际观察到变化并拒绝；四次身份查询是两个 case 各 source/state 的逐次证据。
- `acquire/release` 通过 runtime 级 `flock`，仅在预算计数和租约元数据更新的短临界区持锁。预算包含所有租约目录，人工、残缺、未知隔离租约均计入。runtime 预算文件固定 max_parallel；不同配置不能对同一 runtime 放宽预算。无 PID 自动回收、无自动排队；满额申请明确拒绝并保留未派发结果。
- `review-budget-runner-a/b-attempt-1` 是两个真实 Python runner 在同一临时 runtime 同时竞争 max_parallel=1，mysql transport 为模拟。只有一个 worker artifact，另一 runner 125，见 `review-budget-verification.json`；另实测人工租约、未知隔离目录均阻止另一个槽申请，显式释放后恢复容量。这不是两个真实 MySQL 槽的故障实验。
- `review-live-a-admitted-attempt-1` 对真实槽 a 的 source/state TCP 身份检查通过并派发简单 Python marker；`review-live-a-rejected-attempt-1` 仅改变内存中 a.state 的期望 UUID，实际观测到原实例 UUID，125 拒绝，marker 未出现。`review-live-a-verification.json` 保存结果。只做 a 的只读身份查询，未写 MySQL 数据或修改实际配置，两个 attempt 后 a 租约均释放。C 独占 b，全程没有连接、修改或使用 b。

**后续已按额外授权执行真实双槽 Scala 定点，结果见上节**。原有 16 个物理隔离故障结果保持历史证据；最终 Scala 4/4 不掩盖后置 5 秒自然释放失败，也不冒充完整故障矩阵。

## 实际环境

可用配置为 `pool-attempt-2.json`；`local-pool.json` 是失败初始化 attempt-1 的保留配置，不能作为可用环境。

| 槽 | source TCP / UUID | state TCP / UUID |
|---|---|---|
| a | 14365 / `889e2d3f-765b-4456-a55e-a441a5ffc580` | 14366 / `97907728-948c-47ee-9b0e-fa8fbc12b6e3` |
| b | 14375 / `561ae360-2c2c-4de9-bf01-3b913e7226a6` | 14376 / `542fff04-a65b-4f5f-a911-90ff25211e3d` |

四个实例是 Homebrew MySQL 9.5.0 的独立 mysqld，data/socket/pid/log/binlog 都位于 `/private/tmp/cdc-lab-pool-20261025-attempt2/<slot>/<role>/`。每实例 buffer pool 64MiB、max_connections 24；四个 UUID 和数据目录不同。source/state 各自承载独立 binlog、DDL、控制表及状态域。主机内存 24GiB、初始可用磁盘 127GiB，未停止其他服务。实测资源、四实例 PID/RSS、原有 3306/13365/13366 监听者见 `resource-and-protected-state.json`；旧三实例未进行写入、停止、purge 或初始化。

凭据引用是 `/private/tmp/cdc-lab-pool-20261025-attempt2/<slot>/credentials.json`，0600；文件值及 token 不进入仓库或公开日志。b 已交给 C 独占。A 的初始化/隔离/TCP 修复租约已释放；A 此后仅在临时 synthetic p/q 槽跑无 MySQL 的 Python worker，不占 C 的 b。

## 本轮检查与结果

- `unit-red.log`：最初 6 项因缺少实现而失败；后续新增的 identity、owner/token 发布、忙槽、子进程、清理隔离红例分别保留 `unit-*-red.log`。当时 `unit-green-final-3.log` 为 **12/12**；review 后最新结果为上节的 **15/15**。覆盖跨进程独占、owner/token 拒绝、保护端口及重复身份、私有凭据权限、冻结篡改、并行时间重叠、失败/超时释放、忙槽结果完整、错误目标先拒绝再 SQL、子进程清理与清理未知时保留租约、拒绝未配置槽名。
- `isolation-attempt-1/result.json`：**16/16** 真实故障检查通过。a→b 与 b→a，各 victim 的 source/state 分别做真实 `LOCK WAIT`、`ALTER TABLE`、先归档后 `PURGE BINARY LOGS`、mysqld `SIGKILL` 与重启。每个切点对比旁槽 source/state 的 UUID/port/datadir、逐行 fixture、SHOW CREATE TABLE 与原始 binlog 状态均一致，再验证旁槽双实例可继续写入；崩溃实例恢复后数据也匹配。锁等待切点由 information_schema.innodb_trx 的 LOCK WAIT 及阻塞 UPDATE 存活共同证明。归档原始 binlog 保留在私有 runtime 的 binlog-archives；结果列出路径及每文件 SHA256，purge 后旧文件从 SHOW BINARY LOGS 消失。**此轮初次故障隔离通过 socket 连接执行**，不能据此推导 JDBC 准入。
- C 第一次 JDBC 运行暴露账号只允许 localhost 的设施缺陷。`tcp-red-a.json/log` 在 a 用真实 TCP 复现 `ERROR 1130`，保留红例。修正初始化逻辑，同时在新四实例补 `cdc_lab@127.0.0.1`，只通过 stdin 传入密码。`tcp-green-all-four.json` 记录 **4/4** 实例通过真实 TCP 的 UUID/port/datadir 与 64MiB/24 预算核对；b 的补授权在 C 暂停及现有租约内完成、租约未改。之后 `Pool.sql` 强制走配置 TCP，避免 socket 掩盖 JDBC 准入。C 已获通知重跑。完整新槽初始化流程在这项修正后未另建一轮四实例；补授权使用了与修正初始化相同的 CREATE USER/GRANT 语句并通过真实 TCP 检查。
- `runner-smoke-attempt-1/2`：两个真实 Python worker 同时运行，初次原始执行区间重叠 **502583000ns**，case/slot/源码摘要与各自 artifact.json 精确对应；可变 worker 源文件在 freeze 后改为抛错，冻结副本仍成功执行。每 case 的工作目录、runner.log、artifact.json 与文件摘要独立，index 直接记录归属。原始 secret 输出仅私存，公开日志已替换。attempt-2 使用最终 cleanup/index 语义，两个 worker 都确认清理并释放租约。这是 **Python 冻结/并行 smoke**，没有执行 Scala/JVM 或 CDC 故障矩阵。
- `cleanup-smoke-attempt-1`：真实父进程启动同进程组、忽略 TERM 的子进程后退出；TERM 等待发现存活，升级 KILL，确认清理后下一 case 才开始，下一 case 再检查前一子进程已不存活。index 记录最后释放时间晚于清理确认。`cleanup-smoke-verification.json` 通过。无法观察清理结果的隔离分支由故障注入单测证明：126、保留租约、后续 case 125 且不启动；不是实测操作系统拒绝 kill 的故障。
- `py_compile` 对四个 Python 文件通过；`credential-scan.json` 比较新槽实际密码与本包文件，未发现泄漏。最终 diff 仅两处已授权 Scala 测试连接配置，加本包新工具/材料。未经授权的旧文件没有被改写。

## 保留的失败与边界

初始化 attempt-1 错误地把自动生成用户名 `cdc_lab` 的下划线拒绝；只停止该新实例，data/log/凭据/租约留在其原 runtime。`initialize-attempt-1.json` 与原配置保留。Python 开发中 Path.open 不支持 opener、只读 artifact 的篡改测试需先 chmod 等失败日志也原样保留。`initialize-attempt-2.log` 记录两槽初始化成功。C 的首个 TCP 准入失败材料由 C 保留，不以补授权后的结果覆盖。

已执行上节的定点双 suite；没有验证完整 Scala 双槽并行故障矩阵、真实长稳/高吞吐或平台生命周期；A 的工具不改变生产 T/J/D 语义。JVM heap 需在受控 argv 指定，MySQL buffer pool 不是总 RSS 上限；无 OS CPU/RSS/磁盘硬配额。清理只保证本 runner 新进程组，主动 setsid 脱离的 daemon 不在保障内。遇到异常终止或无法确认进程组结束，保留租约并隔离，需人工核实后恢复；不按 PID 或超时自动抢占。材料、私有数据目录和原始 binlog 未自动清理。
