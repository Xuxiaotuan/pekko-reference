import { useEffect, useState } from 'react'
import { Alert, Button, Descriptions, Space, Table } from 'antd'
import { Link, useParams } from 'react-router-dom'
import { workflowAPI } from '../api/workflow'
import { errorMessage } from '../api/client'
import type { WorkflowSummary } from '../types/workflow'
import ExecuteWorkflowButton from '../components/ExecuteWorkflowButton'

export default function WorkflowSummaryPage() {
  const { id } = useParams()
  const [data, setData] = useState<WorkflowSummary | null>(null)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let active = true
    setData(null); setError('')
    if (id) workflowAPI.summary(id).then(value => { if (active) setData(value) }).catch(reason => { if (active) setError(errorMessage(reason)) })
    return () => { active = false }
  }, [id, refresh])
  return <Space direction="vertical" style={{ width: '100%' }}>
    <h1>工作流摘要</h1>
    <Alert type="info" message="此接口返回摘要，不能用于读取或编辑完整定义。执行接受与执行完成是不同状态。" />
    {error && <Alert type="error" message={error} />}
    {data && <><Descriptions bordered items={[
      { key: 'id', label: '工作流 ID', children: data.workflowId },
      { key: 'revision', label: '修订', children: data.revision },
      { key: 'status', label: '当前状态', children: data.status },
    ]} /><Space><ExecuteWorkflowButton key={data.workflowId} workflowId={data.workflowId} /><Button onClick={() => setRefresh(n => n + 1)}>刷新状态</Button><Link to={`/history/${encodeURIComponent(data.workflowId)}`}>近期历史</Link></Space>
      <Table scroll={{ x: 640 }} rowKey="executionId" dataSource={data.recentExecutions} pagination={false} columns={[{ title: '近期执行 ID', dataIndex: 'executionId' }, { title: '状态', dataIndex: 'status' }]} />
    </>}
    <Link to="/workflows">返回列表</Link>
  </Space>
}
