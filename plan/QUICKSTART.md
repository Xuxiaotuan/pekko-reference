# 🚀 快速开始指南

## 环境要求

- **JDK**: 11+ (推荐 Amazon Corretto 11)
- **Scala**: 2.13.12
- **SBT**: 1.9.8+
- **Node.js**: 16+ (前端开发)
- **内存**: 最少 2GB，推荐 4GB+

## 单节点快速启动

```bash
# 1. 克隆项目
git clone <repository-url>
cd pekko-reference

# 2. 设置JDK 11环境
export JAVA_HOME=/path/to/jdk11
export PATH=$JAVA_HOME/bin:$PATH

# 3. 编译项目
sbt "project pekko-server" compile

# 4. 启动单节点集群
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer"
```

## 多节点集群启动

```bash
# 节点1 - Seed节点 (端口2551)
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2551"

# 节点2 - 工作节点 (端口2552) 
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2552"

# 节点3 - 工作节点 (端口2553)
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2553"
```

## 前端启动

```bash
cd xxt-ui
npm install
npm run dev
```

访问 http://localhost:3000

## 验证服务

### HTTP端点

| 端点 | 方法 | 功能 | 
|------|------|------|
| `/` | GET | API文档 |
| `/api/v1/status` | GET | API状态检查 |
| `/api/v1/workflows` | GET | 工作流列表 |
| `/api/v1/workflows` | POST | 创建工作流 |
| `/api/v1/workflows/{id}/execute` | POST | 执行工作流 |
| `/health` | GET | 整体健康状态 |
| `/health/live` | GET | 存活探针 |
| `/health/ready` | GET | 就绪探针 |
| `/monitoring/cluster/status` | GET | 集群状态 |
| `/monitoring/metrics` | GET | 系统指标 |

### 快速健康检查

```bash
# 健康检查
curl http://localhost:8080/health

# 集群状态  
curl http://localhost:8080/monitoring/cluster/status

# API状态
curl http://localhost:8080/api/v1/status
```

## 核心配置

### application.conf

```hocon
pekko {
  pekko-sys = "pekko-cluster-system"
  project-version = "0.3"
  
  actor.provider = "cluster"
  cluster {
    seed-nodes = ["pekko://pekko-cluster-system@127.0.0.1:2551"]
    roles = ["coordinator", "worker"]
    min-nr-of-members = 1
  }
}
```

### JVM配置

```bash
# 推荐JVM参数
-Xms2G -Xmx4G
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
```

## 常见问题

### 端口被占用

```bash
# 查看端口占用
lsof -i :8080
lsof -i :2551

# 杀死进程
kill -9 <PID>
```

### 编译失败

```bash
# 清理并重新编译
sbt clean
sbt "project pekko-server" compile
```

### 前端无法连接后端

检查CORS配置和后端是否正常启动：
```bash
curl http://localhost:8080/health
```
