# V2_READER C1 未知提交失败材料

这组材料来自同一版本、同一 bigdata-lab 隔离双 MySQL 环境的 C1 `PROXY_COMMIT_OK_DROPPED` 复跑。旧 owner 的 Reader 连接在提交回复丢失后进入 `local_release_unconfirmed`，Journal 保持 `RECOVERY_PENDING`，未进入目标表逐字段对账。该结果是安全暂停证据，不代表主流程 V2_READER 的对账结果。
