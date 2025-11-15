import apiClient from './client'
import type {
  TaskStatistics,
  TaskSubmitRequest,
  TaskSubmitResponse,
  ClusterStatus,
  SQLQueryRequest,
  SQLQueryResult,
  SystemMetrics,
} from '../types'

// 任务相关API
export const taskAPI = {
  // 提交任务
  submitTask: (data: TaskSubmitRequest) =>
    apiClient.post<TaskSubmitResponse>('/v1/tasks', data),

  // 获取任务统计
  getStatistics: () =>
    apiClient.get<TaskStatistics>('/v1/tasks/statistics'),

  // 获取任务状态
  getTaskStatus: (taskId: string) =>
    apiClient.get(`/v1/tasks/${taskId}`),

  // 获取任务列表
  getTaskList: (params?: { status?: string; limit?: number }) =>
    apiClient.get('/v1/tasks', { params }),
}

// 集群相关API
export const clusterAPI = {
  // 获取集群状态
  getStatus: () =>
    apiClient.get<ClusterStatus>('/v1/cluster/status'),

  // 获取节点列表
  getNodes: () =>
    apiClient.get('/v1/cluster/nodes'),

  // 获取Leader信息
  getLeader: () =>
    apiClient.get('/v1/cluster/leader'),
}

// SQL查询API
export const sqlAPI = {
  // 执行SQL查询
  executeQuery: (data: SQLQueryRequest) =>
    apiClient.post<SQLQueryResult>('/v1/sql/execute', data),

  // 验证SQL语法
  validateQuery: (sql: string) =>
    apiClient.post('/v1/sql/validate', { sql }),

  // 获取查询历史
  getQueryHistory: (limit?: number) =>
    apiClient.get('/v1/sql/history', { params: { limit } }),
}

// 监控相关API
export const monitoringAPI = {
  // 获取系统指标
  getMetrics: () =>
    apiClient.get<SystemMetrics>('/monitoring/metrics'),

  // 获取健康状态
  getHealth: () =>
    apiClient.get('/health'),

  // 获取存活状态
  getLiveness: () =>
    apiClient.get('/health/live'),

  // 获取就绪状态
  getReadiness: () =>
    apiClient.get('/health/ready'),
}
