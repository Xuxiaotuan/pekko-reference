import { Routes, Route, Navigate } from 'react-router-dom'
import { Layout } from 'antd'
import AppLayout from './components/Layout/AppLayout'
import Dashboard from './pages/Dashboard'
import SQLConsole from './pages/SQLConsole'
import TaskMonitor from './pages/TaskMonitor'
import ClusterStatus from './pages/ClusterStatus'
import DAGEditor from './pages/DAGEditor'
import WorkflowListPage from './pages/WorkflowListPage'
import WorkflowEditorPage from './pages/WorkflowEditorPage'
import './App.css'

function App() {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <AppLayout>
        <Routes>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/sql" element={<SQLConsole />} />
          <Route path="/tasks" element={<TaskMonitor />} />
          <Route path="/cluster" element={<ClusterStatus />} />
          <Route path="/dag" element={<DAGEditor />} />
          <Route path="/workflows" element={<WorkflowListPage />} />
          <Route path="/workflows/:id/edit" element={<WorkflowEditorPage />} />
        </Routes>
      </AppLayout>
    </Layout>
  )
}

export default App
