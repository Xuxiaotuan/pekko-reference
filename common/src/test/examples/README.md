# 历史示例与运行入口

A 阶段将 12 个 Scala 文件从 `src/main/scala` 原样迁入
`src/test/scala/examples/legacy`，保留原包名。它们仍参与 Test 编译，但不进入生产 JAR。
生产 CDC 使用 `workflow/nodes/sources/MySQLCdcSourceNode`、完整组装配和
`workflow/nodes/sinks/MySQLCdcApplySinkNode`；这里的模拟器不提供真实数据库恢复证据。

| 原路径（相对 Scala 源根） | 新位置 |
|---|---|
| `cn/xuyinyin/magic/workflow/actors/WorkflowActor.scala` | `../scala/examples/legacy/` 下原相对路径 |
| `cn/xuyinyin/magic/stream/cdc/`（7 文件） | 同上 |
| `cn/xuyinyin/magic/common/DDataHelper.scala` | 同上 |
| `cn/xuyinyin/magic/single/PNCounterCache.scala`、`PekkoGc.scala` | 同上 |
| `cn/xuyinyin/magic/stream/PekkoStreamUtils.scala` | 同上 |

使用 Java 17，在仓库根目录执行：

```bash
sbt 'pekko-server/Test/runMain cn.xuyinyin.magic.stream.cdc.BasicCdcDemo'
sbt 'pekko-server/Test/runMain cn.xuyinyin.magic.stream.cdc.SimpleCdcStreamDemo'
sbt 'pekko-server/Test/runMain cn.xuyinyin.magic.stream.cdc.WatermarkAlgorithmDemo'
sbt 'pekko-server/Test/runMain cn.xuyinyin.magic.stream.cdc.WatermarkStreamAlgorithmDemo'
```

这些演示包含模拟数据、等待和输出，不是自动化恢复验收。
`PekkoStreamUtils` 也有 main，但原实现没有主动终止 ActorSystem，手工运行后需要停止进程。
其余 Actor / CRDT / GC 类保留为历史参考，Test 编译不代表其行为获得新验证。

`legacy-datafusion/quick-test.sh` 是原根目录脚本的逐字节归档：它引用现已不存在的
DataFusion 客户端，仅用于历史追溯，**不是可运行入口**。当前根目录 `quick-test.sh`
改为有限的本地 CDC 检查。当前发布配置使用 JDBC，不再打包旧 LevelDB 后端；
仓库外若有依赖旧 main 示例或 LevelDB 的启动方式，需要单独适配。
