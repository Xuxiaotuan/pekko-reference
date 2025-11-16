import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Card,
  Table,
  Tag,
  Descriptions,
  Timeline,
  Statistic,
  Row,
  Col,
  Spin,
  Empty,
  Button,
  Space
} from 'antd';
import {
  ClockCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  SyncOutlined,
  ReloadOutlined
} from '@ant-design/icons';

/**
 * 执行历史页面
 * 
 * 展示工作流的所有执行历史：
 * - 执行列表
 * - 执行详情
 * - 节点时间线
 * - 性能统计
 */

interface NodeExecutionDetail {
  nodeId: string;
  nodeType: string;
  startTime: number;
  endTime?: number;
  duration?: number;
  status: string;
  recordsProcessed: number;
  error?: string;
}

interface ExecutionDetail {
  executionId: string;
  workflowName: string;
  startTime: number;
  endTime?: number;
  status: string;
  duration?: number;
  nodes: NodeExecutionDetail[];
}

interface ExecutionHistoryData {
  workflowId: string;
  executions: ExecutionDetail[];
}

const ExecutionHistory: React.FC = () => {
  const { workflowId } = useParams<{ workflowId: string }>();
  const [loading, setLoading] = useState(true);
  const [historyData, setHistoryData] = useState<ExecutionHistoryData | null>(null);
  const [selectedExecution, setSelectedExecution] = useState<ExecutionDetail | null>(null);

  const fetchExecutionHistory = async () => {
    setLoading(true);
    try {
      const response = await fetch(`/api/history/${workflowId}`);
      const data = await response.json();
      setHistoryData(data);
      if (data.executions && data.executions.length > 0) {
        setSelectedExecution(data.executions[0]);
      }
    } catch (error) {
      console.error('Failed to fetch execution history:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (workflowId) {
      fetchExecutionHistory();
    }
  }, [workflowId]);

  const formatDuration = (ms?: number): string => {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(2)}s`;
    return `${(ms / 60000).toFixed(2)}min`;
  };

  const formatTime = (timestamp: number): string => {
    return new Date(timestamp).toLocaleString('zh-CN');
  };

  const getStatusTag = (status: string) => {
    const statusConfig = {
      completed: { color: 'success', icon: <CheckCircleOutlined />, text: '完成' },
      failed: { color: 'error', icon: <CloseCircleOutlined />, text: '失败' },
      running: { color: 'processing', icon: <SyncOutlined spin />, text: '运行中' },
      pending: { color: 'default', icon: <ClockCircleOutlined />, text: '等待' }
    };
    const config = statusConfig[status as keyof typeof statusConfig] || statusConfig.pending;
    return (
      <Tag color={config.color} icon={config.icon}>
        {config.text}
      </Tag>
    );
  };

  // 执行列表列定义
  const executionColumns = [
    {
      title: '执行ID',
      dataIndex: 'executionId',
      key: 'executionId',
      width: 150,
      render: (id: string, record: ExecutionDetail) => (
        <Button
          type={selectedExecution?.executionId === id ? 'primary' : 'link'}
          onClick={() => setSelectedExecution(record)}
        >
          {id}
        </Button>
      )
    },
    {
      title: '开始时间',
      dataIndex: 'startTime',
      key: 'startTime',
      width: 180,
      render: (time: number) => formatTime(time)
    },
    {
      title: '结束时间',
      dataIndex: 'endTime',
      key: 'endTime',
      width: 180,
      render: (time?: number) => time ? formatTime(time) : '-'
    },
    {
      title: '耗时',
      dataIndex: 'duration',
      key: 'duration',
      width: 120,
      render: (duration?: number) => formatDuration(duration)
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => getStatusTag(status)
    },
    {
      title: '节点数',
      dataIndex: 'nodes',
      key: 'nodes',
      width: 100,
      render: (nodes: NodeExecutionDetail[]) => nodes.length
    }
  ];

  // 节点执行列定义
  const nodeColumns = [
    {
      title: '节点ID',
      dataIndex: 'nodeId',
      key: 'nodeId',
      width: 150
    },
    {
      title: '节点类型',
      dataIndex: 'nodeType',
      key: 'nodeType',
      width: 150,
      render: (type: string) => <Tag color="blue">{type}</Tag>
    },
    {
      title: '耗时',
      dataIndex: 'duration',
      key: 'duration',
      width: 120,
      render: (duration?: number) => formatDuration(duration)
    },
    {
      title: '处理记录数',
      dataIndex: 'recordsProcessed',
      key: 'recordsProcessed',
      width: 120
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => getStatusTag(status)
    },
    {
      title: '错误',
      dataIndex: 'error',
      key: 'error',
      render: (error?: string) => error ? <Tag color="red">{error}</Tag> : '-'
    }
  ];

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '100px 0' }}>
        <Spin size="large" tip="加载执行历史..." />
      </div>
    );
  }

  if (!historyData || historyData.executions.length === 0) {
    return (
      <Card>
        <Empty description="暂无执行历史" />
      </Card>
    );
  }

  return (
    <div style={{ padding: '24px' }}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {/* 页面标题 */}
        <Card>
          <Row justify="space-between" align="middle">
            <Col>
              <h2 style={{ margin: 0 }}>执行历史</h2>
              <p style={{ color: '#666', margin: '8px 0 0' }}>
                工作流ID: {workflowId}
              </p>
            </Col>
            <Col>
              <Button
                icon={<ReloadOutlined />}
                onClick={fetchExecutionHistory}
                loading={loading}
              >
                刷新
              </Button>
            </Col>
          </Row>
        </Card>

        {/* 执行统计 */}
        <Card title="执行统计">
          <Row gutter={16}>
            <Col span={6}>
              <Statistic
                title="总执行次数"
                value={historyData.executions.length}
                suffix="次"
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="成功"
                value={historyData.executions.filter(e => e.status === 'completed').length}
                valueStyle={{ color: '#3f8600' }}
                suffix="次"
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="失败"
                value={historyData.executions.filter(e => e.status === 'failed').length}
                valueStyle={{ color: '#cf1322' }}
                suffix="次"
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="平均耗时"
                value={formatDuration(
                  historyData.executions.reduce((sum, e) => sum + (e.duration || 0), 0) /
                  historyData.executions.length
                )}
              />
            </Col>
          </Row>
        </Card>

        {/* 执行列表 */}
        <Card title="执行记录">
          <Table
            dataSource={historyData.executions}
            columns={executionColumns}
            rowKey="executionId"
            pagination={{ pageSize: 10 }}
            size="middle"
          />
        </Card>

        {/* 执行详情 */}
        {selectedExecution && (
          <Card title={`执行详情: ${selectedExecution.executionId}`}>
            <Space direction="vertical" size="large" style={{ width: '100%' }}>
              {/* 基本信息 */}
              <Descriptions bordered column={2}>
                <Descriptions.Item label="工作流名称">
                  {selectedExecution.workflowName}
                </Descriptions.Item>
                <Descriptions.Item label="状态">
                  {getStatusTag(selectedExecution.status)}
                </Descriptions.Item>
                <Descriptions.Item label="开始时间">
                  {formatTime(selectedExecution.startTime)}
                </Descriptions.Item>
                <Descriptions.Item label="结束时间">
                  {selectedExecution.endTime ? formatTime(selectedExecution.endTime) : '-'}
                </Descriptions.Item>
                <Descriptions.Item label="总耗时">
                  {formatDuration(selectedExecution.duration)}
                </Descriptions.Item>
                <Descriptions.Item label="节点数量">
                  {selectedExecution.nodes.length}
                </Descriptions.Item>
              </Descriptions>

              {/* 节点执行时间线 */}
              <Card title="执行时间线" size="small">
                <Timeline mode="left">
                  {selectedExecution.nodes.map((node, index) => (
                    <Timeline.Item
                      key={node.nodeId}
                      color={
                        node.status === 'completed' ? 'green' :
                        node.status === 'failed' ? 'red' :
                        node.status === 'running' ? 'blue' : 'gray'
                      }
                      dot={
                        node.status === 'running' ? <SyncOutlined spin /> :
                        node.status === 'completed' ? <CheckCircleOutlined /> :
                        node.status === 'failed' ? <CloseCircleOutlined /> :
                        <ClockCircleOutlined />
                      }
                    >
                      <div>
                        <strong>{node.nodeId}</strong>
                        <Tag color="blue" style={{ marginLeft: 8 }}>{node.nodeType}</Tag>
                        {getStatusTag(node.status)}
                      </div>
                      <div style={{ color: '#666', fontSize: '12px', marginTop: 4 }}>
                        开始: {formatTime(node.startTime)}
                        {node.endTime && ` | 结束: ${formatTime(node.endTime)}`}
                        {node.duration && ` | 耗时: ${formatDuration(node.duration)}`}
                        {node.recordsProcessed > 0 && ` | 处理: ${node.recordsProcessed} 条记录`}
                      </div>
                      {node.error && (
                        <div style={{ color: '#cf1322', fontSize: '12px', marginTop: 4 }}>
                          错误: {node.error}
                        </div>
                      )}
                    </Timeline.Item>
                  ))}
                </Timeline>
              </Card>

              {/* 节点执行详情表格 */}
              <Card title="节点执行详情" size="small">
                <Table
                  dataSource={selectedExecution.nodes}
                  columns={nodeColumns}
                  rowKey="nodeId"
                  pagination={false}
                  size="small"
                />
              </Card>
            </Space>
          </Card>
        )}
      </Space>
    </div>
  );
};

export default ExecutionHistory;
