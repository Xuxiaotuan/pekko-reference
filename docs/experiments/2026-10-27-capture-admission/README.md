# A1/A2：共享 Reader 启动恢复分类与新订阅准入

本轮接入生产启动门和命令准入，保留现有完整组、L、原始回调前缀 ACK、目标组账本及 T/J/D 格式。旧 `B + INITIAL` 会在创建 Engine 前暂停；新 B 要等待当前 attempt 的真实生命周期、原始 ACK 和实际完成态 D。**安全暂停不等于补齐历史漏数，整个 CDC 平台仍为 `evidence_incomplete`。**

范围来自 [A/B 计划](../../development/plans/2026-10-26-capture-admission-and-release.md)。前置 [A0/B0 探针](../2026-10-26-capture-admission-release/README.md)保留原样；本轮只推进 A1/A2，没有实施 B 的有界关闭、物理释放与持久配额协议。用户本次授权本地提交，不包含推送或网站发布。

## 1. 版本与可复核材料

基线 HEAD：`d7769786db4d1e85085fd244449e30a10831d5bf`。最终冻结候选 `candidate-7`：

- 全源 SHA-256：`1994577eba65a1818e844b19581760b4a1679583eb0faaa73d02078cf15ff141`。
- 生产 SHA-256：`d18289cf98b2e3fd9f86c08d0a8d1993dda36d19660bf5570a43ebdf44c7cec8`。
- manifest 覆盖 `server/src`、`project` 和 `build.sbt`；文档及实验 runner 不在这个源码摘要中，另由归档索引覆盖。冻结时同时编译并核对源码未变化，运行使用冻结 JAR，不使用可变的 `target/classes`。
- Scala 2.13.12、Pekko 1.1.3、Persistence JDBC 1.1.1、Debezium 3.6.1.Final、Connector/J 8.0.33、JDK 17.0.14、MySQL 9.5.0；不新增依赖。
- 两个专用 source/state 物理槽：a `14365/14366`，b `14375/14376`。派发前核对 UUID/port/datadir，新增故障 fixture 在写入前再次核对 UUID。未操作旧 3306/13365/13366，也未 KILL 会话、重启数据库或 purge。

[最终候选 manifest](candidate.json) · [最终源码](source.zip) · [最终实测材料](validated-evidence.zip) · [中间版本与失败材料](exploratory-evidence.zip) · [验证汇总](summary.json) · [完整归档索引](archive-index.json) · [凭据检查](credential-scan.json) · [最终资源确认](final-cleanup.json)

旧 worker 单独使用 A0/B0 的冻结生产版本 `4953d46ef2fa32c4ecc588afd04c14a067aab024f6b0a218145ea8dc67ee55f4`，全源 `40b901fe17fbc02ca5b94c5c667cfa1ed9a28282957515e47db6338cea516667`。它用于构造真实遗留状态，不把旧 worker 的行为归入新生产代码。

## 2. 生产实现结论

### 启动先判断能否恢复，再创建 Engine

[`CaptureStartupGuard.scala`](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureStartupGuard.scala)只包含运行 DTO、纯分类、状态转换和固定版本 MBean 观察，不是新 Journal 事件。启动检查实际 D 的唯一 key/partition、完整坐标、snapshot 状态、history、L 的 ACK 引用及旧控制行：

| 已有事实 | 本版行为 |
|---|---|
| 全新捕获域，没有旧承诺 | 可以启动；仍不能立即写新 B |
| 已绑定 B/BARRIER/GROUP，实际 D=INITIAL | `INITIAL_WITH_COMMITMENT`，创建 Engine 前暂停 |
| B_PENDING 或尚未绑定的 fence | `UNBOUND_MARKER_UNPROVEN`，不默认源写入从未发生 |
| 已有证据却缺 D、缺 history 或 D 无 L 覆盖 | 分类拒绝；不改写 SDK D、不自动选 latest |
| 旧控制表缺失，或保留的控制行不匹配 | 拒绝；不 CREATE 掩盖旧缺项 |
| reader head 显示历史，但 L 帧缺失/序号不连续 | `CAPTURE_LOG_HEAD_INCONSISTENT`；不误判为新域 |
| 合法完成态 D，现存证据可校验 | 允许启动；新增命令仍等待当前 attempt 的准入证据 |

检查现存完成态 D 的 ACK 链，只能证明已交付输入的覆盖。**本轮没有新增持久化 startup proof，不能事后识别所有“过去已经静默跳点、随后又写回完成态 D”的旧状态；不能据此宣称所有旧命名空间历史连续或已修复漏数。**

### 新 B 需要本 attempt 的可恢复锚点

[`SharedMySQLCapture.scala`](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala)在启动前确认相同 capture 的 MBean 不存在，每个对象只启动一次，再观察本 attempt 的 completed/skipped。Skipped 要求 loaded D 已是合法完成态。

准入还要求：当前实际 D 非 INITIAL、通过既有 L 引用链校验，关联的 ACK 序号高于启动前 floor，且不高于当前 attempt 已返回的原始 ACK。旧 Schema、旧 D、Engine start Future 都不能单独开门。查询发生在控制路径，SDK callback 不等待 D。

`coverSequence`/`verifiedOffsetSequence` 只返回原有 ACK 的序号，旧 `cover`/`verifyOffset` 签名保留；没有改变 payload、digest、分组或 ACK 前缀规则。T 是目标事务与账本，J 是 Actor 接受进度，D 仍由 SDK 自己持久化；准入不把三者合并成一个进度。

`subscribe` 在登记意图前检查准入，Schema/JDBC 校验后再次检查。未准入的新命令没有新意图和源 B；同身份查询返回已有事实，冲突身份拒绝，旧 B_PENDING 不隐式补写。close 先撤准入，已经越过 `SUBSCRIBE_ADMITTED` 的在途源写可以收尾。新 K/fence 同样受准入约束，已绑定且内容相同的 fence 可只读返回。

源 marker COMMIT 未确定时保留原 operation，进入 `PAUSED_UNPROVEN`，返回 `ReleaseUnknown/releaseConfirmed=false`；不补写第二个 B。状态库连接关闭未知也在 [`MySQLCaptureStore`](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/MySQLCaptureStore.scala)根部保留这个分类，优先报告关闭未知、保留原查询异常并禁止后续再开连接。额度不能因异常被归成普通拒绝而释放。

### Bootstrap 只适配准入等待

[`BootstrapRunner.scala`](../../../server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala)在新 B 前增加最多 90 秒的兼容等待，检查取消、停止和 failure。静默 skipped 恢复可能要等服务端 heartbeat；1000ms 的 heartbeat 配置不是 1 秒就绪保证。

这段等待位于现有专用阻塞 runner；没有改变分页、LOW/HIGH、窗口 fold、F 转流、T/J 重放或持久化类型。它也不是未来 70 万表的冷队列调度器。取消时不再调用 subscribe/maximum；维护停止经 finally 收尾。

## 3. 实际实验与验证范围

最终候选的定点实验按下表验收；“拒绝符合预期”与“全量转流对账相等”是两类结果。

| 实验 | 实际验证目标 | 证据层级 |
|---|---|---|
| 真实旧版 PREPARED/INITIAL | 新版启动前 `UNBOUND_MARKER_UNPROVEN`；Engine 创建次数为 0 | 安全拒绝，不是自动恢复 |
| 真实旧版 B-bound/INITIAL | 新版启动前 `INITIAL_WITH_COMMITMENT`；Engine 创建次数为 0；旧五字段差异保留 | 安全拒绝，不是修复旧缺口 |
| 同一独占 fixture 的缺 D、缺源控制表、L 丢失注入 | 分类拒绝，控制表不被重新创建 | 故障边界断言；不是完整所有损坏矩阵 |
| 真实 Reader 提前拒绝、两表并发新增、同身份幂等、close 与源写竞争 | 早期无新意图/B；两表 B 绑定；close 撤准入后新命令拒绝，已准入命令收尾 | 真实 SDK/源控制路径；不单独算 Actor 恢复对账 |
| 源 marker COMMIT OK 被真实代理截留，再丢弃回复 | 实际源控制行已提交，暂停、不重写 B、保持 release-unknown；实际 D/L/ACK 顺序保留 | 源提交不确定；T/J 为空，不能称目标恢复成功 |
| `SharedCaptureReaderSpec` 2 项 | 正常 Schema/B/捕获路径，以及真实 JDBC close 后代理抛异常的未知释放 | 真实 MySQL Reader；不是全部故障窗口 |
| I01 | 5 表初始化并持续同步，运行中新增 2 表；7 表各 64 行、6 字段 | 真实 MySQL + Actor Journal + Sink，全量 448 行/2688 字段 |
| I11 | RR 读视图丢失，按原范围重读并恢复 | 同上，63 行/378 字段 |
| I16 | baseline T 已提交，J 尚未接受后故障恢复 | 同上，64 行/384 字段 |
| I21 | 激活 T 已提交，J 尚未进入 STREAMING 后恢复 | 同上，64 行/384 字段 |
| I21-postF | 固定 F 后故障，再有源写入，恢复后继续回放 | 同上，64 行/384 字段 |

逐字段比较由保存的完整行集合重新计算，检查主键缺失/额外、每个字段及重复主键；不只相信 worker 的 `equal=true`。Bootstrap 使用既有离线 verifier 重新计算 L digest、ACK 引用、spool、保留的边界及 T 证据。**只跑上述 5 个 Bootstrap variant，完整 I01—I24 扩展矩阵仍为未完成验收；verifier 的 `completeMatrix=false` 保留。**

暂停样本保存实际 INITIAL、L、源控制行、T/J 和源/目标行。崩溃前采样到旧 worker 物理结束之间可能追加合法 L 帧；离线复核检查旧帧未变和业务状态保留，不要求跨这个区间 L 完全不变。“启动检查不改状态”另由测试中旧进程结束后的 before/after 精确断言验证，该中间 before 快照没有单独归档，不冒充有额外原始切点文件。

marker COMMIT 样本源/目标 4 行、24 字段相等，来自预复制基线，且 T/J 为空。它只证明故障时没有写新业务结果，**不是 LOW/HIGH 初始化或目标端恢复一致性的证明**。旧 B-bound 样本的五字段差异则是预期保留的历史失败。

常规后端测试 603/603；按仓库约定排除 ExternalIntegration，另排除已知清理超时的 `cn.xuyinyin.magic.actor.ActorSpec`，没有削弱或改写其代码。真实实验另行运行。取证工具测试 19/19，runner 隔离测试 5/5；新增 Store 关闭分类 2 项包含在 603 中。

## 4. 中间失败与审查

- Guard 首次缺实现为编译 RED；随后曾遇 ScalaTest `key` 名冲突，保留编译日志。
- 第一轮旧版准备态夹具误要求“最后一个 Schema 已交付”，实际 SDK 可缓冲最后一条。改为从真实已交付 Schema 中选表；保留原失败，不用伪 Schema 或 fake ACK 使其通过。
- Store 关闭未知的两项复现测试修前分别出现“分类仍为已释放”和“抛普通 SQLException”，修后采用根部 typed failure，保留查询主异常及 fenced 状态；后续调用也返回 release-unknown。
- 一次中间常规回归与另一编译共用可变 classes，出现旧测试仍期望普通异常、新实现已抛 typed failure 的混用。保留 602 成功/1 失败日志，停止这种复用；最终稳定源码重新运行 603/603，真实故障实验使用冻结 classpath。
- 独立只读审查发现 reader head 不能被空 L 掩盖、命令路径关闭未知不能降级，以及旧完成态 D 的历史连续性不能被扩大；修正实现或报告后复核。只读审查本身不作为实验通过证据。
- runner 的初版只在退出后检查物理 idle，同租约并发依赖调用方串行。实际矩阵由外层等待前项 `after_idle=true` 后派发；最终 runner 又增加启动前会话/事务检查和同槽文件锁。5 项测试覆盖残留会话/事务、观察失败、同槽互斥、异槽并行和拒绝启动；更新后另跑真实 Reader 2 项。早期 receipt 中的 runner 摘要保留，不冒充使用了后来增加的锁。

候选 2/3/5 的生产摘要与最终不同，候选 6 还未包含后续 fenced 访问的 typed 分类。中间实测均按各自 receipt 的源码摘要保存，不充当最终候选的故障验证。最终验收使用 `summary.json` 列明的同生产版本运行。

## 5. 未验证边界与下一步

1. **B1 未实现。** close 返回、completion 成功、JVM 退出均不能证明服务端连接释放。实验 runner 在两个实例实际会话与事务归零前保留槽租约，不启动同槽下一项；这是测试隔离措施，不是生产释放/配额协议。
2. 未决 B/fence 保守暂停，包括可能尚未真正写源的旧意图；没有自动判断、重写或修复历史缺口。源 COMMIT 明确拒绝/各种网络晚到结果的完整矩阵未覆盖。
3. 没有为新共享捕获链路增加多 owner 自动接管、guardian 或 JVM 重启后的持久配额恢复。命名锁和当前 MBean 只用于本单 owner 运行，不是物理 fencing 证明。
4. 当前 D/ACK 校验与 L 库存检查的长期成本、巨大 history 的启动成本、90 秒等待在 70 万真实表上的可用性均未测；没有 GC。不能据此承诺规模或长期常驻 SLA。
5. 未覆盖 DDL、任意 SDK 版本、权限不足、数据库/主机重启、同步 close 永不返回、所有旧 INITIAL 变种或完整 A/B 26 项矩阵。旧 R02、历史启动遗漏和 C2 失败材料均保留。

下一步应单独实施并验证 B1 的有界关闭与未知资源保留，随后才讨论持久冷调度和新链路 HA，不能将本轮启动门作为这些能力已经完成的依据。

### 复跑

沿用 Pool 的专用槽、私有凭据和租约文件。先冻结当前候选；旧版复现另外提供 A0/B0 原冻结 classpath。示例路径须替换，runner 不自动释放调用方租约：

```sh
python3 scripts/experiments/capture-admission/run.py \
  --config /private/pool.json --lease /private/lease-a.json \
  --candidate /private/final/candidate.json --legacy-candidate /private/legacy/candidate.json \
  --java /path/to/jdk17/bin/java --output /private/admission-run \
  --suite admission --suite marker --suite reader

python3 scripts/experiments/capture-admission/run.py \
  --config /private/pool.json --lease /private/lease-b.json \
  --candidate /private/final/candidate.json --java /path/to/jdk17/bin/java \
  --output /private/bootstrap-I11 --suite bootstrap --case 'for I11'
```

不得只换 schema/server-id 就在同一槽并发；前项实际释放未知时停止复用。原证据不覆盖，新运行另建目录。
