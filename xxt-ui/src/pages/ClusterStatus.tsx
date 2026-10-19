import { useEffect, useState } from 'react'
import { Alert, Button, Descriptions, Space, Table } from 'antd'
import { clusterAPI, type ClusterStats } from '../api/services'
import { errorMessage } from '../api/client'

export default function ClusterStatus() {
  const [data, setData] = useState<ClusterStats | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    clusterAPI.stats().then(value => { if (active) setData(value) }).catch(reason => { if (active) setError(errorMessage(reason)) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [refresh])
  return <Space direction="vertical" style={{ width: '100%' }}>
    <h1>集群状态</h1>
    <Button loading={loading} onClick={() => setRefresh(n => n + 1)}>刷新集群</Button>
    {error && <Alert type="error" message={error} description={data ? '显示上次成功观测，不代表本次采集成功。' : '尚未取得集群观测。'} />}
    {data && <><Descriptions bordered items={[
      { key: 'name', label: '集群', children: data.clusterName },
      { key: 'nodes', label: '当前观测成员数', children: data.nodeCount },
      { key: 'leader', label: 'Leader', children: data.leader ?? '未选出' },
      { key: 'self', label: '当前节点', children: data.selfAddress },
    ]} />
      <Alert type="warning" message="分片数量、活跃实体数和实际归属尚未采集" description={data.sharding.reason} />
      <Table scroll={{ x: 640 }} rowKey="address" pagination={false} dataSource={data.members} columns={[
        { title: '成员地址', dataIndex: 'address' }, { title: '成员状态', dataIndex: 'status' },
        { title: '角色', dataIndex: 'roles', render: (roles: string[]) => roles.join(', ') },
      ]} />
    </>}
  </Space>
}
