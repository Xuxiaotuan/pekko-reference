import { useEffect, useState } from 'react'
import { Alert, Button, Empty, Select, Space, Table, Tag } from 'antd'
import { Link } from 'react-router-dom'
import { workflowAPI } from '../api/workflow'
import { errorMessage } from '../api/client'
import type { WorkflowPage } from '../types/workflow'
import ExecuteWorkflowButton from '../components/ExecuteWorkflowButton'

export default function WorkflowListPage() {
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const [refresh, setRefresh] = useState(0)
  const [data, setData] = useState<WorkflowPage | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    workflowAPI.list(page, pageSize, controller.signal).then(value => {
      if (!controller.signal.aborted) setData(value)
    }).catch(reason => {
      if (!controller.signal.aborted) setError(errorMessage(reason))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, pageSize, refresh])
  const current = data?.page === page && data.pageSize === pageSize
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <h1>CDC / 工作流</h1>
    <Alert type="info" message="分页摘要：不提供全局总数、全局排序或稳定快照。深页查询仍会枚举 Journal ID 并查询 Actor。" />
    <Space><Link to="/workflows/new/import"><Button type="primary">导入完整 DSL</Button></Link>
      <Button onClick={() => { setPage(0); setRefresh(n => n + 1) }}>刷新到第一页</Button>
      <span>每页</span><Select aria-label="每页条数" value={pageSize} options={[2, 20, 50, 100].map(value => ({ value, label: value }))} onChange={size => { setPageSize(size); setPage(0) }} />
    </Space>
    {error && <Alert type="error" message={error} description={data ? '下方为上次成功结果，不代表本次请求成功。' : '尚未取得列表，不等于没有工作流。'} />}
    <Table scroll={{ x: 640 }} rowKey="workflowId" loading={loading} dataSource={data?.items ?? []} pagination={false}
      locale={{ emptyText: error ? '列表不可用' : <Empty description="当前页没有工作流" /> }}
      columns={[
        { title: '工作流 ID', dataIndex: 'workflowId', render: (id: string) => <Link to={`/workflows/${encodeURIComponent(id)}`}>{id}</Link> },
        { title: '修订', dataIndex: 'revision' },
        { title: '状态', dataIndex: 'status', render: (status: string) => <Tag>{status}</Tag> },
        { title: '操作', render: (_, item) => <Space><ExecuteWorkflowButton workflowId={item.workflowId} /><Link to={`/history/${encodeURIComponent(item.workflowId)}`}>近期历史</Link></Space> },
      ]} />
    <Space><Button disabled={loading || !!error || page === 0} onClick={() => setPage(n => n - 1)}>上一页</Button>
      <span>第 {(data?.page ?? page) + 1} 页{!current && data ? '（上次结果）' : ''}</span>
      <Button disabled={loading || !!error || !current || !data || data.items.length < data.pageSize} onClick={() => setPage(n => n + 1)}>下一页</Button>
    </Space>
    <span>不显示推算总页数；满页后的下一页可能为空。新增任务或状态变化期间，跨页结果不具有快照一致性。</span>
  </Space>
}
