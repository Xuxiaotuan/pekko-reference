#!/bin/bash

echo "🚀 测试DataFusion集成..."
echo ""

# 检查DataFusion服务
if ! lsof -i :50051 > /dev/null 2>&1; then
    echo "❌ DataFusion服务未运行"
    echo "请先启动: cd datafusion-service && cargo run --release"
    exit 1
fi

echo "✅ DataFusion服务正在运行"
echo ""

# 编译测试（只编译我们需要的）
echo "📦 编译测试代码..."
sbt "Test / compile" 2>&1 | grep -E "(Compiling|compiled|error|Error)" | tail -20

echo ""
echo "🧪 运行DataFusionIntegrationSpec..."
echo ""

# 直接运行测试类
cd pekko-server
scala -cp "target/scala-2.13/test-classes:target/scala-2.13/classes:$(sbt 'export test:fullClasspath' | tail -1)" \
  org.scalatest.run cn.xuyinyin.magic.datafusion.DataFusionIntegrationSpec

echo ""
echo "✅ 测试完成"
