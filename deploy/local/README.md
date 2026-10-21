# 本地 Compose 环境

配置在 [docker-compose.yml](docker-compose.yml)。仓库根同名文件通过 Compose include 转发，保留根目录执行 `docker compose up` / `docker compose config` 的入口和原项目/卷身份。需要支持 include 的 Docker Compose 2.20+；本轮只核验配置，不启动服务或迁移旧数据卷。

先准备 `DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`；两者均不写入仓库。根目录执行：

```sh
docker compose config --quiet
docker compose up -d
```

直接使用本目录配置时，显式保留项目名：`docker compose -p pekko-reference -f deploy/local/docker-compose.yml ...`。不同项目名会生成不同资源名，不应将新建卷误认为原数据丢失。

Compose 挂载 `server/src/main/resources` 中的配置和 SQL。Kubernetes 环境模板另见 [kubernetes](../kubernetes/bigdata-lab/README.md)。本地配置不是生产高可用证明。
