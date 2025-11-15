import { useEffect, useState } from 'react'
import { Card, Statistic, Row, Col, Alert, Spin, Empty } from 'antd'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  SyncOutlined,
  ClockCircleOutlined,
  ReloadOutlined,
  RiseOutlined
} from '@ant-design/icons'
import axios from 'axios'

interface TaskStatistics {
  totalTasks: number
  runningTasks: number
  completedTasks: number
  failedTasks: number
  successRate: string
}

const TaskMonitor = () => {
  const [loading, setLoading] = useState(true)
  const [statistics, setStatistics] = useState<TaskStatistics | null>(null)
  const [error, setError] = useState<string>('')

  useEffect(() => {
    loadStatistics()
    const interval = setInterval(loadStatistics, 10000) // 每10秒刷新
    return () => clearInterval(interval)
  }, [])

  const loadStatistics = async () => {
    try {
      setLoading(true)
      const response = await axios.get('/api/v1/tasks/statistics')
      setStatistics(response.data)
      setError('')
    } catch (err: any) {
      console.error('加载任务统计失败:', err)
      setError(err.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ padding: 24 }}>
      <div style={{ marginBottom: 24, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1 style={{ margin: 0 }}>📊 任务监控</h1>
        <ReloadOutlined 
          style={{ fontSize: 18, cursor: 'pointer' }} 
          spin={loading}
          onClick={loadStatistics}
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
        {statistics ? (
          <>
            {/* 统计卡片 */}
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col xs={24} sm={12} lg={6}>
                <Card>
                  <Statistic
                    title="总任务数"
                    value={statistics.totalTasks}
                    prefix={<ClockCircleOutlined />}
                    valueStyle={{ fontSize: 28 }}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Card>
                  <Statistic
                    title="运行中"
                    value={statistics.runningTasks}
                    valueStyle={{ color: '#1890ff', fontSize: 28 }}
                    prefix={<SyncOutlined spin />}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Card>
                  <Statistic
                    title="已完成"
                    value={statistics.completedTasks}
                    valueStyle={{ color: '#3f8600', fontSize: 28 }}
                    prefix={<CheckCircleOutlined />}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} lg={6}>
                <Card>
                  <Statistic
                    title="失败"
                    value={statistics.failedTasks}
                    valueStyle={{ color: '#cf1322', fontSize: 28 }}
                    prefix={<CloseCircleOutlined />}
                  />
                </Card>
              </Col>
            </Row>

            {/* 成功率 */}
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={24}>
                <Card>
                  <Statistic
                    title="任务成功率"
                    value={statistics.successRate}
                    valueStyle={{ color: '#3f8600', fontSize: 32, fontWeight: 'bold' }}
                    prefix={<RiseOutlined />}
                  />
                  <div style={{ marginTop: 16, fontSize: 12, color: '#999' }}>
                    <p>✅ 完成任务：{statistics.completedTasks}</p>
                    <p>❌ 失败任务：{statistics.failedTasks}</p>
                    <p>⏳ 运行中任务：{statistics.runningTasks}</p>
                  </div>
                </Card>
              </Col>
            </Row>

            {/* 任务分布图表（占位） */}
            <Card title="任务统计" style={{ marginBottom: 16 }}>
              <div style={{ 
                padding: 40, 
                textAlign: 'center', 
                background: '#fafafa', 
                borderRadius: 4 
              }}>
                <p style={{ fontSize: 48, margin: 0 }}>📊</p>
                <p style={{ marginTop: 16, color: '#999' }}>
                  任务分布图表将在这里显示
                </p>
                <p style={{ fontSize: 12, color: '#999' }}>
                  （饼图/柱状图/趋势图）
                </p>
              </div>
            </Card>

            {/* 提示信息 */}
            <Card>
              <div style={{ fontSize: 12, color: '#999' }}>
                <p>💡 提示：</p>
                <ul style={{ paddingLeft: 20 }}>
                  <li>页面每10秒自动刷新一次统计数据</li>
                  <li>点击右上角刷新图标可以手动刷新</li>
                  <li>任务成功率 = 已完成任务 / (已完成 + 失败) × 100%</li>
                  <li>可以在"工作流编辑器"页面创建和管理工作流任务</li>
                </ul>
              </div>
            </Card>
          </>
        ) : (
          <Card>
            <Empty 
              description="暂无任务数据"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            >
              <p style={{ color: '#999', fontSize: 12 }}>
                提交任务后，这里将显示统计信息
              </p>
            </Empty>
          </Card>
        )}
      </Spin>
    </div>
  )
}

export default TaskMonitor
