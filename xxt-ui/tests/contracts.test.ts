import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'

// Node 18 compatibility; actual browser persistence is checked separately in the live UI.
if (typeof globalThis.crypto === 'undefined') Object.defineProperty(globalThis, 'crypto', { value: webcrypto })
if (typeof globalThis.sessionStorage === 'undefined') {
  const values = new Map<string, string>()
  Object.defineProperty(globalThis, 'sessionStorage', { value: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value) },
    removeItem: (key: string) => { values.delete(key) },
    clear: () => values.clear(),
  } })
}
import { createServer, type IncomingMessage } from 'node:http'
import apiClient from '../src/api/client'
import { workflowAPI } from '../src/api/workflow'
import { pendingExecution, reserveExecution, confirmExecution } from '../src/api/executionRequests'

const requests: { method: string; path: string; body: string }[] = []
const server = createServer(async (req: IncomingMessage, res) => {
  let body = ''
  for await (const chunk of req) body += chunk
  requests.push({ method: req.method!, path: req.url!, body })
  res.setHeader('Content-Type', 'application/json')
  if (req.url?.includes('/execute?')) {
    res.statusCode = 202
    res.end('{"workflowId":"wf-a","executionId":"run-a"}')
  } else if (req.url?.startsWith('/api/history/')) {
    res.end('{"workflowId":"wf-a","executions":[]}')
  } else if (req.method === 'POST') {
    res.statusCode = 201
    res.end('{"workflowId":"wf-a","revision":1}')
  } else {
    res.end('{"page":1,"pageSize":2,"items":[{"workflowId":"wf-a","revision":1,"status":"idle"}]}')
  }
})
before(async () => {
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  apiClient.defaults.baseURL = `http://127.0.0.1:${address.port}`
})
after(() => new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())))

test('list preserves the server page DTO and sends server-side pagination', async () => {
  const page = await workflowAPI.list(1, 2)
  assert.deepEqual(page, { page: 1, pageSize: 2, items: [{ workflowId: 'wf-a', revision: 1, status: 'idle' }] })
  assert.equal(requests.at(-1)?.path, '/api/v1/workflows?page=1&pageSize=2')
})
test('execute preserves the same explicit request identity across retries and returns acceptance', async () => {
  const first = await workflowAPI.execute('wf-a', 'request-stable')
  await workflowAPI.execute('wf-a', 'request-stable')
  assert.deepEqual(first, { workflowId: 'wf-a', executionId: 'run-a' })
  assert.equal(requests.at(-1)?.path, '/api/v1/workflows/wf-a/execute?requestId=request-stable')
  assert.equal(requests.at(-2)?.path, requests.at(-1)?.path)
})
test('history uses the existing history resource and unwraps its DTO once', async () => {
  assert.deepEqual(await workflowAPI.history('wf-a'), { workflowId: 'wf-a', executions: [] })
  assert.equal(requests.at(-1)?.path, '/api/history/wf-a')
})

test('create retains the persisted revision response instead of discarding it', async () => {
  const definition = { id: 'wf-a', name: 'fixture', description: '', version: '1', author: 'test', tags: [], nodes: [{ id: 's', type: 'source', nodeType: 'sequence.numbers', label: 's', position: { x: 0, y: 0 }, config: { start: 1, end: 1 } }, { id: 't', type: 'sink', nodeType: 'console.log', label: 't', position: { x: 0, y: 0 }, config: {} }], edges: [{ id: 'e', source: 's', target: 't' }], metadata: { createdAt: '2026-10-19', updatedAt: '2026-10-19' } }
  assert.deepEqual(await workflowAPI.create(definition), { workflowId: 'wf-a', revision: 1 })
  assert.deepEqual(JSON.parse(requests.at(-1)!.body), definition)
})

test('pending execution survives component lifetimes, isolates workflows and ignores stale confirmation', () => {
  sessionStorage.clear()
  const first = reserveExecution('wf-a')
  assert.equal(reserveExecution('wf-a'), first)
  assert.notEqual(reserveExecution('wf-b'), first)
  confirmExecution('wf-a', 'another-request')
  assert.equal(pendingExecution('wf-a'), first)
  confirmExecution('wf-a', first)
  assert.equal(pendingExecution('wf-a'), null)
  assert.notEqual(reserveExecution('wf-a'), first)
  sessionStorage.clear()
})
