# 独立 CDC 的受管 guardian 接入实验

## 版本和环境

2026-11-08，基线提交 `68b0a1d20d8ccdb05fb244c58ba87dfe09d128e7`，实验使用其上的未提交候选源码；打包 JAR 的 SHA-256 为 `892f8b43775b0073e2c12ad20a277b2f0ed72e72ce16900baa52ca799a8c63df`。这不是基线提交本身的实验。最终关闭 supervisor 调度开关的候选 JAR SHA-256 为 `ff5bb00d45c8ab990ed90272a3324fa7e09e9dc216446e67d502b59c3548f458`；两份候选的结果分别保留。环境为两个专用本机 MySQL 9.5.0 实例、独立 guardian/supervisor/worker JVM、真实 Actor JDBC Journal 和 MySQL Sink；只使用实验数据库。MySQL 的 performance schema 和连接属性采集在本实验中开启。

## 结果和证据

受管独立入口先完成一张三字段表的全量初始化并进入 STREAMING。两次在实时阶段终止不同 worker 进程，故障后分别对源表执行更新、删除、插入，或更新和插入。两次均从 supervisor 日志观察到 guardian 隔离证明、数据库观察、配额归还、旧 worker 退休和新 worker 启动。第一次追平后源与目标各 2 行、6 个字段相等；第二次各 3 行、9 个字段相等。第二次保留的任务状态为 STREAMING、HEALTHY，同时 `failureCode` 仍为 `execution_interrupted`；状态字段的语义不能仅凭健康字段推断。

- [首次排空失败](attempt1-summary.md)、[归属观察](attempt1-observation.json)、[supervisor 启动日志](attempt1-supervisor.log)、[重启日志](attempt1-supervisor-restart.log)：连接属性采集关闭时，状态库未归属连接导致 `drain_wait`，没有恢复许可。
- [实验环境建表遗漏](attempt2-startup-missing-schema.log)：首次启动新 fixture 缺少旧调度元数据表而失败；补齐后继续，未删除原始失败。
- [第一次接管状态、证明、数据和 T/J/L/D 观察](attempt2-takeover-evidence.json)、[第二次接管](attempt3-takeover-evidence.json)、[supervisor 顺序日志](attempt3-supervisor.log)。T 是目标组账本记录，J 包括 Journal 序号及消费者投影，L 是 capture reader 状态，D 是 Debezium offset 表的实际记录；这些快照不是同一数据库的原子横切面，不能只凭其数值建立全局先后关系。

最终候选在 supervisor 和 worker 均配置 `pekko.workflow.table-scheduling.enabled=false` 时重新运行：一张表进入实时后，终止 guardian 管理的 worker 并在故障后执行更新、删除和插入，隔离、排空和配额归还后新 worker 启动；源与 Sink 逐字段对账为 2 行、6 个字段相等。[最终候选的 T/J/L/D 与对账](final-scheduling-disabled-evidence.json)、[supervisor 证据](final-scheduling-disabled-supervisor.log)。CDC 路由返回 200，Workflow 路由返回 404。

首次 worker 刚启动时 supervisor 记录了短暂 `SQLSyntaxErrorException`，之后不再出现；当前日志没有足够错误上下文，尚未定位，不能表述为已修复。原始 ACK 回调顺序未单独采集。一次 supervisor 重启导致的排空失败和两次 worker 异常恢复不是完整故障矩阵。

## Owner 生命周期接口回归

在上述候选基础上，guardian 进程隔离与数据库排空观察改为依赖 `ManagedOwnerRegistry` 接口，具体实现仍是 `SnapshotOwnershipStore`，仍使用旧 Workflow 调度存储和锁。该版本 JAR SHA-256：`7984594ce06d8ef5622e7eaae866b8f79577ecb2a7a841e231222832dca12159`。

真实 MySQL、Journal 和 Sink 上，关闭调度开关，终止实时 worker 并在故障后执行更新、删除和插入；新 worker 启动后，源和 Sink 各 2 行、6 个字段相等。[对账与持久化记录](registry-interface-evidence.json)、[supervisor 日志](registry-interface-supervisor.log)。相关 4 组测试 30 项通过，包含旧 Journal 样本读取。该实验没有移除旧调度表，因此不能证明独立控制存储已经实现，也没有验证源端 fencing。

## 控制事务与 owner 状态机抽取回归

在接口回归基础上抽出 `ManagedControlTransactions`、`ManagedOwnerStore`；旧适配仍使用 `pekko_table_admission_lock` 与旧配额表。新旧事务实例针对同一锁的 H2 并发测试、事务上下文身份约束、旧 Journal 样本读取及相关回归共 37 项通过。JAR SHA-256：`8552ab3ece32b045de9960e709c386df28fddc6235fb05afc1bedf944fb8cd9c`。

真实 MySQL、Journal 和 Sink 单表实验中，终止实时 worker 并在故障后插入、更新和删除，guardian 隔离与排空后启动新 worker；源和 Sink 各 2 行、6 个字段相等。[对账及 T/J/L/D](control-core-evidence.json)、[supervisor 日志](control-core-supervisor.log)。这是旧兼容锁上的回归，不是独立锁迁移或新旧入口同时运行的 MySQL 验收；没有覆盖未知 B 和源端 fencing。

## CDC 控制存储候选

候选代码增加 `CdcManagedControlStore` 和 `ManagedCdcOwnerStore`，独立 CDC supervisor 与 worker 在受管模式下使用该实现。为与未改造的旧入口互斥，两者仍访问原 `pekko_table_admission_lock` 和旧配额表，沿用旧审计序列；因此它尚不是无需旧调度表的独立数据库方案。旧 owner 尚未排空时不归还旧配额；确认 `db_drained` 后将旧 Workflow 任务重新置为 ready，未将该任务转换为 CDC 任务。

真实 MySQL、Actor Journal 和 Sink 单表测试中，终止实时 worker，故障后更新、删除、插入；guardian 隔离与排空后新 worker 接管，源与 Sink 各 2 行、6 个字段相等。使用 JAR SHA-256 `5db90841fc2e78b4647e1103be6902f582a4f40f6fe49436d08cd2d36764280a`；[T/J/L/D 与对账](cdc-control-store-evidence.json)、[supervisor 日志](cdc-control-store-supervisor.log)。[MySQL 并发测试](cdc-control-mysql-test.log)验证两种存储实例共锁，以及旧配额在未排空时保留、夹具设置排空后重新入队。该夹具直接设置了排空状态，不能证明旧 owner 故障到配额迁移的端到端恢复；也没有验证独立新锁切换、未知 B 或源端 fencing。

## 带旧格式配额的真实接管

在独立 CDC 控制存储候选和真实 MySQL、Journal、Sink 上，实验先使 CDC 表进入实时，然后向实验库注入一条绑定该 worker 的旧格式 `unknown` Workflow 配额。终止 worker 后，guardian 记录隔离和排空，配额从 `unknown` 变为 `ready`，grant 和 owner 清空；新 worker 接管，故障后源表的增删改与 Sink 各 2 行、6 字段一致。[配额前后、T/J/L/D 与对账](cdc-legacy-quota-takeover-evidence.json)、[supervisor 顺序日志](cdc-legacy-quota-takeover-supervisor.log)。旧配额是实验注入，并非由真实旧 Workflow 作业创建；该结果也不证明配额已转换为 CDC 任务或所有迁移故障窗口已覆盖。

## 真实旧 Workflow 作业的配额恢复

在同一隔离 MySQL 实例中运行真实旧 Workflow Actor、Actor Journal 与 MySQL Sink，使用旧调度入口创建真实任务和配额；接管协调器使用 `CdcManagedControlStore`。固定目标已提交、Journal 尚未确认窗口并终止旧 worker，验证 guardian 进程隔离、数据库排空、旧配额释放和旧 Workflow 候选 worker 的自动恢复。源与目标 10 行、60 个字段一致，原始 T/J、owner、许可和审计顺序见[实验目录](real-workflow-cdc-control/result.json)及同目录 `cut.json`、`final.json`。这是测试进程组合，不是 CDC standalone supervisor 和旧 Workflow 之间的跨版本上线迁移；当前仍共用旧锁。

实验基线 `68b0a1d20d8ccdb05fb244c58ba87dfe09d128e7` 加未提交候选，测试运行类路径未冻结；相关源码散列见 [source-hashes.json](real-workflow-cdc-control/source-hashes.json)，不能把基线提交单独当作实验版本。首次执行由于构建排除外部集成标签而运行 0 项，保留 [零用例日志](real-workflow-zero-tests.log)；显式启用标签后的真实用例和 10 行全字段对账通过。数据库层新锁切换、未知 B 和源端 fencing 未被这一用例验证。

## 旧账号锁与存量事务探针

在专用 MySQL 9.5.0 中创建独立测试账号与表，旧连接开启事务并更新行；管理员锁定账号后，新连接被拒绝，但旧连接仍存在且事务仍处于 `innodb_trx`，并可成功提交。关闭旧连接后重试仍被拒绝。该探针证明账号锁不是事务排空证明；它没有验证代理用户或以该账号为 DEFINER 的对象。[测试日志](cutover-account-isolation-test.log)和[源码散列与范围](cutover-account-isolation-evidence.json)。

现有 supervisor 与 worker 的控制库配置校验要求 URL、用户名和密码一致；如果新旧入口共用该账号，直接锁账号也会阻断新入口。当前没有落地持久化切换屏障、数据库锁切换和配额迁移，继续使用旧兼容锁。

## 旧 admission 策略标记探针

在隔离 MySQL 9.5.0 上，将旧 admission 锁行的策略值更新为不匹配标记，重新构造旧存储实例后，旧准入事务拒绝执行且不覆盖该标记；相关两组测试 19 项通过。[日志](cutover-policy-test.log)、[源码散列与范围](cutover-policy-evidence.json)。新实例运行在同一 JVM，不能代替真实进程重启；该探针没有隔离已打开的 Journal 或 Sink 连接，也不构成可用切换屏障。

## 旧账号锁定组件

`LegacyMySqlAccountLock` 对精确账户执行锁定前核对实例 UUID、管理账户和受保护账户，拒绝通配 host，并在重复调用时读取锁状态。隔离 MySQL 的测试覆盖保护账户、错误 UUID、通配 host 和重复调用；账号锁定后旧事务仍可提交。两组测试 19 项通过。[实验日志](legacy-account-lock-component-test.log)、[源码散列和边界](legacy-account-lock-component-evidence.json)。该组件尚未接入持久化切换流程；它不验证代理登录、DEFINER、旧会话排空或 guardian 隔离，不是完整切换屏障。

## 独立连接上的旧账号锁定

后续候选将 `ALTER USER` 放在通过 `DriverManager` 新建的管理连接中，避免隐式提交调用方的应用事务。隔离 MySQL 中，调用方管理事务的测试行在账号锁操作后仍能回滚；旧账号的既有事务仍可提交。两组回归 19 项通过，覆盖受保护账号、错误实例 UUID、通配 host 和重复锁定。[日志](legacy-account-fresh-connection-test.log)、[源码散列和限制](legacy-account-fresh-connection-evidence.json)。组件尚未接入持久化切换，也不能证明 guardian 隔离或所有账号别名、代理及 DEFINER 的排空。

## 旧账号 host 变体的失败与修复

真实 MySQL 中为同一用户名创建另一个 host 账号，旧账号锁组件此前仍返回成功；[失败日志](legacy-account-alias-red.log)保留该反例。候选修改在发现同名用户有其他 host 时拒绝锁定；[回归日志](legacy-account-alias-green.log)记录两组 19 项测试通过。该检查和锁操作之间没有阻止其他管理员并发创建或解锁账号，也没有证明 PROXY、DEFINER、guardian 或持久化切换，不能把它当成完整的旧写入者隔离证明。[版本散列与边界](legacy-account-alias-evidence.json)。

## 账号锁定意图与独立 JVM 崩溃探针

新增的锁定意图将精确账号、实例、受保护账号摘要和操作 ID 绑定到 supervisor 控制代次，凭证不入库。测试用独立 JVM 在账号锁 DDL 提交后、意图确认前异常退出，观察到退出码 37 和数据库中 `INTENT`；更换控制代次后重新检查并记录 `ACCOUNT_LOCK_OBSERVED`，外部解锁后重入会再次锁定。旧事务仍能提交，因此这个观察不能授权切换或代替排空。[测试日志](legacy-intent-process-final-test.log)、[源码散列与边界](legacy-intent-process-final-evidence.json)。两组测试 20 项通过；这里不是生产 supervisor 故障、guardian 隔离或旧 Workflow 端到端切换。

## 持久化观察过期后的重检

真实 MySQL 实验中，独立测试 JVM 在账号锁 DDL 后异常退出，数据库仍保留 `INTENT`。新的控制代次重入并记录 `ACCOUNT_LOCK_OBSERVED` 后，另一个管理连接解锁旧账号，旧账号能够重新连接，而历史状态仍然不变；再次重入会重新锁定账号。两组共 20 项测试通过。[测试日志](legacy-intent-stale-observation-test.log)、[子进程日志](legacy-intent-stale-observation-child.log)、[源码散列与边界](legacy-intent-stale-observation-evidence.json)。这说明持久化记录是历史观察，不能作为当前账号隔离证明；代理身份、管理员并发变更、guardian 接管及数据库锁迁移仍未打通。

## 代理授权清单探针

专用 MySQL 9.5.0 的代理开关默认关闭，但 `mysql.proxies_priv` 已存在 root 的通配代理授权；实验控制账号对两个临时账号执行显式 `GRANT PROXY` 返回 MySQL 1698，未建立该授权，临时账号已清理。[只读清单与失败代码](proxy-inventory-evidence.json)。此结果不能说明代理登录不可用，也未证明高权限账号无法并发修改账号或代理配置。MySQL 文档明确指出账号锁不阻断代理和 `DEFINER` 对象，因此持久化的账号锁观察不能作为切换许可。

## 受控管理员下的代理与 DEFINER 检查

采用用户确认的前提：特权管理员可信，切换期间账号和代理授权变更由同一受管控制器独占。候选代码把可信代理主体绑定到不可变意图，检查针对旧账号及通配目标的代理授权；不在可信清单内时拒绝。它还要求直接全局对象可见性，并在锁定前后检查旧账号作为视图、例程、触发器、事件的 `DEFINER`。

[首次回归失败](trusted-proxy-first-failure.log)由测试连接的可重复读快照导致：该连接先查询代理表，再读旧事务提交结果时仍看到旧视图；使用独立连接读取代理清单后回归通过。另一个[真实缺口](definer-negative-before-fix.log)是旧账号作为视图 DEFINER 时旧代码没有拒绝。最终[真实 MySQL 测试](definer-inventory-final.log)覆盖四类对象和缺少全局可见性时的拒绝，两组 20 项通过。[源码散列与边界](trusted-proxy-definer-evidence.json)。可信管理员独占尚未由系统验证或强制；没有验证实际代理登录、guardian 排空、锁与配额切换或真实 Workflow 端到端迁移。

## 旧账号授权约束回归

在用户接受受控可信管理员、切换期间单一控制器独占账号和代理授权变更的前提下，候选账号锁定组件拒绝旧账号的角色、代理、系统库授权、账号管理能力、全局写权限及 `EXECUTE`。此前全局 `UPDATE ON *.*` 和库级 `EXECUTE` 被误判为安全，分别保留[全局授权失败](legacy-global-grant-negative.log)和[EXECUTE 失败](legacy-execute-negative.log)。[最终真实 MySQL 与单元回归](legacy-grants-final-mysql.log)共 32 项通过，覆盖真实授权修改及独立测试 JVM 崩溃重入；[源码散列和边界](legacy-grants-final-evidence.json)。

授权独占目前是明确假设，尚未由系统强制；实际代理登录、guardian 隔离及排空、持久化切换屏障、锁和配额迁移均未由本轮证明。

## 强制角色和匿名账号负例

在用户接受的受控管理员、单一控制器权限独占前提下，真实 MySQL 负例发现：设置 `mandatory_roles` 后，原组件仍接受旧账号锁定，[失败日志](mandatory-role-before-fix.log)已保留。候选代码现对非空强制角色或匿名账号安全拒绝，并在锁定前后重新检查；[最终回归](mandatory-role-final-mysql.log)共 32 项通过，涵盖强制角色、匿名账号、未知代理、四类 DEFINER 对象、旧账号危险授权和测试 JVM 中断重入。[源码散列与边界](mandatory-role-final-evidence.json)。该限制较保守；管理员独占尚未由系统强制，持久化切换、guardian 排空及锁/配额迁移仍未完成。

## 库名通配授权负例

旧账号拥有 `UPDATE ON m%.*` 时，该模式可能覆盖 `mysql` 系统库；[修复前单元测试](legacy-schema-wildcard-before-fix.log)显示此前只检查字面 `mysql` 的逻辑没有拒绝。候选代码按保守模式判断库名授权是否可能覆盖系统库；无法判定的转义形式也拒绝。[真实 MySQL 回归](legacy-schema-wildcard-final.log)覆盖通配授权、强制角色、匿名账号、旧 DEFINER 对象与崩溃重入，共 32 项通过；[源码散列和边界](legacy-schema-wildcard-final-evidence.json)。该验证没有完成持久化切换屏障或 guardian 端到端恢复。

## 控制代次与账号 DDL 的固定窗口

候选代码先提交不可变账号锁意图，然后持有 supervisor 控制事务锁执行独立 MySQL 管理连接的账号 DDL；该连接使用受限连接与 socket 超时，不能通过 JDBC URL 覆盖凭据或超时。真实 MySQL 测试让一个控制事务持锁，另一控制代次等待，释放后旧代次被拒绝；独立测试 JVM 在账号 DDL 后异常退出，意图仍可由新代次重检。旧事务仍能提交。四组测试 35 项通过，[日志](controller-fence-account-final.log)、[源码散列与范围](controller-fence-account-final-evidence.json)。

这只是组件级窗口，DDL 期间持有兼容锁，具体等待和生产容量未测；账号锁观察不是 guardian 隔离或排空证明。切换主流程、特权管理独占的强制措施、新数据库锁和旧配额迁移、真实旧 Workflow 端到端验证均未完成。

## 源码边界

候选入口允许经 supervisor 启动受管 `CdcStandalone`；未受管 HA 与未完成的 V2 源端 fencing 继续安全拒绝。独立入口没有启动 Workflow Sharding、通用调度 Actor 或前端路由；最终实验 supervisor 与 worker 的调度开关均为 false。此前候选的 supervisor 和 owner 存储依赖 `TableSchedulingStore` 及旧表。后续 CDC 控制存储候选不再实例化该类，但仍依赖旧锁、旧审计序列及旧配额表，见上节。历史持久化样本由本轮相关单元测试读取，不能外推到全部历史状态。

排空观察现在要求本观察连接的 `pekko_owner` 属性真实可见，避免把采集关闭且丢失计数为零误当成完整观测；属性不可见时返回不完整，不能获得排空许可。未知 B、进行中的初始化和 source fence 的跨库竞争没有在本实验得到端到端证明，继续安全暂停；不把单表、两次实时故障结论扩大到 70 万表或所有故障窗口。

相关六组测试共 42 项通过，`Universal/stage` 构建通过；两个实验实例在进程关闭后观察到事务为零，随后停止并释放租约。
