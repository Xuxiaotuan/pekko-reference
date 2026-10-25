#!/usr/bin/env python3
"""Offline verification of one frozen 12-case run. No live process or SQL operations."""
from pathlib import Path
import argparse
import copy
import hashlib
import io
import json
import os
import runpy
import tarfile
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sha = lambda data: hashlib.sha256(data).hexdigest()
SCENARIOS = {'G5_BEFORE_FORK', 'G6_FORK_UNRECORDED', 'G7_START_ACK_LOST',
             'G7_EXECUTED_ACK_LOST', 'G10_SOURCE', 'G10_TARGET', 'G10_JOURNAL',
             'G10_SNAPSHOT', 'G14_CONTROL_DENIED', 'G14_SIGNAL_ONLY',
             'G15_LEGACY_UNBOUND', 'G15_UNIT_MISSING'}


def resources(before, after, entries, read):
    assert before['mysql'] == after['mysql']
    assert before['hostBoot'] == after['hostBoot']
    assert not after['processes'] and not after['transactions'] and not after['unitDefinitions']
    assert all(c['USER'] == 'event_scheduler' and c['COMMAND'] == 'Daemon'
               for c in after['connections'])
    # Failed, unloaded systemd tombstones are retained; no running unit is acceptable.
    units = after['units'].splitlines()
    names = {name for e in entries for name in (
        'pekko-osprobe-' + e['schema'].rsplit('_', 1)[1] + '-old.service',
        'pekko-osprobe-' + e['schema'].rsplit('_', 1)[1] + '-new.service')}
    assert all(line.split()[0] in names and line.split()[1:4] == ['not-found', 'failed', 'failed']
               for line in units)
    for schema, value in before['quotaState'].items():
        assert after['quotaState'][schema] == value
    assert set(after['quotaState']) - set(before['quotaState']) == {e['schema'] for e in entries}
    released = retained = 0
    for entry in entries:
        directory = entry['scenario'] + '-' + entry['schema'].rsplit('_', 1)[1]
        q = after['quotaState'][entry['schema']]
        state = 'final.json' if entry['outcome'] == 'CONVERGED' else 'sealed.json'
        accepted = json.loads(read('matrix/' + directory + '/' + state))
        # Preserve every identity and row, not just the first task or a nonempty grant.
        for table, key in (('pekko_table_tasks', None), ('pekko_snapshot_owners', 'owners'),
                           ('pekko_snapshot_writer_permissions', 'permissions'),
                           ('guardian_os_tickets', 'tickets'), ('guardian_os_control', 'host'),
                           ('pekko_snapshot_owner_instances', 'instances')):
            expected = accepted['data']['control'] if key is None else accepted[key]
            assert q[table] == expected, (entry['scenario'], table)
        task = q['pekko_table_tasks'][0]
        tickets = q['guardian_os_tickets']
        if entry['outcome'] == 'CONVERGED':
            assert task['phase'] == 'completed' and task['grant_id'] is None and task['owner_id'] is None
            assert q['pekko_snapshot_writer_permissions'][0]['phase'] == 'parked'
            assert {t['guardian']: t['phase'] for t in tickets} == {'old': 'retired', 'new': 'launched'}
            released += 1
        else:
            sealed = json.loads(read('matrix/' + directory + '/sealed.json'))
            assert task['phase'] == 'running' and task['grant_id'] and task['owner_id'] == entry['oldBoot']
            assert q['pekko_snapshot_owners'] == sealed['owners']
            assert q['pekko_snapshot_writer_permissions'] == sealed['permissions']
            assert len(tickets) == 1 and tickets[0]['phase'] == 'sealed'
            retained += 1
    assert released == 8 and retained == 4
    return {'probeProcesses': 0, 'probeConnections': 0, 'transactions': 0,
            'failedUnitTombstones': len(units), 'completedTaskGrantsCleared': released,
            'pausedTaskGrantsRetained': retained, 'newLabTicketsStillLaunched': released,
            'oldControlQuotaSnapshotsUnchanged': len(before['quotaState'])}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--archived-source', action='store_true')
    args = p.parse_args()
    for line in (HERE / 'SHA256SUMS').read_text().splitlines():
        expected, name = line.split('  ', 1)
        assert '/' not in name and sha((HERE / name).read_bytes()) == expected, name
    manifest = json.loads((HERE / 'archive-manifest.json').read_text())
    archive = (HERE / 'evidence.tar.gz').read_bytes()
    assert sha(archive) == manifest['archiveSha256']
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
        members = tar.getmembers()
        assert all(m.isfile() for m in members) and len({m.name for m in members}) == len(members)
        files = {m.name: tar.extractfile(m).read() for m in members}
    assert set(files) == set(manifest['files'])
    for name, data in files.items():
        assert sha(data) == manifest['files'][name], name
    read = files.__getitem__
    load = lambda name: json.loads(read(name))
    frozen = json.loads((HERE / 'frozen-runtime.json').read_text())
    assert load('matrix/source-manifest.json') == frozen['sourceManifest']
    assert load('matrix/runtime-manifest.json') == frozen['runtimeManifest']
    for name in ('classes.jar', 'test-classes.jar'):
        assert sha(read('runtime/' + name)) == frozen['runtimeManifest'][name]
    with zipfile.ZipFile(io.BytesIO(read('runtime/source.zip'))) as z:
        assert set(z.namelist()) == set(frozen['sourceManifest'])
        for name, expected in frozen['sourceManifest'].items():
            assert sha(z.read(name)) == expected
            if not args.archived_source:
                assert sha((REPO / name).read_bytes()) == expected, name
    # Materialize only these pinned Python files so their original relative imports work.
    with tempfile.TemporaryDirectory() as temporary:
        for name in files:
            if name.startswith('verifiers/'):
                relative = Path(name).relative_to('verifiers')
                assert len(relative.parts) == 2 and relative.name == 'verify.py'
                path = Path(temporary) / 'docs/experiments' / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(read(name))
        legacy = runpy.run_path(str(Path(temporary) / 'docs/experiments/2026-10-24-guardian-os-restart/verify.py'))
    environment = load('matrix/environment.json')
    result = load('matrix/result.json')
    entries = result['cases']
    assert len(entries) == result['accepted'] == result['total'] == 12
    assert {e['scenario'] for e in entries} == SCENARIOS
    assert environment['runtime'] == frozen['runtime']
    outcomes = []
    for entry in entries:
        assert entry['runtime'] == frozen['runtime']
        directory = entry['scenario'] + '-' + entry['schema'].rsplit('_', 1)[1]
        case_read = lambda name: read('matrix/' + directory + '/' + name)
        assert json.loads(case_read('result.json')) == entry
        outcomes.append(legacy['case'](case_read, environment['publicKey']))
    assert sum(o[0] == 'CONVERGED' for o in outcomes) == result['converged'] == 8
    assert sum(o[0] == 'SAFE_PAUSE' for o in outcomes) == result['paused'] == 4
    assert sum(o[1] for o in outcomes) == 80
    census = resources(load('resources-before.json'), load('resources-after.json'), entries, read)
    post = load('data-after-cleanup.json')
    assert post['sessionTimeZone'] == '+00:00'
    assert set(post['schemas']) == {e['schema'] for e in entries}
    for entry in entries:
        directory = entry['scenario'] + '-' + entry['schema'].rsplit('_', 1)[1]
        state = 'final.json' if entry['outcome'] == 'CONVERGED' else 'sealed.json'
        accepted = load('matrix/' + directory + '/' + state)['data']
        actual = post['schemas'][entry['schema']]
        for field in ('T', 'J', 'audit'):
            assert actual[field] == accepted[field], (directory, field)
        for field in ('source', 'target'):
            assert actual[field] == accepted['comparison'][field], (directory, field)
    # The verifier must reject both physical residuals and fabricated quota release.
    controls = 0
    for kind in ('process', 'quota', 'changed-original-grant', 'missing-positive-owners', 'extra-running-task'):
        altered = copy.deepcopy(load('resources-after.json'))
        if kind == 'process':
            altered['processes'].append({'pid': 'unconfirmed'})
        elif kind in ('quota', 'changed-original-grant'):
            paused = next(e for e in entries if e['outcome'] == 'SAFE_PAUSE')
            altered['quotaState'][paused['schema']]['pekko_table_tasks'][0]['grant_id'] = (
                None if kind == 'quota' else 'not-original-grant')
        elif kind == 'missing-positive-owners':
            completed = next(e for e in entries if e['outcome'] == 'CONVERGED')
            altered['quotaState'][completed['schema']]['pekko_snapshot_owners'] = []
        else:
            completed = next(e for e in entries if e['outcome'] == 'CONVERGED')
            tasks = altered['quotaState'][completed['schema']]['pekko_table_tasks']
            extra = copy.deepcopy(tasks[0])
            extra.update(workflow_id='unexpected-task', phase='running',
                         owner_id='unexpected-owner', grant_id='unexpected-grant')
            tasks.append(extra)
        try:
            resources(load('resources-before.json'), altered, entries, read)
        except AssertionError:
            controls += 1
        else:
            raise AssertionError('corruption accepted: ' + kind)
    boundary = json.loads((HERE / 'source-boundary.json').read_text())
    if not args.archived_source:
        for name, expected in boundary['preservedTrackedFiles'].items():
            assert sha((REPO / name).read_bytes()) == expected, name
        for name, target in boundary['preservedSymlinks'].items():
            assert os.readlink(REPO / name) == target
        for name, expected in boundary['previousPublicMaterials'].items():
            assert sha((REPO / name).read_bytes()) == expected, name
    backend = read('logs/backend.log').decode(errors='replace')
    assert 'Tests: succeeded 586, failed 0' in backend and 'Suites: completed 111, aborted 0' in backend
    assert 'All tests passed.' in backend
    assert backend.count('[success] Total time:') == 2  # test and package
    actor = read('logs/actor-sbt.log').decode(errors='replace')
    assert 'Suites: completed 1, aborted 0' in actor and 'Tests: succeeded 1, failed 0' in actor
    diagnostics = []
    for mode in ('shared', 'per-message'):
        for count in (100000, 1000000):
            events = [json.loads(line) for line in read(f'actor/actor-{mode}-{count}.jsonl').splitlines()]
            phases = {e['phase']: e for e in events}
            assert phases['START']['shutdownDefault'] == '10s'
            assert phases['START']['mode'] == mode and phases['START']['messages'] == count
            assert phases['BEFORE_TERMINATE']['createdReplyProbes'] == (count if mode == 'per-message' else 1)
            assert phases['TEN_SECOND_BOUNDARY']['terminatedWithin10s'] and phases['FINAL']['terminated']
            diagnostics.append({'mode': mode, 'messages': count,
                                'heapUsed': phases['BEFORE_TERMINATE']['memory']['heapUsed'],
                                'shutdownMillis': phases['FINAL']['elapsedMillis']})
    with zipfile.ZipFile(io.BytesIO(read('runtime/classes.jar'))) as production, \
            zipfile.ZipFile(io.BytesIO(read('runtime/test-classes.jar'))) as test, \
            zipfile.ZipFile(io.BytesIO(read('build/package.jar'))) as package:
        prod = {n: production.read(n) for n in production.namelist() if n.endswith('.class')}
        assert len(prod) == 859
        assert {n: package.read(n) for n in package.namelist() if n.endswith('.class')} == prod
        assert not (set(n for n in test.namelist() if n.endswith('.class')) & set(package.namelist()))
    print(json.dumps({'sameFrozenVersion': frozen['runtime'], 'acceptedCases': 12, 'converged': 8,
                      'safePause': 4, 'rows': 80, 'fieldValues': 480, 'D': 'N/A',
                      'resources': census, 'resourceCorruptionControlsRejected': controls,
                      'postCleanupTJournalAuditAndAllFieldsUnchanged': 12,
                      'backendTests': 586, 'backendSuitesAborted': 0, 'productionClasses': 859,
                      'productionGuardianRestartImplemented': False,
                      'actorSpecOriginalTimeoutReproducedThisRound': False,
                      'actorDiagnostics': diagnostics,
                      'noLiveOperations': True}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
