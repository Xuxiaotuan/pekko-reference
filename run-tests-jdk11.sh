#!/bin/bash

# 设置JDK 11
export JAVA_HOME=/Users/xujiawei/Library/Java/JavaVirtualMachines/temurin-11.0.29/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

echo "🔧 使用Java版本:"
java -version

echo ""
echo "🚀 检查DataFusion服务..."
if ! lsof -i :50051 > /dev/null 2>&1; then
    echo "❌ DataFusion服务未运行"
    echo "请先启动: cd datafusion-service && cargo run --release"
    exit 1
fi

echo "✅ DataFusion服务正在运行"
echo ""

echo "📦 运行DataFusion集成测试..."
sbt "testOnly cn.xuyinyin.magic.datafusion.DataFusionIntegrationSpec"

echo ""
echo "📦 运行SQL工作流集成测试..."
sbt "testOnly cn.xuyinyin.magic.datafusion.integration.SQLWorkflowIntegrationSpec"

echo ""
echo "✅ 测试完成！"
