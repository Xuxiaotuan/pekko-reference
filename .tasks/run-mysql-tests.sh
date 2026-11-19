#!/bin/bash

# MySQL集成测试脚本

set -e

echo "======================================"
echo "MySQL批量同步集成测试"
echo "======================================"
echo ""

# 颜色定义
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}选择测试类型:${NC}"
echo "1. 完整集成测试 (推荐) - 测试连接器加载、执行引擎、端到端同步"
echo "2. 性能测试 - 测试大量数据同步吞吐量"
echo "3. 运行所有MySQL测试"
echo ""

read -p "请选择 (1/2/3): " choice

case $choice in
  1)
    echo ""
    echo -e "${GREEN}运行完整集成测试...${NC}"
    echo ""
    sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest -- -z 执行完整"
    ;;
  2)
    echo ""
    echo -e "${GREEN}运行性能测试...${NC}"
    echo ""
    sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest -- -z 性能测试"
    ;;
  3)
    echo ""
    echo -e "${GREEN}运行所有MySQL测试...${NC}"
    echo ""
    sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest"
    ;;
  *)
    echo -e "${YELLOW}无效选择，默认运行完整集成测试${NC}"
    sbt "project pekko-server" "testOnly *MySQLBatchSyncIntegrationTest -- -z 执行完整"
    ;;
esac

echo ""
echo "======================================"
echo -e "${GREEN}测试完成！${NC}"
echo "======================================"
