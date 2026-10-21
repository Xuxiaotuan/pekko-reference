import { useNavigate } from 'react-router-dom'
import { useEffect } from 'react'

const DAGEditor = () => {
  const navigate = useNavigate()

  useEffect(() => {
    // 自动跳转到工作流列表页面
    navigate('/workflows', { replace: true })
  }, [navigate])

  return null
}

export default DAGEditor
