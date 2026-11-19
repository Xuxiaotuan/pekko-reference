# 生产进程入口的未知 B：C1 样本

状态：**单个指定窗口数据收敛**。隔离的两台 MySQL 9.5.0 上，独立 JVM 实际运行 `SnapshotGuardianMain` 和 `SnapshotSupervisorMain`，由生产 guardian 按 `SnapshotSupervisorSettings.builder` 启动 `PekkoServer`，其 `PekkoGuardian` 加载 `CdcPlatformExtension`；使用真实共享 Reader、Pekko JDBC Journal 和 MySQL Sink。测试配置覆盖端口、独立账号和 schema，并在 `application-test.conf` 后补齐 `application.conf` 默认项。实验代理仅在环回连接的 MySQL 握手中声明不支持 TLS，使生产默认连接可以通过明文故障代理；没有改变生产配置，也不证明 TLS 连接下的行为。

[最终样本](C1-783029950f67/) 中，代理截获源 B 的真实 COMMIT 成功回复；`01-unknown.json`、`02-isolated.json` 和 `03-recovered.json` 分别记录故障、旧 worker 的 guardian 签名隔离与恢复后状态。旧进程隔离及服务端排空后，permit 中 `marker_present=1`、`consumed=1`，任务进入 STREAMING；源表与目标表各 64 行、384 个字段逐项相等，缺失行、多余行和字段差异均为空。切点、过程和恢复后 JSON 中保留 B、D、L、T、原始 Journal 数据、进程凭据和 proxy 事件。生产入口没有安装测试 worker 的回调观察器，因此本样本不证明原始 ACK 顺序。

清理后，[实际数据库尾态](C1-783029950f67/final-database-state.tsv)显示两个 snapshot owner 均 `db_drained`、ticket 均 `retired`、12 个 grant 均 `RELEASED`；但清理强制停止后继 worker，CDC owner 数据库记录仍为 `ACTIVE`。`cleanup.json` 中 PASS 只表示测试定义的断言，不能证明 CDC 正常停机或该 owner 已释放。

前两次失败均发生在故障注入前：首次因测试 worker 配置缺少应用默认项，PekkoServer 未能启动；第二次补配置后，生产 Debezium 的默认 TLS 协商与原先只接受明文的实验代理不兼容。分别保留 [首次](C1-826c8d87b0fa/) 和[第二次](C1-716243688186/) 原始日志及清理信息。首次最终尾态仍有一个 `drain_wait` owner 和 `launched` ticket，不能声称遗留状态已安全退休；隔离实验进程及专用连接已结束，保留数据库记录供审查。

成功样本的 [源码摘要](C1-783029950f67/source-sha256.txt) 在启动时生成，实验后与工作区逐文件比对一致；它不单独证明每个已编译类与源码的字节对应。此样本不覆盖其他未知 B 窗口、guardian 失联、持续工作中的完整 ACK 次序、生产 TLS 或跨主机接管。
