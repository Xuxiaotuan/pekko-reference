import { useEffect, useState } from 'react'
import { Card, Descriptions, Alert, Spin, Row, Col, Statistic, Badge } from 'antd'
import {
  ClusterOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  TeamOutlined,
  CrownOutlined,
  ReloadOutlined
} from '@ant-design/icons'
import axios from 'axios'

const ClusterStatus = () => {
  const [loading, setLoading] = useState(true)
  const [clusterStatus, setClusterStatus] = useState<string>('')
  const [error, setError] = useState<string>('')

  useEffect(() => {
    loadClusterStatus()
    const interval = setInterval(loadClusterStatus, 30000)
    return () => clearInterval(interval)
  }, [])

  const loadClusterStatus = async () => {
    try {
      setLoading(true)
      const response = await axios.get('/monitoring/cluster/status')
      setClusterStatus(response.data)
      setError('')
    } catch (err: any) {
      console.error('加载集群状态失败:', err)
      setError(err.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  // 解析集群状态
  const parseClusterInfo = () => {
    if (!clusterStatus) return null
    
    const lines = clusterStatus.split('\n')
    let self = ''
    let leader = ''
    let membersCount = 0
    let unreachableCount = 0

    lines.forEach(line => {
      if (line.includes('Self:')) self = line.split('Self:')[1]?.trim() || ''
      if (line.includes('Leader:')) leader = line.split('Leader:')[1]?.trim() || ''
      if (line.includes('Members:')) membersCount = parseInt(line.split('Members:')[1]?.trim() || '0')
      if (line.includes('Unreachable:')) unreachableCount = parseInt(line.split('Unreachable:')[1]?.trim() || '0')
    })

    return { self, leader, membersCount, unreachableCount }
  }

  const info = parseClusterInfo()

  return (
    <div style={{ padding: 24 }}>
      <div style={{ marginBottom: 24, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1 style={{ margin: 0 }}>
          <ClusterOutlined /> 集群状态
        </h1>
        <ReloadOutlined 
          style={{ fontSize: 18, cursor: 'pointer' }} 
          spin={loading}
          onClick={loadClusterStatus}
        />
      </div>

      {error && (
        <Alert
          message="错误"
          description={error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          onClose={() => setError('')}
        />
      )}

      <Spin spinning={loading}>
        {/* 统计卡片 */}
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="集群状态"
                value={info && info.membersCount > 0 ? "在线" : "离线"}
                valueStyle={{ color: info && info.membersCount > 0 ? '#3f8600' : '#cf1322', fontSize: 20 }}
                prefix={info && info.membersCount > 0 ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="成员节点"
                value={info?.membersCount || 0}
                prefix={<TeamOutlined />}
                valueStyle={{ fontSize: 24 }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="Leader节点"
                value={info?.leader && info.leader !== 'None' ? "已选举" : "未选举"}
                prefix={<CrownOutlined />}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="不可达节点"
                value={info?.unreachableCount || 0}
                valueStyle={{ 
                  color: info && info.unreachableCount > 0 ? '#cf1322' : '#3f8600',
                  fontSize: 24 
                }}
                prefix={info && info.unreachableCount > 0 ? <CloseCircleOutlined /> : <CheckCircleOutlined />}
              />
            </Card>
          </Col>
        </Row>

        {/* 详细信息 */}
        <Card 
          title="集群详情" 
          style={{ marginBottom: 16 }}
          extra={
            <Badge 
              status={info && info.membersCount > 0 ? 'success' : 'error'} 
              text={info && info.membersCount > 0 ? '正常运行' : '离线'} 
            />
          }
        >
          {info ? (
            <Descriptions column={1} bordered>
              <Descriptions.Item label="当前节点">
                <CheckCircleOutlined style={{ color: '#52c41a', marginRight: 8 }} />
                {info.self || '未知'}
              </Descriptions.Item>
              <Descriptions.Item label="Leader节点">
                <CrownOutlined style={{ marginRight: 8 }} />
                {info.leader || '未选举'}
              </Descriptions.Item>
              <Descriptions.Item label="成员数量">
                <TeamOutlined style={{ marginRight: 8 }} />
                {info.membersCount} 个节点
              </Descriptions.Item>
              <Descriptions.Item label="健康状态">
                {info.unreachableCount === 0 ? (
                  <span style={{ color: '#52c41a' }}>
                    <CheckCircleOutlined /> 所有节点正常
                  </span>
                ) : (
                  <span style={{ color: '#ff4d4f' }}>
                    <CloseCircleOutlined /> {info.unreachableCount} 个节点不可达
                  </span>
                )}
              </Descriptions.Item>
            </Descriptions>
          ) : (
            <p style={{ color: '#999' }}>暂无数据</p>
          )}
        </Card>

        {/* 原始状态 */}
        <Card title="详细状态信息">
          <pre style={{
            background: '#f5f5f5',
            padding: 16,
            borderRadius: 4,
            overflow: 'auto',
            maxHeight: 400,
            fontSize: 12,
            lineHeight: 1.6
          }}>
            {clusterStatus || '暂无数据'}
          </pre>
        </Card>
      </Spin>

      <div style={{ marginTop: 16, color: '#999', fontSize: 12 }}>
        <p>💡 提示：页面每30秒自动刷新一次，也可以点击右上角刷新图标手动刷新</p>
      </div>
    </div>
  )
}

export default ClusterStatus
