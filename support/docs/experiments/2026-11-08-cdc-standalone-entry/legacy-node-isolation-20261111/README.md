# 旧节点 SPI 与静态快照执行器隔离（2026-11-11）

当前工作区源码指纹见 `source-sha256.txt`。旧节点 SPI 使用 Workflow DSL，静态快照执行器仅由旧执行器调用；它们迁入 `cdc.compat.nodes`，名称明确表示兼容层。批次、Split 状态及旧 Journal 序列化类型未迁动，生产旧执行器仍保留，不能称为完全删除 Workflow。

`component-tests.log`：52 项组件测试通过，覆盖旧分片执行、窗口让出、旧事件反序列化及 Source 节点。`mysql-tests.log`：隔离 MySQL 9.5.0 上旧 Journal 事件重放和 receipt 核验两项通过；双 MySQL、真实 Debezium Reader、Actor Journal、Sink 的同 owner RAW 页回调异常恢复通过。`reconciliation.json` 记录全量 63 行、每行 6 字段一致，详细 T/J/L/D 和数据见 P09 子目录。实验后的实例、连接和事务记录分别在 `source-final-state.tsv` 与 `state-final-and-journal.tsv`，两个专用实例已关闭。

本次注入不是网络断连或 guardian 跨 JVM 接管；旧 Journal 仅覆盖特定历史样本；未知 B 继续安全暂停。旧节点 API 的源码包名改变，未验证外部二进制客户端兼容。
