import { useEffect, useState } from 'react'
import { Alert, Button, Descriptions, Space, Table, Tag } from 'antd'
import { Link, useParams } from 'react-router-dom'
import { workflowAPI } from '../api'
import { errorMessage } from '../../../shared/api/client'
import type { ExecutionHistoryData, ExecutionDetail } from '../types'

const time = (value?: number | null) => value == null ? '未记录' : new Date(value).toLocaleString('zh-CN')
const duration = (value?: number | null) => value == null ? '未记录' : `${value} ms`
export default function ExecutionHistory() {
  const { workflowId } = useParams()
  const [data, setData] = useState<ExecutionHistoryData | null>(null)
  const [selected, setSelected] = useState<ExecutionDetail | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true); setError(''); setData(null); setSelected(null)
    if (workflowId) workflowAPI.history(workflowId).then(value => {
      if (active) { setData(value); setSelected(value.executions[0] ?? null) }
    }).catch(reason => { if (active) setError(errorMessage(reason)) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [workflowId, refresh])
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <h1>近期执行记录：{workflowId}</h1>
    <Alert type="info" message="当前接口最多返回最近 50 条已记录执行，不是完整 Journal 审计历史。节点时间和处理量未采集时显示“未记录”。" />
    <Space><Button onClick={() => setRefresh(n => n + 1)}>刷新历史</Button><Link to={`/workflows/${encodeURIComponent(workflowId ?? '')}`}>查看当前摘要</Link></Space>
    {error && <Alert type="error" message={error} />}
    <Table scroll={{ x: 640 }} loading={loading} rowKey="executionId" dataSource={data?.executions ?? []} pagination={false} locale={{ emptyText: error ? '历史不可用' : '没有近期执行记录' }} columns={[
      { title: '执行 ID', dataIndex: 'executionId', render: (id: string, item) => <a onClick={() => setSelected(item)}>{id}</a> },
      { title: '状态', dataIndex: 'status', render: (status: string) => <Tag>{status}</Tag> },
      { title: '开始时间', dataIndex: 'startTime', render: time },
      { title: '结束时间', dataIndex: 'endTime', render: time },
      { title: '耗时', dataIndex: 'duration', render: duration },
    ]} />
    {selected && <><Descriptions title="执行详情" items={[{ key: 'name', label: '工作流名称', children: selected.workflowName }, { key: 'id', label: '执行 ID', children: selected.executionId }]} />
      <Table scroll={{ x: 640 }} rowKey="nodeId" pagination={false} dataSource={selected.nodes} columns={[
        { title: '节点', dataIndex: 'nodeId' }, { title: '类型', dataIndex: 'nodeType' }, { title: '状态', dataIndex: 'status' },
        { title: '开始时间', dataIndex: 'startTime', render: time }, { title: '结束时间', dataIndex: 'endTime', render: time },
        { title: '耗时', dataIndex: 'duration', render: duration },
        { title: '处理量', dataIndex: 'recordsProcessed', render: (n?: number | null) => n ?? '未记录' },
        { title: '错误 / 消息', dataIndex: 'error', render: (s?: string | null) => s ?? '未记录' },
      ]} />
    </>}
  </Space>
}
