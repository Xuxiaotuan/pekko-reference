import apiClient from '../../shared/api/client'

export interface Schedule { id: string; workflowId: string; enabled: boolean; definition: string }
export const scheduleAPI = {
  list: async (): Promise<{ schedules: Schedule[]; count: number }> =>
    (await apiClient.get<{ schedules: Schedule[]; count: number }>('/api/v1/schedules')).data,
  change: async (id: string, action: 'pause' | 'resume'): Promise<{ scheduleId: string }> =>
    (await apiClient.post<{ scheduleId: string }>(`/api/v1/schedules/${encodeURIComponent(id)}/${action}`)).data,
}
