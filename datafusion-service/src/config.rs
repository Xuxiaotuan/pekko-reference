use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::path::PathBuf;

/// DataFusion Service配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServiceConfig {
    /// 服务器配置
    pub server: ServerConfig,

    /// 查询配置
    pub query: QueryConfig,

    /// TLS配置
    #[serde(default)]
    pub tls: TlsConfig,

    /// 日志配置
    #[serde(default)]
    pub logging: LoggingConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    /// 监听地址
    #[serde(default = "default_host")]
    pub host: String,

    /// 监听端口
    #[serde(default = "default_port")]
    pub port: u16,

    /// 最大连接数
    #[serde(default = "default_max_connections")]
    pub max_connections: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryConfig {
    /// 最大内存使用（MB）
    #[serde(default = "default_max_memory_mb")]
    pub max_memory_mb: usize,

    /// 批处理大小
    #[serde(default = "default_batch_size")]
    pub batch_size: usize,

    /// 查询超时（秒）
    #[serde(default = "default_timeout_seconds")]
    pub timeout_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TlsConfig {
    /// 是否启用TLS
    #[serde(default)]
    pub enabled: bool,

    /// 证书路径
    pub cert_path: Option<PathBuf>,

    /// 密钥路径
    pub key_path: Option<PathBuf>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingConfig {
    /// 日志级别
    #[serde(default = "default_log_level")]
    pub level: String,

    /// 日志格式
    #[serde(default = "default_log_format")]
    pub format: String,
}

// 默认值函数
fn default_host() -> String {
    "0.0.0.0".to_string()
}

fn default_port() -> u16 {
    50051
}

fn default_max_connections() -> usize {
    100
}

fn default_max_memory_mb() -> usize {
    4096
}

fn default_batch_size() -> usize {
    8192
}

fn default_timeout_seconds() -> u64 {
    300
}

fn default_log_level() -> String {
    "info".to_string()
}

fn default_log_format() -> String {
    "json".to_string()
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            host: default_host(),
            port: default_port(),
            max_connections: default_max_connections(),
        }
    }
}

impl Default for QueryConfig {
    fn default() -> Self {
        Self {
            max_memory_mb: default_max_memory_mb(),
            batch_size: default_batch_size(),
            timeout_seconds: default_timeout_seconds(),
        }
    }
}

impl Default for LoggingConfig {
    fn default() -> Self {
        Self {
            level: default_log_level(),
            format: default_log_format(),
        }
    }
}

impl Default for ServiceConfig {
    fn default() -> Self {
        Self {
            server: ServerConfig::default(),
            query: QueryConfig::default(),
            tls: TlsConfig::default(),
            logging: LoggingConfig::default(),
        }
    }
}

impl ServiceConfig {
    /// 从TOML文件加载配置
    pub fn from_file(path: &str) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path)?;
        let config: ServiceConfig = toml::from_str(&content)?;
        Ok(config)
    }

    /// 从环境变量加载配置（覆盖文件配置）
    pub fn from_env(mut self) -> Self {
        // Server配置
        if let Ok(host) = std::env::var("DATAFUSION_HOST") {
            self.server.host = host;
        }
        if let Ok(port) = std::env::var("DATAFUSION_PORT") {
            if let Ok(port) = port.parse() {
                self.server.port = port;
            }
        }
        if let Ok(max_conn) = std::env::var("DATAFUSION_MAX_CONNECTIONS") {
            if let Ok(max_conn) = max_conn.parse() {
                self.server.max_connections = max_conn;
            }
        }

        // Query配置
        if let Ok(max_memory) = std::env::var("DATAFUSION_MAX_MEMORY_MB") {
            if let Ok(max_memory) = max_memory.parse() {
                self.query.max_memory_mb = max_memory;
            }
        }
        if let Ok(batch_size) = std::env::var("DATAFUSION_BATCH_SIZE") {
            if let Ok(batch_size) = batch_size.parse() {
                self.query.batch_size = batch_size;
            }
        }
        if let Ok(timeout) = std::env::var("DATAFUSION_TIMEOUT_SECONDS") {
            if let Ok(timeout) = timeout.parse() {
                self.query.timeout_seconds = timeout;
            }
        }

        // TLS配置
        if let Ok(enabled) = std::env::var("DATAFUSION_TLS_ENABLED") {
            self.tls.enabled = enabled.to_lowercase() == "true";
        }
        if let Ok(cert_path) = std::env::var("DATAFUSION_TLS_CERT") {
            self.tls.cert_path = Some(PathBuf::from(cert_path));
        }
        if let Ok(key_path) = std::env::var("DATAFUSION_TLS_KEY") {
            self.tls.key_path = Some(PathBuf::from(key_path));
        }

        // Logging配置
        if let Ok(level) = std::env::var("DATAFUSION_LOG_LEVEL") {
            self.logging.level = level;
        }
        if let Ok(format) = std::env::var("DATAFUSION_LOG_FORMAT") {
            self.logging.format = format;
        }

        self
    }

    /// 验证配置
    pub fn validate(&self) -> anyhow::Result<()> {
        if self.server.port == 0 {
            anyhow::bail!("Invalid port: 0");
        }
        if self.server.max_connections == 0 {
            anyhow::bail!("max_connections must be greater than 0");
        }
        if self.query.max_memory_mb == 0 {
            anyhow::bail!("max_memory_mb must be greater than 0");
        }
        if self.query.batch_size == 0 {
            anyhow::bail!("batch_size must be greater than 0");
        }
        if self.tls.enabled {
            if self.tls.cert_path.is_none() {
                anyhow::bail!("TLS enabled but cert_path not provided");
            }
            if self.tls.key_path.is_none() {
                anyhow::bail!("TLS enabled but key_path not provided");
            }
        }
        Ok(())
    }

    /// 获取监听地址
    pub fn socket_addr(&self) -> anyhow::Result<SocketAddr> {
        let addr = format!("{}:{}", self.server.host, self.server.port);
        Ok(addr.parse()?)
    }
}
