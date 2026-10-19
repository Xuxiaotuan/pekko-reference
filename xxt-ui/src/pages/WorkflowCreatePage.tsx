import { useState } from 'react'
import { Alert, Button, Input, Space } from 'antd'
import { Link } from 'react-router-dom'
import { workflowAPI } from '../api/workflow'
import { errorMessage } from '../api/client'
import type { DefinitionCreated } from '../types/workflow'

export default function WorkflowCreatePage() {
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [created, setCreated] = useState<DefinitionCreated | null>(null)
  const submit = async () => {
    setError(''); setCreated(null); setBusy(true)
    try {
      const definition: unknown = JSON.parse(draft)
      setCreated(await workflowAPI.create(definition))
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <h1>导入完整 Workflow DSL</h1>
    <Alert type="info" message="提交完整 JSON 定义，由现有后端校验。保存定义不等于连接验证通过或执行已启动。" />
    <Alert type="warning" message="只支持线性 Source→Transform*→Sink；CDC 必须 mysql.cdc→mysql.cdc.apply 直连、使用 passwordEnv、禁用调度。" description="完整组模式的 generation、sourceLineage 和可信初始化前提必须由定义明确提供；此页面不会生成这些身份、初始化目标或补齐恢复位点。metadata.schedule 不是独立调度 API 的原子注册。" />
    <Input.TextArea aria-label="完整 DSL JSON" value={draft} onChange={event => { setDraft(event.target.value); setCreated(null) }} rows={22} placeholder="粘贴包含 id、name、nodes、edges、metadata 等字段的完整 Workflow JSON" disabled={busy} style={{ fontFamily: 'monospace' }} />
    <Button type="primary" loading={busy} disabled={!draft.trim()} onClick={submit}>保存完整定义</Button>
    {error && <Alert type="error" message={error} />}
    {created && <Alert type="success" message={`定义已保存：${created.workflowId}，revision=${created.revision}`} description={<Link to={`/workflows/${encodeURIComponent(created.workflowId)}`}>查看摘要并显式请求执行</Link>} />}
    <Link to="/workflows">返回列表</Link>
  </Space>
}
