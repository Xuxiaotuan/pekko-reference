import { useEffect, useState } from 'react'
import { Alert, Button, Card, Space } from 'antd'
import { monitoringAPI } from '../../../shared/api/health'
import { errorMessage } from '../../../shared/api/client'

export default function Dashboard() {
  const [live, setLive] = useState<string | null>(null)
  const [ready, setReady] = useState<string | null>(null)
  const [errors, setErrors] = useState<string[]>([])
  const [refresh, setRefresh] = useState(0)
  const [loading, setLoading] = useState(false)
  useEffect(() => {
    let active = true
    setLoading(true)
    Promise.allSettled([monitoringAPI.live(), monitoringAPI.ready()]).then(results => {
      if (!active) return
      setLive(results[0].status === 'fulfilled' ? results[0].value : null)
      setReady(results[1].status === 'fulfilled' ? results[1].value : null)
      setErrors(results.flatMap((r, i) => r.status === 'rejected' ? [`${i === 0 ? '存活' : '就绪'}：${errorMessage(r.reason)}`] : []))
      setLoading(false)
    })
    return () => { active = false }
  }, [refresh])
  return <Space direction="vertical" style={{ width: '100%' }}>
    <h1>服务状态</h1>
    <Alert type="info" message="存活不等于就绪；就绪检查成员状态、Sharding 和 JDBC。此页不提供未采集的 CDC 吞吐、全局任务数或成功率。" />
    <Button loading={loading} onClick={() => setRefresh(n => n + 1)}>刷新状态</Button>
    {errors.map(error => <Alert key={error} type="error" message={error} />)}
    <Card title={`存活检查：${live === null ? '未知 / 不可用' : 'HTTP 200'}`}><pre>{live ?? '未取得成功响应'}</pre></Card>
    <Card title={`就绪检查：${ready === null ? '未知 / 不可用' : 'HTTP 200'}`}><pre>{ready ?? '未取得成功响应'}</pre></Card>
  </Space>
}
