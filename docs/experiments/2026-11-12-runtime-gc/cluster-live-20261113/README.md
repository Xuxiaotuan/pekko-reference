# 真实集群 acceptance 边界

本轮在远端 `xjw` 集群创建了隔离的 `cdc-runtime-acceptance` namespace 和临时 State MySQL；旧 `pekko-cdc-single`、`pekko-workflow`、旧 MySQL 和旧 Secret 没有修改。

## 已验证

- amd64 Runtime 镜像导入节点成功，Deployment `pekko-cdc-runtime` 曾达到 `1/1 Ready`。
- 通过 pod port-forward，`/api/v1/cdc-status` 和 `/api/v1/cdc-captures` 可访问。
- Source Debezium Engine 成功连接现有 MySQL，读取 `ROW/FULL` binlog，并建立 JDBC offset/history 表；日志中出现 binlog offset 推进。
- Source 与临时 State MySQL 的 UUID 不同，账号和 namespace 均为独立 acceptance 资源。
- 进程 probe 已改成 loopback 约束下的进程存活检查；API 就绪仍通过 port-forward 验证。

## 未收敛与失败证据

- Source MySQL 的 ClusterIP 从 xjw 节点访问失败；通过 xxt NodePort `192.168.31.251:32372` 可达，记录在 [network-boundary.txt](network-boundary.txt)。
- Runtime 启动后在 State 复查连接阶段出现 `Communications link failure`，随后按协议进入 `UNKNOWN/STORAGE_UNAVAILABLE`；此前配置解析失败和 `OWNER_RELEASE_UNPROVEN` 现场也保留在同目录。
- 本轮没有把 `202 Accepted`、Source binlog offset 或 Pod Ready 写成目标初始化完成；没有完成真实任务的最终逐字段 Sink 对账。

acceptance Runtime 已缩容到 0，临时 State 数据和源侧专用 schema/账号保留，便于下一轮从相同切点复现。旧业务资源没有删除。
