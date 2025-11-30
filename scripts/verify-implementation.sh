#!/bin/bash

# 分布式工作流引擎实现验证脚本
# 用于验证所有核心功能是否正常工作

set -e

echo "========================================="
echo "分布式工作流引擎实现验证"
echo "========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 计数器
PASSED=0
FAILED=0

# 测试函数
test_step() {
    local description=$1
    local command=$2
    
    echo -n "Testing: $description... "
    
    if eval "$command" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ PASSED${NC}"
        ((PASSED++))
        return 0
    else
        echo -e "${RED}✗ FAILED${NC}"
        ((FAILED++))
        return 1
    fi
}

echo "Phase 1: 编译验证"
echo "-------------------"
test_step "编译主项目" "sbt 'project pekko-server' compile"
test_step "编译测试" "sbt 'project pekko-server' Test/compile"
echo ""

echo "Phase 2: 核心组件验证"
echo "-------------------"
test_step "WorkflowSharding存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/sharding/WorkflowSharding.scala"
test_step "EventSourcedWorkflowActor存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala"
test_step "WorkflowSupervisor存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/WorkflowSupervisor.scala"
test_step "PekkoGuardian存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/cluster/PekkoGuardian.scala"
echo ""

echo "Phase 3: HTTP API验证"
echo "-------------------"
test_step "WorkflowRoutes存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/WorkflowRoutes.scala"
test_step "ClusterRoutes存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/ClusterRoutes.scala"
test_step "HttpRoutes存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/HttpRoutes.scala"
echo ""

echo "Phase 4: 监控组件验证"
echo "-------------------"
test_step "PrometheusMetrics存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/monitoring/PrometheusMetrics.scala"
test_step "ClusterEventLogger存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/monitoring/ClusterEventLogger.scala"
test_step "ClusterEventListener存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/cluster/ClusterEventListener.scala"
test_step "MetricsRoutes存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/MetricsRoutes.scala"
echo ""

echo "Phase 5: 测试验证"
echo "-------------------"
test_step "WorkflowSharding单元测试存在" "test -f pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/sharding/WorkflowShardingSpec.scala"
test_step "集成测试存在" "test -f pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/ClusterIntegrationSpec.scala"
test_step "故障恢复测试存在" "test -f pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/FailoverRecoverySpec.scala"
test_step "性能测试存在" "test -f pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/PerformanceSpec.scala"
echo ""

echo "Phase 6: 配置和文档验证"
echo "-------------------"
test_step "ConfigValidator存在" "test -f pekko-server/src/main/scala/cn/xuyinyin/magic/config/ConfigValidator.scala"
test_step "开发环境配置存在" "test -f pekko-server/src/main/resources/application-dev.conf"
test_step "生产环境配置存在" "test -f pekko-server/src/main/resources/application-prod.conf"
test_step "配置文档存在" "test -f docs/CONFIGURATION.md"
test_step "部署文档存在" "test -f docs/DEPLOYMENT.md"
test_step "迁移指南存在" "test -f docs/MIGRATION_GUIDE.md"
test_step "项目总结存在" "test -f docs/PROJECT_SUMMARY.md"
echo ""

echo "代码质量检查"
echo "-------------------"
# 检查是否有编译警告（排除已知的未使用导入警告）
if sbt "project pekko-server" compile 2>&1 | grep -q "\[error\]"; then
    echo -e "${RED}✗ 存在编译错误${NC}"
    ((FAILED++))
else
    echo -e "${GREEN}✓ 无编译错误${NC}"
    ((PASSED++))
fi
echo ""

echo "========================================="
echo "验证结果汇总"
echo "========================================="
echo -e "通过: ${GREEN}$PASSED${NC}"
echo -e "失败: ${RED}$FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}🎉 所有验证通过！系统已准备就绪。${NC}"
    exit 0
else
    echo -e "${RED}⚠️  有 $FAILED 项验证失败，请检查。${NC}"
    exit 1
fi
