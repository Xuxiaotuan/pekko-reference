# Runtime GC 生命周期 soak

本轮在专用 Source/State MySQL、真实 Journal 和 Sink 上连续串行执行 RG01（旧 namespace 启用、GC、停止、重启、继续写入）10 轮，时间为 2026-11-13 Asia/Shanghai 对应的 UTC 记录 16:59:20—17:11:16。

- 10/10 轮通过；每轮保留 `suite.log`、worker 日志、before/partial/final、restart plan 和逐字段比较。
- 10 轮最终 Source/Target 逐字段相等，差异、缺行、额外行均为 0；最终 State 连接和 InnoDB 事务采样均为 0。
- 每轮都经历了 Runtime stop、owner STOPPED、重新启动、GC operation 续跑和新写入后的对账。
- 汇总见 [summary.json](summary.json)。

这是一组约 12 分钟的重复生命周期 soak，证明该固定负载下的资源释放与重启边界；它不等同于天级长期运行、70 万真实活跃表吞吐或 DDL/多 owner 组合验收。
