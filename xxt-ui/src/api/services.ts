import apiClient from './client'

export interface ClusterStats {
  clusterName: string; nodeCount: number; leader: string | null; selfAddress: string; selfRoles: string[]
  members: { address: string; status: string; roles: string[] }[]
  sharding: { totalShards: number | null; activeShards: number | null; totalEntities: number | null; availability: 'unavailable'; reason: string }
}
export interface Schedule { id: string; workflowId: string; enabled: boolean; definition: string }
export const clusterAPI = {
  stats: async (): Promise<ClusterStats> => (await apiClient.get<ClusterStats>('/api/v1/cluster/stats')).data,
}
export const monitoringAPI = {
  live: async (): Promise<string> => (await apiClient.get<string>('/health/live')).data,
  ready: async (): Promise<string> => (await apiClient.get<string>('/health/ready')).data,
}
export const scheduleAPI = {
  list: async (): Promise<{ schedules: Schedule[]; count: number }> =>
    (await apiClient.get<{ schedules: Schedule[]; count: number }>('/api/v1/schedules')).data,
  change: async (id: string, action: 'pause' | 'resume'): Promise<{ scheduleId: string }> =>
    (await apiClient.post<{ scheduleId: string }>(`/api/v1/schedules/${encodeURIComponent(id)}/${action}`)).data,
}
