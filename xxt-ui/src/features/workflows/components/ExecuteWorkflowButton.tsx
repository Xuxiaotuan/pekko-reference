import { useState } from 'react'
import { Button, message } from 'antd'
import axios from 'axios'
import { workflowAPI } from '../api'
import { errorMessage } from '../../../shared/api/client'
import { pendingExecution, reserveExecution, confirmExecution } from '../executionRequests'

/** Retain an uncertain request across status refresh/navigation in this tab; never auto-retry. */
export default function ExecuteWorkflowButton({ workflowId }: { workflowId: string }) {
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(() => pendingExecution(workflowId) !== null)
  const execute = async () => {
    if (busy) return
    setBusy(true)
    let id: string | undefined
    try {
      id = reserveExecution(workflowId)
      const accepted = await workflowAPI.execute(workflowId, id)
      message.info(`已接受执行：${accepted.executionId}；请查询状态或近期历史`)
      if (id) confirmExecution(workflowId, id)
      setUncertain(false)
    } catch (error) {
      const response = axios.isAxiosError(error) ? error.response : undefined
      const data: unknown = response?.data
      const reason = typeof data === 'object' && data !== null && 'error' in data ? data.error : undefined
      if (response?.status === 409 && (reason === 'duplicate execution' || reason === 'workflow already running')) {
        message.info(reason === 'duplicate execution' ? '本次请求已被接受，请查询状态或近期历史' : reason === 'workflow already running' ? '已有执行正在运行，请查询状态' : errorMessage(error))
        if (id) confirmExecution(workflowId, id)
        setUncertain(false)
      } else {
        const definitive = response?.status === 400 || response?.status === 404
        if (definitive && id) confirmExecution(workflowId, id)
        setUncertain(!definitive)
        message.error(`${definitive ? '' : '执行请求结果未知；'}${errorMessage(error)}`)
      }
    } finally { setBusy(false) }
  }
  return <Button loading={busy} onClick={execute}>{uncertain ? '沿用 requestId 重试' : '请求执行'}</Button>
}
