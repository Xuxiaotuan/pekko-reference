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
    'release': 'workflow.integration.ManagedCaptureReleaseSpec',
    'l-ownership': 'workflow.integration.CaptureLogResourceOwnershipSpec',
    'platform': 'workflow.integration.CdcPlatformMatrixSpec',
    'metadata': 'workflow.integration.CdcPlatformMetadataSpec',
    'step-view': 'workflow.integration.BootstrapStepViewRecoverySpec',
    'recovery-windows': 'workflow.integration.RealMySQLCdcRecoveryWindowsSpec',
    'automatic-recovery': 'workflow.integration.RealMySQLCdcAutomaticRecoverySpec',
    'session-release': 'workflow.integration.RealMySQLCdcSessionReleaseSpec',
    'shared': 'workflow.integration.SharedCaptureMatrixSpec',
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


def fulfill_source_purge(pool, slot, token, root, private_archive):
    request = json.loads((root / 'source-purge-request.json').read_text())
    identity = pool.slots[slot]['source']
    if request.get('uuid') != identity['uuid'] or request.get('port') != identity['port']:
        raise RuntimeError('purge request identity mismatch')
    if not pool.verify_slot(slot, token)['admitted']:
        raise RuntimeError('physical slot identity mismatch')
    idle = observe_resources(pool, slot, token)
    if not idle['idle']:
        raise RuntimeError('purge request slot not idle')
    private_archive.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    archived = pool.archive_and_purge(slot, 'source', token, private_archive)
    os.chmod(private_archive, 0o700)
    write_json(root / 'source-purge-response.json', dict(purged=True, archived=archived, privateArchive=str(private_archive),
               identity=dict(uuid=identity['uuid'], port=identity['port']), idleBefore=idle))


def try_source_purge(pool, slot, token, root, private_archive, observations):
    sample = observe_resources(pool, slot, token)
    observations.append(sample)
    if not sample['idle']:
        return False
    fulfill_source_purge(pool, slot, token, root, private_archive)
    return True


def cleanup_group(group):
    try:
        return terminate_group(group)
    except OSError as error:
        # Preserve a refused signal; independently observe this exact spawned process group.
        result = subprocess.run(['ps', '-ax', '-o', 'pid=,pgid=,stat='], capture_output=True, text=True, timeout=3)
        remaining = None if result.returncode else [dict(pid=int(row[0]), state=row[2])
            for line in result.stdout.splitlines() if len(row := line.split()) == 3
            and int(row[1]) == group and not row[2].startswith('Z')]
        return dict(group=group, signal_error=type(error).__name__ + ': ' + str(error),
                    remaining_live=remaining, confirmed_exited=remaining == [])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['config', 'lease', 'candidate', 'output', 'java']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--legacy-candidate', type=Path)
    parser.add_argument('--suite', action='append', choices=SUITES, required=True)
    parser.add_argument('--case', help='ScalaTest name substring; e.g. "for I11"')
    parser.add_argument('--test-name', action='append', default=[])
    parser.add_argument('--source-purge', action='store_true', help='Explicit isolated R16-binlog only; archives remain private')
    a = parser.parse_args()
    if a.source_purge and (a.suite != ['shared'] or a.case != 'for R16-binlog'):
        parser.error('source purge requires only shared and exact --case for R16-binlog')
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
    if any(s in a.suite for s in ['platform','step-view']):
        env['RUN_CDC_PLATFORM_MATRIX']='1'
    if 'metadata' in a.suite:
        env['RUN_CDC_PLATFORM_METADATA']='1'
    if legacy:
        env['CAPTURE_LEGACY_CLASSPATH'] = os.pathsep.join(legacy['classpath'])
    a.output = a.output.resolve()
    a.output.mkdir(parents=True, exist_ok=False)
    env['CAPTURE_ADMISSION_EVIDENCE'] = str(a.output / 'cases')
    env['CDC_PLATFORM_EVIDENCE'] = str(a.output / 'cases')
    if getattr(a, 'source_purge', False):
        env['CAPTURE_SOURCE_PURGE_GATE'] = str(a.output)
    args = [str(a.java), '-Xmx768m', '-Duser.timezone=UTC', '-cp',
            os.pathsep.join(candidate['classpath']), 'org.scalatest.tools.Runner', '-o']
    for suite in a.suite:
        args += ['-s', 'cn.xuyinyin.magic.' + SUITES[suite]]
    if a.case:
        args += ['-z', a.case]
    for name in getattr(a, 'test_name', []):
        args += ['-t', name]
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
    purge_observations = []
    raw_path = pool.root / ('admission-' + uuid.uuid4().hex + '.log')
    try:
        result['started_ns'] = time.time_ns()
        with open(raw_path, 'x', opener=lambda p, f: os.open(p, f, 0o600)) as raw:
            process = subprocess.Popen(args, cwd=a.output, env=env, stdout=raw,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            result['pid'] = process.pid
            try:
                if getattr(a, 'source_purge', False):
                    deadline = time.monotonic() + 1000
                    while process.poll() is None:
                        if (a.output / 'source-purge-request.json').exists() and not (a.output / 'source-purge-response.json').exists():
                            try_source_purge(pool, slot, token, a.output,
                                pool.root / slot / 'private-binlog-archives' / uuid.uuid4().hex, purge_observations)
                        if time.monotonic() >= deadline:
                            raise subprocess.TimeoutExpired(args, 1000)
                        time.sleep(.2)
                    result['exit_code'] = process.returncode
                else:
                    result['exit_code'] = process.wait(timeout=1000)
            except subprocess.TimeoutExpired:
                result['exit_code'] = 124
                result['cleanup'] = cleanup_group(process.pid)
    except Exception as error:
        result.update(exit_code=1, runner_error=type(error).__name__ + ': ' + str(error))
        raise
    finally:
        if process and process.poll() is None and 'cleanup' not in result:
            result['cleanup'] = cleanup_group(process.pid)
        if result.get('cleanup', {}).get('confirmed_exited') is False:
            result['exit_code'] = 125
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
        if purge_observations:
            write_json(a.output / 'source-purge-wait.json', purge_observations)
        write_json(a.output / 'release.json', observations)
        write_json(a.output / 'receipt.json', result)
        if raw_path.exists():
            (a.output / 'suite.log').write_text(raw_path.read_text(errors='replace').replace(
                env['CDC_WINDOW_MYSQL_PASSWORD'], '[REDACTED]'))
    print(json.dumps(result))
    raise SystemExit(result.get('exit_code', 125))


if __name__ == '__main__':
    main()
