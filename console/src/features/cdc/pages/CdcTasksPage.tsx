import { useEffect, useState } from 'react'
import axios from 'axios'
import { Alert, Button, Card, Descriptions, Drawer, Form, Input, Select, Space, Table, Typography, message } from 'antd'
import { cdcTaskAPI } from '../api'
import type { CdcCaptureView, CdcPlatformStatus, CdcTaskDefinition, CdcTaskPage, CdcTaskRequest, CdcTaskView } from '../types'
import { confirmCdcSubmission, pendingCdcSubmission, reserveCdcSubmission } from '../submissions'
import { cdcTaskForDisplay, reconcileCdcSelection } from '../projections'
import { errorMessage } from '../../../shared/api/client'

function savedSubmission(): { pending: CdcTaskRequest | null; error: string } {
  try { return { pending: pendingCdcSubmission(), error: '' } }
  catch (error) { return { pending: null, error: errorMessage(error) } }
}
const timestamp = (value: number | null) => value === null ? '尚未验证' : new Date(value).toLocaleString()
const initialization = (task: CdcTaskView) => task.initializationEvidence ? '已初始化（历史证明）' : '未完成初始化'

export default function CdcTasksPage() {
  const [saved] = useState(savedSubmission)
  const [pending, setPending] = useState(saved.pending)
  const [form] = Form.useForm<CdcTaskDefinition>()
  const [status, setStatus] = useState<CdcPlatformStatus | null>(null)
  const [captures, setCaptures] = useState<CdcCaptureView[]>([])
  const [page, setPage] = useState<CdcTaskPage | null>(null)
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined])
  const [pageIndex, setPageIndex] = useState(0)
  const [refresh, setRefresh] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(saved.error)
  const [selected, setSelected] = useState<CdcTaskView | null>(null)
  const [detailError, setDetailError] = useState('')
  const [detailBusy, setDetailBusy] = useState(false)
  const after = cursors[pageIndex]
  const selectedId = selected?.taskId
  const displayedSelection = selected ? cdcTaskForDisplay(selected, !!error || !!detailError) : null

  useEffect(() => {
    let active = true
    setBusy(true)
    Promise.all([cdcTaskAPI.status(), cdcTaskAPI.captures(), cdcTaskAPI.list(after), selectedId ? cdcTaskAPI.get(selectedId) : Promise.resolve(null)]).then(([platform, sources, result, detail]) => {
      if (active) {
        setStatus(platform); setCaptures(sources); setPage(result); setError(saved.error)
        setSelected(current => reconcileCdcSelection(current, detail ? [...result.items, detail] : result.items))
        if (detail) setDetailError('')
      }
    }).catch(reason => { if (active) setError(errorMessage(reason)) }).finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [after, refresh, saved.error, selectedId])

  const submit = async (definition: CdcTaskDefinition) => {
    setBusy(true)
    try {
      const request = reserveCdcSubmission(definition)
      setPending(request)
      const accepted = await cdcTaskAPI.submit(request)
      confirmCdcSubmission(request.requestId); setPending(null); setSelected(accepted.task); setDetailError('')
      form.resetFields(); setPageIndex(0); setCursors([undefined]); setRefresh(n => n + 1)
      message.success(accepted.created ? '任务已持久受理；初始化完成后才能持续提供目标数据' : '已找到同一请求对应的任务')
    } catch (reason) {
      if (axios.isAxiosError(reason) && reason.response?.status === 400) {
        const original = pendingCdcSubmission()
        if (original) confirmCdcSubmission(original.requestId)
        setPending(null)
      }
      setError(errorMessage(reason))
    }
    finally { setBusy(false) }
  }
  const inspect = async (id: string) => {
    setDetailBusy(true)
    try {
      const detail = await cdcTaskAPI.get(id)
      setSelected(current => current?.taskId === id ? reconcileCdcSelection(current, [detail]) : detail)
      setDetailError('')
    }
    catch (reason) { setDetailError(errorMessage(reason)); if (!selected) setError(errorMessage(reason)) }
    finally { setDetailBusy(false) }
  }
  const cancel = async (task: CdcTaskView) => {
    setBusy(true)
    try {
      const current = await cdcTaskAPI.cancel(task.taskId)
      setSelected(selectedTask => reconcileCdcSelection(selectedTask, [current]))
      message.success(current.phase === 'CANCELED' ? '取消已完成' : '取消意图已受理，正在等待内核排空')
      setRefresh(n => n + 1)
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  const next = () => {
    if (!page?.nextAfter) return
    const cursor = page.nextAfter
    setCursors(values => [...values.slice(0, pageIndex + 1), cursor]); setPageIndex(n => n + 1)
  }
  const unavailable = !status?.enabled || !!saved.error

  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Typography.Title level={2}>常驻 CDC 任务</Typography.Title>
    <Alert type="info" message="新增表先同步存量，再持续跟随；HTTP 202 只代表请求已受理。" description="首版使用独占空目标表，初始化完成前不应向业务发布。取消不会删除目标数据；本页不提供重置进度或强制恢复。" />
    {error && <Alert type="error" message={error} description={page ? '下表是上次成功查询的结果，实时健康状态暂记未知。' : '尚未取得真实任务状态；没有使用演示数据。'} />}
    <Space wrap>
      <Button loading={busy} onClick={() => setRefresh(n => n + 1)}>刷新状态</Button>
      <Typography.Text>Owner：{error ? 'UNKNOWN' : status?.ownerState ?? 'UNKNOWN'}</Typography.Text>
      <Typography.Text>活动步骤：{status ? `${status.activeSteps} / ${status.maxActiveSteps}` : '未知'}</Typography.Text>
      {status?.reason && <Typography.Text type="warning">{status.reason}</Typography.Text>}
    </Space>
    <Card title="新增同步表" size="small">
      {pending && <Alert style={{ marginBottom: 16 }} type="warning" message="上次请求受理结果尚未确认，重试将使用原定义和 requestId。" description={<Typography.Text copyable>{pending.requestId}</Typography.Text>} />}
      <Form form={form} layout="vertical" initialValues={saved.pending ?? { generation: 'g1' }} onFinish={submit} disabled={busy || !!pending || unavailable}>
        <Space align="start" wrap>
          <Form.Item name="captureId" label="已配置的捕获域" rules={[{ required: true }]}><Select style={{ width: 240 }} options={captures.map(c => ({ value: c.captureId, label: `${c.captureId} / ${c.database} (${c.readiness})` }))} /></Form.Item>
          <Form.Item name="table" label="源表" rules={[{ required: true }, { pattern: /^[A-Za-z_][A-Za-z0-9_]{0,127}$/, message: '表名以字母或下划线开头，最多 128 字符' }]}><Input /></Form.Item>
          <Form.Item name="generation" label="订阅代" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]+$/, message: '仅支持字母、数字、下划线和连字符' }]}><Input /></Form.Item>
          <Form.Item name="targetTable" label="独占空目标表" rules={[{ required: true }, { pattern: /^[A-Za-z_][A-Za-z0-9_]{0,127}$/, message: '表名以字母或下划线开头，最多 128 字符' }]}><Input /></Form.Item>
        </Space>
        {!pending && <Button htmlType="submit" type="primary" disabled={busy || unavailable}>提交同步请求</Button>}
      </Form>
      {pending && <Button type="primary" disabled={busy || unavailable} onClick={() => submit(pending)}>重试原请求</Button>}
    </Card>
    <Table<CdcTaskView> rowKey="taskId" dataSource={page?.items ?? []} loading={busy} pagination={false} scroll={{ x: 1100 }} locale={{ emptyText: error ? '任务查询不可用' : '没有任务' }} columns={[
      { title: '任务', dataIndex: 'taskId', render: (id: string) => <Button type="link" disabled={detailBusy} onClick={() => inspect(id)}>{id.slice(0, 16)}</Button> },
      { title: '源表', render: (_, task) => `${task.captureId}.${task.table}` },
      { title: '目标 / 代', render: (_, task) => `${task.targetTable} / ${task.generation}` },
      { title: '期望状态', dataIndex: 'desiredState' },
      { title: '内核阶段', dataIndex: 'phase' },
      { title: '捕获准入', dataIndex: 'captureReadiness' },
      { title: '初始化', render: (_, task) => initialization(task) },
      { title: '实时健康', render: (_, task) => error ? 'UNKNOWN' : task.runtimeHealth },
      { title: '资源释放', dataIndex: 'releaseStatus' },
      { title: '操作', render: (_, task) => <Button danger disabled={busy || !!error || !task.cancelAllowed} onClick={() => cancel(task)}>请求取消</Button> },
    ]} />
    <Space><Button disabled={busy || pageIndex === 0} onClick={() => setPageIndex(n => n - 1)}>上一页</Button><Typography.Text>第 {pageIndex + 1} 页</Typography.Text><Button disabled={busy || !page?.nextAfter || !!error} onClick={next}>下一页</Button></Space>
    <Drawer title="CDC 任务详情" open={selected !== null} onClose={() => { setSelected(null); setDetailError('') }} width={680}>
      {(error || detailError) && <Alert type="error" message={error || detailError} description="详情是上次观察值，当前健康未知；初始化与取消证明仅表示历史事实。" />}
      {displayedSelection && <>
        <Space style={{ marginBottom: 16 }}><Button loading={detailBusy} onClick={() => inspect(displayedSelection.taskId)}>刷新详情</Button><Button danger disabled={busy || detailBusy || !displayedSelection.cancelAllowed} onClick={() => cancel(displayedSelection)}>请求取消</Button></Space>
        <Descriptions bordered column={1} size="small" items={[
          { key: 'taskId', label: '任务 ID', children: <Typography.Text copyable>{displayedSelection.taskId}</Typography.Text> },
          { key: 'requestId', label: '请求 ID', children: displayedSelection.requestId },
          { key: 'table', label: '源 / 目标', children: `${displayedSelection.captureId}.${displayedSelection.table} → ${displayedSelection.targetTable}` },
          { key: 'generation', label: '订阅代 / 执行', children: `${displayedSelection.generation} / ${displayedSelection.executionId ?? '尚未开始'}` },
          { key: 'phase', label: '期望 / 内核阶段', children: `${displayedSelection.desiredState} / ${displayedSelection.phase}` },
          { key: 'revisions', label: '控制 / Journal revision', children: `${displayedSelection.controlRevision} / ${displayedSelection.journalRevision}` },
          { key: 'capture', label: '捕获准入', children: displayedSelection.captureReadiness },
          { key: 'initialized', label: '初始化', children: initialization(displayedSelection) },
          { key: 'health', label: '实时健康', children: displayedSelection.runtimeHealth },
          { key: 'release', label: '资源释放', children: displayedSelection.releaseStatus },
          { key: 'failure', label: '暂停 / 故障原因', children: displayedSelection.failureCode ?? '无' },
          { key: 'observedAt', label: '观察时间', children: timestamp(displayedSelection.observedAt) },
          { key: 'lastVerifiedAt', label: '最后验证时间', children: timestamp(displayedSelection.lastVerifiedAt) },
          { key: 'proof', label: '初始化证明引用', children: displayedSelection.initializationEvidence ?? '无' },
          { key: 'cancelProof', label: '取消证明引用', children: displayedSelection.cancellationEvidence ?? '无' },
          { key: 'fingerprint', label: '定义摘要', children: displayedSelection.definitionFingerprint },
        ]} />
      </>}
    </Drawer>
  </Space>
}
