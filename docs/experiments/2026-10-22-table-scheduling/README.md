# 跨表有界调度：实现、真实故障窗口与 70 万条元数据

基线为 `b24762f367596abac15a8a40fe044aab1c35edcc`，实现尚未提交、推送或部署。源码以[逐文件摘要](source-manifest.json)和证据包内冻结 classpath/JAR 为准；中间失败使用各自旧源码和运行时，不能用最终源码替它们重新归属版本。

这一步运行静态 `mysql.snapshot → mysql.write`。SQL 索引保存运行意图和授权，Actor Journal 保存独立 Split 进度，目标数据、账本和摘要同事务提交。快照没有 binlog offset，D 明确为 N/A；原 CDC 的 T/J/D 和完整组/原始回调前缀 ACK 协议没有扩展。操作方式见[使用指南](../../guides/mysql-table-scheduling.md)。

## 实现与保证层级

源码实现了持久化待执行索引、实例连接向量和节点配额原子准入、16 个 Split 完整窗口让出，以及手动/定时/恢复在连接器 IO 前的统一授权门。冷任务可通过新 POST/游标分页留在数据库，不为每条记录创建 Actor、Future 或定时器。资源未知继续占配额，不用 TTL 或成员移除回收。

这些是源码与相应测试支持的结论。真实实验仅支持下面固定数据集、版本和窗口的观测，不构成任意故障下恰好一次、生产可用或 70 万张物理表容量承诺。控制目录和 Journal 没有跨库原子事务；旧 Cron 仍可能先唤醒实体，不能把新入口的冷目录边界移植为旧 Cron 的激活上限。

## 真实多表矩阵

MySQL 8.4.11，Java 17.0.14，Pekko 1.1.3，Connector/J 8.0.33，真实 JDBC Actor Journal、Cluster Sharding（remember-entities 开启）、Source 和 MySQL Sink。16 张源表对应 16 张目标表：一张 257 行、十四张各 65 行、一张空表，总计 1167 行。每行比较 id、label、amount、note、updated_at、revision，包含负数/稀疏/大整数键、NULL、空白、Unicode、DECIMAL 和微秒时间。源表静止、Schema 固定，目标初始为空。

准入参数：全局表数 6、每节点 4、同实例 Source+Sink 连接预留合计 16、未绑定启动数 2、页预留 128 KiB；每表 P=2、每页 2 行、每片 3 行、maxPageBytes=4096，候选页 128。每张表有独立 execution 和 Journal；窗口让出不改变其身份。

| 场景 | 实际固定窗口 | 验收含义 |
|---|---|---|
| BASELINE | 16 个冷意图先落库，再启动 dispatcher | 全部逐字段收敛；完成实体退出 |
| HELD_REDELIVERY | SQL 已发 HELD、尚未投递，重新查询同授权 | 不重复发放资源；全部收敛 |
| COORDINATOR_RESTART | table-00 已 T 提交而 J 未确认，重启 dispatcher | 配额不清空；同一授权继续；全部收敛 |
| T_J_CRASH | 真实目标 COMMIT/关闭后挂起返回，J 未写时杀 JVM | 确认旧进程及连接消失后人工重新排队，原 execution 重放；全部收敛 |
| J_REPLY_LOST | 真实 J 已持久化，挂起其回复再模拟回复丢失 | 按实际 J 恢复，不重复物理目标效果；全部收敛 |
| READ_T | 前页 T 返回挂起时，实际读到下一页后注入读取失败 | 未提前 Retry/Block，排空 T→J 后重试；全部收敛 |
| STOP_READ_T | 同上窗口请求停止 | 排空并确认释放后退出，再恢复；全部收敛 |
| CLOSE_UNKNOWN | 指定 table-00、Split0、序号0的目标已实际提交/关闭后，close 观察抛错 | 指定表保留 unknown 和配额，其余15表收敛；指定表缺行不算通过一致性 |
| RELEASE_REPLY_LOST | 归还事务实际 COMMIT 后，代理抛连接异常 | 重读索引确认，不遗留旧本地授权；全部收敛 |
| GRANT_REPLY_LOST | 发放 HELD 事务实际 COMMIT 后丢回复 | 重查同一授权，不越过启动/连接预算；全部收敛 |
| BIND_REPLY_LOST | HELD→RUNNING owner 绑定实际 COMMIT 后丢回复 | 重读准确授权/owner，不把 running 配额滞留为无人认领；全部收敛 |
| RELEASE_REPLY_HELD | 其余15表完成后，table-00 的 READY 已 COMMIT、归还回复仍挂起，下一窗口获发授权 | 本地旧授权未确认时先 defer，新绑定不先写 RUNNING；重读确认旧授权消失后下一窗口能继续 T，最终全部收敛 |

最终统计、随机库名、每表行数/差异和对应运行时见[结果](result.json)。所有正向收敛项核对完整键集合和六字段，并核对插入审计没有重复物理目标效果；unknown 项保留缺行和 T/J 差距。切点、真实 Journal CBOR、目标账本/摘要、顺序日志和 SQL dump 在证据包中。

T_J_CRASH 的重新排队是测试代表运维在确认旧 JVM 退出、旧 MySQL 连接全部消失后显式执行，材料标记 `automatic:false`。没有自动跨 owner 接管。COMMIT/读取/close 故障使用测试代理在真实操作前后注入观察结果，不是实际断电或自然网络分区。T 门闩挂起前目标事务和连接已关闭，不能称为仍有开放事务资源的排空实验。

资源观测区分两层：Reader 峰值与 attached 峰值按生产对象实际计数；每轮保存 SQL 授权向量。另从 information_schema.processlist 采样该随机测试库的物理连接，包含 Journal、控制池和观察者。逻辑连接预留 16 不等于物理总连接硬上限，实际采样包含超过 16 的时刻；采样也可能漏掉短暂峰值，不作为全实例连接封顶证明。

## 70 万条调度元数据

独立的 `-Xmx256m` JVM 用每批 1000 条 JDBC 插入真实 MySQL，创建 700000 条带完整代表性 DSL 的调度记录。定义从同一个小表模板复制并替换任务标识，没有模拟 70 万份不同物理表或 Schema。随后通过生产 Store 做游标分页、实际授权、绑定、让出、unknown 保留和公平性检查；第二个全新 JVM 再读取并拒绝绑定旧 owner 的 running 授权。不是经 HTTP 创建 70 万条的吞吐实验，也没有创建 70 万张物理表或运行对应 Reader。

实际 SQL COUNT=700000；装载及检查约 27 秒（不含 fixture 建库），装载期强制 GC 后堆约 14.1–15.2 MiB，第二进程约 14.7 MiB，最大堆 256 MiB。RSS 单独保存，不能由存活堆推导物理内存硬界限。新进程仍看到 held2、running1、unknown1、ready699996，旧授权没有按重启或时间消失；ready 查询 EXPLAIN 使用 pekko_table_ready。information_schema.TABLE_ROWS 是估计值，不用它替代 COUNT。

这只说明本次代表性定义大小下，持久化目录及有界 JDBC 页没有把 70 万份定义同时驻留在该进程。数据库数据/索引空间约 959 MB/75 MB，原始数字见 load-result.json；更宽 DSL、更高候选页、请求去重历史、Journal 增长、真实工作负载及长期尾延迟需另测。

## 审查、兼容与回归

[审查记录](review.md)保留三个独立审查阻断项的 RED/GREEN：失败实体的本地名额、定义修订分叉、bind COMMIT 回复丢失。作者后续自审还用提交后门闩复现“旧归还回复未到而下一窗口已获授权”的本地映射竞态，并补上 RELEASE_REPLY_HELD。另保留归还结果丢失、remember-entities 实体常驻、close 探针被另一表抢走，以及单表旧暂停实体提前退出等真实失败，不以最终成功覆盖。

原单表和 CDC 的本轮回归数量、分类及具体归属见 result.json；完整日志与每项原始材料另存证据包。CDC 核心的15份受保护源码摘要未变，但公共 Actor/Engine 有调度集成，因此仍需运行回归，不能用源码摘要代替实验。新旧分支的修改不会被称为生产故障全覆盖。

| 材料 | 源码与范围归属 |
|---|---|
| 最终12项跨表矩阵 | 全部当前生产源码；真实单节点 Sharding、Source/Journal/Sink；不是多节点 singleton 自动转移 |
| 22项原 Split 回归 | Source、Actor、Engine、Sink 和持久化源码与当前一致；冻结后仅未使用的跨表 Runtime.bind 增加本地旧授权检查，详见结果中的差异列表 |
| 27项原 CDC 回归 | 使用最终清洁隔离实例的冻结运行时，全部当前生产源码一致；串行运行，避免共享实例的历史加载及复制身份争用风险 |
| 70万条目录 | Store、Models、SQL 与当前一致；独立 JDBC JVM，没有执行 Actor/Engine/Runtime.bind |
| HTTP及旧CBOR | 局部路由/实际序列化测试；不当作真实 HTTP 部署或混合版本升级实验 |

CDC 最终回归使用新隔离 MySQL 实例，镜像和 mysqld 参数与原实例相同。此前共享实例的两轮未完成回归在初始快照前加载数千条 Schema 历史时超时，目标尚为空，不计入故障窗口验收；原数据及失败日志保留。固定复制 serverId 是另一项并行设施风险，未单独证实它导致超时。

旧持久化类名、persistenceId、Checkpoint 和原事件保留；18 份历史 CBOR 原 manifest 解码后重新序列化逐字节一致，新非空 admission 状态、事件及授权消息也用实际 CBOR 往返验证。尚无混合版本滚动升级、旧二进制降级或多节点网络分区实验。208 个既有实验文件摘要不变，旧 C2 失败证据完整保留。

## 材料与复核

[结果](result.json)、[证据包分片与原始总摘要](evidence-archive.json)、[逐文件摘要](archive-manifest.json)、[源码摘要](source-manifest.json)、[历史保全](historical-artifact-check.json)、[核心 CDC 源码边界](cdc-source-boundary.json)、[构建产物检查](package-check.json)、[清理记录](cleanup.json)、[SHA256SUMS](SHA256SUMS)。证据包按64 MiB分为 [part1](evidence.tar.gz.part-001)、[part2](evidence.tar.gz.part-002)、[part3](evidence.tar.gz.part-003)，核验器直接读取分片，无需合并；重复运行时 JAR 按 SHA 去重，原路径映射见 [runtime-jars.json](runtime-jars.json)。私有设置和实际凭据未打包。源码版本、失败运行、测试设施竞争及元数据实验分别归属；最终 SQL dump 是各随机实验库停止执行后的保存，不是单表发布原子性的证明。

最早两次探索运行尚未冻结 classpath，保留原始日志及当时可核实的生产源码归档，只作问题发现记录；它们不属于最终 12 项验收。后续失败和最终矩阵均有冻结 JAR 与源码摘要，不能把早期记录补标为最终版本实验。首次 RELEASE_REPLY_HELD 探索虽通过当时断言，但切点没有覆盖下一授权重叠，只保留为覆盖不足的探索，不算最终竞态验证。

只读独立核验：

```sh
python3 docs/experiments/2026-10-22-table-scheduling/verify.py
# 后续源码变动后，仅核验归档版本与现有历史材料：
python3 docs/experiments/2026-10-22-table-scheduling/verify.py --archived-source
```

核验器重新比较键和字段，解码保存的真实 Journal CBOR 对照 T，检查切点无提前 J/Retry、授权向量及元数据结果；不连接数据库，也不伪装成新实验。

显式重跑使用 [run.py](run.py)。先准备独立 loopback MySQL，设置 JAVA_HOME 为 Java17，设置 CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD 与 RUN_MYSQL_TABLE_SCHEDULING=1；用 SBT 编译测试并生成 Test/fullClasspath，再运行：

```sh
sbt 'project pekko-server' 'Test / compile' 'export Test / fullClasspath'
python3 docs/experiments/2026-10-22-table-scheduling/run.py tables
python3 docs/experiments/2026-10-22-table-scheduling/run.py metadata
python3 docs/experiments/2026-10-22-table-scheduling/run.py snapshot-regression
python3 docs/experiments/2026-10-22-table-scheduling/run.py cdc-regression
```

重跑会创建随机测试库并保留，不删除数据或卷；不得连接生产实例。并行实验各用独立控制库和数据集，资源观测按本 fixture 库记录，不证明多个独立部署共同遵守一个实例总预算。

## 未验证边界

没有扩展共享 CDC、自动多 owner 接管、多 owner fencing、DDL、源端在线写入/主键迁移一致性、全量到 binlog 切换、70 万张物理表/常驻任务容量、硬 JVM RSS 或全实例物理连接上限。历史较多的原实例已实际观察到 CDC 初始化在应用数千条 Schema 记录时超过180秒，尚未到目标故障注入；新隔离实例的通过不表示已经解决该初始化成本或证明大规模 CDC 可用。永久阻塞的 JDBC close、源实例替换、断电、混合版本升级、在线改配额、跨库原子定义注册及暂停后的生产人工继续/放弃 API 均未验证或未实现。目标按页可见，不是全表原子发布。
