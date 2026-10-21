#!/bin/bash

echo "🧪 快速DataFusion集成测试"
echo ""

# 检查服务
if ! lsof -i :50051 > /dev/null 2>&1; then
    echo "❌ DataFusion服务未运行在端口50051"
    exit 1
fi

echo "✅ DataFusion服务正在运行"
echo ""

# 使用sbt console运行简单测试
sbt <<EOF
import cn.xuyinyin.magic.datafusion._
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

implicit val ec = ExecutionContext.global

println("创建客户端...")
val config = FlightClientConfig.default
val client = DataFusionClient(config)

println("测试1: 健康检查")
val health = Await.result(client.healthCheck(), 10.seconds)
println(s"健康状态: \$health")

println("测试2: 简单查询")
val result = Await.result(client.executeQuery("SELECT * FROM users"), 10.seconds)
println(s"查询成功: \${result.success}")
println(s"返回行数: \${result.data.size}")

client.close()
println("✅ 测试完成")

:quit
EOF
