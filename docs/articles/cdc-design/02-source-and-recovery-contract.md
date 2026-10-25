# 二：从分页快照到持续同步——沿源码走通 LOW/HIGH 与 B→F

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：自建与复用](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

> **文章修订：2026-10-26；代码基线 [`55fad0c`](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)。** 本文依据源码与保存材料论证，没有重跑数据库。版本、验证范围和未覆盖项统一见[系列导读](README.md#全系列验证范围汇总)。

一个实例中已有五张表持续同步，现在增加 f/g。本文回答：怎样在分页期间接住变化，并把新表完整地交给持续增量消费？依据 `BootstrapRunner → BootstrapSnapshot → BootstrapFold → BootstrapStore` 的调用链，本文解释当前固定范围、LOW/HIGH、封存和 B→F 的实现；其恢复与启动前提分别在第四篇证明、第三篇检验。

这里的 Split 是持久化范围窗口，不是 Flink 的 `MySqlSnapshotSplit` 对象；同表窗口当前串行执行。首版采用独占空目标、固定 Schema 和单列整数主键，初始化完成前不向业务发布。

## 1. 先准备增量保留边界，再读取存量

入口是 [BootstrapRunner.run](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L172)。它验证源谱系、固定 Schema 和目标占用后，订阅表并等待 B：

```scala
capture.subscribe(id, table, generation)
poll("B") {
  logStore.subscription(id).exists(_.b.nonEmpty)
}

if (!state.planned) {
  val upper = source.maximum()
  update(_.copy(
    phase = "SCANNING",
    upper = upper,
    planned = true
  ))
}
```

代码块按当前源码展开排版，省略日志与外层条件。`maximum()` 对主键执行 `SELECT MAX(...)`，得到扫描上界 U。`planned=true` 区分“已计划的空表”和“尚未读取上界”，避免空表恢复时反复计划。

B 是保留增量的起点，不是初始化完成证明。当前启动准入仍有已知 INITIAL-D 缺口，第三篇会给出真实反例；本文算法的前提是这次订阅的必要变更确实被 Reader 连续捕获并保存在 L 中。

## 2. Split 范围与 SQL 页不是同一个粒度

[BootstrapSnapshot.nextUpper](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapSnapshot.scala#L25) 在剩余范围内按主键选出下一段上界。对非首段，等价 SQL 为：

```sql
SELECT id AS n
FROM source_table
WHERE id > :lower AND id <= :U
ORDER BY id
LIMIT :chunkRows;
```

首段没有 `id > :lower` 条件。若候选键不足 `chunkRows`，窗口直接结束在冻结的 U；否则取最后一个候选键。这使稀疏键、负数和删除后的尾段仍可按同一范围规则结束。

[scanStep](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L117) 将范围保存为不可变 spec：

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

[scanStep](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L125) 的核心顺序是：

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

[BootstrapSnapshot.scan](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapSnapshot.scala#L31) 在 LOW 之后建立读视图：

```scala
c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
c.setReadOnly(true)
c.setAutoCommit(false)
q.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
```

同一窗口的所有 SQL 页共用这个连接和 RR 事务。每页立即写入状态库的 RAW 页；循环检查取消、30 秒时长、总行数和字节预算。退出时 rollback 只读事务，连接由外层关闭；`source.scan()` 返回后才请求 HIGH。

顺序因此是：LOW 覆盖 → 开启读视图 → 分页 → 结束读视图/关闭连接 → HIGH 覆盖。各窗口并没有共享一个全表 RR 视图。

这里有一个必须单独记住的边界：**covered 不等于实际 D 已持久化。** 当前 [回调确认代码](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala#L181) 先 `store.cover(...)`，再调用原始 SDK 句柄；SDK 后续 flush 又是另一阶段。证明 L 可回放和证明 Reader 下次从哪里重新读，是两件事。

## 4. 用快照作为基底，按日志顺序回填

[BootstrapFold](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapProtocol.scala#L17) 使用 `TreeMap[BigDecimal, JsObject]` 保存当前窗口内容。先读取 RAW 页，再扫描 LOW 之后、HIGH 之前的完整日志组：

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

[BootstrapStore.seal](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStore.scala#L76) 将 FINAL 页与 manifest 在同一状态事务保存。manifest 包含固定范围 spec、LOW/HIGH 点、L 序号、RAW 页摘要、回填组摘要链、FINAL 页摘要及行数。

```scala
manifest = Some(
  spool.seal(w, proof, fold.result, limits.pageRows)
)
```

RAW/FINAL 存在状态库，一个窗口的 fold 在内存中，并有内容字节预算。这不是“全表装入内存”，也不是“总 JVM heap 已有硬上限”：JSON 对象、索引和 SDK 队列还有额外开销。

封存后，[BootstrapTarget.applyPage](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapTarget.scala#L54) 把页效果与 receipt 放在同一目标事务，随后 Runner 才推进 Journal：

```scala
val fresh = target.applyPage(w, p, m, rows) // 先 T
update(_.copy(appliedPage = p + 1))         // 后 J
```

目标页 identity 包含窗口、页号与 manifest 内容。若 T 成功而 J 未持久化，恢复重交同一页时通过 receipt 核实，而不是读取源端此刻的新数据后沿用旧身份。具体恢复分支放在第四篇。

## 6. 为什么各窗口 HIGH 之后还要回放 B→F

假设窗口一 `(−∞,32]` 已完成，主键 1 随后变成 1000；或者窗口二中的主键 40 变成 −40。只靠各窗口自己的 LOW/HIGH，后发生的迁移可能落到已经越过的范围。

当前实现没有把“每窗口 HIGH 过滤”当成全表完成证明。所有范围完成后，[scanStep](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L110) 固定一个 F，并把增量应用游标重置到订阅 B 的 L 序号：

```scala
val f = fence(id + "_F", "F", context("F"))
update(_.copy(
  phase = "REPLAYING",
  fence = Some(f.id),
  deltaAfter = logStore.subscription(id).get.bSequence.get
))
```

然后对整张表完整回放 B→F 的原始组，而非只回放最后一个窗口 HIGH 之后的日志。窗口 fold 不把原始组写成“目标已应用”；baseline 页 receipt 与原始组账本分命名空间，因此不会因快照看见过该行就错误跳过必要回放。

完整回放可能先把某个 baseline 行改回较早值，再继续推进；中间源状态可能与目标暂时不同。这就是第一版要求独占空目标、初始化完成前不向业务发布的原因。最终收敛要求必要日志仍被保留、捕获连续且目标 Apply 满足其事务/幂等契约；不是只靠 Upsert 三个字。

## 7. F 确定有限追赶边界，审计决定能否激活

[replay](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L156) 在初始化与实时阶段共用同一条应用路径，区别是上界：

```scala
val entries =
  if (tail)
    logStore.read(id, s.deltaAfter, limits.groupBatch)
  else
    logStore.readThrough(id, s.deltaAfter, s.fence.get, limits.groupBatch)
```

每次先等待 Sink 的完整组提交，再将 `deltaAfter` 和批序号写进 J。有限回放没有剩余 GROUP 时，游标明确推进到 F 的帧序号；空表、F 前无业务 GROUP 也能切换，不需要伪造一个业务事件。

进入 ACTIVATING 后，[audit](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L87) 核对完成范围连续、封存输入证明、目标 baseline receipt 和 B→F 组账本；随后写目标激活 receipt，再由 Journal 接受 STREAMING：

```scala
val proof = audit()
target.activate(proof)
// 原代码在这里还会复查取消
val accepted = update(_.copy(phase = "STREAMING"))
```

`audit()` 是保存材料与目标账本的一致性审计，不是重新扫描源库的逐字段对账。后者来自第三篇的独立真实实验。激活 receipt 也不是 SQL 原子换表，不能单独作为业务已发布凭据。

STREAMING 继续从 `deltaAfter` 读取 L，空批暂时休眠。共享 Reader 没有在此重启或改成每表一个 Engine；变化的是表级消费者由有限 B→F 切到持续 tail。

## 8. 实现结论与验收边界

源码已包含分页、真实 marker、窗口回填、封存、整段恢复重读、B→F、激活与持续消费。[第三篇](03-real-mysql-journal-failure.md)以 I08、I11、I16 和启动反例检验这条路径；[第四篇 G4](04-protocol-and-validation.md#g4)给出有限回放收敛的逐键推理。完整矩阵的 61 项检查另见导读。

[启动反例 E0](03-real-mysql-journal-failure.md#e-startup)仍保留 B 已绑定且实际 D=INITIAL 后重启越过变更的真实结果。它破坏的是本篇所依赖的捕获连续性，不能因为窗口 fold 自身规则成立就忽略。当前还未提供同表并行 Split 调度、DDL 演进、自动接管或 70 万真实表容量保证。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/02-delivery-and-ack-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。
