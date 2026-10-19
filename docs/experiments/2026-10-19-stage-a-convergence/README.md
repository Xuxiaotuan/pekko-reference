# A 阶段：发布边界、构建收敛与真实恢复回归

2026-10-19。本轮在 `dca7d25cfcf7747d7d08231259c3353331755b84` 工作区上执行
[收敛设计的 A 阶段](../../superpowers/specs/2026-10-19-cdc-platform-convergence-design.md)。
没有提交、推送或发布，没有改 CDC 恢复协议、持久化事件/类名、必要工作流与调度、UI/API。
前轮未提交的恢复探针和实验材料原样保留。

## 改了什么

- 12 个旧 Actor、模拟 CDC、CRDT/GC/Stream 示例，共 2,164 行，逐字节迁入 Test。
  原包名保留，生产 JAR 不含这些类。每个路径与哈希见 [validation.json](validation.json)；
  手工运行方式见[示例说明](../../../pekko-server/src/test/examples/README.md)。
- 删除 9 个无当前生产引用的直接依赖：LevelDB 两项、CSV connector、Arrow 三项、gRPC 两项、commons-pool2。
  Slick connector 仍被测试使用，调整为 Test；JDBC Journal 所需 Slick core 保留。
  删除重复 ScalaTest 声明，保留 commonSettings 中原版本的 Test 依赖。
- 修正 `runServer`、`dockerBuild`、`dockerPublish`、`checkDeps`、`cleanAll` 使用的项目 ID。
  根 `quick-test.sh` 原先引用已不存在的 DataFusion 客户端，现在运行有限的本地 CDC 检查；
  原脚本逐字节保存在 `src/test/examples/legacy-datafusion`，明确标为历史材料。

Runtime 依赖 JAR 从 237 个 / 162,130,891 字节变成 192 个 / 143,736,316 字节：
净减少 45 个、18,394,575 字节（约 17.54 MiB）。48 个旧文件名移出，3 个较低版本进入：
Guava 33.3.1→33.3.0、commons-codec 1.17.1→1.16.0、error_prone_annotations 2.30.0→2.28.0。
这些是移除直接依赖后的实际解析结果；详细文件名在 `validation.json`。
Pekko、JDBC persistence、Debezium、Jackson、H2、MySQL Connector/J 的已解析版本未变；
MySQL Connector/J 清理前后实际均为 9.1.0，并非以 build 中被淘汰的 8.0.33 声明判断。
commons-pool2 仍通过 Calcite 传递进入发布目录，只删掉了冗余的直接声明。
**依赖字节数不包含主 JAR，也不是进程内存、吞吐或 70 万表容量测试。**

## 本轮实际检查

| 检查 | 实际结果 | 证明范围 |
|---|---|---|
| Compile / compile、Test / compile | 通过 | 迁移后主/测试源集可编译 |
| Compile / packageBin、Universal / stage | 通过 | 发布产物生成；12 文件中的 33 个顶层类型在 Test、均不在主 JAR，保留正式 Actor、Scheduler、Source、Sink |
| `./quick-test.sh` | 54 项通过 | Validator、完整组装配、Apply Sink 本地单测 |
| 4 个模拟 CDC / 水位演示 main | 均退出 0 | BasicCdcDemo、SimpleCdcStreamDemo、WatermarkAlgorithmDemo、WatermarkStreamAlgorithmDemo；不等于真实 CDC |
| 发布目录启动；修正后的 `sbt runServer` 启动 | 各自 4 个端点 200 | 隔离 file-H2、loopback 临时端口；live、ready、workflows、schedules；ready 显示 memberUp/shardingInitialized/jdbcAvailable 均 true |
| `checkDeps`；Docker 两个任务 inspect | 通过 | 别名解析与任务定位；没有构建/发布 Docker 镜像，也没有执行 cleanAll |
| 完整 `pekko-server / test` | 410 项通过，64 套件完成、4 套件中止，退出 1 | **完整门仍为 evidence_incomplete** |
| 两个端口中止套件分别在独立 JVM 运行 | 8 项通过，均退出 0 | MySQLPerformanceTest 与 HealthRoutesSpec；完整运行时二者固定 2551 端口发生 BindException，不改现有测试设施来遮盖 |
| 真实八窗口 | 8 个观察断言通过，7 个收敛，C2 仍失败 | 新 MySQL 8.4 容器、真实 Source / JDBC Actor Journal / Sink，24 个子 JVM |
| 证据独立复核；5 项负向校验 | 通过 | 从原始回调、组账本、审计表、Journal、D、完整行重新核对，不仅相信测试输出 |
| 原文件保全 | 252 个受保护文件哈希一致；12 个迁移文件字节一致；六组旧实验 SHA256SUMS 通过 | main/test、实验与相关文档/UI 的具体保护范围见 preservation-manifest.json；旧实验复核见 historical-checksums.json |

完整测试中的其余两个中止仍是 `MysqlSqlParserSpec` 的 `GeneratedMessageV3` 缺类和
`ComprehensiveCalciteTest` 的 `ConnectionPropertiesImpl` 初始化失败。此次没有修复或排除它们。
410 与独立复查的 8 项应分开阅读，不能写成“完整测试 418 项全通过”。
性能示例中的 mock 计数只作为原测试回归，不构成实际 MySQL 性能证据。

启动探针首次复查 `runServer` 时，临时 Python 脚本生成了两组端口却轮询了错误的一组；
修正探针后实际端口上的四个接口均为 200。两次原始记录均留在 archive，未修改生产启动代码。
清理前 JAR 的临时复制选中了旧产物，因此不主张清理前后主 JAR 的二进制或体积比较；
发布排除结论依据清理后明确名称的 packageBin JAR，依赖体积则依据前后导出的 Runtime 类路径。

## 真实恢复矩阵

每项源表和目标表均为 64 行、6 个字段；“字段差异”在共有主键上逐字段统计，缺失/多余主键另计。
T 是目标已提交完整组数，J 是 Actor Checkpoint sequence，二者不是同一种进度单位。
完整 D 行、原始 ACK 顺序、Journal payload、源/目标全部字段均保存在 archive；
摘要由[独立校验器](verify.py)从原始文件重算，见 [result.json](result.json)。

| 场景 / 切点 | 切点 T/J | 恢复后 T/J | 恢复首批组数 | 缺失/多余/字段差异 | 结果 |
|---|---:|---:|---:|---:|---|
| N0：T 已提交、J 尚未持久化；重启重新分批 | 70/5 | 74/7 | 3 | 0/0/0 | 收敛 |
| J1：J 已持久化、ACK 尚未返回 | 17/1 | 74/74 | 1 | 0/0/0 | 收敛 |
| J2：J 已持久化、D 的实际写入被锁阻塞并超时 | 2/1 | 74/3 | 72 | 0/0/0 | 收敛 |
| C1：真实 COMMIT 成功、回复被代理扣住，等待中杀进程 | 17/0 | 74/73 | 1 | 0/0/0 | 收敛 |
| C2：真实 COMMIT 成功、回复丢失，先等 Actor 持久化失败再重启 | 17/0 | 17/0 | 无 | 2/2/212 | **FAIL_TERMINAL** |
| C0：代理未转发 COMMIT 即终止 | 1/0 | 74/73 | 1 | 0/0/0 | 收敛 |
| K1：主键 DELETE/CREATE 跨回调，两个原始 ACK 之间终止 | 2/1 | 6/5 | 1 | 0/0/0 | 收敛 |
| K2：同 K1，并让 D 的写入受锁阻塞/超时 | 2/1 | 6/3 | 4 | 0/0/0 | 收敛 |

C2 的 T 事务已经提交，COMMIT 的 OK 被丢弃后异常导致 `ExecutionFailed` 持久化，
恢复时没有自动重建 Reader。该失败和清理前一致，未改 generation、D 或账本来制造收敛。
校验器的 `artifactVerification=PASS` 只表示证据内部一致，绝不表示 C2 恢复成功。
其余七项的目标审计序列也与真实 SourceRecord 推导的操作逐项一致，验证重放没有再次产生目标副作用。

## 复核与复跑

- [evidence.tar.gz](evidence.tar.gz)：290 个成员，包含八项原始回调/确认顺序、T/J/D、全部行、SQL dump、进程日志、构建/启动/测试记录和本轮源码快照。
- [source-manifest.json](source-manifest.json)：本轮实际源码/构建哈希；不覆盖旧实验的源码版本。
- [run-manifest.json](run-manifest.json)：容器镜像、MySQL 启动参数、八项 schema、批大小与 JVM 数。
- [preservation-manifest.json](preservation-manifest.json)：清理前保护清单和迁移文件哈希。
- [SHA256SUMS](SHA256SUMS)：本目录的外层完整性校验。

```bash
cd docs/experiments/2026-10-19-stage-a-convergence
shasum -a 256 -c SHA256SUMS
python3 verify.py
python3 verify_tests.py
```

`verify.py` 和五项负向校验从上一轮逐字节复制，仅对本目录的新 archive 复核。
要重新运行数据库故障实验，使用[上一轮说明](../2026-10-19-recovery-windows/README.md)中的
`RUN_MYSQL_CDC_RECOVERY_WINDOWS=1` 与专用临时 MySQL 设置，先执行 Test / compile。
不能把现有归档校验当作重新跑了数据库。

本轮保留单 writer、固定 Schema、既有初始化和同一 MySQL lineage 的边界。
未验证 DDL、多 owner/fencing、共享读取、70 万常驻任务容量、生产高可用或滚动升级；
C2 协议修复及 B/C/D 阶段另行设计。当前结果支持 A 阶段保持已知行为，不支持平台已达到 Flink CDC 的完整能力。
