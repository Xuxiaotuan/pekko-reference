import axios from 'axios';
import { Workflow, NodeTypeDefinition, ExecutionResult } from '../types/workflow';

// 使用相对路径，通过Vite代理转发到后端
// 开发环境：http://localhost:3200/api/v1 → http://localhost:8080/api/v1
// 生产环境：需要配置实际的后端地址
const API_BASE_URL = '/api/v1';

export const workflowAPI = {
  // 获取所有工作流
  getAll: async (): Promise<Workflow[]> => {
    const response = await axios.get(`${API_BASE_URL}/workflows`);
    return response.data;
  },

  // 获取单个工作流
  getById: async (id: string): Promise<Workflow> => {
    const response = await axios.get(`${API_BASE_URL}/workflows/${id}`);
    return response.data;
  },

  // 创建工作流
  create: async (workflow: Workflow): Promise<void> => {
    await axios.post(`${API_BASE_URL}/workflows`, workflow);
  },

  // 更新工作流
  update: async (id: string, workflow: Workflow): Promise<void> => {
    await axios.put(`${API_BASE_URL}/workflows/${id}`, workflow);
  },

  // 删除工作流
  delete: async (id: string): Promise<void> => {
    await axios.delete(`${API_BASE_URL}/workflows/${id}`);
  },

  // 执行工作流
  execute: async (id: string): Promise<ExecutionResult> => {
    const response = await axios.post(`${API_BASE_URL}/workflows/${id}/execute`);
    return response.data;
  },

  // 获取节点类型定义
  getNodeTypes: async (): Promise<Record<string, NodeTypeDefinition[]>> => {
    const response = await axios.get(`${API_BASE_URL}/workflows/node-types`);
    return response.data;
  },

  // 获取执行历史
  getExecutions: async (workflowId: string): Promise<any[]> => {
    const response = await axios.get(`${API_BASE_URL}/workflows/${workflowId}/executions`);
    return response.data;
  },

  // 获取执行日志
  getExecutionLogs: async (workflowId: string, executionId: string): Promise<any> => {
    const response = await axios.get(`${API_BASE_URL}/workflows/${workflowId}/executions/${executionId}/logs`);
    return response.data;
  },
};
