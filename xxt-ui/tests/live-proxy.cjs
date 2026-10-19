// Test-only loopback HTTP fault proxy. Never loaded by the application.
const http = require('node:http')
const fs = require('node:fs')
const target = new URL(process.env.TEST_BACKEND_URL)
if (!['127.0.0.1', 'localhost'].includes(target.hostname)) throw new Error('loopback backend required')
const controls = process.env.TEST_CONTROL_FILE
const log = process.env.TEST_PROXY_LOG
let ordinal = 0
http.createServer(async (req, res) => {
  const number = ++ordinal
  const mode = JSON.parse(fs.readFileSync(controls, 'utf8'))
  const list = req.method === 'GET' && req.url.startsWith('/api/v1/workflows?')
  const failure = (list && mode.failList) || (req.url.startsWith('/api/history/') && mode.failHistory) || (req.url.startsWith('/api/v1/cluster/') && mode.failCluster) || (req.url.startsWith('/api/v1/schedules') && mode.failSchedules) || (req.url.startsWith('/health/') && mode.failHealth)
  if (failure) {
    res.writeHead(503, { 'Content-Type': 'application/json' })
    res.end('{"error":"injected HTTP dependency failure"}')
    fs.appendFileSync(log, JSON.stringify({ number, path: req.url, injectedStatus: 503 })+'\n')
    return
  }
  const drop = req.method === 'POST' && req.url.includes('/execute?') && mode.dropNextExecuteReply
  if (drop) { mode.dropNextExecuteReply = false; fs.writeFileSync(controls, JSON.stringify(mode)) }
  const body = []
  for await (const chunk of req) body.push(chunk)
  const upstream = http.request(new URL(req.url, target), { method: req.method, headers: { ...req.headers, host: target.host } }, reply => {
    const chunks = []
    reply.on('data', chunk => chunks.push(chunk))
    reply.on('end', () => {
      const bytes = Buffer.concat(chunks)
      fs.appendFileSync(log, JSON.stringify({ number, method: req.method, path: req.url, upstreamStatus: reply.statusCode, response: bytes.toString(), replyReplaced: !!drop })+'\n')
      const send = () => {
        if (drop) { res.writeHead(504, { 'Content-Type': 'application/json' }); res.end('{"error":"injected reply loss after actual backend response"}') }
        else { res.writeHead(reply.statusCode, reply.headers); res.end(bytes) }
      }
      if (list && req.url.includes('page=1&') && mode.delayPageOneMs) setTimeout(send, mode.delayPageOneMs)
      else send()
    })
  })
  upstream.on('error', error => { res.writeHead(502, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ error: error.message })) })
  upstream.end(Buffer.concat(body))
}).listen(Number(process.env.TEST_PROXY_PORT), '127.0.0.1')
