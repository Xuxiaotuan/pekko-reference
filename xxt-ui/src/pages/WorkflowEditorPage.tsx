import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Button, message, Space, Card, Drawer, Form, Input } from 'antd';
import { SaveOutlined, ArrowLeftOutlined, PlayCircleOutlined } from '@ant-design/icons';
import ReactFlow, {
  MiniMap,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  addEdge,
  Connection,
  Node,
  BackgroundVariant,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { workflowAPI } from '../api/workflow';
import { Workflow, NodeTypeDefinition } from '../types/workflow';

const WorkflowEditorPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [nodeTypes, setNodeTypes] = useState<Record<string, NodeTypeDefinition[]>>({});
  const [selectedNode, setSelectedNode] = useState<Node | null>(null);
  const [drawerVisible, setDrawerVisible] = useState(false);
  const [configForm] = Form.useForm();

  useEffect(() => {
    loadWorkflow();
    loadNodeTypes();
  }, [id]);

  const loadWorkflow = async () => {
    if (!id) return;
    try {
      const data = await workflowAPI.getById(id);
      setWorkflow(data);

      // 转换节点为ReactFlow格式
      const flowNodes = data.nodes.map((node) => ({
        id: node.id,
        type: 'default',
        position: node.position,
        data: {
          label: (
            <div style={{ padding: '12px 16px' }}>
              <div style={{ 
                fontWeight: 600, 
                fontSize: 14,
                marginBottom: 4,
                color: '#ffffff'
              }}>
                {node.label}
              </div>
              <div style={{ 
                fontSize: 11, 
                color: 'rgba(255,255,255,0.8)',
                fontFamily: 'monospace'
              }}>
                {node.nodeType}
              </div>
            </div>
          ),
          nodeType: node.nodeType,
          config: node.config,
        },
        style: {
          background: node.type === 'source' 
            ? 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' 
            : node.type === 'transform' 
            ? 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)' 
            : 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)',
          border: 'none',
          borderRadius: 12,
          padding: 0,
          minWidth: 180,
          boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
          color: '#fff',
        },
      }));

      const flowEdges = data.edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        animated: true,
        style: { 
          stroke: '#667eea',
          strokeWidth: 2,
        },
        type: 'smoothstep',
      }));

      setNodes(flowNodes);
      setEdges(flowEdges);
    } catch (error) {
      console.error('Failed to load workflow:', error);
      message.error('加载工作流失败');
    }
  };

  const loadNodeTypes = async () => {
    try {
      const types = await workflowAPI.getNodeTypes();
      setNodeTypes(types);
    } catch (error) {
      console.error('Failed to load node types:', error);
    }
  };

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const onNodeClick = useCallback((_event: React.MouseEvent, node: Node) => {
    setSelectedNode(node);
    const nodeLabel = typeof node.data.label === 'string' 
      ? node.data.label 
      : node.id;
    configForm.setFieldsValue({
      label: nodeLabel,
      ...node.data.config,
    });
    setDrawerVisible(true);
  }, [configForm]);

  const handleSave = async () => {
    if (!workflow) return;

    try {
      // 转换回工作流格式
      const updatedWorkflow: Workflow = {
        ...workflow,
        nodes: nodes.map((node) => ({
          id: node.id,
          type: node.style?.borderColor === '#1890ff' ? 'source' 
              : node.style?.borderColor === '#ff4d4f' ? 'sink' 
              : 'transform',
          nodeType: node.data.nodeType,
          label: typeof node.data.label === 'string' ? node.data.label : node.id,
          position: node.position,
          config: node.data.config || {},
        })),
        edges: edges.map((edge) => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          animated: edge.animated,
        })),
        metadata: {
          ...workflow.metadata,
          updatedAt: new Date().toISOString(),
        },
      };

      await workflowAPI.update(workflow.id, updatedWorkflow);
      message.success('工作流已保存');
    } catch (error) {
      console.error('Failed to save workflow:', error);
      message.error('保存工作流失败');
    }
  };

  const handleExecute = async () => {
    if (!workflow) return;
    
    try {
      await handleSave(); // 先保存
      message.loading({ content: '正在执行工作流...', key: 'execute' });
      const result = await workflowAPI.execute(workflow.id);
      message.success({ content: '工作流执行完成', key: 'execute' });
      console.log('Execution result:', result);
    } catch (error: any) {
      message.error({ content: `执行失败: ${error.message}`, key: 'execute' });
    }
  };

  const addNode = (nodeType: string, category: string) => {
    const id = `${category}_${Date.now()}`;
    const newNode: Node = {
      id,
      type: 'default',
      position: { x: Math.random() * 400 + 100, y: Math.random() * 300 + 100 },
      data: {
        label: (
          <div style={{ padding: '12px 16px' }}>
            <div style={{ 
              fontWeight: 600, 
              fontSize: 14,
              marginBottom: 4,
              color: '#ffffff'
            }}>
              {nodeType}
            </div>
            <div style={{ 
              fontSize: 11, 
              color: 'rgba(255,255,255,0.8)',
              fontFamily: 'monospace'
            }}>
              {nodeType}
            </div>
          </div>
        ),
        nodeType,
        config: {},
      },
      style: {
        background: category === 'source' 
          ? 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' 
          : category === 'transform' 
          ? 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)' 
          : 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)',
        border: 'none',
        borderRadius: 12,
        padding: 0,
        minWidth: 180,
        boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
        color: '#fff',
      },
    };

    setNodes((nds) => [...nds, newNode]);
  };

  const handleConfigSave = () => {
    if (!selectedNode) return;

    const values = configForm.getFieldsValue();
    const { label: newLabel, ...config } = values;
    
    setNodes((nds) =>
      nds.map((node) =>
        node.id === selectedNode.id
          ? { 
              ...node, 
              data: { 
                ...node.data, 
                label: (
                  <div style={{ padding: '12px 16px' }}>
                    <div style={{ 
                      fontWeight: 600, 
                      fontSize: 14,
                      marginBottom: 4,
                      color: '#ffffff'
                    }}>
                      {newLabel || node.data.nodeType}
                    </div>
                    <div style={{ 
                      fontSize: 11, 
                      color: 'rgba(255,255,255,0.8)',
                      fontFamily: 'monospace'
                    }}>
                      {node.data.nodeType}
                    </div>
                  </div>
                ),
                config 
              } 
            }
          : node
      )
    );
    setDrawerVisible(false);
    message.success('节点配置已更新');
  };

  const handleDeleteNode = () => {
    if (!selectedNode) return;
    
    setNodes((nds) => nds.filter((node) => node.id !== selectedNode.id));
    setEdges((eds) => eds.filter(
      (edge) => edge.source !== selectedNode.id && edge.target !== selectedNode.id
    ));
    setDrawerVisible(false);
    setSelectedNode(null);
    message.success('节点已删除');
  };

  if (!workflow) {
    return <div style={{ padding: 24 }}>加载中...</div>;
  }

  return (
    <div style={{ 
      height: 'calc(100vh - 64px)', 
      display: 'flex', 
      flexDirection: 'column',
      background: '#f5f5f5'
    }}>
      {/* 顶部工具栏 */}
      <div style={{
        padding: '16px 24px',
        background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
        boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
      }}>
        <Space size={16}>
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/workflows')}
            style={{
              background: 'rgba(255,255,255,0.2)',
              border: 'none',
              color: '#fff',
            }}
          >
            返回
          </Button>
          <span style={{ 
            fontSize: 18, 
            fontWeight: 600,
            color: '#fff',
            textShadow: '0 2px 4px rgba(0,0,0,0.1)'
          }}>
            ✏️ {workflow.name}
          </span>
        </Space>
        <Space size={12}>
          <Button
            size="large"
            icon={<PlayCircleOutlined />}
            onClick={handleExecute}
            style={{
              background: '#52c41a',
              border: 'none',
              color: '#fff',
              fontWeight: 600,
              boxShadow: '0 2px 8px rgba(82,196,26,0.3)'
            }}
          >
            执行工作流
          </Button>
          <Button
            type="primary"
            size="large"
            icon={<SaveOutlined />}
            onClick={handleSave}
            style={{
              background: '#fff',
              color: '#667eea',
              border: 'none',
              fontWeight: 600,
              boxShadow: '0 2px 8px rgba(0,0,0,0.1)'
            }}
          >
            保存
          </Button>
        </Space>
      </div>

      <div style={{ flex: 1, display: 'flex' }}>
        {/* 左侧节点面板 */}
        <div style={{
          width: 280,
          background: '#fff',
          borderRight: '1px solid #e8e8e8',
          overflowY: 'auto',
          padding: 20,
          boxShadow: '2px 0 8px rgba(0,0,0,0.05)'
        }}>
          <h3 style={{
            fontSize: 16,
            fontWeight: 600,
            marginBottom: 20,
            color: '#1a1a1a',
            display: 'flex',
            alignItems: 'center',
            gap: 8
          }}>
            🎨 节点库
          </h3>
          
          {/* Source节点 */}
          <div style={{ marginBottom: 20 }}>
            <h4 style={{ 
              fontSize: 12, 
              color: '#999', 
              marginBottom: 12,
              fontWeight: 600,
              textTransform: 'uppercase',
              letterSpacing: 1
            }}>
              📥 数据源
            </h4>
            {nodeTypes.source?.map((nodeType) => (
              <Card
                key={nodeType.type}
                size="small"
                hoverable
                style={{ 
                  marginBottom: 8, 
                  cursor: 'pointer',
                  borderRadius: 8,
                  border: '2px solid #f0f0f0',
                  transition: 'all 0.3s ease'
                }}
                bodyStyle={{ padding: 12 }}
                onClick={() => addNode(nodeType.type, 'source')}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <span style={{ fontSize: 24 }}>{nodeType.icon}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 2 }}>
                      {nodeType.displayName}
                    </div>
                    <div style={{ fontSize: 11, color: '#999', lineHeight: 1.4 }}>
                      {nodeType.description}
                    </div>
                  </div>
                </div>
              </Card>
            ))}
          </div>

          {/* Transform节点 */}
          <div style={{ marginBottom: 20 }}>
            <h4 style={{ 
              fontSize: 12, 
              color: '#999', 
              marginBottom: 12,
              fontWeight: 600,
              textTransform: 'uppercase',
              letterSpacing: 1
            }}>
              🔄 数据转换
            </h4>
            {nodeTypes.transform?.map((nodeType) => (
              <Card
                key={nodeType.type}
                size="small"
                hoverable
                style={{ 
                  marginBottom: 8, 
                  cursor: 'pointer',
                  borderRadius: 8,
                  border: '2px solid #f0f0f0',
                  transition: 'all 0.3s ease'
                }}
                bodyStyle={{ padding: 12 }}
                onClick={() => addNode(nodeType.type, 'transform')}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <span style={{ fontSize: 24 }}>{nodeType.icon}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 2 }}>
                      {nodeType.displayName}
                    </div>
                    <div style={{ fontSize: 11, color: '#999', lineHeight: 1.4 }}>
                      {nodeType.description}
                    </div>
                  </div>
                </div>
              </Card>
            ))}
          </div>

          {/* Sink节点 */}
          <div>
            <h4 style={{ 
              fontSize: 12, 
              color: '#999', 
              marginBottom: 12,
              fontWeight: 600,
              textTransform: 'uppercase',
              letterSpacing: 1
            }}>
              📤 数据输出
            </h4>
            {nodeTypes.sink?.map((nodeType) => (
              <Card
                key={nodeType.type}
                size="small"
                hoverable
                style={{ 
                  marginBottom: 8, 
                  cursor: 'pointer',
                  borderRadius: 8,
                  border: '2px solid #f0f0f0',
                  transition: 'all 0.3s ease'
                }}
                bodyStyle={{ padding: 12 }}
                onClick={() => addNode(nodeType.type, 'sink')}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <span style={{ fontSize: 24 }}>{nodeType.icon}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 2 }}>
                      {nodeType.displayName}
                    </div>
                    <div style={{ fontSize: 11, color: '#999', lineHeight: 1.4 }}>
                      {nodeType.description}
                    </div>
                  </div>
                </div>
              </Card>
            ))}
          </div>
        </div>

        {/* 中间画布 */}
        <div style={{ flex: 1 }}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={onNodeClick}
            fitView
          >
            <Controls />
            <MiniMap />
            <Background variant={BackgroundVariant.Dots} gap={12} size={1} />
          </ReactFlow>
        </div>
      </div>

      {/* 右侧配置抽屉 */}
      <Drawer
        title="节点配置"
        placement="right"
        width={400}
        open={drawerVisible}
        onClose={() => setDrawerVisible(false)}
        extra={
          <Space>
            <Button danger onClick={handleDeleteNode}>
              删除节点
            </Button>
            <Button type="primary" onClick={handleConfigSave}>
              保存配置
            </Button>
          </Space>
        }
      >
        {selectedNode && (
          <Form
            form={configForm}
            layout="vertical"
          >
            <Form.Item label="节点ID">
              <Input value={selectedNode.id} disabled />
            </Form.Item>
            <Form.Item label="节点类型">
              <Input value={selectedNode.data.nodeType} disabled />
            </Form.Item>
            <Form.Item name="label" label="节点名称">
              <Input placeholder="输入自定义节点名称" />
            </Form.Item>
            
            {/* 动态配置字段 */}
            {selectedNode.data.nodeType === 'file.csv' && (
              <>
                <Form.Item name="path" label="文件路径" rules={[{ required: true }]}>
                  <Input placeholder="/data/input.csv" />
                </Form.Item>
                <Form.Item name="delimiter" label="分隔符">
                  <Input placeholder="," />
                </Form.Item>
              </>
            )}

            {selectedNode.data.nodeType === 'file.text' && (
              <Form.Item name="path" label="文件路径" rules={[{ required: true }]}>
                <Input placeholder="/data/input.txt" />
              </Form.Item>
            )}

            {selectedNode.data.nodeType === 'filter' && (
              <Form.Item name="condition" label="过滤条件" rules={[{ required: true }]}>
                <Input.TextArea rows={3} placeholder="value > 10" />
              </Form.Item>
            )}

            {selectedNode.data.nodeType === 'map' && (
              <Form.Item name="expression" label="映射表达式" rules={[{ required: true }]}>
                <Input.TextArea rows={3} placeholder="value * 2" />
              </Form.Item>
            )}

            {selectedNode.data.nodeType === 'batch' && (
              <Form.Item name="batchSize" label="批次大小" rules={[{ required: true }]}>
                <Input type="number" placeholder="100" />
              </Form.Item>
            )}

            {selectedNode.data.nodeType === 'console.log' && (
              <Form.Item name="limit" label="最大行数">
                <Input type="number" placeholder="100" />
              </Form.Item>
            )}

            {selectedNode.data.nodeType === 'random.numbers' && (
              <>
                <Form.Item name="count" label="生成数量" rules={[{ required: true }]}>
                  <Input type="number" placeholder="100" />
                </Form.Item>
                <Form.Item name="min" label="最小值" rules={[{ required: true }]}>
                  <Input type="number" placeholder="1" />
                </Form.Item>
                <Form.Item name="max" label="最大值" rules={[{ required: true }]}>
                  <Input type="number" placeholder="100" />
                </Form.Item>
              </>
            )}

            {selectedNode.data.nodeType === 'sequence.numbers' && (
              <>
                <Form.Item name="start" label="起始值" rules={[{ required: true }]}>
                  <Input type="number" placeholder="1" />
                </Form.Item>
                <Form.Item name="end" label="结束值" rules={[{ required: true }]}>
                  <Input type="number" placeholder="100" />
                </Form.Item>
                <Form.Item name="step" label="步长">
                  <Input type="number" placeholder="1" />
                </Form.Item>
              </>
            )}
          </Form>
        )}
      </Drawer>
    </div>
  );
};

export default WorkflowEditorPage;
