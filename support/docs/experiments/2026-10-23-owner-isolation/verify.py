#!/usr/bin/env python3
"""Offline archive/row/Journal validation. Never starts a process or connects to MySQL."""
from pathlib import Path
import base64
import copy
import hashlib
import io
import json
import runpy
import sys
import tarfile
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sha = lambda data: hashlib.sha256(data).hexdigest()


def validate_case(case, read, cbor):
    prefix = case['archivePrefix'] + '/'
    load = lambda name: json.loads(read(prefix + name))
    raw = load('result.json')
    assert raw['passed'] and not raw['automaticTakeover'] and raw['D'] == 'N/A'
    cut, before, end = (load(n) for n in ('cut.json', 'before-test-requeue.json', 'final.json'))
    observations, events = load('observations.json'), load('events.json')
    keys = {'-9', '1', '2', '3', '4', '5', '6', '7', '8', '9007199254740993'}
    fields = {'id', 'label', 'amount', 'note', 'updated_at', 'revision'}
    comparison = end['comparison']
    source, target = comparison['source'], comparison['target']
    assert len(source) == len(target) == 10
    assert all(set(row) == fields for row in source + target)
    left, right = ({row['id']: row for row in rows} for rows in (source, target))
    assert set(left) == set(right) == keys and left == right
    assert comparison['equal'] and not comparison['missingKeys'] and not comparison['extraKeys'] and not comparison['fieldDifferences']
    assert len(end['audit']) == 10 and all(row['op'] == 'I' for row in end['audit'])
    assert {row['row_id'] for row in end['audit']} == keys

    def pages(state):
        rows = [r for r in state['J'] if r['persistence_id'] == 'workflow-table-00']
        assert [int(r['sequence_number']) for r in rows] == list(range(1, len(rows) + 1))
        decoded = [cbor(base64.b64decode(r['payload_base64'])) for r in rows]
        return [e for e in decoded if e.get('update', {}).get('_type', '').endswith('.CommitSnapshotPage')]

    final_pages = pages(end)
    assert len(final_pages) == len(end['T']) == len(end['digests']) == 5
    ledger = {row['batch_id']: row for row in end['T']}
    assert len(ledger) == 5 and {row['batch_id'] for row in end['digests']} == set(ledger)
    for event in final_pages:
        cp = event['update']['checkpoint']; t = ledger[cp['batchId']]
        assert cp['batchId'] == sha(f"{event['executionId']}|{cp['sourceNodeId']}|{cp['partitionId']}|{cp['batchSequence']}".encode())
        assert event['executionId'] == t['execution_id']
        assert (cp['sourceNodeId'], cp['partitionId'], cp['batchSequence']) == (t['source_node_id'], t['partition_id'], int(t['batch_sequence']))
        assert (cp['cursor']['kind'], cp['cursor']['value'], cp['cursor']['upperBound']) == (t['cursor_kind'], t['cursor_value'], t['upper_bound'])
        assert cp['sourceRowsScanned'] == cp['targetRowsWritten'] == int(t['source_rows']) == int(t['target_rows']) == 2
    expected_cut = {'KILL_T_BEFORE_J': (1, 0), 'KILL_DB_JOURNAL_WAIT': (1, 0), 'KILL_J_AFTER_STORE': (1, 1)}
    assert (len(cut['T']), len(pages(cut))) == expected_cut.get(case['case'], (0, 0))
    assert (len(before['T']), len(pages(before))) == (len(cut['T']), len(pages(cut)))
    assert end['D'].startswith('N/A') and end['control'][0]['phase'] == 'completed'
    assert before['control'][0]['grant_id'] == cut['control'][0]['grant_id']
    assert before['control'][0]['owner_id'] == case['originalOwner']
    requeue = load('test-operator-requeue.json')
    assert not requeue['automatic'] and not requeue['productionProtocolChanged'] and requeue['processDead']
    assert requeue['oldOwner'] == case['originalOwner'] and requeue['oldGrant'] == cut['control'][0]['grant_id']
    assert case['originalOwner'] != case['recoveredOwner'] and case['originalPid'] != case['recoveredPid']
    assert not load('process-after-kill.json')['alive']
    assert all(not load('workers-after-cleanup.json')[who]['alive'] for who in ('a', 'b'))
    assert load('principal-cleanup.json')['passed']
    assert load('principal-cleanup.json')['temporaryPrincipalsRemoved'] == (1 if case['case'] == 'OBSERVER_DENIED' else 0)
    assert any(e['kind'] == 'OLD_BIND_RESULT' and not e['allowed'] for e in events['b'])
    a_ids = {e['executionId'] for e in events['a'] if e['kind'] == 'ENGINE_STARTED'}
    b_ids = {e['executionId'] for e in events['b'] if e['kind'] == 'ENGINE_STARTED'}
    assert len(a_ids) == 1 and a_ids == b_ids
    if case['case'] in ('KILL_T_BEFORE_J', 'KILL_DB_JOURNAL_WAIT'):
        assert any(e['kind'] == 'T_CONFIRMED' and e['replay'] for e in events['b'])
    if case['case'] == 'KILL_J_AFTER_STORE':
        first_read = next(e for e in events['b'] if e['kind'] == 'READER_OPEN')
        assert first_read['resume'] == '1'
        assert not any(e['kind'] == 'T_CONFIRMED' and e['replay'] for e in events['b'])
    first_dead = next(o for o in observations if o['stage'] == 'process-dead-first-observation')
    assert not first_dead['process']['alive'] and first_dead['database']['complete']
    if case['case'].startswith('KILL_DB_'):
        db = first_dead['database']
        assert db['oldSessions'] and db['oldTransactions'], 'missing real post-exit SQL witness'
        role = 'target' if case['case'] == 'KILL_DB_TARGET_WAIT' else 'journal'
        assert any(row['role'] == role and row['state'] == 'User lock' for row in db['oldSessions'])
        ids = {row['id'] for row in db['oldSessions']}
        assert any(row['trx_mysql_thread_id'] in ids for row in db['oldTransactions'])
        assert raw['serverWorkObservedAfterProcessDeath']
    drains = [o for o in observations if o['stage'] == 'waiting-for-confirmed-database-release']
    assert len(drains) >= 2
    for o in drains[-2:]:
        db = o['database']
        assert not o['process']['alive'] and db['complete'] and not db['oldSessions'] and not db['oldTransactions'] and not db['unattributed']
    if case['case'] == 'SIGSTOP_READ':
        proc = load('sigstop-process.json')
        assert proc['alive'] and 'T' in proc['ps'].split()[2]
        assert all(o['process']['alive'] for o in observations if o['stage'].startswith('sigstop'))
    if case['case'] == 'OBSERVER_DENIED':
        assert not load('observer-denied.json')['complete']
    return 10


def main():
    for line in (HERE / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        assert sha((HERE / name).read_bytes()) == digest, name
    report = json.loads((HERE / 'result.json').read_text())
    manifest = json.loads((HERE / 'archive-manifest.json').read_text())
    with tarfile.open(HERE / 'evidence.tar.gz', 'r:gz') as archive:
        files = {}
        for item in archive.getmembers():
            assert item.isfile() and not Path(item.name).is_absolute() and '..' not in Path(item.name).parts
            data = archive.extractfile(item).read()
            assert sha(data) == manifest[item.name]
            assert item.name not in files
            files[item.name] = data
    assert set(files) == set(manifest)
    current = '--archived-source' not in sys.argv
    if current:
        for path, digest in json.loads((HERE / 'source-manifest.json').read_text()).items():
            assert sha((REPO / path).read_bytes()) == digest, path
    boundary = json.loads((HERE / 'production-source-boundary.json').read_text())
    for path, digest in boundary['files'].items():
        assert sha(files['production/' + path]) == digest, path
        if current:
            assert sha((REPO / path).read_bytes()) == digest, path
    runtime_manifest = json.loads(files['runners/' + report['selectedRuntime'] + '/source-manifest.json'])
    assert runtime_manifest == json.loads((HERE / 'source-manifest.json').read_text())
    with zipfile.ZipFile(io.BytesIO(files['runners/' + report['selectedRuntime'] + '/source.zip'])) as archive:
        assert set(archive.namelist()) == set(runtime_manifest)
        for path, digest in runtime_manifest.items():
            assert sha(archive.read(path)) == digest, path
    assert all(runtime_manifest[path] == digest for path, digest in boundary['files'].items() if path in runtime_manifest)
    cbor = runpy.run_path(str(HERE.parent / '2026-10-22-table-scheduling/verify.py'))['cbor']
    rows = sum(validate_case(case, files.__getitem__, cbor) for case in report['cases'])
    assert len(report['cases']) == 8 and len({c['case'] for c in report['cases']}) == 8
    log = files['logs/backend-test-package-final.log'].decode(errors='replace')
    assert 'Total number of tests run: 537' in log and 'Suites: completed 98, aborted 0' in log
    assert 'Tests: succeeded 537, failed 0, canceled 0, ignored 0, pending 0' in log and 'All tests passed.' in log
    cleanup = report['cleanup']
    assert cleanup['clientsExcludingObserverAndInternalDaemon'] == cleanup['innodbTransactionsBeforeStop'] == cleanup['testObserverPrincipalsRemaining'] == 0
    assert cleanup['stopped'] and cleanup['containersAndVolumesRetained']
    # Prove the evidence oracle fails on corrupted copies; never alter the original evidence.
    negative = 0
    wait_case = next(c for c in report['cases'] if c['case'] == 'KILL_DB_TARGET_WAIT')
    for filename, mutation in [
        ('observations.json', lambda v: next(o for o in v if o['stage'] == 'process-dead-first-observation')['database'].update(oldTransactions=[])),
        ('final.json', lambda v: v['comparison']['target'][0].update(note='corrupted field')),
        ('test-operator-requeue.json', lambda v: v.update(automatic=True)),
    ]:
        path = wait_case['archivePrefix'] + '/' + filename
        changed = copy.deepcopy(json.loads(files[path])); mutation(changed)
        def read(name):
            return json.dumps(changed).encode() if name == path else files[name]
        try:
            validate_case(wait_case, read, cbor)
        except AssertionError:
            negative += 1
        else:
            raise AssertionError('negative control was accepted: ' + filename)
    print(json.dumps(dict(archiveIntegrity=True, currentSourceChecked=current, scenarios=8,
        comparedRowsAcrossCases=rows, fieldsPerRow=6, negativeControlsRejected=negative,
        scope='offline evidence validation; not a live rerun or production takeover proof')))


if __name__ == '__main__':
    main()
