# 清理DataFusion任务清单

> **目标**: 完全移除DataFusion相关代码和依赖

---

## 📋 执行清单（按顺序）

### Step 1: 删除DataFusion目录

```bash
cd /Users/xujiawei/magic/scala-workbench/pekko-reference

# 删除Rust服务目录
rm -rf datafusion-service/

# 删除配置文件
rm -f .env.datafusion.example
rm -f docker-compose-datafusion.yml
rm -f test-datafusion-integration.sh

# 删除DataFusion文档
rm -f DATAFUSION_*.md
```

---

### Step 2: 删除Scala中的DataFusion代码

#### 2.1 删除datafusion包

```bash
# 删除整个datafusion包
rm -rf pekko-server/src/main/scala/cn/xuyinyin/magic/datafusion/
rm -rf pekko-server/src/test/scala/cn/xuyinyin/magic/datafusion/
```

**删除的文件列表**：
```
pekko-server/src/main/scala/cn/xuyinyin/magic/datafusion/
├── DataFusionClient.scala
├── DataFusionConfig.scala
├── DataFusionExceptions.scala
├── ParameterizedQuery.scala
├── SQLNodeConfig.scala
├── SQLNodeRegistry.scala
└── StructuredLogger.scala

pekko-server/src/test/scala/cn/xuyinyin/magic/datafusion/
├── DataFusionClientSpec.scala
├── DataFusionExceptionsSpec.scala
├── DataFusionIntegrationSpec.scala
├── ParameterizedQuerySpec.scala
└── StructuredLoggerSpec.scala
```

#### 2.2 删除或注释SQLQueryNode

**选项A: 完全删除**（推荐）

```bash
rm -f pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/transforms/SQLQueryNode.scala
rm -f pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/transforms/SQLQueryNodeSpec.scala
```

**选项B: 保留但注释**（如果未来可能用到）

文件保留，但在文件开头添加：
```scala
// DEPRECATED: DataFusion removed in MVP version
// TODO: Implement SQL transform using other approach
```

---

### Step 3: 修改NodeRegistry

**文件**: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala`

找到并删除或注释SQLQueryNode的注册：

```scala
// BEFORE:
// import cn.xuyinyin.magic.workflow.nodes.transforms.SQLQueryNode
// registry.put("sql.query", new SQLQueryNode())

// AFTER:
// ❌ 删除上面两行
```

---

### Step 4: 清理build.sbt依赖

**文件**: `build.sbt`

删除Arrow相关依赖：

```scala
// ❌ 删除这些行
// "org.apache.arrow" % "arrow-memory-netty" % "13.0.0",
// "org.apache.arrow" % "arrow-vector" % "13.0.0",
// "org.apache.arrow" % "flight-core" % "13.0.0",
// "org.apache.arrow" % "flight-grpc" % "13.0.0",
```

---

### Step 5: 清理application.conf

**文件**: `pekko-server/src/main/resources/application*.conf`

删除datafusion配置块：

```hocon
# ❌ 删除这个配置块
# datafusion {
#   enabled = true
#   host = "localhost"
#   port = 50051
#   ...
# }
```

---

### Step 6: 清理README和文档

#### README.md

删除或注释这些部分：

```markdown
❌ 删除：
- DataFusion集成相关章节
- SQL查询节点描述
- Arrow Flight RPC说明
- DataFusion快速开始链接
```

保留这一行描述（作为未来计划）：
```markdown
### 未来计划
- [ ] SQL转换引擎（考虑Apache Calcite或其他方案）
```

#### 删除DataFusion专用文档

```bash
rm -f DATAFUSION_QUICKSTART.md
rm -f DATAFUSION_INTEGRATION_SUMMARY.md
rm -f DATAFUSION_INTEGRATION_PROGRESS.md
```

---

### Step 7: 清理测试文件

删除DataFusion集成测试：

```bash
rm -f pekko-server/src/test/scala/cn/xuyinyin/magic/datafusion/integration/SQLWorkflowIntegrationSpec.scala
```

---

### Step 8: 清理examples

如果有DataFusion示例：

```bash
rm -rf examples/datafusion-*
```

---

### Step 9: 清理k8s配置

**文件**: `k8s/`目录

删除DataFusion的Deployment：

```bash
rm -f k8s/datafusion-service-deployment.yaml
rm -f k8s/datafusion-service-service.yaml
```

---

### Step 10: 验证清理

#### 10.1 编译检查

```bash
cd /Users/xujiawei/magic/scala-workbench/pekko-reference

# 清理编译缓存
sbt clean

# 重新编译
sbt "project pekko-server" compile

# 应该成功，无DataFusion相关错误
```

#### 10.2 搜索残留引用

```bash
# 搜索所有DataFusion引用
grep -r "datafusion" --include="*.scala" --include="*.conf" --include="*.md" .

# 搜索Arrow引用
grep -r "arrow" --include="*.scala" --include="*.sbt" .

# 搜索SQL节点引用
grep -r "SQLQueryNode" --include="*.scala" .
```

**期望结果**：
- ❌ 不应该有残留的datafusion代码引用
- ✅ 可能有文档中的"DataFusion"文字描述（可以保留）
- ✅ README中的"未来计划"可以保留

#### 10.3 运行测试

```bash
# 运行所有测试
sbt test

# 应该通过（除了被删除的DataFusion测试）
```

---

## ✅ 清理完成检查清单

完成后，确认以下项目：

### 文件删除

- [ ] `datafusion-service/` 目录已删除
- [ ] `.env.datafusion.example` 已删除
- [ ] `docker-compose-datafusion.yml` 已删除
- [ ] `DATAFUSION_*.md` 文档已删除
- [ ] `pekko-server/src/main/scala/.../datafusion/` 已删除
- [ ] `pekko-server/src/test/scala/.../datafusion/` 已删除
- [ ] `SQLQueryNode.scala` 已删除或注释

### 代码修改

- [ ] `NodeRegistry.scala` 已移除SQLQueryNode注册
- [ ] `build.sbt` 已移除Arrow依赖
- [ ] `application.conf` 已移除datafusion配置
- [ ] `README.md` 已更新（移除DataFusion章节）

### 验证通过

- [ ] `sbt compile` 成功
- [ ] `sbt test` 通过（除已删除测试）
- [ ] `grep`搜索无残留引用
- [ ] Git状态干净（已commit删除）

---

## 🎯 清理后的项目状态

清理完成后，你的项目将：

✅ **更简洁** - 移除了Rust服务和复杂依赖  
✅ **更专注** - 聚焦MySQL→MySQL核心功能  
✅ **更快编译** - 减少了大量依赖  
✅ **更易维护** - 减少了代码复杂度  

---

## 🚀 下一步

清理完成后，立即开始MVP开发：

```bash
# 确认清理成功
sbt compile

# 开始创建sync-mvp模块
mkdir -p sync-mvp/src/main/scala/cn/xuyinyin/pekkosync

# 开始Day 1任务
# 参考 WEEK1_EXECUTION_PLAN.md
```

---

**预计耗时**: 30-60分钟  
**难度**: 低（主要是删除操作）  
**风险**: 低（都是删除不需要的代码）

**准备好开始清理了吗？** 🧹
