# 受管静态快照：supervisor JVM 重启恢复

本入口在原[硬隔离后接管](mysql-snapshot-takeover.md)之上增加独立 guardian。只覆盖一个固定 deployment 中的本机受管静态快照；guardian 必须仍是原始创建者并持有原 Process。它不是 bootstrap/PekkoGuardian Actor，也不支持 PID 重新收养、guardian JVM 恢复、主机重启或跨机器 supervisor 选主。

## 部署前提

- 先停在可核对的迁移基线，配置原 table scheduling/ownership 控制库，以及新增 `db/mysql/pekko-snapshot-supervisor-schema.sql`。新表是 additive，不能用它替换旧 Journal/Snapshot 或目标账本。
- 固定受控运行制品、worker 配置和 classpath。配置文件及 JAR 不允许运行期间原地替换；本版配置摘要包含 worker 配置文本、堆上限与 classpath 字符串，不是对全部依赖 JAR 的完整性扫描。
- 由受信任部署流程生成 guardian boot、公私钥与至少32字节的本机请求密钥。公钥 pin 预先写入配置；私钥使用 PKCS8/Base64 文件，密钥、配置目录与文件仅部署用户可读。不能从重新连接的 RPC 对端自动接受新 pin。
- 每台机器一个独立 guardian，固定 guardian/supervisor 锁文件和 Unix socket 路径。不能删除锁文件、按超时抢锁，或在原 guardian 不明时替换 socket。
- worker 使用原 PekkoServer 入口，必须启用 snapshot-ha 与 durable table scheduling，deployment 和调度库 URL/账户必须与 supervisor 一致。固定数据库清单、Journal InnoDB、cluster/downing 前提仍按旧指南执行。worker 角色与 seed 节点必须能启动原 dispatcher；本入口不代替集群部署配置。

在既有完整配置之外新增的片段如下。值由部署流程提供，示例不是可直接使用的凭据：

```hocon
pekko.workflow.snapshot-supervisor {
  host-id = "fixed-host-id"
  guardian-boot = "deployment-generated-incarnation"
  public-key = "pinned-Ed25519-SPKI-base64"
  private-key-file = "/private/guardian-signing.key"
  auth-key-file = "/private/guardian-auth.key"
  socket = "/private/guardian.sock"
  guardian-lock = "/private/guardian.lock"
  supervisor-lock = "/private/supervisor.lock"
  max-workers = 1
  worker-config-file = "/private/worker.conf"
  worker-xmx-mib = 512
}
```

分别运行 `cn.xuyinyin.magic.workflow.scheduling.SnapshotGuardianMain <supervisor.conf>` 和 `cn.xuyinyin.magic.workflow.scheduling.SnapshotSupervisorMain <supervisor.conf>`。它们是独立 JVM，继续保留原 PekkoServer 命令和 Scala API。新入口先验证 worker HA 配置；误关 HA 会在 fork 前失败；带 managed launch 属性的 worker 也不能绕过 bootstrap。

guardian 首次登记实例、公钥和额度策略，并绑定自己的 PID/start 与原管理器随机 capability。该随机值只是拒绝管理器替换的绑定记录，不能据此恢复 Process 能力。原 guardian 死亡后，重新运行相同配置仍会拒绝替代；恢复该机器需要另外设计的外部隔离和重新部署流程，本版不自动清空旧记录。

## 恢复顺序与配额

supervisor 持有固定本机锁，事务性取得新 controller epoch；所有 supervisor 控制变更在原 admission 事务内检查 epoch。原 writer generation、T/J/D 和批次身份不变。

启动票据先落库并占 worker 槽位，再发送 LAUNCH；`dispatching` 超时不能当作未创建。guardian 串行处理创建/取消，保存原 Process，库存可补录已 fork 但 SQL 身份回复未知的子进程。确认未创建才能取消；终态 boot 墓碑不复用。

新 supervisor 认证原 guardian 后，沿票据找回身份，复用原 operation，取得原始子进程退出证明。验签和身份校验先于原 owner 的 fenced 转换，原始签名证明与 owner 转换同事务记录。之后继续原 manifest、完整数据库观察和两轮空确认；未知资源仍占额。旧表任务额度处理完毕后退休进程票据，再启动后继 worker，由原 gate 决定 writer permit。SQL 退休但 RETIRE RPC 丢失时，guardian 从已持久化证明回收原句柄。

表任务的 Source/Sink 连接与页额度仍由原 admission 表维护；worker 票据只管理进程槽位，不复制每张表。健康 owner 的结束连接登记也由完整观察有界清理，不能让每页新目标连接永久累计到4096条登记上限。观察不完整时不删除登记。

控制 SQL COMMIT 回复未知时按稳定 ID 读回，读回失败保留上下文。guardian 原控制能力丢失、认证失败、旧 epoch 或库存未知时，不推断进程已死，不释放未知额度，不启动后继。已记录证明后也会在排空、归还额度及新启动边界复查 guardian；这不是对未来网络故障的原子预知。

这里的安全暂停指接管与资源再分配；仍存活的旧 worker 可能继续其已获许可的工作，并不保证数据面立即冻结。负例实验用固定读取门闩保持 T/J 不变，不能将该现象扩大为 guardian 失联自动停止所有业务写入。

如果真实 fork 已返回，但读取子进程身份失败，guardian 保留原 Process 和进程槽位，库存为 UNKNOWN；不能将其取消为“尚未创建”，即使清理尝试已经结束。此时缺少完整身份和证明，保持暂停，不能靠删除票据或重启 guardian 释放额度；需要另行完成外部隔离和受控处置。本次使用 Java17 权限拒绝复现并验证了该分支，没有验收强杀等待超时后进程仍存活的 OS 故障。

T/J 页恢复继续由原 Split executor 和目标账本完成；静态快照 D=N/A。该入口不重置 Split/CDC 重试预算，不改旧事件、Snapshot 类型、serializer manifest、persistenceId、execution 或 batchId。新旧控制程序不能同时管理同一组进程；数据格式兼容不等于任意滚动回退都安全。

## 验证材料

本轮证据、逐字段对账、失败过程、精确源码和未验证项见[supervisor JVM 恢复实验](../experiments/2026-10-23-supervisor-jvm-recovery/README.md)。此前[测试探针](../experiments/2026-10-23-guardian-restart/README.md)仍只证明实验控制协议，不能与新生产入口的结果混算。所有旧材料保持原样。

原实验目录记录的是首次冻结版本，包含当时的“未提交、未推送”状态。本次提交前的复审、身份失败补强及新旧源码对照见[终审材料](../experiments/2026-10-23-supervisor-final-review/README.md)；不要把两次源码散列混为同一版本。
