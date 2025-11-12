#!/bin/bash

echo "🚀 Testing Pekko HTTP Server Endpoints"
echo "======================================"

# 检查服务器是否运行
echo "1. 检查服务器状态..."
if curl -s http://localhost:8080/ > /dev/null; then
    echo "✅ HTTP服务器正在运行"
else
    echo "❌ HTTP服务器未运行，请先启动服务器："
    echo "   sbt \"project pekko-server\" run"
    exit 1
fi

echo ""
echo "2. 测试根路径 (API文档)..."
echo "请求: GET /"
response=$(curl -s http://localhost:8080/)
echo "响应: $response"
echo ""

echo "3. 测试API状态端点..."
echo "请求: GET /api/v1/status"
response=$(curl -s http://localhost:8080/api/v1/status)
echo "响应: $response"
echo ""

echo "4. 测试健康检查端点..."
echo "请求: GET /health"
response=$(curl -s http://localhost:8080/health)
echo "响应: $response"
echo ""

echo "5. 测试存活探针 (K8s Liveness)..."
echo "请求: GET /health/live"
response=$(curl -s http://localhost:8080/health/live)
echo "响应: $response"
echo ""

echo "6. 测试就绪探针 (K8s Readiness)..."
echo "请求: GET /health/ready"
response=$(curl -s http://localhost:8080/health/ready)
echo "响应: $response"
echo ""

echo "7. 测试集群状态监控..."
echo "请求: GET /monitoring/cluster/status"
response=$(curl -s http://localhost:8080/monitoring/cluster/status)
echo "响应: $response"
echo ""

echo "8. 测试系统指标监控..."
echo "请求: GET /monitoring/metrics"
response=$(curl -s http://localhost:8080/monitoring/metrics)
echo "响应: $response"
echo ""

echo "======================================"
echo "🎉 HTTP端点测试完成！"
echo ""
echo "📋 可用端点总结："
echo "  - /                    - API文档"
echo "  - /api/v1/status       - API状态"
echo "  - /health              - 整体健康状态"
echo "  - /health/live         - K8s存活探针"
echo "  - /health/ready        - K8s就绪探针"
echo "  - /monitoring/cluster/status - 集群状态"
echo "  - /monitoring/metrics  - 系统指标"
