import apiClient from '../../shared/api/client'
import type { WorkflowPage, WorkflowSummary, DefinitionCreated, ExecutionAccepted, ExecutionHistoryData } from './types'
const base = '/api/v1/workflows'
export const workflowAPI = {
  list: async (page: number, pageSize: number, signal?: AbortSignal): Promise<WorkflowPage> =>
    (await apiClient.get<WorkflowPage>(base, { params: { page, pageSize }, signal })).data,
  summary: async (id: string): Promise<WorkflowSummary> =>
    (await apiClient.get<WorkflowSummary>(`${base}/${encodeURIComponent(id)}`)).data,
  create: async (definition: unknown): Promise<DefinitionCreated> =>
    (await apiClient.post<DefinitionCreated>(base, definition)).data,
  execute: async (id: string, requestId: string): Promise<ExecutionAccepted> =>
    (await apiClient.post<ExecutionAccepted>(`${base}/${encodeURIComponent(id)}/execute`, undefined, { params: { requestId } })).data,
  history: async (id: string): Promise<ExecutionHistoryData> =>
    (await apiClient.get<ExecutionHistoryData>(`/api/history/${encodeURIComponent(id)}`)).data,
}
