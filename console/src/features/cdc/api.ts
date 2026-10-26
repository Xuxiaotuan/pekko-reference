import apiClient from '../../shared/api/client'
import type { CdcCaptureView, CdcPlatformStatus, CdcTaskAcceptance, CdcTaskPage, CdcTaskRequest, CdcTaskView } from './types'

export const cdcTaskAPI = {
  async status(): Promise<CdcPlatformStatus> {
    return (await apiClient.get<CdcPlatformStatus>('/api/v1/cdc-status')).data
  },
  async captures(): Promise<CdcCaptureView[]> {
    return (await apiClient.get<{ items: CdcCaptureView[] }>('/api/v1/cdc-captures')).data.items
  },
  async submit(request: CdcTaskRequest): Promise<CdcTaskAcceptance> {
    return (await apiClient.post<CdcTaskAcceptance>('/api/v1/cdc-tasks', request)).data
  },
  async list(after?: string, pageSize = 50): Promise<CdcTaskPage> {
    return (await apiClient.get<CdcTaskPage>('/api/v1/cdc-tasks', { params: { after, pageSize } })).data
  },
  async get(taskId: string): Promise<CdcTaskView> {
    return (await apiClient.get<CdcTaskView>(`/api/v1/cdc-tasks/${encodeURIComponent(taskId)}`)).data
  },
  async cancel(taskId: string): Promise<CdcTaskView> {
    return (await apiClient.post<CdcTaskView>(`/api/v1/cdc-tasks/${encodeURIComponent(taskId)}/cancel`)).data
  },
}
