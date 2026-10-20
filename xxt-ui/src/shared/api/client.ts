import axios from 'axios'

// Keep AxiosResponse intact. API adapters unwrap data; pages receive domain DTOs.
const apiClient = axios.create({ baseURL: '/', timeout: 30000, headers: { 'Content-Type': 'application/json' } })
export default apiClient

export function errorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) return error instanceof Error ? error.message : '未知错误'
  const status = error.response?.status
  const data: unknown = error.response?.data
  if (typeof data === 'object' && data !== null) {
    if ('errors' in data && Array.isArray(data.errors)) return data.errors.join('；')
    if ('error' in data) {
      if (typeof data.error === 'string') return data.error
      if (typeof data.error === 'object' && data.error !== null && 'message' in data.error && typeof data.error.message === 'string') return data.error.message
    }
  }
  if (!error.response) return '请求结果未知，请重试或查询状态；执行重试将沿用原 requestId'
  return `请求失败（HTTP ${status}）`
}
