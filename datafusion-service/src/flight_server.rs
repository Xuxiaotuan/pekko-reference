use arrow_flight::{
    flight_service_server::FlightService, Action, ActionType, Criteria, Empty, FlightData,
    FlightDescriptor, FlightInfo, HandshakeRequest, HandshakeResponse, PollInfo,
    SchemaResult, Ticket,
};
use datafusion::prelude::*;
use arrow::datatypes::Schema;
use std::pin::Pin;
use std::sync::Arc;
use tokio::sync::Mutex;
use tokio_stream::{wrappers::ReceiverStream, Stream};
use tonic::{Request, Response, Status, Streaming};
use tracing::{error, info, warn};

type FlightDataStream = Pin<Box<dyn Stream<Item = Result<FlightData, Status>> + Send>>;
type HandshakeStream = Pin<Box<dyn Stream<Item = Result<HandshakeResponse, Status>> + Send>>;
type ListFlightsStream = Pin<Box<dyn Stream<Item = Result<FlightInfo, Status>> + Send>>;
type DoActionStream = Pin<Box<dyn Stream<Item = Result<arrow_flight::Result, Status>> + Send>>;
type ListActionsStream = Pin<Box<dyn Stream<Item = Result<ActionType, Status>> + Send>>;
type DoPutStream = Pin<Box<dyn Stream<Item = Result<arrow_flight::PutResult, Status>> + Send>>;

/// DataFusion Arrow Flight Service
/// 
/// 提供高性能的SQL查询服务，使用Arrow Flight协议进行数据传输
pub struct DataFusionFlightService {
    /// DataFusion会话上下文
    ctx: Arc<Mutex<SessionContext>>,
}

impl DataFusionFlightService {
    /// 创建新的DataFusion Flight服务
    pub async fn new() -> anyhow::Result<Self> {
        info!("Initializing DataFusion SessionContext");
        
        // 创建DataFusion会话上下文
        let ctx = SessionContext::new();
        
        // 注册一些示例表用于测试
        Self::register_sample_tables(&ctx).await?;
        
        Ok(Self {
            ctx: Arc::new(Mutex::new(ctx)),
        })
    }
    
    /// 注册示例表
    async fn register_sample_tables(ctx: &SessionContext) -> anyhow::Result<()> {
        info!("Registering sample tables");
        
        // 创建示例数据
        let sql = r#"
            CREATE TABLE users (
                id BIGINT,
                name VARCHAR,
                age INT,
                email VARCHAR
            ) AS VALUES 
                (1, 'Alice', 30, 'alice@example.com'),
                (2, 'Bob', 25, 'bob@example.com'),
                (3, 'Charlie', 35, 'charlie@example.com'),
                (4, 'Diana', 28, 'diana@example.com'),
                (5, 'Eve', 32, 'eve@example.com')
        "#;
        
        ctx.sql(sql).await?.collect().await?;
        info!("Sample table 'users' registered successfully");
        
        Ok(())
    }
    
    /// 执行SQL查询并返回Arrow Flight数据流
    async fn execute_sql_query(&self, sql: &str) -> Result<FlightDataStream, Status> {
        info!("Executing SQL query: {}", sql);
        
        let ctx = self.ctx.lock().await;
        
        // 执行SQL查询
        let df = ctx.sql(sql).await.map_err(|e| {
            error!("Failed to parse SQL: {}", e);
            Status::invalid_argument(format!("Invalid SQL: {}", e))
        })?;
        
        // 收集结果
        let batches = df.collect().await.map_err(|e| {
            error!("Failed to execute query: {}", e);
            Status::internal(format!("Query execution failed: {}", e))
        })?;
        
        info!("Query executed successfully, {} batches returned", batches.len());
        
        // 转换为Flight数据流
        let (tx, rx) = tokio::sync::mpsc::channel(32);
        
        tokio::spawn(async move {
            for batch in batches {
                // 将RecordBatch转换为FlightData
                let (flight_data_vec, schema_flight_data) = 
                    arrow_flight::utils::flight_data_from_arrow_batch(&batch, &Default::default());
                
                // 发送schema（如果是第一个batch）
                if tx.send(Ok(schema_flight_data)).await.is_err() {
                    break;
                }
                
                // 发送数据
                for flight_data in flight_data_vec {
                    if tx.send(Ok(flight_data)).await.is_err() {
                        break;
                    }
                }
            }
        });
        
        Ok(Box::pin(ReceiverStream::new(rx)))
    }
}

#[tonic::async_trait]
impl FlightService for DataFusionFlightService {
    type HandshakeStream = HandshakeStream;
    type ListFlightsStream = ListFlightsStream;
    type DoGetStream = FlightDataStream;
    type DoPutStream = DoPutStream;
    type DoActionStream = DoActionStream;
    type ListActionsStream = ListActionsStream;
    type DoExchangeStream = FlightDataStream;

    async fn handshake(
        &self,
        _request: Request<Streaming<HandshakeRequest>>,
    ) -> Result<Response<Self::HandshakeStream>, Status> {
        info!("Handshake requested");
        Err(Status::unimplemented("Handshake not implemented"))
    }

    async fn list_flights(
        &self,
        _request: Request<Criteria>,
    ) -> Result<Response<Self::ListFlightsStream>, Status> {
        info!("📋 List flights requested");
        
        // 返回空的flights列表
        let (tx, rx) = tokio::sync::mpsc::channel(1);
        drop(tx); // 立即关闭，表示没有flights
        
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }

    async fn get_flight_info(
        &self,
        request: Request<FlightDescriptor>,
    ) -> Result<Response<FlightInfo>, Status> {
        let descriptor = request.into_inner();
        info!("ℹ️ Flight info requested for: {:?}", descriptor);
        
        // 创建一个基本的FlightInfo响应
        let flight_info = FlightInfo {
            schema: vec![].into(), // 将在实际查询时填充
            flight_descriptor: Some(descriptor),
            endpoint: vec![],
            total_records: -1, // 未知
            total_bytes: -1,   // 未知
            ordered: false,
            app_metadata: vec![].into(),
        };
        
        Ok(Response::new(flight_info))
    }

    async fn poll_flight_info(
        &self,
        _request: Request<FlightDescriptor>,
    ) -> Result<Response<PollInfo>, Status> {
        Err(Status::unimplemented("poll_flight_info not implemented"))
    }

    async fn get_schema(
        &self,
        request: Request<FlightDescriptor>,
    ) -> Result<Response<SchemaResult>, Status> {
        let descriptor = request.into_inner();
        info!("📊 Schema requested for: {:?}", descriptor);
        
        // 从descriptor中提取SQL查询
        let sql = match descriptor.r#type() {
            arrow_flight::flight_descriptor::DescriptorType::Cmd => {
                String::from_utf8_lossy(&descriptor.cmd).to_string()
            }
            _ => {
                return Err(Status::invalid_argument("Invalid descriptor type for schema request"));
            }
        };
        
        let ctx = self.ctx.lock().await;
        
        // 执行查询以获取schema
        let df = ctx.sql(&sql).await.map_err(|e| {
            error!("Failed to parse SQL for schema: {}", e);
            Status::invalid_argument(format!("Invalid SQL: {}", e))
        })?;
        
        let schema = df.schema();
        let arrow_schema: &Schema = schema.as_ref();
        
        // 序列化schema
        let mut schema_bytes = Vec::new();
        {
            let mut writer = arrow::ipc::writer::FileWriter::try_new(&mut schema_bytes, &arrow_schema)
                .map_err(|e| Status::internal(format!("Failed to create schema writer: {}", e)))?;
            writer.finish()
                .map_err(|e| Status::internal(format!("Failed to finish schema writer: {}", e)))?;
        }
        
        let schema_result = SchemaResult {
            schema: schema_bytes.into(),
        };
        
        info!("Schema retrieved successfully");
        Ok(Response::new(schema_result))
    }

    async fn do_get(
        &self,
        request: Request<Ticket>,
    ) -> Result<Response<Self::DoGetStream>, Status> {
        let ticket = request.into_inner();
        let sql = String::from_utf8_lossy(&ticket.ticket);
        info!("🔍 SQL Query requested: {}", sql);
        
        // 执行SQL查询
        let stream = self.execute_sql_query(&sql).await?;
        
        Ok(Response::new(stream))
    }

    async fn do_put(
        &self,
        _request: Request<Streaming<FlightData>>,
    ) -> Result<Response<Self::DoPutStream>, Status> {
        warn!("do_put requested but not implemented");
        let (tx, rx) = tokio::sync::mpsc::channel(1);
        drop(tx); // 立即关闭
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }

    async fn do_action(
        &self,
        request: Request<Action>,
    ) -> Result<Response<Self::DoActionStream>, Status> {
        let action = request.into_inner();
        info!("🎬 Action requested: {}", action.r#type);
        
        match action.r#type.as_str() {
            "health_check" => {
                info!("Health check action");
                let (tx, rx) = tokio::sync::mpsc::channel(1);
                
                let result = arrow_flight::Result {
                    body: b"healthy".to_vec().into(),
                };
                
                tokio::spawn(async move {
                    let _ = tx.send(Ok(result)).await;
                });
                
                Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
            }
            _ => {
                warn!("Unknown action type: {}", action.r#type);
                Err(Status::unimplemented(format!("Action '{}' not implemented", action.r#type)))
            }
        }
    }

    async fn list_actions(
        &self,
        _request: Request<Empty>,
    ) -> Result<Response<Self::ListActionsStream>, Status> {
        info!("📋 List actions requested");
        
        let (tx, rx) = tokio::sync::mpsc::channel(2);
        
        tokio::spawn(async move {
            // 返回支持的actions
            let health_action = ActionType {
                r#type: "health_check".to_string(),
                description: "Check service health".to_string(),
            };
            
            let _ = tx.send(Ok(health_action)).await;
        });
        
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }

    async fn do_exchange(
        &self,
        _request: Request<Streaming<FlightData>>,
    ) -> Result<Response<Self::DoExchangeStream>, Status> {
        warn!("do_exchange requested but not implemented");
        Err(Status::unimplemented("do_exchange not implemented"))
    }
}