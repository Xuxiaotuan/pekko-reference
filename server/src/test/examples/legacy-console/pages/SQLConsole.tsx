import { useState } from 'react'
import { Card, Button, Space, message } from 'antd'
import { PlayCircleOutlined, ClearOutlined } from '@ant-design/icons'

const SQLConsole = () => {
  const [sql, setSql] = useState('SELECT * FROM users LIMIT 10;')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<any>(null)

  const handleExecute = async () => {
    if (!sql.trim()) {
      message.warning('请输入SQL语句')
      return
    }

    setLoading(true)
    try {
      // TODO: 调用API执行SQL
      await new Promise(resolve => setTimeout(resolve, 1000))
      message.success('SQL执行成功')
      setResult({
        columns: ['id', 'name', 'age'],
        rows: [
          [1, 'Alice', 25],
          [2, 'Bob', 30],
        ],
      })
    } catch (error) {
      message.error('SQL执行失败')
    } finally {
      setLoading(false)
    }
  }

  const handleClear = () => {
    setSql('')
    setResult(null)
  }

  return (
    <div>
      <h1 style={{ marginBottom: 24 }}>SQL控制台</h1>

      <Card 
        title="SQL编辑器" 
        extra={
          <Space>
            <Button 
              type="primary" 
              icon={<PlayCircleOutlined />}
              onClick={handleExecute}
              loading={loading}
            >
              执行
            </Button>
            <Button 
              icon={<ClearOutlined />}
              onClick={handleClear}
            >
              清空
            </Button>
          </Space>
        }
        style={{ marginBottom: 24 }}
      >
        <textarea
          value={sql}
          onChange={(e) => setSql(e.target.value)}
          style={{
            width: '100%',
            minHeight: 200,
            fontFamily: 'monospace',
            fontSize: 14,
            padding: 12,
            border: '1px solid #d9d9d9',
            borderRadius: 4,
          }}
          placeholder="输入SQL语句..."
        />
      </Card>

      {result && (
        <Card title="查询结果">
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {result.columns.map((col: string) => (
                    <th key={col} style={{ 
                      padding: 12, 
                      textAlign: 'left', 
                      borderBottom: '2px solid #f0f0f0',
                      fontWeight: 600,
                    }}>
                      {col}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.rows.map((row: any[], idx: number) => (
                  <tr key={idx}>
                    {row.map((cell, cellIdx) => (
                      <td key={cellIdx} style={{ 
                        padding: 12, 
                        borderBottom: '1px solid #f0f0f0' 
                      }}>
                        {cell}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  )
}

export default SQLConsole
