import type { CdcTaskDefinition, CdcTaskRequest } from './types'

const storageKey = 'pekko.cdc.pending-submission.v1'

export function pendingCdcSubmission(): CdcTaskRequest | null {
  const value = sessionStorage.getItem(storageKey)
  if (value === null) return null
  try {
    const parsed: unknown = JSON.parse(value)
    if (typeof parsed !== 'object' || parsed === null) throw new Error('invalid pending request')
    const fields = parsed as Record<string, unknown>
    const keys = ['requestId', 'captureId', 'table', 'generation', 'targetTable'] as const
    if (!keys.every(key => typeof fields[key] === 'string' && (fields[key] as string).length > 0)) throw new Error('invalid pending request')
    return { requestId: String(fields.requestId), captureId: String(fields.captureId), table: String(fields.table), generation: String(fields.generation), targetTable: String(fields.targetTable) }
  } catch {
    // Do not silently replace an uncertain request with a new identity.
    throw new Error('保存的 CDC 受理上下文无法读取；请先核对服务端任务，勿重复新建')
  }
}

export function reserveCdcSubmission(definition: CdcTaskDefinition): CdcTaskRequest {
  const existing = pendingCdcSubmission()
  if (existing) {
    if (existing.captureId !== definition.captureId || existing.table !== definition.table ||
        existing.generation !== definition.generation || existing.targetTable !== definition.targetTable) {
      throw new Error('上一请求的受理结果尚未确认，请先重试原请求')
    }
    return existing
  }
  const request = { requestId: crypto.randomUUID(), ...definition }
  sessionStorage.setItem(storageKey, JSON.stringify(request))
  return request
}

export function confirmCdcSubmission(requestId: string): void {
  if (pendingCdcSubmission()?.requestId === requestId) sessionStorage.removeItem(storageKey)
}
