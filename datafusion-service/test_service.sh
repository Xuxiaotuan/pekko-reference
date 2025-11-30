#!/bin/bash

# 测试DataFusion Service

echo "🚀 Starting DataFusion Service..."

# 启动服务（后台运行）
RUST_LOG=info CONFIG_PATH=config-test.toml cargo run &
SERVICE_PID=$!

echo "Service PID: $SERVICE_PID"

# 等待服务启动
echo "⏳ Waiting for service to start..."
sleep 5

# 检查服务是否运行
if ps -p $SERVICE_PID > /dev/null; then
    echo "✅ Service is running"
    
    # 这里可以添加更多测试
    # 例如使用grpcurl测试健康检查
    
    echo "🛑 Stopping service..."
    kill $SERVICE_PID
    wait $SERVICE_PID 2>/dev/null
    echo "✅ Service stopped"
else
    echo "❌ Service failed to start"
    exit 1
fi

echo "✅ All tests passed!"
