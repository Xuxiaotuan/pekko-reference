# 本地命令

在仓库根目录使用 Java 17；所有脚本均先切换到仓库根目录，可以从其他工作目录调用。参数传给 SBT，例如 `./quick-test.sh -java-home /path/to/java17`。

| 入口 | 用途 |
|---|---|
| `./quick-test.sh` / `scripts/test.sh` | 同一组局部测试；根入口保持兼容 |
| `scripts/run.sh` / `sbt runServer` | 启动原有 server 主类 |
| `scripts/package.sh` | package、Universal/stage、Docker/stage；不发布镜像 |
| `python3 scripts/check-layout.py` | 根目录、package 路径、项目 ID 和导航检查 |

完整后端使用 `sbt 'pekko-server/test'`。SBT 项目 ID 仍为 `pekko-server`，物理目录改为 `server`。真实 MySQL 实验仍须显式启用，局部测试不代表端到端验证。历史材料及独立核验器见 [experiments](../docs/experiments)。
