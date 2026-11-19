#!/bin/bash

# MySQL真实性能测试脚本

set -e

# 颜色定义
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo ""
echo "======================================"
echo -e "${BLUE}MySQL真实性能测试${NC}"
echo "======================================"
echo ""

# 数据库配置
DB_HOST="100.82.226.63"
DB_PORT="31765"
DB_USER="root"
DB_PASS="asd123456"
DB_NAME="test"

echo -e "${YELLOW}步骤 1: 准备测试数据${NC}"
echo "----------------------------------------"
echo "正在连接MySQL并创建测试表..."
echo ""

# 执行准备脚本
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME < .tasks/prepare-perf-test.sql

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ 测试表创建成功！${NC}"
    echo ""
else
    echo -e "${RED}❌ 测试表创建失败！${NC}"
    exit 1
fi

echo -e "${YELLOW}步骤 2: 验证测试数据${NC}"
echo "----------------------------------------"
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME -e "
SELECT 
    'perf_test_source' AS table_name,
    COUNT(*) AS record_count
FROM perf_test_source
UNION ALL
SELECT 
    'perf_test_sink' AS table_name,
    COUNT(*) AS record_count
FROM perf_test_sink;
"
echo ""

echo -e "${YELLOW}步骤 3: 运行性能测试${NC}"
echo "----------------------------------------"
echo -e "${BLUE}选择测试类型:${NC}"
echo "1. 读取测试 - 从MySQL读取10万条数据"
echo "2. 写入测试 - 向MySQL写入10万条数据"
echo "3. 完整测试 - 读取+写入完整流程"
echo ""

read -p "请选择 (1/2/3): " choice

case $choice in
  1)
    echo ""
    echo -e "${GREEN}运行读取性能测试...${NC}"
    echo ""
    sbt "project pekko-server" "testOnly *MySQLRealPerformanceTest -- -z 真实MySQL读取"
    ;;
  2)
    echo ""
    echo -e "${GREEN}运行写入性能测试...${NC}"
    echo ""
    sbt "project pekko-server" "testOnly *MySQLRealPerformanceTest -- -z 真实MySQL写入"
    ;;
  3)
    echo ""
    echo -e "${GREEN}运行完整性能测试...${NC}"
    echo ""
    sbt "project pekko-server" "testOnly *MySQLRealPerformanceTest"
    ;;
  *)
    echo -e "${YELLOW}无效选择，运行完整测试${NC}"
    sbt "project pekko-server" "testOnly *MySQLRealPerformanceTest"
    ;;
esac

echo ""
echo "======================================"
echo -e "${GREEN}测试完成！${NC}"
echo "======================================"
echo ""
echo "查看结果："
echo "  • 源表记录数: SELECT COUNT(*) FROM perf_test_source;"
echo "  • 目标表记录数: SELECT COUNT(*) FROM perf_test_sink;"
echo ""
