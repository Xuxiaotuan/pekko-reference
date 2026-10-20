import apiClient from './client'

export const monitoringAPI = {
  live: async (): Promise<string> => (await apiClient.get<string>('/health/live')).data,
  ready: async (): Promise<string> => (await apiClient.get<string>('/health/ready')).data,
}
