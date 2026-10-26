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
import apiClient from '../src/shared/api/client'
import { cdcTaskAPI } from '../src/features/cdc/api'
import { pendingCdcSubmission, reserveCdcSubmission, confirmCdcSubmission } from '../src/features/cdc/submissions'
import { reconcileCdcSelection, cdcTaskForDisplay } from '../src/features/cdc/projections'
import type { CdcTaskView } from '../src/features/cdc/types'
import { workflowAPI } from '../src/features/workflows/api'
import { pendingExecution, reserveExecution, confirmExecution } from '../src/features/workflows/executionRequests'

const requests: { method: string; path: string; body: string }[] = []
const server = createServer(async (req: IncomingMessage, res) => {
  let body = ''
  for await (const chunk of req) body += chunk
  requests.push({ method: req.method!, path: req.url!, body })
  res.setHeader('Content-Type', 'application/json')
  if (req.url === '/api/v1/cdc-status') {
    res.end('{"enabled":true,"ownerState":"ACTIVE","reason":null,"activeSteps":1,"maxActiveSteps":4}')
  } else if (req.url === '/api/v1/cdc-captures') {
    res.end('{"items":[{"captureId":"capture-a","database":"shop","readiness":"STARTING","runtimeHealth":"UNKNOWN","reason":null}]}')
  } else if (req.url?.startsWith('/api/v1/cdc-tasks')) {
    if (req.url.endsWith('/cancel')) {
      res.statusCode = 202
      res.end('{"taskId":"cdc-a","desiredState":"CANCELED","phase":"CANCELING"}')
    } else if (req.method === 'POST') {
      res.statusCode = 202
      res.end('{"task":{"taskId":"cdc-a","requestId":"request-a","phase":"QUEUED","desiredState":"RUNNING"},"created":true}')
    } else if (req.url.includes('/cdc-a')) {
      res.end('{"taskId":"cdc-a","requestId":"request-a","phase":"ACTIVATING","desiredState":"RUNNING"}')
    } else {
      res.end('{"items":[{"taskId":"cdc-a","phase":"QUEUED"}],"nextAfter":"cdc-a"}')
    }
  } else if (req.url?.includes('/execute?')) {
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


test('CDC submit retries the same complete request and exposes acceptance without claiming initialization', async () => {
  const definition = { requestId: 'request-a', captureId: 'capture-a', table: 'orders', generation: 'g1', targetTable: 'target_orders' }
  const accepted = await cdcTaskAPI.submit(definition)
  await cdcTaskAPI.submit(definition)
  assert.deepEqual(accepted, { task: { taskId: 'cdc-a', requestId: 'request-a', phase: 'QUEUED', desiredState: 'RUNNING' }, created: true })
  assert.deepEqual(JSON.parse(requests.at(-1)!.body), definition)
  assert.equal(requests.at(-1)?.body, requests.at(-2)?.body)
})

test('CDC list uses a keyset cursor, detail and cancel keep their resource identity', async () => {
  assert.deepEqual(await cdcTaskAPI.list('before', 2), { items: [{ taskId: 'cdc-a', phase: 'QUEUED' }], nextAfter: 'cdc-a' })
  const url = new URL(requests.at(-1)!.path, 'http://fixture')
  assert.equal(url.searchParams.get('after'), 'before')
  assert.equal(url.searchParams.get('pageSize'), '2')
  assert.equal((await cdcTaskAPI.get('cdc-a')).phase, 'ACTIVATING')
  assert.equal(requests.at(-1)?.path, '/api/v1/cdc-tasks/cdc-a')
  assert.equal((await cdcTaskAPI.cancel('cdc-a')).phase, 'CANCELING')
  assert.equal(requests.at(-1)?.path, '/api/v1/cdc-tasks/cdc-a/cancel')
})

test('an uncertain CDC submission preserves its definition and identity across page lifetimes', () => {
  sessionStorage.clear()
  const definition = { captureId: 'capture-a', table: 'orders', generation: 'g1', targetTable: 'target_orders' }
  const first = reserveCdcSubmission(definition)
  assert.deepEqual(reserveCdcSubmission(definition), first)
  assert.deepEqual(pendingCdcSubmission(), first)
  assert.throws(() => reserveCdcSubmission({ ...definition, table: 'changed' }))
  confirmCdcSubmission('other-request')
  assert.deepEqual(pendingCdcSubmission(), first)
  confirmCdcSubmission(first.requestId)
  assert.equal(pendingCdcSubmission(), null)
})


test('CDC status and capture metadata do not turn a starting Reader into initialized tasks', async () => {
  assert.deepEqual(await cdcTaskAPI.status(), { enabled: true, ownerState: 'ACTIVE', reason: null, activeSteps: 1, maxActiveSteps: 4 })
  assert.deepEqual(await cdcTaskAPI.captures(), [{ captureId: 'capture-a', database: 'shop', readiness: 'STARTING', runtimeHealth: 'UNKNOWN', reason: null }])
})

test('corrupt pending CDC state is not silently replaced by a new request', () => {
  sessionStorage.setItem('pekko.cdc.pending-submission.v1', '{corrupt')
  assert.throws(() => pendingCdcSubmission())
  assert.throws(() => reserveCdcSubmission({ captureId: 'capture-a', table: 'orders', generation: 'g1', targetTable: 'target_orders' }))
  assert.equal(sessionStorage.getItem('pekko.cdc.pending-submission.v1'), '{corrupt')
  sessionStorage.clear()
})

const healthyCdcTask: CdcTaskView = {
  taskId: 'cdc-a', requestId: 'request-a', captureId: 'capture-a', table: 'orders', generation: 'g1', targetTable: 'target_orders',
  definitionFingerprint: 'definition-a', desiredState: 'RUNNING', controlRevision: 1, phase: 'STREAMING', journalRevision: 20,
  executionId: 'execution-a', captureReadiness: 'READY', initializationEvidence: 'historical-init-proof', runtimeHealth: 'HEALTHY',
  releaseStatus: 'RELEASED', failureCode: null, observedAt: 100, lastVerifiedAt: 90, cancelAllowed: true,
}

test('CDC selected detail follows newer list projections without regressing revisions or observation time', () => {
  const paused = { ...healthyCdcTask, phase: 'PAUSED', journalRevision: 21, runtimeHealth: 'UNKNOWN', observedAt: 110, cancelAllowed: false }
  assert.deepEqual(reconcileCdcSelection(healthyCdcTask, [paused]), paused)
  assert.deepEqual(reconcileCdcSelection(paused, [healthyCdcTask]), paused)
  assert.deepEqual(reconcileCdcSelection(paused, [{ ...healthyCdcTask, observedAt: 120 }]), paused)
  assert.deepEqual(reconcileCdcSelection(paused, [{ ...paused, controlRevision: 0, observedAt: 120 }]), paused)
  assert.deepEqual(reconcileCdcSelection(paused, [{ ...paused, taskId: 'other' }]), paused)
  assert.equal(reconcileCdcSelection(null, [paused]), null)
})

test('CDC health observation can change without a persisted revision and stale replies cannot restore health', () => {
  const unknown = { ...healthyCdcTask, runtimeHealth: 'UNKNOWN', observedAt: 120, cancelAllowed: false }
  assert.deepEqual(reconcileCdcSelection(healthyCdcTask, [unknown]), unknown)
  assert.deepEqual(reconcileCdcSelection(unknown, [healthyCdcTask]), unknown)
})

test('CDC main or detail query failure masks cached detail health and cancel permission while retaining historical proof', () => {
  const shown = cdcTaskForDisplay(healthyCdcTask, true)
  assert.equal(shown.runtimeHealth, 'UNKNOWN')
  assert.equal(shown.cancelAllowed, false)
  assert.equal(shown.initializationEvidence, 'historical-init-proof')
  assert.equal(shown.lastVerifiedAt, 90)
  assert.equal(cdcTaskForDisplay(healthyCdcTask, false), healthyCdcTask)
  assert.equal(healthyCdcTask.runtimeHealth, 'HEALTHY')
  assert.equal(healthyCdcTask.cancelAllowed, true)
})
