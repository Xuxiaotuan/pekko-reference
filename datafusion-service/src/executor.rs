use datafusion::prelude::*;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::time::timeout;
use tracing::{error, info, warn};

use crate::config::ServiceConfig;

/// SQL查询执行器
pub struct QueryExecutor {
    /// DataFusion会话上下文
    ctx: Arc<SessionContext>,

    /// 服务配置
    config: Arc<ServiceConfig>,
}

impl QueryExecutor {
    /// 创建新的查询执行器
    pub fn new(ctx: Arc<SessionContext>, config: Arc<ServiceConfig>) -> Self {
        Self { ctx, config }
    }

    /// 获取查询的Schema（不执行查询）
    pub async fn get_query_schema(&self, sql: &str) -> anyhow::Result<arrow::datatypes::SchemaRef> {
        info!("Getting schema for SQL query: {}", sql);

        let df = self.ctx.sql(sql).await?;
        let schema = df.schema().inner().clone();

        info!("Schema retrieved successfully");

        Ok(schema)
    }

    /// 执行SQL查询（带超时控制和指标记录）
    pub async fn execute_query(
        &self,
        sql: &str,
    ) -> anyhow::Result<Vec<arrow::record_batch::RecordBatch>> {
        let start_time = Instant::now();
        let query_id = generate_query_id();

        info!(
            query_id = %query_id,
            sql = %sql.chars().take(100).collect::<String>(),
            "Starting SQL query execution"
        );

        // 配置超时时间
        let timeout_duration = Duration::from_secs(self.config.query.timeout_seconds);

        // 执行查询（带超时）
        let result = timeout(timeout_duration, async {
            self.execute_query_internal(sql).await
        })
        .await;

        let duration = start_time.elapsed();

        match result {
            Ok(Ok(batches)) => {
                let total_rows: usize = batches.iter().map(|b| b.num_rows()).sum();
                let total_bytes: usize = batches.iter().map(|b| b.get_array_memory_size()).sum();

                info!(
                    query_id = %query_id,
                    duration_ms = duration.as_millis(),
                    batches = batches.len(),
                    rows = total_rows,
                    bytes = total_bytes,
                    "Query executed successfully"
                );

                Ok(batches)
            }
            Ok(Err(e)) => {
                error!(
                    query_id = %query_id,
                    duration_ms = duration.as_millis(),
                    error = %e,
                    "Query execution failed"
                );
                Err(e)
            }
            Err(_) => {
                warn!(
                    query_id = %query_id,
                    timeout_seconds = self.config.query.timeout_seconds,
                    "Query execution timeout"
                );
                anyhow::bail!(
                    "Query execution timeout after {} seconds",
                    self.config.query.timeout_seconds
                )
            }
        }
    }

    /// 内部查询执行方法
    async fn execute_query_internal(
        &self,
        sql: &str,
    ) -> anyhow::Result<Vec<arrow::record_batch::RecordBatch>> {
        // 解析并执行SQL
        let df = self.ctx.sql(sql).await?;

        // 收集结果
        let batches = df.collect().await?;

        Ok(batches)
    }
}

/// 生成查询ID
fn generate_query_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);

    let id = COUNTER.fetch_add(1, Ordering::SeqCst);
    format!("query-{}", id)
}
