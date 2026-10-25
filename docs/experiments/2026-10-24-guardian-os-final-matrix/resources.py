#!/usr/bin/env python3
"""Read-only resource census, exclusively inside the dedicated probe machine."""
import json
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def run(args):
    p = subprocess.run(args, capture_output=True, text=True, timeout=30)
    assert p.returncode == 0, (args[:2], p.stderr)
    return p.stdout


def query(sql):
    root = ET.fromstring(run(['mysql', '--xml', '-e', sql]))
    nil = '{http://www.w3.org/2001/XMLSchema-instance}nil'
    return [{f.attrib['name']: None if f.attrib.get(nil) == 'true' else f.text or ''
             for f in row} for row in root.findall('row')]


def census():
    assert Path('/var/lib/pekko-guardian-os-probe/private/environment.json').exists()
    assert Path('/proc/1/comm').read_text().strip() == 'systemd'
    schemas = [r['SCHEMA_NAME'] for r in query(
        "SELECT SCHEMA_NAME FROM information_schema.schemata ORDER BY SCHEMA_NAME")
        if re.fullmatch(r'pekko_test_cdc_window_[0-9a-f]{12}', r['SCHEMA_NAME'])]
    quotas = {}
    for schema in schemas:
        tables = {r['TABLE_NAME'] for r in query(
            f"SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA='{schema}'")}
        # Restrict to schemas created by this specific OS probe, not earlier suites.
        if 'guardian_os_control' not in tables:
            continue
        quotas[schema] = {table: query(f'SELECT * FROM `{schema}`.`{table}`') for table in (
            'guardian_os_control', 'guardian_os_tickets', 'pekko_table_tasks',
            'pekko_snapshot_owners', 'pekko_snapshot_writer_permissions',
            'pekko_snapshot_owner_instances') if table in tables}
    processes = []
    for path in Path('/proc').iterdir():
        if not path.name.isdigit():
            continue
        try:
            cmd = (path / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace')
            if 'GuardianOsProbe' in cmd or 'SupervisorRecoveryWorker' in cmd:
                processes.append({'pid': path.name, 'command': cmd,
                                  'cgroup': (path / 'cgroup').read_text(),
                                  'stat': (path / 'stat').read_text()})
        except FileNotFoundError:
            pass
    units = run(['systemctl', 'list-units', '--all', '--no-legend', '--plain', 'pekko-osprobe-*'])
    return {'atNs': time.time_ns(), 'hostBoot': Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
            'mysql': query('SELECT VERSION() AS version, @@server_uuid AS server_uuid'),
            'processes': processes, 'units': units,
            'unitDefinitions': sorted(p.name for p in Path('/run/systemd/system').glob('pekko-osprobe-*')),
            'connections': query('SELECT ID,USER,HOST,DB,COMMAND,TIME,STATE FROM information_schema.processlist WHERE ID<>CONNECTION_ID() ORDER BY ID'),
            'transactions': query('SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.innodb_trx ORDER BY trx_mysql_thread_id'),
            'quotaState': quotas}


def data_after_cleanup(matrix):
    assert matrix.parent == Path('/var/lib/pekko-guardian-os-probe/runs')
    assert re.fullmatch(r'matrix-[0-9]+', matrix.name)
    entries = json.loads((matrix / 'result.json').read_text())['cases']
    result = {}
    for entry in entries:
        schema = entry['schema']
        assert re.fullmatch(r'pekko_test_cdc_window_[0-9a-f]{12}', schema)
        statements = [
            'SELECT * FROM source_rows ORDER BY id',
            'SELECT * FROM target_rows ORDER BY id',
            'SELECT * FROM pekko_sync_batch_ledger ORDER BY partition_id,batch_sequence',
            'SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,TO_BASE64(event_payload) AS payload_base64 FROM event_journal ORDER BY persistence_id,sequence_number',
            'SELECT * FROM target_audit ORDER BY audit_id']
        # The worker JDBC runtime is UTC; TIMESTAMP display must use that same zone.
        sql = "SET SESSION time_zone='+00:00'; SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY; " + '; '.join(statements) + '; COMMIT;'
        xml = run(['mysql', '--default-character-set=utf8mb4', '--xml', schema, '-e', sql])
        root = ET.fromstring('<results>' + re.sub(r'<\?xml[^?]*\?>', '', xml) + '</results>')
        nil = '{http://www.w3.org/2001/XMLSchema-instance}nil'
        sets = [[{f.attrib['name']: None if f.attrib.get(nil) == 'true' else f.text or ''
                  for f in row} for row in rs.findall('row')] for rs in root.findall('resultset')]
        assert len(sets) == len(statements)
        result[schema] = dict(zip(('source', 'target', 'T', 'J', 'audit'), sets))
    return {'atNs': time.time_ns(), 'matrix': matrix.name,
            'isolation': 'per-schema repeatable-read read-only consistent snapshot',
            'sessionTimeZone': '+00:00', 'schemas': result}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--matrix', type=Path)
    args = parser.parse_args()
    print(json.dumps(data_after_cleanup(args.matrix) if args.matrix else census(), ensure_ascii=False, indent=2))
