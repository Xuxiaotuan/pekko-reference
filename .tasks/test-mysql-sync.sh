#!/bin/bash

# MySQL批量同步测试脚本
# 测试: xjwtest -> xjwtest1

set -e

BASE_URL="http://localhost:8080/api/v1/workflows"
WORKFLOW_ID="mysql-xjwtest-sync"

echo "======================================"
echo "MySQL批量同步测试"
echo "======================================"
echo ""

# Step 1: 创建Workflow
echo "Step 1: 创建Workflow..."
curl -X POST "${BASE_URL}" \
  -H "Content-Type: application/json" \
  -d '{
  "id": "mysql-xjwtest-sync",
  "name": "MySQL xjwtest批量同步测试",
  "description": "从xjwtest表同步数据到xjwtest1表",
  "version": "1.0",
  "author": "xujiawei",
  "tags": ["mysql", "batch-sync", "test"],
  "nodes": [
    {
      "id": "mysql-source",
      "nodeType": "mysql.query",
      "label": "MySQL Source - xjwtest",
      "config": {
        "host": "100.82.226.63",
        "port": 31765,
        "database": "test",
        "username": "root",
        "password": "asd123456",
        "sql": "SELECT id, name FROM xjwtest",
        "fetchSize": 1000
      }
    },
    {
      "id": "mysql-sink",
      "nodeType": "mysql.write",
      "label": "MySQL Sink - xjwtest1",
      "config": {
        "host": "100.82.226.63",
        "port": 31765,
        "database": "test",
        "table": "xjwtest1",
        "username": "root",
        "password": "asd123456",
        "batchSize": 1000,
        "mode": "insert"
      }
    }
  ],
  "edges": [
    {
      "source": "mysql-source",
      "target": "mysql-sink"
    }
  ],
  "metadata": {
    "createdAt": "2026-03-22T19:27:00Z",
    "updatedAt": "2026-03-22T19:27:00Z"
  }
}' | jq .

echo ""
echo ""

# Step 2: 执行Workflow
echo "Step 2: 执行Workflow..."
sleep 2
curl -X POST "${BASE_URL}/${WORKFLOW_ID}/execute" \
  -H "Content-Type: application/json" | jq .

echo ""
echo ""

# Step 3: 查询执行状态
echo "Step 3: 查询执行状态..."
sleep 3
curl -X GET "${BASE_URL}/${WORKFLOW_ID}/status" | jq .

echo ""
echo ""

# Step 4: 查看执行日志
echo "Step 4: 查看执行日志..."
sleep 2
curl -X GET "${BASE_URL}/${WORKFLOW_ID}/logs" | jq .

echo ""
echo "======================================"
echo "测试完成！"
echo "======================================"
