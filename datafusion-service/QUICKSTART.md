# DataFusion Service - 快速开始

## 🚀 快速启动

### 1. 安装依赖

确保已安装Rust 1.70+：

```bash
# 检查Rust版本
rustc --version

# 如果未安装，使用rustup安装
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

### 2. 构建项目

```bash
cd datafusion-service

# 开发模式构建
cargo build

# 生产模式构建（优化）
cargo build --release
```

### 3. 运行服务

```bash
# 使用默认配置运行
cargo run --release

# 使用指定配置文件
cargo run --release -- --config config-dev.toml

# 使用环境变量
DATAFUSION_HOST=0.0.0.0 DATAFUSION_PORT=50051 cargo run --release
```

### 4. 验证服务

```bash
# 使用验证脚本
./verify.sh

# 或手动测试
./test_service.sh
```

## 📝 配置说明

### 配置文件

项目提供了多个配置文件：

- `config.toml` - 默认配置
- `config-dev.toml` - 开发环境配置
- `config-test.toml` - 测试环境配置
- `config-prod.toml` - 生产环境配置

### 配置示例

```toml
[server]
host = "0.0.0.0"
port = 50051

[query]
max_concurrent_queries = 100
default_timeout_seconds = 30

[memory]
max_memory_mb = 4096

[logging]
level = "info"
format = "json"
```

### 环境变量

所有配置都可以通过环境变量覆盖：

```bash
export DATAFUSION_HOST=0.0.0.0
export DATAFUSION_PORT=50051
export DATAFUSION_MAX_MEMORY_MB=8192
export RUST_LOG=info
```

## 🧪 测试

### 运行测试

```bash
# 运行所有测试
cargo test

# 运行特定测试
cargo test test_query_executor

# 显示测试输出
cargo test -- --nocapture
```

### 手动测试

使用提供的测试脚本：

```bash
# 测试基本功能
./test_service.sh

# 测试健康检查
curl http://localhost:50051/health
```

## 📊 监控

### 日志

服务使用结构化日志（JSON格式）：

```json
{
  "timestamp": "2024-11-29T10:00:00Z",
  "level": "INFO",
  "message": "Query executed successfully",
  "query_id": "abc123",
  "duration_ms": 150,
  "rows": 1000
}
```

### 指标

服务暴露以下指标：

- 查询执行时间
- 查询总数
- 错误数
- 内存使用

## 🐳 Docker部署

### 构建镜像

```bash
docker build -t datafusion-service:latest .
```

### 运行容器

```bash
docker run -d \
  --name datafusion-service \
  -p 50051:50051 \
  -e DATAFUSION_HOST=0.0.0.0 \
  -e RUST_LOG=info \
  datafusion-service:latest
```

### Docker Compose

```yaml
version: '3.8'
services:
  datafusion:
    build: .
    ports:
      - "50051:50051"
    environment:
      - DATAFUSION_HOST=0.0.0.0
      - RUST_LOG=info
    volumes:
      - ./config-prod.toml:/app/config.toml
```

## 🔧 故障排查

### 服务无法启动

1. 检查端口是否被占用：
```bash
lsof -i :50051
```

2. 检查配置文件是否正确：
```bash
cat config.toml
```

3. 查看日志：
```bash
RUST_LOG=debug cargo run
```

### 查询失败

1. 检查SQL语法
2. 查看错误日志
3. 验证内存配置

### 性能问题

1. 增加内存限制
2. 调整并发查询数
3. 优化SQL查询

## 📚 API文档

### Arrow Flight RPC

服务实现了Arrow Flight协议：

- `do_get` - 执行SQL查询并返回结果
- `get_flight_info` - 获取查询元数据
- `list_flights` - 健康检查

### 查询示例

使用Arrow Flight客户端：

```rust
use arrow_flight::FlightClient;

let client = FlightClient::new("localhost:50051");
let sql = "SELECT * FROM table";
let results = client.do_get(sql).await?;
```

## 🎯 性能优化

### 内存配置

```toml
[memory]
max_memory_mb = 8192  # 根据可用内存调整
```

### 并发配置

```toml
[query]
max_concurrent_queries = 200  # 根据CPU核心数调整
```

### 查询优化

1. 使用列式查询
2. 添加适当的过滤条件
3. 限制返回行数

## 🔐 安全建议

1. **网络安全**
   - 使用防火墙限制访问
   - 考虑使用TLS加密

2. **资源限制**
   - 设置内存限制
   - 限制并发查询数

3. **查询验证**
   - 验证SQL语法
   - 限制查询复杂度

## 📖 更多资源

- [DataFusion文档](https://arrow.apache.org/datafusion/)
- [Arrow Flight文档](https://arrow.apache.org/docs/format/Flight.html)
- [项目README](README.md)

## 🎉 开始使用

现在您可以开始使用DataFusion Service了！

```bash
# 启动服务
cargo run --release

# 在另一个终端测试
./test_service.sh
```

祝您使用愉快！
