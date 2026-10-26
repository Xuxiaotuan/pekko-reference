import { Routes, Route, Navigate, Link } from 'react-router-dom'
import { Alert, Layout } from 'antd'
import AppLayout from './app/AppLayout'
import Dashboard from './features/cluster/pages/Dashboard'
import ClusterStatus from './features/cluster/pages/ClusterStatus'
import WorkflowListPage from './features/workflows/pages/WorkflowListPage'
import WorkflowCreatePage from './features/workflows/pages/WorkflowCreatePage'
import WorkflowSummaryPage from './features/workflows/pages/WorkflowSummaryPage'
import ExecutionHistory from './features/workflows/pages/ExecutionHistory'
import SchedulePage from './features/schedules/pages/SchedulePage'
import CdcTasksPage from './features/cdc/pages/CdcTasksPage'
import './App.css'

function Unavailable() {
  return <Alert type="info" message="此入口尚不提供可验证的能力" description={<span>SQL 演示、通用 DAG、既有定义编辑、删除与日志入口已撤下。<Link to="/workflows">返回工作流列表</Link></span>} />
}
export default function App() {
  return <Layout style={{ minHeight: '100vh' }}><AppLayout><Routes>
    <Route path="/" element={<Navigate to="/workflows" replace />} />
    <Route path="/dashboard" element={<Dashboard />} />
    <Route path="/cluster" element={<ClusterStatus />} />
    <Route path="/workflows" element={<WorkflowListPage />} />
    <Route path="/workflows/new/import" element={<WorkflowCreatePage />} />
    <Route path="/workflows/:id" element={<WorkflowSummaryPage />} />
    <Route path="/history/:workflowId" element={<ExecutionHistory />} />
    <Route path="/schedules" element={<SchedulePage />} />
    <Route path="/cdc-tasks" element={<CdcTasksPage />} />
    <Route path="/tasks" element={<Navigate to="/workflows" replace />} />
    <Route path="*" element={<Unavailable />} />
  </Routes></AppLayout></Layout>
}
