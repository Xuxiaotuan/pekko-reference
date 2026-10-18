# PekkoSync 性能基准测试计划

> **目标**: 用实际数据证明PekkoSync比SeaTunnel和DataX更强

---

## 📊 测试方案

### 测试环境

#### 硬件配置

```
# 3节点集群
每节点配置：
- CPU: 4 vCPU (Intel Xeon or AMD EPYC)
- RAM: 8 GB
- Disk: 100GB SSD
- Network: 1Gbps

# 数据库服务器
MySQL服务器：
- CPU: 8 vCPU
- RAM: 16 GB
- Disk: 500GB SSD

PostgreSQL服务器：
- CPU: 8 vCPU
- RAM: 16 GB
- Disk: 500GB SSD
```

#### 软件版本

```
PekkoSync: v1.0.0
DataX: 3.0
SeaTunnel: 2.3.3

MySQL: 8.0
PostgreSQL: 14
Kafka: 3.6
```

### 测试数据集

#### 数据集1: TPC-H标准测试集

```sql
-- TPC-H 10GB数据集
LINEITEM表：60,000,000行
ORDERS表：  15,000,000行
CUSTOMER表：1,500,000行
```

#### 数据集2: 宽表测试

```sql
-- 100列宽表
CREATE TABLE wide_table (
  id BIGINT PRIMARY KEY,
  col1 VARCHAR(100),
  col2 VARCHAR(100),
  ...
  col100 TEXT
);

-- 插入1000万行
```

#### 数据集3: CDC测试

```sql
-- 持续写入表
CREATE TABLE realtime_orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  amount DECIMAL(10,2),
  status VARCHAR(20),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 每秒插入1000行
-- 每秒更新500行
-- 每秒删除100行
```

---

## 🧪 测试场景

### 场景1: 批量全量同步

**目标**: 对比MySQL → PostgreSQL全量同步性能

#### 测试配置

```yaml
PekkoSync:
  source:
    type: mysql
    fetchSize: 1000
  sink:
    type: postgresql
    batchSize: 1000
  parallelism: 4

DataX:
  reader:
    fetchSize: 1024
  writer:
    batchSize: 1024
  channel: 4

SeaTunnel:
  source:
    result_table_name: "input"
  sink:
    batch_size: 1000
  parallelism: 4
```

#### 测试指标

| 工具 | 总行数 | 耗时 | 吞吐量 | CPU占用 | 内存占用 |
|------|--------|------|--------|---------|----------|
| DataX | 60M | ? | ? | ? | ? |
| SeaTunnel | 60M | ? | ? | ? | ? |
| **PekkoSync** | 60M | ? | ? | ? | ? |

#### 测试脚本

**PekkoSync**

```bash
# 启动3节点集群
./scripts/start-cluster.sh 3

# 创建同步任务
curl -X POST http://localhost:8080/api/v1/sync/jobs \
  -d @benchmark/configs/batch-mysql-to-postgres.json

# 监控性能
./scripts/monitor-metrics.sh job-001
```

**DataX**

```bash
# 运行DataX
python datax.py benchmark/configs/datax-mysql-to-postgres.json
```

**SeaTunnel**

```bash
# 运行SeaTunnel
./bin/seatunnel.sh --config benchmark/configs/seatunnel-mysql-to-postgres.conf
```

---

### 场景2: 实时CDC同步

**目标**: 对比MySQL CDC → ClickHouse实时同步性能

#### 测试配置

```yaml
# 模拟生产负载
MySQL写入:
  INSERT: 1000 rows/sec
  UPDATE: 500 rows/sec
  DELETE: 100 rows/sec

测试时长: 1小时
```

#### 测试指标

| 工具 | 延迟(P50) | 延迟(P99) | 吞吐量 | 数据丢失 | 故障恢复时间 |
|------|-----------|-----------|--------|----------|--------------|
| SeaTunnel CDC | ? | ? | ? | ? | ? |
| **PekkoSync CDC** | ? | ? | ? | ? | ? |

#### 测试脚本

**数据生成器**

```scala
// 模拟高频写入
object MySQLDataGenerator extends App {
  
  val connection = DriverManager.getConnection(...)
  
  // 并发写入
  (1 to 10).par.foreach { thread =>
    while (true) {
      // INSERT
      (1 to 100).foreach { _ =>
        connection.executeUpdate(
          "INSERT INTO realtime_orders ..."
        )
      }
      
      // UPDATE
      (1 to 50).foreach { _ =>
        connection.executeUpdate(
          "UPDATE realtime_orders SET status = 'completed' WHERE ..."
        )
      }
      
      // DELETE
      (1 to 10).foreach { _ =>
        connection.executeUpdate(
          "DELETE FROM realtime_orders WHERE ..."
        )
      }
      
      Thread.sleep(1000)
    }
  }
}
```

**PekkoSync CDC**

```bash
# 启动CDC任务
curl -X POST http://localhost:8080/api/v1/sync/jobs \
  -d @benchmark/configs/cdc-mysql-to-clickhouse.json

# 监控延迟
./scripts/monitor-cdc-lag.sh job-002
```

---

### 场景3: 扩展性测试

**目标**: 测试水平扩展能力

#### 测试方案

```
# 固定数据量：60M行
# 变化节点数：1, 2, 3, 5节点
# 测试吞吐量提升
```

#### 预期结果

| 节点数 | PekkoSync吞吐 | 扩展因子 |
|--------|---------------|----------|
| 1 | 10万行/秒 | 1.0x |
| 2 | 18万行/秒 | 1.8x |
| 3 | 24万行/秒 | 2.4x |
| 5 | 40万行/秒 | 4.0x |

---

### 场景4: 容错测试

**目标**: 测试故障恢复能力

#### 测试方案

```
1. 启动CDC同步任务
2. 运行30分钟后，随机kill一个节点
3. 观察恢复时间和数据完整性
4. 对比数据丢失情况
```

#### 测试指标

| 工具 | 恢复时间 | 数据丢失 | 重复数据 |
|------|----------|----------|----------|
| SeaTunnel | ? | ? | ? |
| **PekkoSync** | < 10秒 | 0 | 0 |

---

### 场景5: 资源占用测试

**目标**: 对比CPU和内存占用

#### 测试配置

```
# 固定：同步60M行数据
# 监控：CPU%、内存使用、GC频率
```

#### 测试指标

| 工具 | 平均CPU | 峰值CPU | 平均内存 | 峰值内存 | GC次数 |
|------|---------|---------|----------|----------|--------|
| DataX | ? | ? | ? | ? | ? |
| SeaTunnel | ? | ? | ? | ? | ? |
| **PekkoSync** | ? | ? | ? | ? | ? |

---

## 📈 性能优化验证

### 优化1: 批量大小调优

```
测试不同batchSize对性能影响：
- 100
- 500
- 1000
- 5000
- 10000
```

### 优化2: 并行度调优

```
测试不同parallelism对性能影响：
- 1
- 2
- 4
- 8
- 16
```

### 优化3: 序列化方式对比

```
测试不同序列化格式：
- Java Serialization
- JSON
- Protobuf
- Arrow
```

---

## 🔬 详细测试脚本

### 测试脚本1: 批量同步性能测试

```bash
#!/bin/bash
# benchmark/scripts/batch-sync-benchmark.sh

set -e

TOOLS=("datax" "seatunnel" "pekkosync")
DATA_SIZES=("1M" "10M" "60M")

# 准备测试数据
prepare_data() {
  local size=$1
  echo "准备 $size 测试数据..."
  mysql -u root -p < benchmark/sql/prepare-${size}.sql
}

# 运行单个测试
run_test() {
  local tool=$1
  local size=$2
  
  echo "测试 $tool - $size ..."
  
  # 清空目标表
  psql -U postgres -c "TRUNCATE TABLE target_table"
  
  # 记录开始时间
  START_TIME=$(date +%s)
  
  case $tool in
    "datax")
      python datax.py benchmark/configs/datax-${size}.json
      ;;
    "seatunnel")
      ./bin/seatunnel.sh --config benchmark/configs/seatunnel-${size}.conf
      ;;
    "pekkosync")
      curl -X POST http://localhost:8080/api/v1/sync/jobs \
        -d @benchmark/configs/pekkosync-${size}.json
      # 等待完成
      ./scripts/wait-for-completion.sh
      ;;
  esac
  
  # 记录结束时间
  END_TIME=$(date +%s)
  DURATION=$((END_TIME - START_TIME))
  
  # 验证数据
  SOURCE_COUNT=$(mysql -u root -se "SELECT COUNT(*) FROM source_table")
  TARGET_COUNT=$(psql -U postgres -t -c "SELECT COUNT(*) FROM target_table")
  
  echo "结果: $tool $size"
  echo "  耗时: ${DURATION}s"
  echo "  源行数: $SOURCE_COUNT"
  echo "  目标行数: $TARGET_COUNT"
  echo "  吞吐量: $((SOURCE_COUNT / DURATION)) 行/秒"
  
  # 保存结果
  echo "$tool,$size,$DURATION,$SOURCE_COUNT,$TARGET_COUNT" >> benchmark/results/batch-sync.csv
}

# 主测试流程
main() {
  echo "开始批量同步性能测试..."
  echo "tool,size,duration_sec,source_count,target_count" > benchmark/results/batch-sync.csv
  
  for size in "${DATA_SIZES[@]}"; do
    prepare_data $size
    
    for tool in "${TOOLS[@]}"; do
      run_test $tool $size
      sleep 60  # 冷却时间
    done
  done
  
  echo "测试完成！结果保存在 benchmark/results/batch-sync.csv"
}

main
```

### 测试脚本2: CDC延迟测试

```bash
#!/bin/bash
# benchmark/scripts/cdc-latency-benchmark.sh

set -e

# 启动数据生成器
start_generator() {
  echo "启动MySQL数据生成器..."
  java -jar benchmark/tools/mysql-data-generator.jar \
    --insert-rate 1000 \
    --update-rate 500 \
    --delete-rate 100 &
  GENERATOR_PID=$!
}

# 测试CDC
test_cdc() {
  local tool=$1
  
  echo "测试 $tool CDC..."
  
  case $tool in
    "seatunnel")
      # 启动SeaTunnel CDC
      ./bin/seatunnel.sh --config benchmark/configs/seatunnel-cdc.conf &
      TOOL_PID=$!
      ;;
    "pekkosync")
      # 启动PekkoSync CDC
      curl -X POST http://localhost:8080/api/v1/sync/jobs \
        -d @benchmark/configs/pekkosync-cdc.json
      ;;
  esac
  
  # 运行1小时
  echo "运行测试 1小时..."
  sleep 3600
  
  # 收集指标
  ./scripts/collect-cdc-metrics.sh $tool > benchmark/results/cdc-${tool}.json
  
  # 停止工具
  if [ -n "$TOOL_PID" ]; then
    kill $TOOL_PID
  fi
}

# 主流程
main() {
  start_generator
  
  for tool in "seatunnel" "pekkosync"; do
    test_cdc $tool
    sleep 300  # 冷却时间
  done
  
  # 停止生成器
  kill $GENERATOR_PID
  
  echo "CDC测试完成！"
}

main
```

---

## 📊 结果分析

### 生成报告脚本

```python
# benchmark/scripts/generate-report.py

import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns

# 读取结果
batch_results = pd.read_csv('benchmark/results/batch-sync.csv')
cdc_results_st = pd.read_json('benchmark/results/cdc-seatunnel.json')
cdc_results_ps = pd.read_json('benchmark/results/cdc-pekkosync.json')

# 生成对比图表
fig, axes = plt.subplots(2, 2, figsize=(15, 12))

# 1. 批量同步吞吐量对比
ax1 = axes[0, 0]
batch_pivot = batch_results.pivot(index='size', columns='tool', values='throughput')
batch_pivot.plot(kind='bar', ax=ax1)
ax1.set_title('批量同步吞吐量对比')
ax1.set_ylabel('吞吐量 (行/秒)')

# 2. CDC延迟对比
ax2 = axes[0, 1]
latency_data = pd.DataFrame({
    'SeaTunnel': [cdc_results_st['latency_p50'], cdc_results_st['latency_p99']],
    'PekkoSync': [cdc_results_ps['latency_p50'], cdc_results_ps['latency_p99']]
}, index=['P50', 'P99'])
latency_data.plot(kind='bar', ax=ax2)
ax2.set_title('CDC延迟对比')
ax2.set_ylabel('延迟 (ms)')

# 3. 资源占用对比
# ...

# 4. 扩展性对比
# ...

plt.tight_layout()
plt.savefig('benchmark/results/performance-comparison.png', dpi=300)
print("报告已生成: benchmark/results/performance-comparison.png")
```

---

## ✅ 验收标准

### 必达目标

- ✅ 批量同步吞吐量 > DataX 1.5倍
- ✅ CDC延迟(P99) < SeaTunnel 50%
- ✅ 扩展性因子 > 2.0x @ 3节点
- ✅ 故障恢复时间 < 10秒
- ✅ 数据零丢失

### 挑战目标

- 🎯 批量同步吞吐量 > DataX 2倍
- 🎯 CDC延迟(P99) < 100ms
- 🎯 扩展性因子 > 2.5x @ 3节点
- 🎯  资源占用 < SeaTunnel 20%

---

**执行时间线**: Week 9-10
**负责人**: 性能测试团队
**交付物**: 完整的性能对比报告 + 图表
