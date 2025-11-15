# 🛠️ 开发指南

## 开发环境设置

### 必需工具

- JDK 11+
- Scala 2.13.12
- SBT 1.9.8+
- Git
- IDE (推荐 IntelliJ IDEA + Scala Plugin)

### IDE配置

#### IntelliJ IDEA

1. 安装Scala插件
2. 导入项目：File → Open → 选择build.sbt
3. 等待SBT下载依赖
4. 配置JDK 11

#### VS Code

1. 安装Metals插件
2. 安装Scala Syntax插件
3. 打开项目文件夹
4. Metals会自动配置项目

## 项目结构详解

### 核心模块

```
pekko-server/src/main/scala/cn/xuyinyin/magic/
├── core/                     # 核心系统
│   ├── cluster/              # 集群管理
│   │   ├── PekkoGuardian.scala      # 全局守护者Actor
│   │   ├── ClusterListener.scala    # 集群事件监听
│   │   ├── HealthChecker.scala      # 健康检查Actor
│   │   └── NodeRole.scala           # 节点角色定义
│   └── config/               # 配置管理
│       └── ConfigLoader.scala
│
├── workflow/                 # 工作流系统 ⭐⭐⭐
│   ├── model/                # 数据模型
│   │   └── WorkflowDSL.scala        # DSL定义
│   ├── actors/               # Actor系统
│   │   ├── WorkflowSupervisor.scala # 工作流监督者
│   │   └── WorkflowActor.scala      # 工作流Actor
│   ├── engine/               # 执行引擎
│   │   ├── WorkflowExecutionEngine.scala
│   │   ├── executors/        # 执行器
│   │   │   ├── NodeExecutor.scala
│   │   │   ├── SourceExecutor.scala
│   │   │   ├── TransformExecutor.scala
│   │   │   └── SinkExecutor.scala
│   │   └── registry/         # 注册中心
│   │       └── NodeRegistry.scala
│   ├── nodes/                # 节点实现
│   │   ├── base/             # 基础定义
│   │   │   ├── NodeSource.scala
│   │   │   └── NodeSink.scala
│   │   ├── sources/          # Source节点
│   │   │   ├── MySQLSource.scala
│   │   │   └── FileSource.scala
│   │   ├── transforms/       # Transform节点
│   │   └── sinks/            # Sink节点
│   │       └── MySQLSink.scala
│   └── scheduler/            # 调度系统
│       ├── WorkflowScheduler.scala
│       └── SchedulerManager.scala
│
└── api/                      # API接口
    └── http/
        ├── models/           # 数据模型
        └── routes/           # 路由
            ├── HttpRoutes.scala
            └── WorkflowRoutes.scala
```

## 添加新节点

### 步骤1：创建节点类

#### Source节点示例

```scala
package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import spray.json._

class HTTPSource extends NodeSource {
  
  override def createSource(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  ): Source[String, NotUsed] = {
    
    val url = node.config.getOrElse("url", "").toString
    val method = node.config.getOrElse("method", "GET").toString
    
    onLog(s"HTTP请求: $method $url")
    
    // 实现HTTP请求逻辑
    Source.single(s"""{"data": "from $url"}""")
  }
}
```

#### Transform节点示例

```scala
package cn.xuyinyin.magic.workflow.nodes.transforms

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

class JSONParser {
  
  def createTransform(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  ): Flow[String, String, NotUsed] = {
    
    onLog("JSON解析转换")
    
    Flow[String].map { jsonStr =>
      // JSON解析逻辑
      jsonStr.parseJson.prettyPrint
    }
  }
}
```

#### Sink节点示例

```scala
package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSink
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import scala.concurrent.{ExecutionContext, Future}

class ElasticsearchSink extends NodeSink {
  
  override def createSink(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    
    val index = node.config.getOrElse("index", "default").toString
    
    onLog(s"写入Elasticsearch索引: $index")
    
    Sink.foreach[String] { data =>
      // 写入ES逻辑
      println(s"Indexing: $data")
    }
  }
}
```

### 步骤2：注册节点

在`NodeRegistry.scala`中注册：

```scala
class NodeRegistry(implicit ec: ExecutionContext) {
  
  private val sources: Map[String, NodeSource] = Map(
    "random.numbers" -> new RandomNumbersSource(),
    "file.csv" -> new FileSource(),
    "http.request" -> new HTTPSource()  // ⬅️ 新增
  )
  
  private val sinks: Map[String, NodeSink] = Map(
    "console.log" -> new ConsoleLogSink(),
    "file.text" -> new FileTextSink(),
    "elasticsearch" -> new ElasticsearchSink()  // ⬅️ 新增
  )
}
```

### 步骤3：添加到Executor

在`SourceExecutor.scala`中添加支持：

```scala
class SourceExecutor extends NodeExecutor {
  
  override def supportedTypes: Set[String] = Set(
    "file.csv",
    "random.numbers",
    "http.request",  // ⬅️ 新增
    // ... 其他类型
  )
}
```

### 步骤4：更新前端

在前端节点面板添加新节点：

```typescript
// xxt-ui/src/config/nodeTypes.ts
export const nodeTypes = {
  sources: [
    { type: 'random.numbers', label: '随机数生成' },
    { type: 'file.csv', label: 'CSV文件' },
    { type: 'http.request', label: 'HTTP请求' }, // ⬅️ 新增
  ],
  // ...
};
```

## 编译和测试

### 编译项目

```bash
# 清理
sbt clean

# 编译
sbt "project pekko-server" compile

# 运行测试
sbt "project pekko-server" test
```

### 单元测试

```scala
class WorkflowExecutionEngineSpec extends AnyFlatSpec {
  
  "WorkflowExecutionEngine" should "validate workflow correctly" in {
    val workflow = WorkflowDSL.Workflow(
      id = "test_1",
      name = "测试工作流",
      nodes = List(
        Node("s1", "random.numbers", "source", Map()),
        Node("k1", "console.log", "sink", Map())
      ),
      edges = List(Edge("s1", "k1"))
    )
    
    // 测试验证逻辑
  }
}
```

### 集成测试

```bash
# 启动服务
sbt "project pekko-server" run &

# 运行集成测试
curl -X POST http://localhost:8080/api/v1/workflows -d @test-workflow.json

# 停止服务
kill %1
```

## 调试技巧

### 启用调试日志

在`logback.xml`中：

```xml
<logger name="cn.xuyinyin.magic.workflow" level="DEBUG"/>
```

### 远程调试

```bash
# 启动时添加调试参数
sbt -jvm-debug 5005 "project pekko-server" run
```

然后在IDE中连接到端口5005。

### Actor消息跟踪

```scala
import org.apache.pekko.actor.typed.receptionist.Receptionist

// 订阅Actor系统事件
context.system.receptionist ! Receptionist.Subscribe(...)
```

## 代码规范

### Scala Style

遵循Scala官方编码规范：

```scala
// 好的
def processData(input: String): Future[Result] = {
  Future.successful(Result(input))
}

// 避免
def processData(input:String):Future[Result]={Future.successful(Result(input))}
```

### 命名约定

- 类名：PascalCase（如`WorkflowActor`）
- 方法名：camelCase（如`createWorkflow`）
- 常量：UPPER_SNAKE_CASE（如`MAX_RETRIES`）
- 包名：小写（如`workflow.actors`）

### 文档注释

```scala
/**
 * 工作流执行引擎
 * 
 * 基于Pekko Stream实现DSL执行
 * 
 * @param system Actor系统
 * @param ec 执行上下文
 */
class WorkflowExecutionEngine(
  implicit system: ActorSystem[_],
  ec: ExecutionContext
) {
  // ...
}
```

## 性能优化

### 1. 避免阻塞操作

```scala
// ❌ 错误 - 阻塞
val result = Await.result(future, Duration.Inf)

// ✅ 正确 - 异步
future.map { result =>
  // 处理结果
}
```

### 2. 合理使用背压

```scala
source
  .buffer(100, OverflowStrategy.backpressure)
  .via(transform)
  .to(sink)
```

### 3. 批量处理

```scala
source
  .grouped(100)  // 批量处理
  .mapAsync(4) { batch =>
    // 异步处理批次
  }
  .to(sink)
```

## 常见问题

### 编译错误

**问题**: 找不到某个包  
**解决**: 
```bash
sbt clean
sbt update
sbt compile
```

### Actor无响应

**问题**: Actor消息没有响应  
**解决**: 检查Actor的行为定义和消息处理

```scala
def behavior: Behavior[Command] = Behaviors.receive { (context, message) =>
  context.log.debug(s"Received: $message")  // 添加日志
  // 处理消息
  Behaviors.same
}
```

### 内存溢出

**问题**: OutOfMemoryError  
**解决**: 增加JVM内存

```bash
sbt -J-Xmx4G "project pekko-server" run
```

## 贡献代码

### 提交前检查清单

- [ ] 代码通过编译
- [ ] 添加了单元测试
- [ ] 更新了文档
- [ ] 遵循代码规范
- [ ] 提交信息清晰

### Git工作流

```bash
# 1. 创建特性分支
git checkout -b feature/new-node-type

# 2. 开发和提交
git add .
git commit -m "feat: 添加HTTP请求节点"

# 3. 推送到远程
git push origin feature/new-node-type

# 4. 创建Pull Request
```

### Commit消息规范

```
feat: 添加新功能
fix: 修复bug
docs: 文档更新
style: 代码格式调整
refactor: 代码重构
test: 测试相关
chore: 构建/工具相关
```

## 相关文档

- [快速开始](QUICKSTART.md)
- [API使用](API_USAGE.md)
- [添加新节点指南](../ADD_NEW_NODE_GUIDE.md)
- [Week2进度](../WEEK2_PROGRESS.md)
