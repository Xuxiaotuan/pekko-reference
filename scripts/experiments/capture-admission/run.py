#!/usr/bin/env python3
"""Run frozen admission tests on a caller-leased, physically isolated MySQL pair.

No KILL, reset, automatic lease release or production data access. Unknown release
keeps the caller's lease occupied. Use capture-admission-release/run.py for idle
Shared/SDK observations, not this ScalaTest runner.
"""
import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'cdc-lab-pool'))
from pool import Pool, digest, terminate_group, verify_candidate, write_json

SUITES = {
    'admission': 'workflow.integration.CaptureAdmissionMatrixSpec',
    'marker': 'workflow.integration.CaptureMarkerUnknownSpec',
    'reader': 'connectors.mysql.source.SharedCaptureReaderSpec',
    'bootstrap': 'workflow.integration.BootstrapMatrixSpec',
}


@contextmanager
def runner_lock(path):
    with Path(path).open('a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def observe_resources(pool, slot, token):
    sample = dict(at_ns=time.time_ns(), idle=True, instances={})
    for role in ['source', 'state']:
        sessions = pool.sql(slot, role, "SELECT ID,USER,HOST,DB,COMMAND FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND USER NOT IN ('event_scheduler','system user')", token)
        transactions = pool.sql(slot, role, 'SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.INNODB_TRX', token)
        sample['instances'][role] = dict(sessions=sessions, transactions=transactions)
        sample['idle'] = sample['idle'] and not sessions.strip() and not transactions.strip()
    return sample


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['config', 'lease', 'candidate', 'output', 'java']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--legacy-candidate', type=Path)
    parser.add_argument('--suite', action='append', choices=SUITES, required=True)
    parser.add_argument('--case', help='ScalaTest name substring; e.g. "for I11"')
    a = parser.parse_args()
    if 'admission' in a.suite and not a.legacy_candidate:
        parser.error('admission requires a pre-fix frozen --legacy-candidate')
    pool = Pool(a.config)
    lease = json.loads(a.lease.read_text())
    with runner_lock(pool.root / lease['slot'] / 'capture-admission-runner.lock'):
        run_locked(a, pool, lease)


def run_locked(a, pool, lease):
    slot, token = lease['slot'], lease['token']
    pool.require(slot, token, lease['owner'])
    candidate = verify_candidate(a.candidate)
    legacy = verify_candidate(a.legacy_candidate) if a.legacy_candidate else None
    env = os.environ.copy()
    env.update(pool.environment(slot))
    env.update(RUN_CAPTURE_ADMISSION_MATRIX='1', CDC_LAB_EXCLUSIVE_LEASE='exclusive-admission-test',
               CAPTURE_EXPECT_SOURCE_UUID=pool.slots[slot]['source']['uuid'],
               CAPTURE_EXPECT_STATE_UUID=pool.slots[slot]['state']['uuid'],
               BOOTSTRAP_SOURCE_MANIFEST_SHA256=candidate['source_sha256'])
    if legacy:
        env['CAPTURE_LEGACY_CLASSPATH'] = os.pathsep.join(legacy['classpath'])
    a.output = a.output.resolve()
    a.output.mkdir(parents=True, exist_ok=False)
    env['CAPTURE_ADMISSION_EVIDENCE'] = str(a.output / 'cases')
    args = [str(a.java), '-Xmx768m', '-Duser.timezone=UTC', '-cp',
            os.pathsep.join(candidate['classpath']), 'org.scalatest.tools.Runner', '-o']
    for suite in a.suite:
        args += ['-s', 'cn.xuyinyin.magic.' + SUITES[suite]]
    if a.case:
        args += ['-z', a.case]
    result = dict(slot=slot, suites=a.suite, case=a.case, runner_sha256=digest(__file__),
                  source_sha256=candidate['source_sha256'], production_sha256=candidate['production_sha256'],
                  legacy_source_sha256=legacy['source_sha256'] if legacy else None,
                  admission=pool.verify_slot(slot, token))
    if not result['admission']['admitted']:
        raise RuntimeError('slot identity or resource admission rejected; no JVM started')
    before = observe_resources(pool, slot, token)
    write_json(a.output / 'before.json', before)
    if not before['idle']:
        result.update(exit_code=125, after_idle=False, refusal='SLOT_NOT_IDLE')
        write_json(a.output / 'receipt.json', result)
        raise RuntimeError('slot sessions/transactions remain; no JVM started, lease retained')
    process = None
    raw_path = pool.root / ('admission-' + uuid.uuid4().hex + '.log')
    try:
        result['started_ns'] = time.time_ns()
        with open(raw_path, 'x', opener=lambda p, f: os.open(p, f, 0o600)) as raw:
            process = subprocess.Popen(args, cwd=a.output, env=env, stdout=raw,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            result['pid'] = process.pid
            try:
                result['exit_code'] = process.wait(timeout=1000)
            except subprocess.TimeoutExpired:
                result['exit_code'] = 124
                result['cleanup'] = terminate_group(process.pid)
    finally:
        if process and process.poll() is None:
            result['cleanup'] = terminate_group(process.pid)
        result['finished_ns'] = time.time_ns()
        observations, deadline = [], time.monotonic() + 180
        while True:
            sample = observe_resources(pool, slot, token)
            observations.append(sample)
            if sample['idle'] or time.monotonic() >= deadline:
                break
            time.sleep(.5)
        result['after_idle'] = observations[-1]['idle']
        if not result['after_idle']:
            result['exit_code'] = 125
        write_json(a.output / 'release.json', observations)
        write_json(a.output / 'receipt.json', result)
        if raw_path.exists():
            (a.output / 'suite.log').write_text(raw_path.read_text(errors='replace').replace(
                env['CDC_WINDOW_MYSQL_PASSWORD'], '[REDACTED]'))
    print(json.dumps(result))
    raise SystemExit(result.get('exit_code', 125))


if __name__ == '__main__':
    main()
