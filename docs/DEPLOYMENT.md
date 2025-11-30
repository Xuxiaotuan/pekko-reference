# 部署指南

本文档提供分布式工作流引擎的部署指南，包括单节点部署、多节点集群部署、Kubernetes部署和故障排查。

## 目录

- [系统要求](#系统要求)
- [单节点部署](#单节点部署)
- [多节点集群部署](#多节点集群部署)
- [Kubernetes部署](#kubernetes部署)
- [监控和日志](#监控和日志)
- [故障排查](#故障排查)
- [性能调优](#性能调优)

## 系统要求

### 硬件要求

**最小配置（开发/测试）：**
- CPU: 2核
- 内存: 4GB
- 磁盘: 20GB

**推荐配置（生产环境单节点）：**
- CPU: 4核
- 内存: 8GB
- 磁盘: 100GB SSD

**推荐配置（生产环境集群节点）：**
- CPU: 8核
- 内存: 16GB
- 磁盘: 200GB SSD

### 软件要求

- Java: JDK 11 或更高版本
- Scala: 2.13.x
- SBT: 1.9.x（仅开发环境）
- 操作系统: Linux (推荐 Ubuntu 20.04+, CentOS 7+)

### 网络要求

- 节点间网络延迟: < 10ms（推荐）
- 带宽: 1Gbps+（推荐）
- 端口:
  - 2551: Pekko Cluster通信
  - 8080: HTTP API
  - 9095: Prometheus指标

## 单节点部署

单节点部署适用于开发、测试和小规模生产环境。

### 1. 准备工作

```bash
# 创建应用目录
sudo mkdir -p /opt/workflow-engine
cd /opt/workflow-engine

# 创建数据目录
sudo mkdir -p /var/lib/pekko/{journal,snapshots}
sudo mkdir -p /var/log/workflow-engine

# 设置权限
sudo chown -R $USER:$USER /opt/workflow-engine
sudo chown -R $USER:$USER /var/lib/pekko
sudo chown -R $USER:$USER /var/log/workflow-engine
```

### 2. 构建应用

```bash
# 克隆代码
git clone <repository-url>
cd pekko-reference

# 构建
sbt "project pekko-server" assembly

# 复制JAR文件
cp pekko-server/target/scala-2.13/pekko-server-assembly-*.jar \
   /opt/workflow-engine/workflow-engine.jar
```

### 3. 配置

创建配置文件 `/opt/workflow-engine/application.conf`:

```hocon
include classpath("application.conf")

pekko {
  cluster {
    seed-nodes = ["pekko://pekko-cluster-system@127.0.0.1:2551"]
    roles = ["coordinator", "worker", "api-gateway"]
  }
  
  remote.artery.canonical {
    hostname = "127.0.0.1"
    port = 2551
  }
}
```

### 4. 启动服务

```bash
# 直接启动
java -Dconfig.file=/opt/workflow-engine/application.conf \
     -Xmx4g -Xms4g \
     -jar /opt/workflow-engine/workflow-engine.jar

# 或使用systemd服务
sudo systemctl start workflow-engine
```

### 5. 验证部署

```bash
# 检查集群状态
curl http://localhost:8080/api/v1/cluster/stats

# 检查健康状态
curl http://localhost:8080/health

# 查看Prometheus指标
curl http://localhost:9095/metrics
```

## 多节点集群部署

多节点集群部署适用于生产环境，提供高可用性和水平扩展能力。

### 架构设计

**推荐3节点配置：**
- Node 1: coordinator + worker
- Node 2: worker + api-gateway
- Node 3: worker + api-gateway

### 1. 准备工作

在每个节点上执行：

```bash
# 创建目录
sudo mkdir -p /opt/workflow-engine
sudo mkdir -p /var/lib/pekko/{journal,snapshots}
sudo mkdir -p /var/log/workflow-engine

# 设置权限
sudo chown -R workflow:workflow /opt/workflow-engine
sudo chown -R workflow:workflow /var/lib/pekko
sudo chown -R workflow:workflow /var/log/workflow-engine
```

### 2. 部署应用

在每个节点上：

```bash
# 复制JAR文件
scp workflow-engine.jar node1:/opt/workflow-engine/
scp workflow-engine.jar node2:/opt/workflow-engine/
scp workflow-engine.jar node3:/opt/workflow-engine/
```

### 3. 配置节点

**Node 1 配置** (`/opt/workflow-engine/node1.conf`):

```hocon
include classpath("application-prod.conf")

pekko {
  cluster {
    seed-nodes = [
      "pekko://pekko-cluster-system-prod@node1:2551",
      "pekko://pekko-cluster-system-prod@node2:2551",
      "pekko://pekko-cluster-system-prod@node3:2551"
    ]
    roles = ["coordinator", "worker"]
  }
  
  remote.artery.canonical {
    hostname = "node1"
    port = 2551
  }
}
```

**Node 2 配置** (`/opt/workflow-engine/node2.conf`):

```hocon
include classpath("application-prod.conf")

pekko {
  cluster {
    seed-nodes = [
      "pekko://pekko-cluster-system-prod@node1:2551",
      "pekko://pekko-cluster-system-prod@node2:2551",
      "pekko://pekko-cluster-system-prod@node3:2551"
    ]
    roles = ["worker", "api-gateway"]
  }
  
  remote.artery.canonical {
    hostname = "node2"
    port = 2551
  }
}
```

**Node 3 配置** (`/opt/workflow-engine/node3.conf`):

```hocon
include classpath("application-prod.conf")

pekko {
  cluster {
    seed-nodes = [
      "pekko://pekko-cluster-system-prod@node1:2551",
      "pekko://pekko-cluster-system-prod@node2:2551",
      "pekko://pekko-cluster-system-prod@node3:2551"
    ]
    roles = ["worker", "api-gateway"]
  }
  
  remote.artery.canonical {
    hostname = "node3"
    port = 2551
  }
}
```

### 4. 启动集群

**按顺序启动节点：**

```bash
# Node 1 (种子节点)
ssh node1
sudo systemctl start workflow-engine

# 等待30秒

# Node 2
ssh node2
sudo systemctl start workflow-engine

# 等待30秒

# Node 3
ssh node3
sudo systemctl start workflow-engine
```

### 5. 验证集群

```bash
# 在任意节点上检查集群状态
curl http://node1:8080/api/v1/cluster/stats

# 应该看到3个成员
{
  "members": 3,
  "status": "Up",
  "leader": "pekko://pekko-cluster-system-prod@node1:2551",
  ...
}
```

### 6. 配置负载均衡

使用Nginx作为负载均衡器：

```nginx
upstream workflow_api {
    least_conn;
    server node2:8080 max_fails=3 fail_timeout=30s;
    server node3:8080 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    server_name workflow.example.com;
    
    location / {
        proxy_pass http://workflow_api;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_connect_timeout 30s;
        proxy_send_timeout 30s;
        proxy_read_timeout 30s;
    }
}
```

## Kubernetes部署

Kubernetes部署提供自动化的容器编排和管理。

### 1. 构建Docker镜像

创建 `Dockerfile`:

```dockerfile
FROM openjdk:11-jre-slim

# 安装必要的工具
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# 创建应用目录
WORKDIR /app

# 复制JAR文件
COPY pekko-server/target/scala-2.13/pekko-server-assembly-*.jar /app/workflow-engine.jar

# 创建数据目录
RUN mkdir -p /var/lib/pekko/journal && \
    mkdir -p /var/lib/pekko/snapshots

# 暴露端口
EXPOSE 2551 8080 9095

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# 启动命令
ENTRYPOINT ["java", \
            "-Dconfig.resource=application-prod.conf", \
            "-Xmx4g", "-Xms4g", \
            "-XX:+UseG1GC", \
            "-XX:MaxGCPauseMillis=200", \
            "-jar", "/app/workflow-engine.jar"]
```

构建镜像：

```bash
docker build -t workflow-engine:latest .
docker tag workflow-engine:latest your-registry/workflow-engine:v1.0.0
docker push your-registry/workflow-engine:v1.0.0
```

### 2. 创建Kubernetes资源

**ConfigMap** (`k8s/configmap.yaml`):

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: workflow-engine-config
  namespace: workflow
data:
  application.conf: |
    include classpath("application-prod.conf")
    
    pekko {
      cluster {
        seed-nodes = [
          "pekko://pekko-cluster-system-prod@workflow-engine-0.workflow-engine-headless.workflow.svc.cluster.local:2551",
          "pekko://pekko-cluster-system-prod@workflow-engine-1.workflow-engine-headless.workflow.svc.cluster.local:2551",
          "pekko://pekko-cluster-system-prod@workflow-engine-2.workflow-engine-headless.workflow.svc.cluster.local:2551"
        ]
      }
    }
```

**StatefulSet** (`k8s/statefulset.yaml`):

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: workflow-engine
  namespace: workflow
spec:
  serviceName: workflow-engine-headless
  replicas: 3
  selector:
    matchLabels:
      app: workflow-engine
  template:
    metadata:
      labels:
        app: workflow-engine
    spec:
      containers:
      - name: workflow-engine
        image: your-registry/workflow-engine:v1.0.0
        ports:
        - containerPort: 2551
          name: cluster
        - containerPort: 8080
          name: http
        - containerPort: 9095
          name: metrics
        env:
        - name: PEKKO_HOSTNAME
          valueFrom:
            fieldRef:
              fieldPath: status.podIP
        - name: PEKKO_PORT
          value: "2551"
        - name: PEKKO_ROLES
          value: '["worker"]'
        resources:
          requests:
            memory: "4Gi"
            cpu: "2"
          limits:
            memory: "8Gi"
            cpu: "4"
        volumeMounts:
        - name: config
          mountPath: /app/config
        - name: data
          mountPath: /var/lib/pekko
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
      volumes:
      - name: config
        configMap:
          name: workflow-engine-config
  volumeClaimTemplates:
  - metadata:
      name: data
    spec:
      accessModes: [ "ReadWriteOnce" ]
      resources:
        requests:
          storage: 100Gi
```

**Headless Service** (`k8s/service-headless.yaml`):

```yaml
apiVersion: v1
kind: Service
metadata:
  name: workflow-engine-headless
  namespace: workflow
spec:
  clusterIP: None
  selector:
    app: workflow-engine
  ports:
  - port: 2551
    name: cluster
```

**Service** (`k8s/service.yaml`):

```yaml
apiVersion: v1
kind: Service
metadata:
  name: workflow-engine
  namespace: workflow
spec:
  type: LoadBalancer
  selector:
    app: workflow-engine
  ports:
  - port: 80
    targetPort: 8080
    name: http
  - port: 9095
    targetPort: 9095
    name: metrics
```

### 3. 部署到Kubernetes

```bash
# 创建命名空间
kubectl create namespace workflow

# 应用配置
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/service-headless.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/statefulset.yaml

# 查看部署状态
kubectl get pods -n workflow
kubectl get svc -n workflow

# 查看日志
kubectl logs -f workflow-engine-0 -n workflow
```

### 4. 扩缩容

```bash
# 扩容到5个节点
kubectl scale statefulset workflow-engine --replicas=5 -n workflow

# 缩容到3个节点
kubectl scale statefulset workflow-engine --replicas=3 -n workflow
```

## 监控和日志

### Prometheus监控

**Prometheus配置** (`prometheus.yml`):

```yaml
scrape_configs:
  - job_name: 'workflow-engine'
    static_configs:
      - targets:
        - 'node1:9095'
        - 'node2:9095'
        - 'node3:9095'
    metrics_path: '/metrics'
    scrape_interval: 15s
```

### Grafana仪表板

导入预定义的Grafana仪表板：

1. 登录Grafana
2. 导入仪表板
3. 选择Prometheus数据源
4. 导入 `grafana/workflow-engine-dashboard.json`

### 日志收集

**使用ELK Stack：**

```yaml
# Filebeat配置
filebeat.inputs:
- type: log
  enabled: true
  paths:
    - /var/log/workflow-engine/*.log
  fields:
    app: workflow-engine
    env: production

output.elasticsearch:
  hosts: ["elasticsearch:9200"]
```

## 故障排查

### 常见问题

#### 1. 节点无法加入集群

**症状：**
```
Cluster Node [pekko://...] - Couldn't join seed nodes after [10] attempts
```

**解决方案：**
- 检查网络连接：`telnet node1 2551`
- 检查防火墙规则
- 验证种子节点配置
- 检查系统名称是否一致

#### 2. Entity无法创建

**症状：**
```
ShardRegion [workflow] is not started
```

**解决方案：**
- 检查节点角色配置
- 验证Sharding配置
- 检查持久化插件
- 查看Journal目录权限

#### 3. 内存溢出

**症状：**
```
java.lang.OutOfMemoryError: Java heap space
```

**解决方案：**
- 增加堆内存：`-Xmx8g`
- 调整Passivation时间
- 减少快照保留数量
- 启用G1 GC：`-XX:+UseG1GC`

#### 4. 性能下降

**症状：**
- 响应时间增加
- 吞吐量下降

**解决方案：**
- 检查CPU和内存使用率
- 调整分片数量
- 优化快照频率
- 检查网络延迟

### 日志分析

**查看集群事件：**
```bash
grep "CLUSTER_EVENT" /var/log/workflow-engine/application.log | jq
```

**查看故障转移：**
```bash
grep "workflow_failover" /var/log/workflow-engine/application.log
```

**查看性能指标：**
```bash
curl http://localhost:9095/metrics | grep workflow
```

## 性能调优

### JVM调优

```bash
java -Xmx8g -Xms8g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:ParallelGCThreads=8 \
     -XX:ConcGCThreads=2 \
     -XX:InitiatingHeapOccupancyPercent=45 \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/var/log/workflow-engine/heap-dump.hprof \
     -jar workflow-engine.jar
```

### 配置调优

**高吞吐量场景：**
```hocon
pekko.cluster.sharding {
  number-of-shards = 200
  passivate-idle-entity-after = 2h
}

pekko.workflow.event-sourcing {
  snapshot-every = 200
}
```

**低延迟场景：**
```hocon
pekko.cluster.sharding {
  number-of-shards = 50
  passivate-idle-entity-after = 30m
}

pekko.workflow.event-sourcing {
  snapshot-every = 50
}
```

### 网络调优

```bash
# 增加TCP缓冲区
sudo sysctl -w net.core.rmem_max=16777216
sudo sysctl -w net.core.wmem_max=16777216
sudo sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216"
sudo sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216"

# 增加连接队列
sudo sysctl -w net.core.somaxconn=4096
sudo sysctl -w net.ipv4.tcp_max_syn_backlog=4096
```

## 备份和恢复

### 备份策略

**Journal备份：**
```bash
# 每日备份
tar -czf journal-$(date +%Y%m%d).tar.gz /var/lib/pekko/journal/
aws s3 cp journal-$(date +%Y%m%d).tar.gz s3://backups/workflow-engine/
```

**Snapshot备份：**
```bash
# 每周备份
tar -czf snapshots-$(date +%Y%m%d).tar.gz /var/lib/pekko/snapshots/
aws s3 cp snapshots-$(date +%Y%m%d).tar.gz s3://backups/workflow-engine/
```

### 恢复流程

```bash
# 停止服务
sudo systemctl stop workflow-engine

# 恢复数据
aws s3 cp s3://backups/workflow-engine/journal-20241128.tar.gz .
tar -xzf journal-20241128.tar.gz -C /var/lib/pekko/

# 启动服务
sudo systemctl start workflow-engine
```

## 安全加固

### 网络安全

```bash
# 配置防火墙
sudo ufw allow 2551/tcp  # Cluster
sudo ufw allow 8080/tcp  # HTTP API
sudo ufw allow 9095/tcp  # Metrics
sudo ufw enable
```

### 应用安全

- 启用TLS/SSL
- 配置认证和授权
- 限制API访问
- 定期更新依赖

## 参考资源

- [Pekko Cluster](https://pekko.apache.org/docs/pekko/current/typed/cluster.html)
- [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Prometheus Monitoring](https://prometheus.io/docs/introduction/overview/)
- [配置指南](./CONFIGURATION.md)
