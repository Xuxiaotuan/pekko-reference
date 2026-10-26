#!/usr/bin/env python3
"""A0/B0 only: leased dual-MySQL slot, frozen JVM, passive server-release observations."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'cdc-lab-pool'))
from pool import Pool, verify_candidate, terminate_group, write_json, digest
from verify import read_events, summarize, check_expected


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path, required=True)
    p.add_argument('--lease', type=Path, required=True)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--java', type=Path, required=True)
    p.add_argument('--suite', choices=['all', 'cold', 'warm', 'sdk'], default='all')
    a = p.parse_args()
    pool = Pool(a.config)
    lease = json.loads(a.lease.read_text())
    slot, token = lease['slot'], lease['token']
    pool.require(slot, token, lease['owner'])
    candidate = verify_candidate(a.candidate)
    a.output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env.update(pool.environment(slot))
    env.update(CDC_LAB_EXCLUSIVE_LEASE='exclusive-a0-b0-test-slot',
               CAPTURE_EXPECT_SOURCE_UUID=pool.slots[slot]['source']['uuid'],
               CAPTURE_EXPECT_STATE_UUID=pool.slots[slot]['state']['uuid'])
    password = env['CDC_WINDOW_MYSQL_PASSWORD']
    cp = os.pathsep.join(candidate['classpath'])

    def snapshot(process=None, capture=None):
        result = {'observed_ns': time.time_ns(), 'instances': {}, 'idle': True}
        socket_names = []
        if process is not None and process.poll() is None:
            sockets = subprocess.run(['lsof', '-nP', '-a', '-p', str(process.pid), '-iTCP', '-FpnT'],
                                     capture_output=True, text=True, timeout=5)
            result['worker_sockets'] = {'pid': process.pid, 'parent_owned_and_alive': process.poll() is None,
                                        'exit_code': sockets.returncode, 'raw': sockets.stdout}
            socket_names = [line[1:] for line in sockets.stdout.splitlines() if line.startswith('n')]
        for role in ['source', 'state']:
            sessions = pool.sql(slot, role, "SELECT ID,USER,HOST,DB,COMMAND,STATE FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND USER NOT IN ('event_scheduler','system user') ORDER BY ID", token)
            transactions = pool.sql(slot, role, 'SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.INNODB_TRX ORDER BY trx_mysql_thread_id', token)
            threads = pool.sql(slot, role, "SELECT THREAD_ID,PROCESSLIST_ID,NAME,TYPE,PROCESSLIST_USER,PROCESSLIST_HOST,PROCESSLIST_DB,PROCESSLIST_COMMAND FROM performance_schema.threads WHERE TYPE='FOREGROUND' AND PROCESSLIST_ID IS NOT NULL AND PROCESSLIST_ID<>CONNECTION_ID() ORDER BY THREAD_ID", token)
            attrs = pool.sql(slot, role, "SELECT PROCESSLIST_ID,ATTR_NAME,ATTR_VALUE FROM performance_schema.session_connect_attrs WHERE PROCESSLIST_ID<>CONNECTION_ID() ORDER BY PROCESSLIST_ID,ORDINAL_POSITION", token)
            identity = pool.sql(slot, role, "SELECT @@server_uuid,@@port,@@datadir,@@version;SHOW GLOBAL STATUS LIKE 'Uptime'", token)
            mappings = []
            for line in sessions.splitlines():
                fields = line.split('\t')
                if len(fields) >= 5:
                    endpoint = fields[2] + '->127.0.0.1:' + str(pool.slots[slot][role]['port'])
                    mappings.append({'connection_id': fields[0], 'command': fields[4],
                                     'tcp_endpoint': endpoint, 'worker_socket_match': endpoint in socket_names})
            lock_owner = pool.sql(slot, role, "SELECT IS_USED_LOCK('pekko_capture_" + capture + "')", token) if role == 'state' and capture else None
            performance_schema = pool.sql(slot, role, 'SELECT @@performance_schema', token).strip()
            result['instances'][role] = dict(sessions=sessions, transactions=transactions, threads=threads,
                                            attributes=attrs, identity=identity, socket_mappings=mappings,
                                            owner_lock_connection=lock_owner, performance_schema=performance_schema)
            result['idle'] = result['idle'] and not sessions.strip() and not transactions.strip()
        return result

    before = snapshot()
    write_json(a.output / 'before.json', before)
    if not before['idle']:
        raise RuntimeError('slot not idle: no process or SQL will be started')
    cases = []

    def run(label, mode, schema, capture, heartbeat, witness=False):
        verify_candidate(a.candidate)
        admission = pool.verify_slot(slot, token)
        if not admission['admitted']:
            raise RuntimeError('slot identity changed')
        folder = a.output / label
        folder.mkdir()
        write_json(folder / 'admission.json', admission)
        argv = [str(a.java), '-Xmx512m', '-Duser.timezone=UTC', '-cp', cp,
                'cn.xuyinyin.magic.workflow.integration.CaptureAdmissionReleaseProbe',
                mode, schema, capture, str(heartbeat), str(folder.resolve())]
        case_env = env.copy()
        case_env['CAPTURE_PROBE_WITNESS'] = '1' if witness else '0'
        case_env['CAPTURE_PROBE_QUIET_SECONDS'] = '60' if label.startswith(('sdk-resume','resume-heartbeat')) else '10'
        log = pool.root / ('a0-b0-' + uuid.uuid4().hex + '.log')
        observations = []
        process = None
        result = dict(case=label, schema=schema, capture=capture, heartbeat_ms=heartbeat,
                      witness=witness, started_ns=time.time_ns(), source_sha256=candidate['source_sha256'],
                      runner_sha256=digest(__file__), private_log=str(log))
        try:
            with open(log, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as raw:
                process = subprocess.Popen(argv, stdout=raw, stderr=subprocess.STDOUT,
                                           env=case_env, start_new_session=True)
                result['pid'] = process.pid
                deadline = time.monotonic() + 100
                while process.poll() is None and time.monotonic() < deadline:
                    observations.append(snapshot(process, capture))
                    time.sleep(.25)
                if process.poll() is None:
                    result['cleanup'] = terminate_group(process.pid)
                    raise RuntimeError('worker timed out; owned group only, slot remains leased')
                result['exit_code'] = process.wait()
            result['java_exit_ns'] = time.time_ns()
            # Never start the next JVM merely because the previous one exited.
            deadline = time.monotonic() + 180
            while True:
                observations.append(snapshot(capture=capture))
                if observations[-1]['idle'] or time.monotonic() >= deadline:
                    break
                time.sleep(.5)
            result['after_idle'] = observations[-1]['idle']
            if mode in ('observe', 'observe-sdk'):
                events = read_events(folder / 'events.jsonl')
                result['evidence'] = summarize(events, observations)
                result['expectation_failures'] = check_expected(result['evidence'], events,
                                                                positive=heartbeat > 0, witness=witness,
                                                                sdk_only=mode == 'observe-sdk')
                if result['expectation_failures']:
                    raise RuntimeError('evidence did not meet case expectations; no next case: ' + ','.join(result['expectation_failures']))
            if result['exit_code'] or not result['after_idle']:
                raise RuntimeError('worker failed or physical release unconfirmed; no next case')
        finally:
            if process is not None and process.poll() is None:
                result['cleanup'] = terminate_group(process.pid)
            result['finished_ns'] = time.time_ns()
            write_json(folder / 'result.json', result)
            write_json(folder / 'resources.json', observations)
            if log.exists():
                (folder / 'worker.log').write_text(log.read_text(errors='replace').replace(password, '[REDACTED]'))
            cases.append(result)
            write_json(a.output / ('progress-' + label + '.json'), cases)
        return result

    try:
        cold = uuid.uuid4().hex[:12]
        warm = uuid.uuid4().hex[:12]
        definitions = [('cold', cold)] if a.suite == 'cold' else [('warm', warm)] if a.suite in ('warm', 'sdk') else [('cold', cold), ('warm', warm)]
        for prefix, suffix in definitions:
            run(prefix + '-init', 'init', 'pekko_test_cdc_window_' + suffix, 'a0_' + suffix, 0)
        if a.suite in ('all', 'cold'):
            run('first-heartbeat-0', 'observe', 'pekko_test_cdc_window_' + cold, 'a0_' + cold, 0)
        if a.suite in ('all', 'warm'):
            run('first-heartbeat-1000', 'observe', 'pekko_test_cdc_window_' + warm, 'a0_' + warm, 1000, True)
            run('resume-heartbeat-1000', 'observe', 'pekko_test_cdc_window_' + warm, 'a0_' + warm, 1000)
            run('resume-heartbeat-0', 'observe', 'pekko_test_cdc_window_' + warm, 'a0_' + warm, 0)
        if a.suite == 'sdk':
            run('sdk-first-heartbeat-1000', 'observe-sdk', 'pekko_test_cdc_window_' + warm, 'a0_' + warm, 1000)
            run('sdk-resume-heartbeat-0', 'observe-sdk', 'pekko_test_cdc_window_' + warm, 'a0_' + warm, 0)
            run('sdk-resume-heartbeat-1000', 'observe-sdk', 'pekko_test_cdc_window_' + warm, 'a0_' + warm, 1000)
    finally:
        write_json(a.output / 'summary.json', {'cases': cases, 'after': snapshot(),
                                             'runner_mutates_production_source': False})
    print(json.dumps({'output': str(a.output), 'cases': len(cases)}))


if __name__ == '__main__':
    main()
