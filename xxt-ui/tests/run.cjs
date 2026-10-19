const { buildSync } = require('esbuild')
const { mkdtempSync, rmSync } = require('node:fs')
const { tmpdir } = require('node:os')
const { join } = require('node:path')
const { spawnSync } = require('node:child_process')
const temp = mkdtempSync(join(tmpdir(), 'pekko-ui-contracts-'))
try {
  const outfile = join(temp, 'contracts.cjs')
  buildSync({ entryPoints: ['tests/contracts.test.ts'], outfile, bundle: true, platform: 'node', format: 'cjs' })
  const result = spawnSync(process.execPath, ['--test', outfile], { stdio: 'inherit' })
  process.exitCode = result.status ?? 1
} finally { rmSync(temp, { recursive: true, force: true }) }
