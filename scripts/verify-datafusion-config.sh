#!/bin/bash

# DataFusion配置验证脚本
# 用于验证DataFusion配置是否正确加载

set -e

echo "🔍 DataFusion配置验证开始..."

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查函数
check_file() {
    local file=$1
    local desc=$2
    
    if [ -f "$file" ]; then
        echo -e "${GREEN}✅ $desc: $file${NC}"
        return 0
    else
        echo -e "${RED}❌ $desc: $file 不存在${NC}"
        return 1
    fi
}

check_config_value() {
    local file=$1
    local key=$2
    local desc=$3
    
    if grep -q "$key" "$file" 2>/dev/null; then
        local value=$(grep "$key" "$file" | head -1 | cut -d'=' -f2 | tr -d ' ')
        echo -e "${GREEN}✅ $desc: $key = $value${NC}"
        return 0
    else
        echo -e "${RED}❌ $desc: $key 未找到${NC}"
        return 1
    fi
}

echo ""
echo "📁 检查配置文件..."

# 检查主配置文件
check_file "pekko-server/src/main/resources/application.conf" "主配置文件"
check_file "pekko-server/src/main/resources/datafusion.conf" "DataFusion配置文件"

# 检查环境特定配置文件
check_file "pekko-server/src/main/resources/datafusion-dev.conf" "开发环境配置"
check_file "pekko-server/src/main/resources/datafusion-prod.conf" "生产环境配置"
check_file "pekko-server/src/main/resources/datafusion-test.conf" "测试环境配置"
check_file "pekko-server/src/main/resources/datafusion-k8s.conf" "K8s环境配置"

echo ""
echo "🔧 检查配置内容..."

# 检查主配置文件中的include语句
if grep -q 'include "datafusion.conf"' "pekko-server/src/main/resources/application.conf"; then
    echo -e "${GREEN}✅ application.conf 正确引用了 datafusion.conf${NC}"
else
    echo -e "${RED}❌ application.conf 未引用 datafusion.conf${NC}"
fi

# 检查DataFusion配置项
DATAFUSION_CONF="pekko-server/src/main/resources/datafusion.conf"
if [ -f "$DATAFUSION_CONF" ]; then
    check_config_value "$DATAFUSION_CONF" "enabled = true" "DataFusion启用状态"
    check_config_value "$DATAFUSION_CONF" "host =" "DataFusion主机配置"
    check_config_value "$DATAFUSION_CONF" "port =" "DataFusion端口配置"
    check_config_value "$DATAFUSION_CONF" "maxTotal =" "连接池最大连接数"
    check_config_value "$DATAFUSION_CONF" "defaultBatchSize =" "默认批处理大小"
fi

echo ""
echo "🐳 检查K8s配置文件..."

# 检查K8s配置文件
check_file "k8s/datafusion-configmap.yaml" "K8s ConfigMap配置"
check_file ".env.datafusion.example" "环境变量示例文件"

echo ""
echo "🏗️ 检查编译状态..."

# 检查编译
if sbt compile > /dev/null 2>&1; then
    echo -e "${GREEN}✅ 项目编译成功${NC}"
else
    echo -e "${RED}❌ 项目编译失败${NC}"
    echo "请运行 'sbt compile' 查看详细错误信息"
fi

echo ""
echo "📊 配置摘要:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ -f "$DATAFUSION_CONF" ]; then
    echo "DataFusion状态: $(grep 'enabled = ' "$DATAFUSION_CONF" | head -1 | cut -d'=' -f2 | tr -d ' ')"
    echo "默认主机: $(grep 'host = ' "$DATAFUSION_CONF" | head -1 | cut -d'=' -f2 | tr -d ' "' | tr -d '"')"
    echo "默认端口: $(grep 'port = ' "$DATAFUSION_CONF" | head -1 | cut -d'=' -f2 | tr -d ' ')"
    echo "最大连接数: $(grep 'maxTotal = ' "$DATAFUSION_CONF" | head -1 | cut -d'=' -f2 | tr -d ' ')"
fi

echo ""
echo "🚀 下一步操作:"
echo "1. 启动DataFusion Service: cd datafusion-service && cargo run --release"
echo "2. 启动Pekko应用: sbt run"
echo "3. 验证连接: curl http://localhost:9906/health"
echo "4. 查看指标: curl http://localhost:9090/metrics | grep datafusion"

echo ""
echo -e "${GREEN}🎉 DataFusion配置验证完成！${NC}"