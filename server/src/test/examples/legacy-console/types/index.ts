// 任务相关类型
export interface Task {
  id: string
  taskType: string
  status: TaskStatus
  priority: number
  createdAt: string
  updatedAt?: string
  completedAt?: string
}

export type TaskStatus = 'pending' | 'running' | 'completed' | 'failed'

export interface TaskStatistics {
  totalTasks: number
  pendingTasks: number
  runningTasks: number
  completedTasks: number
  failedTasks: number
  averageExecutionTime: number
  successRate: number
}

export interface TaskSubmitRequest {
  taskType: string
  sql?: string
  params?: Record<string, any>
}

export interface TaskSubmitResponse {
  taskId: string
  success: boolean
  message: string
}

// 集群相关类型
export interface ClusterNode {
  address: string
  role: string
  status: NodeStatus
  uptime: number
  cpuUsage?: number
  memoryUsage?: number
}

export type NodeStatus = 'up' | 'down' | 'joining' | 'leaving'

export interface ClusterStatus {
  leader: string
  members: ClusterNode[]
  totalNodes: number
  healthyNodes: number
}

// SQL查询相关类型
export interface SQLQueryRequest {
  sql: string
  limit?: number
}

export interface SQLQueryResult {
  columns: string[]
  rows: any[][]
  executionTime: number
  rowCount: number
}

// 监控指标类型
export interface SystemMetrics {
  timestamp: string
  cpuUsage: number
  memoryUsage: number
  diskUsage: number
  networkIO: {
    rx: number
    tx: number
  }
}
