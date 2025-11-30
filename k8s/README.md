# Kubernetes部署指南

本目录包含Pekko Workflow Engine和DataFusion Service的Kubernetes部署配置。

## 文件说明

- `datafusion-service-deployment.yaml` - DataFusion Service部署配置
- `pekko-server-deployment.yaml` - Pekko Server部署配置
- `ingress.yaml` - Ingress配置
- `monitoring/` - 监控相关配置（Prometheus、Grafana）

## 快速部署

### 1. 部署DataFusion Service

```bash
kubectl apply -f datafusion-service-deployment.yaml
```

这将创建：
- Deployment (3个副本)
- Service (ClusterIP)
- ConfigMap (配置文件)
- HorizontalPodAutoscaler (自动扩缩容)

### 2. 部署Pekko Server

```bash
kubectl apply -f pekko-server-deployment.yaml
```

这将创建：
- Deployment (3个副本)
- Service (Headless，用于Pekko Cluster)
- Service (LoadBalancer，外部访问)
- HorizontalPodAutoscaler

### 3. 配置Ingress（可选）

```bash
# 修改ingress.yaml中的域名
kubectl apply -f ingress.yaml
```

### 4. 部署监控（可选）

```bash
kubectl apply -f monitoring/
```

## 验证部署

### 检查Pod状态

```bash
# 查看所有Pod
kubectl get pods

# 查看DataFusion Service
kubectl get pods -l app=datafusion-service

# 查看Pekko Server
kubectl get pods -l app=pekko-server
```

### 检查Service

```bash
kubectl get svc
```

### 查看日志

```bash
# DataFusion Service日志
kubectl logs -l app=datafusion-service -f

# Pekko Server日志
kubectl logs -l app=pekko-server -f
```

## 配置说明

### 资源限制

**DataFusion Service**:
- Requests: 500m CPU, 1Gi Memory
- Limits: 2000m CPU, 4Gi Memory

**Pekko Server**:
- Requests: 500m CPU, 1Gi Memory
- Limits: 2000m CPU, 2Gi Memory

### 自动扩缩容

两个服务都配置了HPA：
- 最小副本数: 3
- 最大副本数: 10
- CPU目标: 70%
- Memory目标: 80%

### 健康检查

**DataFusion Service**:
- Liveness Probe: 每30秒检查一次
- Readiness Probe: 每10秒检查一次

**Pekko Server**:
- Liveness Probe: HTTP GET /health
- Readiness Probe: HTTP GET /health

## 环境变量配置

### DataFusion Service

```yaml
env:
- name: DATAFUSION_HOST
  value: "0.0.0.0"
- name: DATAFUSION_PORT
  value: "50051"
- name: DATAFUSION_MAX_MEMORY_MB
  value: "4096"
- name: RUST_LOG
  value: "info"
```

### Pekko Server

```yaml
env:
- name: DATAFUSION_ENABLED
  value: "true"
- name: DATAFUSION_HOST
  value: "datafusion-service"
- name: DATAFUSION_PORT
  value: "50051"
- name: DATAFUSION_POOL_MAX_TOTAL
  value: "10"
```

## 更新部署

### 更新镜像

```bash
# 更新DataFusion Service
kubectl set image deployment/datafusion-service \
  datafusion-service=datafusion-service:v1.1

# 更新Pekko Server
kubectl set image deployment/pekko-server \
  pekko-server=pekko-server:v1.1
```

### 滚动更新

```bash
kubectl rollout status deployment/datafusion-service
kubectl rollout status deployment/pekko-server
```

### 回滚

```bash
kubectl rollout undo deployment/datafusion-service
kubectl rollout undo deployment/pekko-server
```

## 扩缩容

### 手动扩缩容

```bash
# 扩展到5个副本
kubectl scale deployment datafusion-service --replicas=5
kubectl scale deployment pekko-server --replicas=5
```

### 查看HPA状态

```bash
kubectl get hpa
```

## 监控

### Prometheus指标

访问Prometheus：
```bash
kubectl port-forward svc/prometheus 9090:9090
```

然后访问 http://localhost:9090

### Grafana仪表板

访问Grafana：
```bash
kubectl port-forward svc/grafana 3000:3000
```

然后访问 http://localhost:3000 (默认用户名/密码: admin/admin)

## 故障排查

### Pod无法启动

```bash
# 查看Pod详情
kubectl describe pod <pod-name>

# 查看事件
kubectl get events --sort-by='.lastTimestamp'
```

### 服务连接问题

```bash
# 测试DataFusion Service连接
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  telnet datafusion-service 50051

# 测试Pekko Server连接
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl http://pekko-server:9906/health
```

### 查看资源使用

```bash
kubectl top pods
kubectl top nodes
```

## 清理

### 删除所有资源

```bash
kubectl delete -f datafusion-service-deployment.yaml
kubectl delete -f pekko-server-deployment.yaml
kubectl delete -f ingress.yaml
kubectl delete -f monitoring/
```

## 生产环境建议

1. **使用Secrets管理敏感信息**
   ```bash
   kubectl create secret generic datafusion-secrets \
     --from-literal=api-key=your-api-key
   ```

2. **配置资源配额**
   ```yaml
   apiVersion: v1
   kind: ResourceQuota
   metadata:
     name: pekko-workflow-quota
   spec:
     hard:
       requests.cpu: "10"
       requests.memory: 20Gi
       limits.cpu: "20"
       limits.memory: 40Gi
   ```

3. **配置网络策略**
   ```yaml
   apiVersion: networking.k8s.io/v1
   kind: NetworkPolicy
   metadata:
     name: datafusion-network-policy
   spec:
     podSelector:
       matchLabels:
         app: datafusion-service
     policyTypes:
     - Ingress
     ingress:
     - from:
       - podSelector:
           matchLabels:
             app: pekko-server
   ```

4. **配置Pod Disruption Budget**
   ```yaml
   apiVersion: policy/v1
   kind: PodDisruptionBudget
   metadata:
     name: datafusion-pdb
   spec:
     minAvailable: 2
     selector:
       matchLabels:
         app: datafusion-service
   ```

5. **使用持久化存储**（如果需要）
   ```yaml
   volumeMounts:
   - name: data
     mountPath: /data
   volumes:
   - name: data
     persistentVolumeClaim:
       claimName: datafusion-pvc
   ```

## 更多信息

- [Kubernetes文档](https://kubernetes.io/docs/)
- [Pekko Cluster on Kubernetes](https://pekko.apache.org/docs/pekko/current/cluster-usage.html)
- [DataFusion文档](https://arrow.apache.org/datafusion/)
