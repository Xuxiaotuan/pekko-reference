mod flight_server;

use flight_server::DataFusionFlightService;
use tracing::info;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // 初始化日志
    init_logging();

    info!("🚀 Starting DataFusion Arrow Flight Service...");

    // 获取监听地址
    let addr = "0.0.0.0:50051".parse()?;
    info!("Server will listen on: {}", addr);

    // 创建Flight Service
    let flight_service = DataFusionFlightService::new().await?;
    let server = arrow_flight::flight_service_server::FlightServiceServer::new(flight_service);

    // 启动gRPC服务器
    info!("🌐 DataFusion Arrow Flight Server listening on {}", addr);
    info!("📋 Ready to accept Arrow Flight connections");

    tonic::transport::Server::builder()
        .add_service(server)
        .serve(addr)
        .await?;

    Ok(())
}

/// 初始化日志系统
fn init_logging() {
    let format = std::env::var("LOG_FORMAT").unwrap_or_else(|_| "json".to_string());

    let subscriber = tracing_subscriber::registry();

    if format == "json" {
        subscriber
            .with(tracing_subscriber::fmt::layer().json())
            .with(tracing_subscriber::EnvFilter::from_default_env())
            .init();
    } else {
        subscriber
            .with(tracing_subscriber::fmt::layer())
            .with(tracing_subscriber::EnvFilter::from_default_env())
            .init();
    }
}