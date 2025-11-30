# DataFusion Service Dockerfile
# 多阶段构建，优化镜像大小

# Stage 1: 构建阶段
FROM rust:1.75-slim as builder

WORKDIR /app

# 安装构建依赖
RUN apt-get update && apt-get install -y \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# 复制Cargo文件
COPY Cargo.toml Cargo.lock ./

# 创建虚拟main.rs以缓存依赖
RUN mkdir src && \
    echo "fn main() {}" > src/main.rs && \
    cargo build --release && \
    rm -rf src

# 复制源代码
COPY src ./src

# 构建应用
RUN cargo build --release

# Stage 2: 运行阶段
FROM debian:bookworm-slim

WORKDIR /app

# 安装运行时依赖
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

# 从构建阶段复制二进制文件
COPY --from=builder /app/target/release/datafusion-service /app/datafusion-service

# 复制配置文件
COPY config.toml /app/config.toml
COPY config-prod.toml /app/config-prod.toml

# 创建非root用户
RUN useradd -m -u 1000 datafusion && \
    chown -R datafusion:datafusion /app

USER datafusion

# 暴露端口
EXPOSE 50051

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/app/datafusion-service", "--health-check"]

# 启动服务
ENTRYPOINT ["/app/datafusion-service"]
CMD ["--config", "/app/config-prod.toml"]
