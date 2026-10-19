import { useEffect, useState } from 'react'
import { Alert, Button, Space, Table, message } from 'antd'
import { scheduleAPI, type Schedule } from '../api/services'
import { errorMessage } from '../api/client'

export default function SchedulePage() {
  const [schedules, setSchedules] = useState<Schedule[] | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let active = true
    setBusy(true); setError('')
    scheduleAPI.list().then(data => { if (active) setSchedules(data.schedules) }).catch(reason => { if (active) setError(errorMessage(reason)) }).finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [refresh])
  const change = async (id: string, action: 'pause' | 'resume') => {
    setBusy(true)
    try { await scheduleAPI.change(id, action); message.success(action === 'pause' ? '计划已暂停；当前执行不受此操作终止' : '计划已恢复'); setRefresh(n => n + 1) }
    catch (reason) { setError(errorMessage(reason)); setBusy(false) }
  }
  return <Space direction="vertical" style={{ width: '100%' }}>
    <h1>调度计划</h1>
    <Alert type="info" message="仅管理已有计划。暂停调度不等于停止当前工作流；CDC 不应创建定时计划。" description="本页不新建或编辑计划。独立调度 API 尚未核验工作流类型，此处不宣称后端已全面禁止 CDC 调度。" />
    <Button disabled={busy} onClick={() => setRefresh(n => n + 1)}>刷新计划</Button>
    {error && <Alert type="error" message={error} description={schedules ? '列表为上次成功结果。' : '尚未取得调度列表。'} />}
    <Table scroll={{ x: 640 }} rowKey="id" loading={busy} dataSource={schedules ?? []} pagination={false} locale={{ emptyText: error ? '调度不可用' : '没有调度计划' }} columns={[
      { title: '计划 ID', dataIndex: 'id' }, { title: '工作流 ID', dataIndex: 'workflowId' }, { title: '定义', dataIndex: 'definition' },
      { title: '状态', dataIndex: 'enabled', render: (enabled: boolean) => enabled ? '启用' : '暂停' },
      { title: '操作', render: (_, item) => <Button disabled={busy} onClick={() => change(item.id, item.enabled ? 'pause' : 'resume')}>{item.enabled ? '暂停' : '恢复'}</Button> },
    ]} />
  </Space>
}
