#!/bin/bash

echo "🚀 运行DataFusion集成测试..."
echo ""

# 检查DataFusion服务是否运行
if ! lsof -i :50051 > /dev/null 2>&1; then
    echo "❌ DataFusion服务未运行在端口50051"
    echo "请先启动服务: cd datafusion-service && cargo run --release"
    exit 1
fi

echo "✅ DataFusion服务正在运行"
echo ""

# 运行集成测试
echo "📋 运行 DataFusionIntegrationSpec..."
sbt "testOnly cn.xuyinyin.magic.datafusion.DataFusionIntegrationSpec" 2>&1 | tee /tmp/datafusion-test.log

# 检查测试结果
if grep -q "All tests passed" /tmp/datafusion-test.log || grep -q "Run completed" /tmp/datafusion-test.log; then
    echo ""
    echo "✅ 基础集成测试完成"
else
    echo ""
    echo "⚠️  测试可能有问题，请查看日志"
fi

echo ""
echo "📋 运行 SQLWorkflowIntegrationSpec..."
sbt "testOnly cn.xuyinyin.magic.datafusion.integration.SQLWorkflowIntegrationSpec" 2>&1 | tee /tmp/workflow-test.log

# 检查测试结果
if grep -q "All tests passed" /tmp/workflow-test.log || grep -q "Run completed" /tmp/workflow-test.log; then
    echo ""
    echo "✅ 工作流集成测试完成"
else
    echo ""
    echo "⚠️  测试可能有问题，请查看日志"
fi

echo ""
echo "🎉 集成测试执行完成！"
