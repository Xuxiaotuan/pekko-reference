import { useEffect, useState } from 'react'
import { Card, Row, Col, Statistic, Tag, Spin } from 'antd'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import { taskAPI, clusterAPI } from '../api/services'
import type { TaskStatistics, ClusterStatus } from '../types'

const Dashboard = () => {
  const [loading, setLoading] = useState(true)
  const [taskStats, setTaskStats] = useState<TaskStatistics | null>(null)
  const [clusterStatus, setClusterStatus] = useState<ClusterStatus | null>(null)

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 5000) // 每5秒刷新
    return () => clearInterval(interval)
  }, [])

  const fetchData = async () => {
    try {
      const [stats, cluster] = await Promise.all([
        taskAPI.getStatistics(),
        clusterAPI.getStatus(),
      ])
      setTaskStats(stats as TaskStatistics)
      setClusterStatus(cluster as ClusterStatus)
    } catch (error) {
      console.error('Failed to fetch data:', error)
    } finally {
      setLoading(false)
    }
  }

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '100px 0' }}>
        <Spin size="large" />
      </div>
    )
  }

  return (
    <div>
      <h1 style={{ marginBottom: 24 }}>系统仪表盘</h1>

      {/* 集群状态卡片 */}
      <Card 
        title="集群状态" 
        extra={<Tag color="success">运行中</Tag>}
        style={{ marginBottom: 24 }}
      >
        <Row gutter={16}>
          <Col span={8}>
            <Statistic
              title="Leader节点"
              value={clusterStatus?.leader || 'N/A'}
              prefix={<ThunderboltOutlined />}
            />
          </Col>
          <Col span={8}>
            <Statistic
              title="集群节点"
              value={clusterStatus?.totalNodes || 0}
              suffix="个"
            />
          </Col>
          <Col span={8}>
            <Statistic
              title="健康节点"
              value={clusterStatus?.healthyNodes || 0}
              valueStyle={{ color: '#3f8600' }}
              suffix="个"
            />
          </Col>
        </Row>
      </Card>

      {/* 任务统计卡片 */}
      <Card title="任务统计" style={{ marginBottom: 24 }}>
        <Row gutter={[16, 16]}>
          <Col xs={12} sm={8} lg={4}>
            <Card>
              <Statistic
                title="总任务数"
                value={taskStats?.totalTasks || 0}
                valueStyle={{ fontSize: 32 }}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card>
              <Statistic
                title="等待中"
                value={taskStats?.pendingTasks || 0}
                valueStyle={{ color: '#faad14', fontSize: 32 }}
                prefix={<ClockCircleOutlined />}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card>
              <Statistic
                title="运行中"
                value={taskStats?.runningTasks || 0}
                valueStyle={{ color: '#1890ff', fontSize: 32 }}
                prefix={<SyncOutlined spin />}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card>
              <Statistic
                title="已完成"
                value={taskStats?.completedTasks || 0}
                valueStyle={{ color: '#52c41a', fontSize: 32 }}
                prefix={<CheckCircleOutlined />}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card>
              <Statistic
                title="失败"
                value={taskStats?.failedTasks || 0}
                valueStyle={{ color: '#ff4d4f', fontSize: 32 }}
                prefix={<CloseCircleOutlined />}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} lg={4}>
            <Card>
              <Statistic
                title="成功率"
                value={taskStats?.successRate || 0}
                precision={1}
                suffix="%"
                valueStyle={{ fontSize: 32 }}
              />
            </Card>
          </Col>
        </Row>
      </Card>

      {/* 性能指标卡片 */}
      <Card title="性能指标">
        <Row gutter={16}>
          <Col span={12}>
            <Statistic
              title="平均执行时间"
              value={taskStats?.averageExecutionTime || 0}
              precision={2}
              suffix="ms"
            />
          </Col>
          <Col span={12}>
            <Statistic
              title="系统状态"
              value="正常"
              valueStyle={{ color: '#3f8600' }}
            />
          </Col>
        </Row>
      </Card>
    </div>
  )
}

export default Dashboard
