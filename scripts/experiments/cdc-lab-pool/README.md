# CDC 本机实验槽

只管理受控 JSON 中的新建实验实例。标准库 Python 3.11+、PATH 中的 `mysqld/mysql`、构建时 JDK17 与 sbt；不改旧 bootstrap runner。不同 schema、server-id 不是槽隔离：每槽两个独立 mysqld，四者 UUID、port、datadir 都必须不同。

## 配置与租约

`pool.py example --runtime-root /private/tmp/<new-private-pool> --output <pool.json>` 生成两个槽的清单。实例默认 buffer pool 64MiB、max_connections 24、并行度 2；初始化前检查监听端口。3306、13365、13366 不允许配置为可管理端口。每个 datadir 必须在对应 slot/role 的独占子目录，拒绝交叉和重复 UUID/port。JSON 是受控输入，不是运行不可信代码的安全边界。

以下以本轮实际配置为例。所有命令从仓库根目录运行。凭据和 token 仅写 private runtime，文件 0600、实例父目录 0700；仓库清单只保存引用。不要把 credential JSON、client.cnf、token 或私有原始日志复制到材料。

```sh
python3 scripts/experiments/cdc-lab-pool/pool.py \
  --config docs/experiments/2026-10-25-cdc-lab-pool/pool-attempt-2.json \
  acquire a --owner example-owner --token-file /private/tmp/cdc-lab-pool-20261025-attempt2/a-example-token.json
```

后续 `init a`、`status a`、`start a --role source`、`stop a --role source` 均要求 `--token-file <private-token>`。`init` 只接受完全不存在的实例目录；失败目录不删除、不再次初始化。`stop --crash` 只能针对租约内、TCP UUID/port/datadir 与 PID 命令行均匹配的实例。`release a --owner example-owner --token-file ...` 核对 owner/token。租约目录原子互斥，JSON/token 完整原子发布，旧 token 文件不覆盖；发布失败回收刚取得的租约。acquire/release 只在修改元数据的短临界区持有池级 flock，不在执行命令或 MySQL 查询时持锁；所有进程共用 runtime 根目录的预算。每个租约目录都占用一个并行名额，包括人工持有、残缺和未知隔离租约，不按 PID 回收。runtime 的 .pool-budget.json 固定 max_parallel，拒绝其他配置对同一 runtime 使用不同预算。预算不足立即拒绝申请，调用者另开 attempt 重试，不自动排队。不会按 PID 自动抢占、过期回收或删除旧材料，异常终止留下的租约须先人工核对运行者与子进程再处理。

`Pool.environment(slot)` 从私有凭据引用生成 JDBC/TCP 环境，不要打印返回值。`Pool.sql` 在执行 SQL **之前**，通过配置 TCP 端口核对 UUID/port/datadir。初始化同时创建 localhost 与 127.0.0.1 账号，TCP 校验不能用 socket 校验代替：

```sh
python3 scripts/experiments/cdc-lab-pool/tcp_check.py \
  --config <pool.json> --slot a --token-file <private-token> --output <new-result.json>
```

`Pool.archive_and_purge` 先切日志，把将删除的所有 binlog 复制到新的私有归档目录并记录 SHA256，再 purge。原始 binlog 可能包含账号认证材料，因此归档必须留在私有 runtime，只将路径和摘要纳入公开材料。没有自动 GC、批量清理或通用删除命令。

## 构建一次、冻结再分片

等所有候选源码/测试落盘后，由统一集成负责人执行一次：

```sh
python3 scripts/experiments/cdc-lab-pool/pool.py freeze \
  --repo "$PWD" --java-home /Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home \
  --output <new-candidate-directory>
```

该命令只做一次 `sbt project pekko-server / Test compile + fullClasspath`。构建前后源码不一致就拒绝冻结并保留构建日志。所有 classpath 目录打 JAR、所有外部依赖文件复制进候选目录；另保存 source.zip、全量/生产/测试摘要及工具摘要。冻结文件只读，每个 case 执行前后再核对 artifact SHA256。可通过 Python `freeze(repo, destination, classpath)` 冻结已经由统一负责人构建好的 classpath，避免第二次构建。

jobs JSON 使用显式 case id 与 argv，没有 shell 展开。例如：

```json
[
  {"case_id":"bootstrap-case","timeout_seconds":600,
   "argv":["/Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home/bin/java","-Xmx1G","-Duser.timezone=UTC","-cp","{classpath}","org.scalatest.tools.Runner","-o","-s","cn.xuyinyin.magic.workflow.integration.BootstrapMatrixSpec","-z","approved-case-name"]}
]
```

```sh
python3 scripts/experiments/cdc-lab-pool/pool.py --config <pool.json> run \
  --candidate <candidate-directory>/candidate.json --jobs <jobs.json> --slots a b --output <new-attempt-directory>
```

按清单顺序 round-robin 固定 case→slot，每槽串行、两槽并行。每个 case 在 Popen 前都使用该租约对 source/state 的配置 TCP 端口重新执行身份查询；实际 UUID、port、datadir 与期望值逐项匹配才派发，拒绝结果同样保存实际观测和错误，不能用独立 tcp_check 的历史结果代替。result 的 instance_admission 记录两实例本次实际值与准入结论，plan/result/index 另记录实际 runner 工具摘要。每个 case 工作目录就是自己的 attempt，旧测试相对 `target/...` 输出因此也落入该 case 目录。`plan.json` 写明归属，`result.json/index.json` 写 case/slot/源码/测试/candidate 摘要、开始结束时间、退出码以及本 case 子树文件的精确路径和摘要；不会扫描公共 target 目录猜归属。

失败、超时、已有租约均保留显式结果；忙槽不影响另一槽结果；超时杀自己的新进程组；父进程退出后，对留在该组的子进程先 TERM 并等待 0.5 秒，必要时 KILL 并等待 2 秒，通过 killpg/ps 确认没有活成员后才运行下一 case 或释放租约。清理阶段及成员状态写入 result，租约释放时间写入 index。无法确认清理时该 case 返回 126、保留租约隔离、后续 case 记为 125 且不启动；不得直接重租。正常异常路径释放本次取得的租约。原始 stdout/stderr 保存在私有 0600 日志，材料中的 runner.log 替换该槽密码；argv 含该密码会被拒绝。命令由可信执行者控制，不提供对恶意 argv、脱离进程组的 daemon、主动输出任意凭据的沙箱隔离。

资源限额包括 MySQL 的 buffer/max_connections、最多两个 worker 以及逐 job 超时。JVM heap 必须在受控 argv 显式限制（建议每槽 `-Xmx1G`）；没有操作系统级 CPU/RSS/磁盘硬配额，也不能把 buffer pool 当成 mysqld 总 RSS 上限。内存/磁盘不足时降 `max_parallel`，不停止其他服务。

## 验证

```sh
python3 -m unittest discover -s scripts/experiments/cdc-lab-pool -p 'test_*.py'
```

`isolation_check.py` 会对全新 fixture 实际产生锁等待、DDL、binlog purge 和 SIGKILL，只允许在两个租约都独占且旁槽没有其他消费者时执行。每次新 attempt，原始失败不覆盖。实际结果及证据边界见 `docs/experiments/2026-10-25-cdc-lab-pool/README.md`。

最终定点验收适配器 `scala_slot_check.py` 只在所属 slot 执行已冻结的 SharedCaptureReaderSpec（JDK17 / 512MiB heap），保存 source/state 的真实身份、用户连接/事务及受保护 C schema 的表清单和 CHECKSUM；MySQL 内部线程单独列出。它不会清理数据或隐藏释放失败。最终 4/4 Scala 通过但 5 秒后置清理失败、随后自然归零的分层证据见实验 README。
