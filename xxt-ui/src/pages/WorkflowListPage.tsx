import { useEffect, useState } from 'react';
import { Button, Card, Space, Tag, message, Modal, Form, Input } from 'antd';
import { PlusOutlined, PlayCircleOutlined, EditOutlined, DeleteOutlined, HistoryOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { workflowAPI } from '../api/workflow';
import { Workflow } from '../types/workflow';

const { TextArea } = Input;

const WorkflowListPage = () => {
  const navigate = useNavigate();
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [loading, setLoading] = useState(true);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    loadWorkflows();
  }, []);

  const loadWorkflows = async () => {
    try {
      setLoading(true);
      const data = await workflowAPI.getAll();
      setWorkflows(data);
    } catch (error) {
      console.error('Failed to load workflows:', error);
      message.error('加载工作流列表失败');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async (values: any) => {
    try {
      const newWorkflow: Workflow = {
        id: `wf_${Date.now()}`,
        name: values.name,
        description: values.description || '',
        version: '1.0',
        author: 'current_user',
        tags: values.tags ? values.tags.split(',').map((t: string) => t.trim()) : [],
        nodes: [],
        edges: [],
        metadata: {
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      };

      await workflowAPI.create(newWorkflow);
      message.success('工作流创建成功');
      setCreateModalVisible(false);
      form.resetFields();
      loadWorkflows();
      
      // 跳转到编辑器
      navigate(`/workflows/${newWorkflow.id}/edit`);
    } catch (error) {
      console.error('Failed to create workflow:', error);
      message.error('创建工作流失败');
    }
  };

  const handleDelete = async (id: string) => {
    Modal.confirm({
      title: '确定要删除这个工作流吗？',
      content: '此操作不可恢复',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await workflowAPI.delete(id);
          message.success('工作流已删除');
          loadWorkflows();
        } catch (error) {
          console.error('Failed to delete workflow:', error);
          message.error('删除工作流失败');
        }
      },
    });
  };

  const handleExecute = async (id: string) => {
    try {
      message.loading({ content: '正在执行工作流...', key: 'execute' });
      const result = await workflowAPI.execute(id);
      message.success({
        content: `工作流执行完成: ${result.status}`,
        key: 'execute',
      });
      Modal.info({
        title: '执行结果',
        content: (
          <pre>{JSON.stringify(result, null, 2)}</pre>
        ),
      });
    } catch (error: any) {
      console.error('Failed to execute workflow:', error);
      message.error({
        content: `工作流执行失败: ${error.message || '未知错误'}`,
        key: 'execute',
      });
    }
  };

  return (
    <div style={{ 
      minHeight: 'calc(100vh - 64px)', 
      background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
      padding: '40px 24px'
    }}>
      {/* 顶部标题区 */}
      <div style={{ 
        maxWidth: 1200, 
        margin: '0 auto 40px',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center'
      }}>
        <div>
          <h1 style={{ 
            color: '#fff', 
            fontSize: 32, 
            fontWeight: 700,
            margin: 0,
            textShadow: '0 2px 4px rgba(0,0,0,0.1)'
          }}>
            🚀 工作流管理
          </h1>
          <p style={{ 
            color: 'rgba(255,255,255,0.9)', 
            fontSize: 14, 
            margin: '8px 0 0',
            textShadow: '0 1px 2px rgba(0,0,0,0.1)'
          }}>
            创建、编辑和执行你的数据流工作流
          </p>
        </div>
        <Button
          type="primary"
          size="large"
          icon={<PlusOutlined />}
          onClick={() => setCreateModalVisible(true)}
          style={{
            height: 48,
            fontSize: 16,
            fontWeight: 600,
            background: '#fff',
            color: '#667eea',
            border: 'none',
            boxShadow: '0 4px 12px rgba(0,0,0,0.15)'
          }}
        >
          创建工作流
        </Button>
      </div>

      {/* 工作流卡片网格 */}
      <div style={{ 
        maxWidth: 1200,
        margin: '0 auto',
        display: 'grid', 
        gridTemplateColumns: 'repeat(auto-fill, minmax(350px, 1fr))', 
        gap: 24
      }}>
        {workflows.map((workflow) => (
          <Card
            key={workflow.id}
            hoverable
            style={{
              height: '100%',
              borderRadius: 12,
              overflow: 'hidden',
              border: 'none',
              boxShadow: '0 4px 20px rgba(0,0,0,0.1)',
              transition: 'all 0.3s ease',
              background: '#fff'
            }}
            bodyStyle={{ padding: 20 }}
            actions={[
              <Button
                type="link"
                icon={<PlayCircleOutlined />}
                onClick={() => handleExecute(workflow.id)}
              >
                执行
              </Button>,
              <Button
                type="link"
                icon={<HistoryOutlined />}
                onClick={() => navigate(`/history/${workflow.id}`)}
              >
                历史
              </Button>,
              <Button
                type="link"
                icon={<EditOutlined />}
                onClick={() => navigate(`/workflows/${workflow.id}/edit`)}
              >
                编辑
              </Button>,
              <Button
                type="link"
                danger
                icon={<DeleteOutlined />}
                onClick={() => handleDelete(workflow.id)}
              >
                删除
              </Button>,
            ]}
          >
            <div style={{ marginBottom: 16 }}>
              <h3 style={{ 
                fontSize: 18, 
                fontWeight: 600, 
                margin: '0 0 8px',
                color: '#1a1a1a'
              }}>
                {workflow.name}
              </h3>
              <p style={{ 
                fontSize: 14, 
                color: '#666', 
                margin: 0,
                lineHeight: 1.6
              }}>
                {workflow.description || '暂无描述'}
              </p>
            </div>
            
            {workflow.tags.length > 0 && (
              <div style={{ marginBottom: 16 }}>
                <Space size={[0, 8]} wrap>
                  {workflow.tags.map((tag) => (
                    <Tag key={tag} color="purple" style={{ borderRadius: 4 }}>
                      {tag}
                    </Tag>
                  ))}
                </Space>
              </div>
            )}
            
            <div style={{ 
              display: 'grid',
              gridTemplateColumns: 'repeat(2, 1fr)',
              gap: 12,
              padding: 12,
              background: '#f7f7f7',
              borderRadius: 8
            }}>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 24, fontWeight: 600, color: '#667eea' }}>
                  {workflow.nodes.length}
                </div>
                <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>
                  节点数
                </div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 24, fontWeight: 600, color: '#764ba2' }}>
                  {workflow.edges.length}
                </div>
                <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>
                  连线数
                </div>
              </div>
            </div>
            
            <div style={{ 
              marginTop: 12, 
              fontSize: 12, 
              color: '#999',
              display: 'flex',
              justifyContent: 'space-between'
            }}>
              <span>📝 {workflow.author}</span>
              <span>v{workflow.version}</span>
            </div>
          </Card>
        ))}
      </div>

      {workflows.length === 0 && !loading && (
        <div style={{ textAlign: 'center', padding: '60px 0', color: '#999' }}>
          <p style={{ fontSize: 16, marginBottom: 16 }}>还没有工作流</p>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>
            创建第一个工作流
          </Button>
        </div>
      )}

      <Modal
        title="创建新工作流"
        open={createModalVisible}
        onOk={() => form.submit()}
        onCancel={() => {
          setCreateModalVisible(false);
          form.resetFields();
        }}
        okText="创建"
        cancelText="取消"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreate}
        >
          <Form.Item
            name="name"
            label="工作流名称"
            rules={[{ required: true, message: '请输入工作流名称' }]}
          >
            <Input placeholder="例如：用户数据ETL" />
          </Form.Item>
          <Form.Item
            name="description"
            label="描述"
          >
            <TextArea rows={3} placeholder="描述工作流的用途..." />
          </Form.Item>
          <Form.Item
            name="tags"
            label="标签"
            extra="多个标签用逗号分隔"
          >
            <Input placeholder="例如：etl, data-cleaning" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default WorkflowListPage;
