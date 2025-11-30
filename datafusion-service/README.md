# DataFusion Service

高性能SQL查询服务，使用Apache Arrow DataFusion和Arrow Flight RPC协议。

## 功能特性

- ✅ Arrow Flight RPC服务器
- ✅ DataFusion SQL查询引擎
- ✅ 配置管理（文件 + 环境变量）
- ✅ 结构化日志（JSON格式）
- ✅ SQL查询执行（do_get方法）
- ✅ 查询元数据获取（get_flight_info方法）
- ✅ 查询超时控制（可配置）
- ✅ 查询指标记录（执行时间、数据量）
- ✅ 健康检查（list_flights方法）
- 🚧 参数化查询（计划中）
- 🚧 TLS支持（计划中）

## 快速开始

### 构建

```bash
cargo build --release
```

### 运行

使用默认配置：

```bash
cargo run
```

使用自定义配置文件：

```bash
CONFIG_PATH=config.toml cargo run
```

使用环境变量：

```bash
DATAFUSION_HOST=0.0.0.0 \
DATAFUSION_PORT=50051 \
MAX_MEMORY_MB=4096 \
RUST_LOG=info \
cargo run
```

### 测试

```bash
cargo test
```

## 配置

配置优先级：环境变量 > 配置文件 > 默认值

### 配置文件

参见 `config.toml` 示例配置。

### 环境变量

- `CONFIG_PATH`: 配置文件路径
- `DATAFUSION_HOST`: 监听地址
- `DATAFUSION_PORT`: 监听端口
- `MAX_MEMORY_MB`: 最大内存使用（MB）
- `RUST_LOG`: 日志级别（trace, debug, info, warn, error）
- `LOG_FORMAT`: 日志格式（json, text）

## 架构

```
┌─────────────────────────────────────┐
│  Arrow Flight Server                │
│  - do_get: 执行SQL查询              │
│  - get_flight_info: 获取元数据      │
│  - list_flights: 健康检查           │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  Query Executor                     │
│  - SQL解析和执行                    │
│  - 超时控制（可配置）               │
│  - 查询指标记录                     │
│  - 查询ID生成                       │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  DataFusion                         │
│  - 向量化执行                       │
│  - 查询优化                         │
│  - Schema推断                       │
└─────────────────────────────────────┘
```

## 查询流程

1. **客户端发送查询**
   - 通过Arrow Flight Ticket发送SQL
   
2. **服务端解析**
   - 从Ticket提取SQL字符串
   - 生成唯一查询ID
   
3. **执行查询**
   - 使用DataFusion执行SQL
   - 应用超时控制
   - 记录执行指标
   
4. **返回结果**
   - 将RecordBatch转换为FlightData流
   - 零拷贝传输
   
5. **日志记录**
   - 查询开始/结束
   - 执行时间
   - 数据量（行数、字节数）
   - 错误信息

## 开发

### 项目结构

```
datafusion-service/
├── src/
│   ├── main.rs           # 主程序入口
│   ├── config.rs         # 配置管理
│   ├── flight_server.rs  # Arrow Flight服务器
│   └── executor.rs       # SQL查询执行器
├── Cargo.toml            # 依赖配置
├── config.toml           # 默认配置
└── README.md             # 本文档
```

### 依赖

- `datafusion`: SQL查询引擎
- `arrow-flight`: Arrow Flight RPC协议
- `tonic`: gRPC框架
- `tokio`: 异步运行时
- `tracing`: 日志框架

## 许可证

MIT
