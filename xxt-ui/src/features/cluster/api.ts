import apiClient from '../../shared/api/client'

export interface ClusterStats {
  clusterName: string; nodeCount: number; leader: string | null; selfAddress: string; selfRoles: string[]
  members: { address: string; status: string; roles: string[] }[]
  sharding: { totalShards: number | null; activeShards: number | null; totalEntities: number | null; availability: 'unavailable'; reason: string }
}
export const clusterAPI = {
  stats: async (): Promise<ClusterStats> => (await apiClient.get<ClusterStats>('/api/v1/cluster/stats')).data,
}
