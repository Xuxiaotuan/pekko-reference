import { useState } from 'react'
import { Layout, Menu, theme } from 'antd'
import { useNavigate, useLocation } from 'react-router-dom'
import {
  DashboardOutlined,
  ClusterOutlined,
  ApartmentOutlined,
} from '@ant-design/icons'
import type { MenuProps } from 'antd'

const { Header, Sider, Content } = Layout

interface AppLayoutProps {
  children: React.ReactNode
}

const AppLayout: React.FC<AppLayoutProps> = ({ children }) => {
  const [collapsed, setCollapsed] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const { token } = theme.useToken()

  const menuItems: MenuProps['items'] = [
    {
      key: '/cdc-tasks',
      icon: <ApartmentOutlined />,
      label: '常驻 CDC 任务',
    },
    {
      key: '/dashboard',
      icon: <DashboardOutlined />,
      label: '服务状态',
    },
    {
      key: '/schedules',
      icon: <ApartmentOutlined />,
      label: '调度计划',
    },
    {
      key: '/cluster',
      icon: <ClusterOutlined />,
      label: '集群状态',
    },
    {
      key: '/workflows',
      icon: <ApartmentOutlined />,
      label: 'CDC / 工作流',
    },
  ]

  const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
    navigate(key)
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider 
        collapsible
        breakpoint="lg"
        collapsedWidth={64}
        onBreakpoint={broken => setCollapsed(broken)}
        collapsed={collapsed} 
        onCollapse={setCollapsed}
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
        }}
      >
        <div 
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontSize: collapsed ? 16 : 20,
            fontWeight: 'bold',
            transition: 'all 0.2s',
          }}
        >
          {collapsed ? 'XXT' : 'XXT-UI'}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={handleMenuClick}
        />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 64 : 200, transition: 'margin-left 0.2s' }}>
        <Header 
          style={{ 
            padding: '0 24px', 
            background: token.colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
          }}
        >
          <h2 style={{ margin: 0, fontSize: 18 }}>Pekko CDC / Workflow</h2>
          <div style={{ fontSize: 14, color: token.colorTextSecondary }}>
            管理预览
          </div>
        </Header>
        <Content style={{ margin: 16, padding: 8, minHeight: 280, minWidth: 0 }}>
          {children}
        </Content>
      </Layout>
    </Layout>
  )
}

export default AppLayout
