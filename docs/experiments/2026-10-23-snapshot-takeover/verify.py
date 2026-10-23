#!/usr/bin/env python3
"""Offline evidence checks only: no MySQL access, subprocess, fencing, or live recovery."""
from pathlib import Path
import base64, copy, hashlib, io, json, runpy, sys, tarfile, zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sha = lambda value: hashlib.sha256(value).hexdigest()


def validate_case(case, read, cbor):
    load = lambda name: json.loads(read(case['archivePrefix'] + '/' + name))
    result, cut, final = (load(n) for n in ('result.json', 'cut.json', 'final.json'))
    assert result['automaticTakeover'] and result['sameCluster'] and result['D'] == 'N/A'
    data, ha = final['data'], final['ha']
    comparison = data['comparison']
    keys = {'-9', '1', '2', '3', '4', '5', '6', '7', '8', '9007199254740993'}
    fields = {'id', 'label', 'amount', 'note', 'updated_at', 'revision'}
    source, target = comparison['source'], comparison['target']
    assert len(source) == len(target) == 10
    assert all(set(row) == fields for row in source + target)
    left, right = ({r['id']: r for r in rows} for rows in (source, target))
    assert set(left) == set(right) == keys and left == right
    assert comparison['equal'] and not any(comparison[k] for k in ('missingKeys', 'extraKeys', 'fieldDifferences'))
    assert len(data['audit']) == 10 and {r['row_id'] for r in data['audit']} == keys
    assert all(r['op'] == 'I' for r in data['audit'])

    def journal(state):
        rows = [r for r in state['J'] if r['persistence_id'] == 'workflow-table-00']
        assert [int(r['sequence_number']) for r in rows] == list(range(1, len(rows) + 1))
        return [cbor(base64.b64decode(r['payload_base64'])) for r in rows]

    def pages(state):
        return [r for r in journal(state) if r.get('update', {}).get('_type', '').endswith('.CommitSnapshotPage')]

    ledger = {r['batch_id']: r for r in data['T']}
    assert len(pages(data)) == len(data['T']) == len(data['digests']) == len(ledger) == 5
    assert set(ledger) == {r['batch_id'] for r in data['digests']}
    for event in pages(data):
        cp = event['update']['checkpoint']; t = ledger[cp['batchId']]
        assert cp['batchId'] == sha(f"{event['executionId']}|{cp['sourceNodeId']}|{cp['partitionId']}|{cp['batchSequence']}".encode())
        assert event['executionId'] == t['execution_id']
        assert (cp['partitionId'], cp['batchSequence']) == (t['partition_id'], int(t['batch_sequence']))
        assert (cp['cursor']['kind'], cp['cursor']['value'], cp['cursor']['upperBound']) == (t['cursor_kind'], t['cursor_value'], t['upper_bound'])
        assert cp['sourceRowsScanned'] == cp['targetRowsWritten'] == int(t['source_rows']) == int(t['target_rows']) == 2
    expected = {'KILL_T_BEFORE_J': (1, 0), 'KILL_DB_JOURNAL_WAIT': (1, 0), 'KILL_J_AFTER_STORE': (1, 1)}
    assert (len(cut['data']['T']), len(pages(cut['data']))) == expected.get(result['scenario'], (0, 0))
    task = ha['tasks'][0]; old = cut['ha']['tasks'][0]
    assert task['phase'] == 'completed' and task['execution_id'] == old['execution_id']
    permission = ha['permissions'][0]
    assert permission['execution_id'] == task['execution_id']
    assert int(permission['generation']) == (3 if result['scenario'] == 'B_RECOVERY_CRASH' else 2)
    assert permission['owner_id'] != old['owner_id']
    audit = ha['audit']; sequence = lambda r: int(r['event_sequence'])
    assert len({sequence(r) for r in audit}) == len(audit)
    for fence in (r for r in audit if r['event_type'] == 'fenced'):
        owner = fence['owner_id']; proof = json.loads(fence['details'])
        assert proof['owner'] == owner and proof['provider'] == 'managed-child-jvm'
        assert proof['details']['alive'] is False and proof['details']['automaticRestart'] is False
        identity = next(r for r in ha['owners'] if r['owner_id'] == owner)
        assert str(proof['pid']) == identity['process_id'] and proof['startIdentity'] == identity['start_identity']
        assert proof['operation'] == identity['operation_id'] and proof['deployment'] == identity['deployment_id']
        owned = [r for r in audit if r['owner_id'] == owner]
        drained = next(r for r in owned if r['event_type'] == 'db_drained')
        observation = json.loads(drained['details'])
        assert sequence(fence) < sequence(drained)
        assert observation['complete'] and observation['emptyRounds'] == 2
        assert observation['sessions'] == observation['transactions'] == observation['unattributed'] == 0
        prior = [json.loads(r['details']) for r in owned if r['event_type'] == 'database_observed' and sequence(r) < sequence(drained)]
        assert any(r['complete'] and r['emptyRounds'] == 1 for r in prior)
        for release in (r for r in owned if r['event_type'] == 'old_quota_released'):
            assert sequence(drained) < sequence(release)
    permitted = [r for r in audit if r['event_type'] == 'recovery_permitted']
    assert ha['journalWrites'] and len(ha['journalWrites']) == len(journal(data))
    for write in ha['journalWrites']:
        permit = next(r for r in permitted if r['owner_id'] == write['owner_id'] and json.loads(r['details'])['permit'] == write['permit_id'])
        assert int(write['admission_sequence']) >= sequence(permit)
        assert int(write['generation']) == json.loads(permit['details'])['generation']
    final_permit = next(r for r in permitted if json.loads(r['details'])['permit'] == permission['permit_id'])
    assert sequence(final_permit) > max(sequence(r) for r in audit if r['event_type'] == 'old_quota_released')
    assert all(not row['alive'] for row in load('workers-after-cleanup.json'))
    assert load('database-cleanup.json') == [{'clients': '0'}]
    if result['scenario'].startswith('KILL_DB_'):
        observations = load('post-exit-observations.json')
        role = 'target' if result['scenario'] == 'KILL_DB_TARGET_WAIT' else 'journal'
        assert any(o['sessions'] and o['transactions'] and any(s['role'] == role and s['state'] == 'User lock' for s in o['sessions']) and
                   {t['trx_mysql_thread_id'] for t in o['transactions']} & {s['id'] for s in o['sessions']} for o in observations)
        held = load('dead-with-server-work.json')['ha']['tasks'][0]
        assert held['grant_id'] == old['grant_id'] and held['owner_id'] == old['owner_id']
    if result['scenario'] == 'STALE_GRANT':
        before, after = load('stale-grant-before.json'), load('stale-grant-after.json')
        assert before['T'] == after['T'] and before['J'] == after['J']
    if result['scenario'] == 'OBSERVER_SEPARATE_ACCOUNT':
        assert any(o['unattributed'] for o in load('separate-observer-unattributed.json'))
    if result['scenario'] == 'CRON_RECOVERY':
        triggers = load('cron-final.json')['ha']['scheduleTriggers']
        assert [r['disposition'] for r in triggers] == ['accepted', 'skipped']
        assert [r['scheduled_at'] for r in triggers] == ['1000', '2000']
        started = [e for e in journal(data) if e.get('executionId') == task['execution_id'] and 'trigger' in e]
        assert len(started) == 1
        assert started[0]['trigger'] == dict(kind='scheduled', requestId=None, scheduleId='cron', scheduledAt=1000, triggerId='cron-1000')
    if result['scenario'] == 'KILL_BOOTSTRAP':
        initial = load('bootstrap-owner-and-manifest.json')
        assert len(initial['owners']) == 2 and all(r['manifest_version'] != '0' for r in initial['owners'])
        assert len(load('bootstrap-drained.json')['owners']) == 2
    if result['scenario'] == 'STARTUP_COMMIT_REPLY_LOST':
        assert any(r['phase'] == 'startup_failed' for r in load('startup-reply-lost.json')['owners'])
    return 10


def main():
    for line in (HERE / 'SHA256SUMS').read_text().splitlines():
        digest, path = line.split('  ', 1)
        assert sha((HERE / path).read_bytes()) == digest, path
    report = json.loads((HERE / 'result.json').read_text())
    manifest = json.loads((HERE / 'archive-manifest.json').read_text())
    files = {}
    parts = json.loads((HERE / 'archive-parts.json').read_text())
    packed = b''.join((HERE / part['file']).read_bytes() for part in parts['parts'])
    assert len(packed) == parts['bytes'] and sha(packed) == parts['sha256']
    for part in parts['parts']:
        assert sha((HERE / part['file']).read_bytes()) == part['sha256']
    with tarfile.open(fileobj=io.BytesIO(packed), mode='r:gz') as archive:
        for item in archive.getmembers():
            assert item.isfile() and not Path(item.name).is_absolute() and '..' not in Path(item.name).parts
            data = archive.extractfile(item).read()
            assert sha(data) == manifest[item.name] and item.name not in files
            files[item.name] = data
    assert set(files) == set(manifest)
    current = '--archived-source' not in sys.argv
    source = json.loads((HERE / 'source-manifest.json').read_text())
    runtime = 'runners/' + report['selectedRuntime'] + '/'
    assert json.loads(files[runtime + 'source-manifest.json']) == source
    with zipfile.ZipFile(io.BytesIO(files[runtime + 'source.zip'])) as archive:
        assert set(archive.namelist()) == set(source)
        for path, digest in source.items():
            assert sha(archive.read(path)) == digest, path
            if current: assert sha((REPO / path).read_bytes()) == digest, path
    for key in report['regressionRuntimes'].values():
        assert json.loads(files['runners/' + key + '/source-manifest.json']) == source
        assert json.loads(files['runners/' + key + '/result.json'])['exitCode'] == 0
    assert json.loads(files[runtime + 'result.json'])['exitCode'] == 0
    cbor = runpy.run_path(str(HERE.parent / '2026-10-22-table-scheduling/verify.py'))['cbor']
    assert len(report['cases']) == 22 and len({c['case'] for c in report['cases']}) == 22
    rows = sum(validate_case(case, files.__getitem__, cbor) for case in report['cases'])
    for case in report['cases']:
        assert json.loads(files[case['archivePrefix'] + '/result.json'])['runtime'] == report['selectedRuntime']
    regression_counts = {'converged': 0, 'safe_pause': 0}
    for suite, stats in report['regressions'].items():
        assert len(stats['outcomes']) == stats['tests']
        for entry in stats['outcomes']:
            outcome = entry['outcome']
            negative = outcome.get('classification') == 'safe_pause' or outcome.get('automaticRecovery') == 'SAFE_BLOCK'
            regression_counts['safe_pause' if negative else 'converged'] += 1
            if not negative:
                name = 'regressions/' + entry['directory'].removeprefix('target/') + '/final.json'
                comparison = json.loads(files[name])['comparison']
                left, right = ({r['id']: r for r in comparison[k]} for k in ('source', 'target'))
                assert left == right and comparison['equal']
                assert len(left) == len(comparison['source']) and len(right) == len(comparison['target'])
                assert len(left) == comparison['sourceCount'] == comparison['targetCount']
                assert not any(comparison[k] for k in ('missingKeys', 'extraKeys', 'fieldDifferences'))
    log = files['logs/backend-test-package-final.log'].decode(errors='replace')
    assert f"Tests: succeeded {report['backendTests']}, failed 0, canceled 0, ignored 0, pending 0" in log and 'All tests passed.' in log
    assert report['cleanup']['clientsExcludingObserverAndInternalDaemon'] == report['cleanup']['transactions'] == report['cleanup']['temporaryAccounts'] == 0
    assert report['cleanup']['stopped'] and report['cleanup']['containersAndVolumesRetained']
    case = next(c for c in report['cases'] if c['case'] == 'KILL_DB_TARGET_WAIT')
    negatives = 0
    for name, mutate in [
        ('final.json', lambda v: v['data']['comparison']['target'][0].update(note='corrupted')),
        ('post-exit-observations.json', lambda v: [o.update(transactions=[]) for o in v]),
        ('final.json', lambda v: next(r for r in v['ha']['audit'] if r['event_type'] == 'fenced').update(details=json.dumps({'details': {'alive': True}}))),
    ]:
        path = case['archivePrefix'] + '/' + name
        value = copy.deepcopy(json.loads(files[path])); mutate(value)
        def read(p): return json.dumps(value).encode() if p == path else files[p]
        try: validate_case(case, read, cbor)
        except (AssertionError, KeyError, StopIteration): negatives += 1
        else: raise AssertionError('corrupted evidence accepted: ' + name)
    print(json.dumps(dict(archiveIntegrity=True, currentSourceChecked=current, scenarios=22, comparedRows=rows,
                         fieldsPerRow=6, regressions=regression_counts, negativeControlsRejected=negatives,
                         scope='offline evidence; no live experiment rerun')))


if __name__ == '__main__': main()
