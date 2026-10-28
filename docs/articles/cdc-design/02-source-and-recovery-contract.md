# 二：从分页快照到持续同步——沿源码走通 LOW/HIGH 与 B→F

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：自建与复用](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

[A/B 补测](../../experiments/2026-10-27-ab-followup/README.md)保留了静默源的 ACK 与实际 D、旧 B/INITIAL 的安全暂停，以及 D 滞后和原始 ACK 切点；A05 尚不能仅从现有持久状态证明源写从未发生。

> **文章修订：2026-10-27。** 当前源码使用仓库相对入口，版本与新平台组合实测见[平台报告](../../experiments/2026-10-27-cdc-platform/README.md)；历史 LOW/HIGH 矩阵与旧失败保留原版本。新增源码行为不预填为实验通过。

一个实例中已有五张表持续同步，现在增加 f/g。本文回答：怎样在分页期间接住变化，并把新表完整地交给持续增量消费？沿 `CdcTaskStore → CdcPlatformRuntime → BootstrapRunner.step → BootstrapSnapshot/BootstrapStore` 的调用链，本文解释固定范围、LOW/HIGH、封存与 B→F。平台现在按有限步骤驱动这条内核；计算规则没有因为调度方式改变而简化，恢复与启动前提仍由第四篇推导、第三篇检验。

这里的 Split 是持久化范围窗口，不是 Flink 的 `MySqlSnapshotSplit` 对象；同表窗口当前串行执行。首版采用独占空目标、固定 Schema 和单列整数主键，初始化完成前不向业务发布。

## 1. 先准备增量保留边界，再读取存量

新入口先由 [CdcTaskStore](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcTaskStore.scala)持久接受意图，再由 [CdcPlatformRuntime](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcPlatformRuntime.scala)授予一次有限工作。`202 Accepted` 只证明意图已保存，不等于 B 已生效或目标已初始化。

[BootstrapRunner.step](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala)验证源谱系、固定 Schema、目标占用和当前 J。省略恢复、取消分支后，B 与 U 的处理条件可展开为：

```scala
if (logStore.subscription(id).isEmpty) {
  capture.subscribe(id, table, generation)
  didWork = true
} else if (logStore.subscription(id).exists(_.b.isEmpty)) {
  complete = false // 让出；没有用一个 B_PENDING 冒充捕获已生效
} else if (!state.planned) {
  val upper = source.maximum()
  update(_.copy(
    phase = "SCANNING",
    upper = upper,
    planned = true
  ))
  didWork = true
}
```

`maximum()` 对主键执行 `SELECT MAX(...)`，得到扫描上界 U。`planned=true` 区分“已计划的空表”和“尚未读取上界”，避免空表恢复时反复计划。兼容 `run()` 仍保留原连续调用方式；平台路径不再让每张表长期停在 B 的等待循环中。

写 B 前还要通过 capture 级启动准入：当前 attempt 的生命周期、原始 ACK 与实际完成态 D 必须共同成立。[已提交的 A1/A2](../../experiments/2026-10-27-capture-admission/README.md)已验证旧 B＋INITIAL 等状态的安全拒绝，以及限定正常路径。`step()` 读取缓存状态分配工作，capture 协调器刷新完整 D/L 证据，真正新增 B/fence 的入口仍实际校验。

B 是增量保留起点，不是初始化完成证明。[旧启动反例](03-real-mysql-journal-failure.md#e-startup)仍然说明：窗口算法成立不能替代源端连续性。新门不会追溯补齐已有缺口，也不能识别所有过去跳点后又形成完成态 D 的旧命名空间。

## 2. Split 范围与 SQL 页不是同一个粒度

[BootstrapSnapshot.nextUpper](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapSnapshot.scala) 在剩余范围内按主键选出下一段上界。对非首段，等价 SQL 为：

```sql
SELECT id AS n
FROM source_table
WHERE id > :lower AND id <= :U
ORDER BY id
LIMIT :chunkRows;
```

首段没有 `id > :lower` 条件。若候选键不足 `chunkRows`，窗口直接结束在冻结的 U；否则取最后一个候选键。这使稀疏键、负数和删除后的尾段仍可按同一范围规则结束。

[scanStep](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala) 将范围保存为不可变 spec：

```scala
val spec = canonical(JsObject(
  "index"  -> JsNumber(s.index),
  "lower"  -> s.nextLower.map(JsString(_)).getOrElse(JsNull),
  "upper"  -> JsString(u),
  "schema" -> JsString(source.codec.schemaId)
))
spool.register(w, spec)
```

窗口内部再用主键游标分页，而不是 OFFSET：

```sql
SELECT 已固定的全部列
FROM source_table
WHERE id > :cursor AND id <= :windowUpper
ORDER BY id
LIMIT :pageRows;
```

默认 `chunkRows=32`、`pageRows=8` 是首版代码参数，不是生产推荐容量。`chunkRows` 规划范围，`pageRows` 限制单次返回；范围冻结后并发插入可能增多，仍需总行数和字节预算。当前要求单列有符号整数主键，不覆盖字符串键、复合键或任意排序规则。

## 3. 用真实 LOW/HIGH 将一次一致性读夹住

[scanStep](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala) 的核心顺序是：

```scala
val low = fence(w + "_L", "LOW", context(w))

admission.reading {
  source.scan(
    w, lower(spec), number(spec, "upper"),
    limits.pageRows, spool, () => canceled
  )
}

if (canceled) return
val high = fence(w + "_H", "HIGH", context(w))
```

这里的 `fence()` 先请求 marker，再等待 `logStore.fence(name).exists(_.covered)`。LOW/HIGH 都经源库 `pekko_capture_control` 的独占事务产生。共享 Reader 从真实 binlog 捕获控制行，核实事务结束后绑定源点，在 L 中保存 FENCE 帧，并通过 ACK 引用链建立覆盖证明。不是用程序时钟或当前最大业务主键冒充水位。

[BootstrapSnapshot.scan](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapSnapshot.scala) 在 LOW 之后建立读视图：

```scala
c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
c.setReadOnly(true)
c.setAutoCommit(false)
q.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
```

同一窗口的所有 SQL 页共用这个连接和 RR 事务。每页立即写入状态库的 RAW 页；循环检查取消、30 秒时长、总行数和字节预算。退出时 rollback 只读事务，连接由外层关闭；`source.scan()` 返回后才请求 HIGH。

顺序因此是：LOW 覆盖 → 开启读视图 → 分页 → 结束读视图/关闭连接 → HIGH 覆盖。各窗口并没有共享一个全表 RR 视图。

这里有一个必须单独记住的边界：**covered 不等于实际 D 已持久化。** 当前 [回调确认代码](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala) 先 `store.coverSequence(...)`，再调用原始 SDK 句柄；SDK 后续 flush 又是另一阶段。证明 L 可回放和证明 Reader 下次从哪里重新读，是两件事。

## 4. 用快照作为基底，按日志顺序回填

[BootstrapFold](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapProtocol.scala) 使用 `TreeMap[BigDecimal, JsObject]` 保存当前窗口内容。先读取 RAW 页，再扫描 LOW 之后、HIGH 之前的完整日志组：

```scala
fold.snapshot(rows)

val entries = logStore.readThrough(
  id, after, high.id, limits.groupBatch
)
entries.foreach { entry =>
  entry.group.members.foreach { case (_, event) =>
    fold.event(event.op.code, event.key, event.after)
  }
  after = entry.sequence
}
```

其中每一页 RAW 都会装入 fold；上例只展示一次读取和一次日志批处理，真实实现循环到相应区间读完。合并规则如下：

```scala
if (within(n)) op match {
  case "d" =>
    replace(n, None)
  case "c" | "u" =>
    val value = after.getOrElse(
      throw new IllegalArgumentException("missing full row image")
    )
    require(key(value) == n)
    replace(n, Some(value))
  case _ =>
    throw new IllegalArgumentException("unsupported bootstrap event")
}
```

`within` 检查 `(lower, upper]`。CREATE/UPDATE 使用完整 after image 覆盖；DELETE 删除键。上游主键修改提供 d/c 完整组，fold 对每个成员按自己的键判定范围。当前要求 InnoDB、ROW/FULL 和固定 Schema；不能把部分列更新直接套进这套替换算法。

在假设日志完整、顺序正确、Schema 稳定的条件下，幂等的完整行替换和删除可把快照推进到 HIGH 对应的窗口行状态。这个推理不包含跨表事务整体可见，也不能从普通 group 推出整个源事务一次性提交。

## 5. 回填结果要封存，不能只保存一个扫描游标

[BootstrapStore.seal](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStore.scala) 将 FINAL 页与 manifest 在同一状态事务保存。manifest 包含固定范围 spec、LOW/HIGH 点、L 序号、RAW 页摘要、回填组摘要链、FINAL 页摘要及行数。

```scala
manifest = Some(
  spool.seal(w, proof, fold.result, limits.pageRows)
)
```

RAW/FINAL 存在状态库，一个窗口的 fold 在内存中，并有内容字节预算。这不是“全表装入内存”，也不是“总 JVM heap 已有硬上限”：JSON 对象、索引和 SDK 队列还有额外开销。

封存后，[BootstrapTarget.applyPage](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapTarget.scala) 把页效果与 receipt 放在同一目标事务，随后 Runner 才推进 Journal：

```scala
val fresh = target.applyPage(w, p, m, rows) // 先 T
update(_.copy(appliedPage = p + 1))         // 后 J
```

目标页 identity 包含窗口、页号与 manifest 内容。若 T 成功而 J 未持久化，恢复重交同一页时通过 receipt 核实，而不是读取源端此刻的新数据后沿用旧身份。具体恢复分支放在第四篇。

## 6. 为什么各窗口 HIGH 之后还要回放 B→F

假设窗口一 `(−∞,32]` 已完成，主键 1 随后变成 1000；或者窗口二中的主键 40 变成 −40。只靠各窗口自己的 LOW/HIGH，后发生的迁移可能落到已经越过的范围。

当前实现没有把“每窗口 HIGH 过滤”当成全表完成证明。所有范围完成后，[scheduledScan](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala)固定 F。F 请求与 F 已 covered 是两个事实，步骤分别处理：

```scala
logStore.fence(id + "_F") match {
  case None =>
    capture.requestFence(id + "_F", id, "F", context("F"))
  case Some(f) if f.covered =>
    update(_.copy(
      phase = "REPLAYING",
      fence = Some(f.id),
      deltaAfter = logStore.subscription(id).get.bSequence.get
    ))
  case _ =>
    // 原请求还未覆盖；结束本步，等待原身份的边界
}
```

这段代码按相同分支展开，省略 observer 和 `didWork` 返回值。等待 F 不意味着可以另选一个更靠后的 F；已登记的 operation、绑定点与后续恢复责任保持不变。

然后对整张表完整回放 B→F 的原始组，而非只回放最后一个窗口 HIGH 之后的日志。窗口 fold 不把原始组写成“目标已应用”；baseline 页 receipt 与原始组账本分命名空间，因此不会因快照看见过该行就错误跳过必要回放。

完整回放可能先把某个 baseline 行改回较早值，再继续推进；中间源状态可能与目标暂时不同。这就是第一版要求独占空目标、初始化完成前不向业务发布的原因。最终收敛要求必要日志仍被保留、捕获连续且目标 Apply 满足其事务/幂等契约；不是只靠 Upsert 三个字。

## 7. F 确定有限追赶边界，审计决定能否激活

[replay](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala) 在初始化与实时阶段共用同一条应用路径，区别是上界：

```scala
val entries =
  if (tail)
    logStore.read(id, s.deltaAfter, limits.groupBatch)
  else
    logStore.readThrough(id, s.deltaAfter, s.fence.get, limits.groupBatch)
```

每次先等待 Sink 的完整组提交，再将 `deltaAfter` 和批序号写进 J。有限回放没有剩余 GROUP 时，游标明确推进到 F 的帧序号；空表、F 前无业务 GROUP 也能切换，不需要伪造一个业务事件。

进入 ACTIVATING 后，核对完成范围连续、封存输入证明、目标 baseline receipt 和 B→F 组账本。平台的 `StepAudit.advance()` 每次检查一个已完成窗口或一批组，全部校验完成后才执行：

```scala
stepAudit.get.advance().foreach { proof =>
  // 原代码仍会复查取消及维护停止
  target.activate(proof)             // 激活 receipt 的 T
  val accepted = update(_.copy(phase = "STREAMING")) // 随后 J 接受
}
```

这个审计 cursor 留在有界活动缓存中；失去缓存会重新校验，不能凭“之前调用过 audit”直接激活。兼容 `run()` 的 `audit()` 仍连续执行同一证明链，新的调度层则把它拆成有限单位。

审计验证的是保存材料与目标账本，不是重新扫描源库的逐字段对账。后者必须来自独立实验。激活 receipt 也不是 SQL 原子换表，不能单独作为业务已发布凭据。

STREAMING 继续从 `deltaAfter` 读取 L，每步至多一批。无剩余组时不保留一个休眠线程：平台将该表 park，之后由 capture 级 L dispatch 的持久游标和 `wake_seq` 唤醒。共享 Reader 不在这里重启，也没有变成每表一个 Engine。

## 8. 调度让出后，哪些事实允许复用

有界执行反复结束连接，因此必须区分“这步已经干净结束”和“这份输入已经封存”。前者是资源事实，后者是恢复算法的内容事实。

同 runtime 内，控制索引保存已确认释放的 grant 及完整 J progress。新 Runner 只有核对定义、execution、revision 和全部数据进度后，才把它认作正常续调，不额外消耗一次 Interrupted 恢复预算。Journal 的合法取消转换可以单调改变取消状态；任意迟到 checkpoint、定义或游标变化不能拿旧 clean progress 跳过校验。新 JVM 不复用旧 owner 的这份许可。

即使 clean progress 完全匹配，旧 RR 也已经结束。例如第 0 页写入 RAW 后发生可重试错误，连接正常 rollback/close，J 仍指向未封存窗口。资源可以释放，但下次必须沿用旧 spec 的 `(lower, upper]`，换 attempt 整段重读。不能重新调用 `nextUpper` 移动边界，也不能把旧第 0 页与新 RR 的后续页拼成同一快照。

[BootstrapStepContinuation](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStep.scala)还检查一个更强的拒绝条件：

```scala
val lostView = progress.window.nonEmpty && !windowSealed
if (lostView) require(
  progress.manifest.isEmpty && progress.appliedPage == 0,
  "accepted bootstrap seal missing; source reread forbidden"
)
```

J 已接受 manifest hash 或目标页后，丢失 seal 已经是证据损坏，不能降格为普通 RAW 失败。此时暂停；如果 seal 存在而 J 还没记录其 hash，则继续校验原 seal，不生成新内容。这个 guard 同时约束连续 `run()` 与新步骤恢复路径。

同 owner 重建、失去真实读视图、保留原范围与恢复预算的组合，必须由[本轮平台实验](../../experiments/2026-10-27-cdc-platform/README.md)单独验证。历史 I11 的进程故障实验不能替代新步骤 API 的缓存重建实验。

## 9. 实现结论与验收边界

源码包含分页、真实 marker、窗口回填、封存、整段恢复重读、B→F、激活与持续消费；平台还增加了持久意图、有限步骤和 L 唤醒。两层需要分别验收。[第三篇](03-real-mysql-journal-failure.md)以 I08、I11、I16 和启动反例检验这条路径；[第四篇 G4](04-protocol-and-validation.md#g4)给出有限回放收敛的逐键推理。完整矩阵的 61 项检查另见导读。

[启动反例 E0](03-real-mysql-journal-failure.md#e-startup)仍保留 B 已绑定且实际 D=INITIAL 后重启越过变更的真实结果。它破坏的是本篇所依赖的捕获连续性。A1/A2 的安全门改变了后续特定风险状态的启动行为，不删除旧反例，也不把“暂停”写成“历史已经一致”。本轮平台的步骤、API 和资源结果以独立报告为准，不能直接合并进旧 61 项数字。当前没有同表并行 Split、DDL 演进、新共享链路自动接管或 70 万真实表容量保证。

### 同 owner 的干净 continuation 也不能保存 RR

本轮 P09 使用真实 MySQL、Actor Journal 和 Sink。在 RAW 页已经落盘后，由测试 observer 抛出一次 SQLException；随后显式停止并重建 Journal Actor 和 Runner，保持相同 owner/execution，再修改源端五字段并删除原范围的上界行。新 Runner 使用原 spec 和范围、新 attempt 与新 RR 连接，没有把一次已计入的失败再次消耗恢复预算；最终 63 行、378 字段相等。

这验证了上文“进度可以延续，读视图不能延续”的指定窗口。异常来自测试探针，Runner 替换也由测试显式触发，不能写成真实网络断线或任意自动驱逐恢复。原始页、连接、范围、L/T/J/D 及全字段证据见[平台实验 P09](../../experiments/2026-10-27-cdc-platform/README.md)。

当前源码还明确区分源 marker 和目标事务：LOW/HIGH/F 的源 COMMIT 结果未知时，捕获域暂停，保留原 operation，不靠反复写 marker 继续初始化。对应 13 个故障切点验的是 SAFE_PAUSED，而非自动收敛；已完成态 D 仅落后于新的已绑定边界则仍可能合法重放，不能把它混同 INITIAL。

## 未绑定 B 与未知 COMMIT 的反例

已提交但未绑定的 B，即使 D 是完成态且位于 B 之前，本轮重启仍返回 `UNBOUND_MARKER_UNPROVEN`、没有创建 Engine。这是安全暂停，不是自动重放收敛；A05 需要的未发出源写证明仍未建立。另一个实验将源 COMMIT 的回复固定为未知，再直接走 Store 的待定取消路径，观察到 B_PENDING 被标为 CANCELED，随后源事务迟提交。这个结果违反“结果未知时不提前完成取消”的验收预期；测试没有覆盖完整 Runner 取消链路，不能据此声称所有调用路径都会发生。

完成态 D 的文件名被测试改为另一 binlog 命名空间后，真实重启以 `CHECKPOINT_UNCOVERED` 拒绝并且没有创建 Engine；此时四行 24 字段对账相等。这是对已停写样本的安全暂停，不是恢复收敛。

在一项测试门固定的 A15 实验中，关闭后的迟到 SDK callback 被消费逻辑拒绝，状态保持 STOPPING，新订阅未登记；它只覆盖同一实例，不能推广到所有跨 JVM 迟到信号。

`B_BOUND` 是观察事件名，不足以单独证明 B 已持久绑定。上面的实际事务、状态与对账见[补测报告](../../experiments/2026-10-28-ab-matrix/README.md)；本轮没有修改恢复协议。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/02-delivery-and-ack-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。[A1/A2 报告](../../experiments/2026-10-27-capture-admission/README.md)与[平台报告](../../experiments/2026-10-27-cdc-platform/README.md)分别给出后续版本的证据入口，不覆盖旧材料。
