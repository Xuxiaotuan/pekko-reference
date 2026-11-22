#!/usr/bin/env python3
"""Offline evidence checks. No MySQL, process control, private keys, or recovery operations."""
from pathlib import Path
import base64, copy, hashlib, io, json, runpy, sys, tarfile, zipfile
from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.exceptions import InvalidSignature

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sha = lambda value: hashlib.sha256(value).hexdigest()


def equal_rows(comparison):
    left, right = ({r['id']: r for r in comparison[k]} for k in ('source', 'target'))
    assert len(left) == len(comparison['source']) and len(right) == len(comparison['target'])
    assert left == right and comparison['equal']
    assert len(left) == comparison['sourceCount'] == comparison['targetCount']
    fields = {'id', 'label', 'amount', 'note', 'updated_at', 'revision'}
    assert all(set(r) == fields for r in comparison['source'] + comparison['target'])
    assert not any(comparison[k] for k in ('missingKeys', 'extraKeys', 'fieldDifferences'))
    return len(left)


def signed(envelope, binding):
    key = load_der_public_key(base64.b64decode(binding['publicKey']))
    key.verify(base64.b64decode(envelope['signature']), envelope['payload'].encode())
    value = json.loads(envelope['payload'])
    assert value['guardian'] == binding
    return value


def pages(data, cbor):
    records = [r for r in data['J'] if r['persistence_id'] == 'workflow-table-00']
    assert [int(r['sequence_number']) for r in records] == list(range(1, len(records) + 1))
    values = [cbor(base64.b64decode(r['payload_base64'])) for r in records]
    return [r for r in values if r.get('update', {}).get('_type', '').endswith('.CommitSnapshotPage')]


def check_ledger(data, execution, cbor):
    ledger = {r['batch_id']: r for r in data['T']}
    committed = pages(data, cbor)
    assert len(ledger) == len(data['T']) == len(data['digests']) == len(committed) == 5
    assert set(ledger) == {r['batch_id'] for r in data['digests']}
    for event in committed:
        cp = event['update']['checkpoint']; row = ledger[cp['batchId']]
        assert event['executionId'] == row['execution_id'] == execution
        assert cp['batchId'] == sha(f"{execution}|{cp['sourceNodeId']}|{cp['partitionId']}|{cp['batchSequence']}".encode())
        assert (cp['partitionId'], cp['batchSequence']) == (row['partition_id'], int(row['batch_sequence']))
        assert (cp['cursor']['value'], cp['cursor']['upperBound']) == (row['cursor_value'], row['upper_bound'])
        assert cp['sourceRowsScanned'] == cp['targetRowsWritten'] == int(row['source_rows']) == int(row['target_rows']) == 2


def check_retired(state, binding):
    audit = state['audit']; seq = lambda r: int(r['event_sequence'])
    owners = {r['owner_id']: r for r in state['owners']}
    for ticket in state['launches']:
        assert ticket['guardian_boot'] == binding['bootId']
        if ticket['phase'] == 'cancelled':
            assert ticket['process_id'] is None and ticket['boot_id'] not in owners
        if ticket['phase'] != 'retired':
            continue
        assert ticket['signed_proof'] and ticket['operation_id']
        proof = signed(json.loads(ticket['signed_proof']), binding)['receipt']
        identity = proof['identity']; owner = owners[ticket['boot_id']]
        assert identity['bootId'] == ticket['boot_id'] == owner['owner_id']
        assert str(identity['pid']) == ticket['process_id'] == owner['process_id']
        assert identity['startIdentity'] == ticket['start_identity'] == owner['start_identity']
        assert identity['deploymentId'] == ticket['deployment_id'] == owner['deployment_id']
        assert proof['operationId'] == ticket['operation_id'] == owner['operation_id']
        assert proof['provider'] == 'managed-child-jvm' and json.loads(proof['details'])['alive'] is False
        assert owner['phase'] in ('startup_failed', 'db_drained')
        assert owner['manifest_version'] == owner['sealed_version']
        if owner['phase'] == 'db_drained':
            events = [r for r in audit if r['owner_id'] == owner['owner_id']]
            fence = next(r for r in events if r['event_type'] == 'fenced')
            drain = next(r for r in events if r['event_type'] == 'db_drained')
            assert seq(fence) < seq(drain)
            observation = json.loads(drain['details'])
            assert observation['complete'] and observation['emptyRounds'] == 2
            assert observation['sessions'] == observation['transactions'] == observation['unattributed'] == 0
            assert any(json.loads(r['details']).get('emptyRounds') == 1 for r in events if r['event_type'] == 'database_observed')
            for release in (r for r in events if r['event_type'] == 'old_quota_released'):
                assert seq(drain) < seq(release)


def validate_case(case, read, cbor):
    prefix = case['archivePrefix'] + '/'
    load = lambda name: json.loads(read(prefix + name))
    result = load('result.json'); scenario = result['scenario']
    assert result['productionSupervisorProtocol'] and result['D'] == 'N/A' and scenario == case['case']
    binding = load('guardian-binding.json')
    frames = [json.loads(line) for line in read(prefix + 'guardian-wire.jsonl').decode().splitlines()]
    for frame in frames:
        value = signed(frame['response'], binding)
        assert value['request'] == json.loads(frame['request']['payload'])
        if value['ok'] and value['request']['method'] == 'STOP':
            proof = signed(json.loads(value['result']['proof']), binding)['receipt']
            assert proof['operationId'] == value['request']['args']['operation']
            assert proof['identity'] == value['request']['args']['identity']
    cleanup = load('cleanup.json')
    assert cleanup['scenarioCompleted'] and cleanup['cleanupIsNotIsolationProof']
    assert all(not p['alive'] for p in cleanup['processes'])
    assert load('database-cleanup.json') == [{'clients': '0'}]
    assert load('supervisor-dead.json')['supervisor']['alive'] is False
    negative = result['outcome'] == 'SAFE_PAUSE'
    final = load('paused.json' if negative else 'final.json'); data = final['data']
    if negative:
        before = load('before-guardian-loss.json') if scenario == 'S15_AFTER_PROOF' else load('supervisor-dead.json')['state']
        for key in ('owners', 'permissions', 'launches'):
            assert final[key] == before[key]
        for key in ('T', 'J', 'control', 'audit'):
            assert data[key] == before['data'][key]
        assert len(data['T']) == len(pages(data, cbor)) == 0
        assert len(data['comparison']['source']) == 10 and not data['comparison']['target']
        assert len(data['comparison']['missingKeys']) == 10 and not data['comparison']['equal']
        assert len(final['launches']) == 1 and final['launches'][0]['phase'] == 'launched'
        if scenario == 'S15_AFTER_PROOF':
            assert final['owners'][0]['phase'] == 'fenced' and final['launches'][0]['signed_proof']
        return 'safe_pause', 0
    count = equal_rows(data['comparison']); assert count == 10
    assert len(data['audit']) == 10 and all(r['op'] == 'I' for r in data['audit'])
    check_ledger(data, result['originalExecution'], cbor)
    check_retired(final, binding)
    assert final['data']['control'][0]['phase'] == 'completed'
    assert final['data']['control'][0]['execution_id'] == result['originalExecution']
    assert sum(t['phase'] not in ('cancelled', 'retired') for t in final['launches']) == 1
    audit = final['audit']; sequence = lambda r: int(r['event_sequence'])
    permits = [r for r in audit if r['event_type'] == 'recovery_permitted']
    writes = load('journal-writer-permits.json')
    assert len(writes) == len([r for r in data['J'] if r['persistence_id'] == 'workflow-table-00'])
    for write in writes:
        permit = next(r for r in permits if r['owner_id'] == write['owner_id'] and json.loads(r['details'])['permit'] == write['permit_id'])
        assert int(write['admission_sequence']) >= sequence(permit)
        assert int(write['generation']) == json.loads(permit['details'])['generation']
    final_permit = final['permissions'][0]
    accepted = next(r for r in permits if json.loads(r['details'])['permit'] == final_permit['permit_id'])
    assert all(sequence(r) < sequence(accepted) for r in audit if r['event_type'] == 'old_quota_released')
    expected = {'S5_TARGET_TX': (0, 0), 'S6_T_BEFORE_J': (1, 0), 'S7_J_AFTER_STORE': (1, 1),
                'S8_COMMITTED_UNKNOWN': (1, 0), 'S8_UNCOMMITTED_UNKNOWN': (0, 0)}
    if scenario in expected:
        cut = load('cut.json')['data']
        assert (len(cut['T']), len(pages(cut, cbor))) == expected[scenario]
    if scenario.startswith('S10_') and scenario.endswith('_RESIDUAL'):
        before, after = load('database-wait.json'), load('database-after-worker-exit.json')
        old = read(prefix + 'old-boot').decode().strip()
        role = 'source' if scenario == 'S10_SOURCE_RESIDUAL' else 'target' if scenario == 'S10_TARGET_RESIDUAL' else 'journal'
        matches = lambda rows: {(r['ID'], r['trx_id']) for r in rows if r['owner'] == old and r['role'] == role and r['trx_id'] is not None}
        assert matches(before) & matches(after), 'no same real residual server transaction'
        if scenario == 'S10_SNAPSHOT_RESIDUAL':
            assert any(r['owner'] == old and '@snapshot_wait=GET_LOCK' in (r.get('INFO') or '') for r in before)
            assert any(r['owner'] == old and '@snapshot_wait=GET_LOCK' in (r.get('INFO') or '') for r in after)
        held, cut = load('blocked-before-database-release.json'), load('cut.json')
        assert held['data']['control'][0]['grant_id'] == cut['data']['control'][0]['grant_id']
        assert held['permissions'] == cut['permissions']
    if scenario.startswith('S13_'):
        cut, blocked = load('cut.json'), load('contender-blocked.json')
        assert cut['controller'] == blocked['controller'] and cut['data']['T'] == blocked['data']['T'] and cut['data']['J'] == blocked['data']['J']
    if scenario == 'S14_AUTH_IDENTITY':
        assert load('foreign-process-survived.json')['alive']
        assert sum(not signed(frame['response'], binding)['ok'] for frame in frames) >= 4
    if scenario.startswith('S12_') or scenario == 'S11_PERMIT_REPLY_LOST':
        assert int(final_permit['generation']) == 3
        assert sum(t['phase'] == 'retired' for t in final['launches']) == 2
        controllers = [json.loads(line.split('WINDOW ', 1)[1])['controller'] for line in read(prefix+'C.log').decode().splitlines()
                       if 'WINDOW ' in line and '"kind":"CONTROLLER_BOUND"' in line]
        assert len(controllers) == 1 and final['controller'][0]['controller_boot'] == controllers[0]['bootId']
        if scenario == 'S11_PERMIT_REPLY_LOST':
            second = load('before-second-restart.json')['permissions'][0]
            assert int(second['generation']) == 2
            log = read(prefix+second['owner_id']+'.worker.log').decode()
            events = [json.loads(line.split('WINDOW ', 1)[1]) for line in log.splitlines() if 'WINDOW ' in line]
            dropped = next(i for i, r in enumerate(events) if r['kind'] == 'PERMIT_COMMIT_REPLY_DROPPED')
            held = next(i for i, r in enumerate(events) if r['kind'] == 'PERMIT_READBACK_SQL_HELD')
            assert dropped < held and events[held]['sql'] == 'SELECT policy_hash FROM pekko_table_admission_lock WHERE id=1 FOR UPDATE'
    return 'converged', count


def main():
    for line in (HERE/'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1); assert sha((HERE/name).read_bytes()) == digest, name
    report = json.loads((HERE/'result.json').read_text()); manifest = json.loads((HERE/'archive-manifest.json').read_text()); files = {}
    parts = json.loads((HERE/'archive-parts.json').read_text())
    assert all(sha((HERE/name).read_bytes()) == parts['individual'][name] for name in parts['parts'])
    raw = b''.join((HERE/name).read_bytes() for name in parts['parts'])
    assert sha(raw) == parts['sha256'] and len(raw) == parts['bytes']
    with tarfile.open(fileobj=io.BytesIO(raw), mode='r:gz') as archive:
        for item in archive.getmembers():
            assert item.isfile() and not Path(item.name).is_absolute() and '..' not in Path(item.name).parts
            value = archive.extractfile(item).read(); assert item.name not in files and sha(value) == manifest[item.name]
            files[item.name] = value
    assert set(files) == set(manifest)
    source = json.loads((HERE/'source-manifest.json').read_text()); current = '--archived-source' not in sys.argv
    for runtime in [report['selectedRuntime'], report['mainRuntime'], *report['regressionRuntimes'].values()]:
        prefix = 'runners/' + runtime + '/'
        assert json.loads(files[prefix+'result.json'])['exitCode'] == 0
        assert json.loads(files[prefix+'source-manifest.json']) == source
    with zipfile.ZipFile(io.BytesIO(files['runners/'+report['selectedRuntime']+'/source.zip'])) as archive:
        assert set(archive.namelist()) == set(source)
        for name, digest in source.items():
            assert sha(archive.read(name)) == digest
            if current: assert sha((REPO/name).read_bytes()) == digest, name
    package = report['packagedArtifact']; data = files['build/'+package['file']]
    assert sha(data) == package['sha256'] and len(data) == package['bytes']
    with zipfile.ZipFile(io.BytesIO(data)) as built, zipfile.ZipFile(io.BytesIO(files['runners/'+report['selectedRuntime']+'/classes.jar'])) as frozen, zipfile.ZipFile(io.BytesIO(files['runners/'+report['selectedRuntime']+'/test-classes.jar'])) as tests:
        classes = [n for n in frozen.namelist() if n.endswith('.class')]
        assert len(classes) == package['classesMatchedFrozen'] and all(built.read(n) == frozen.read(n) for n in classes)
        assert 'cn/xuyinyin/magic/workflow/scheduling/SnapshotGuardianMain.class' in built.namelist()
        assert 'cn/xuyinyin/magic/workflow/integration/SupervisorRecoveryWorker.class' not in built.namelist()
        assert not set(built.namelist()) & {n for n in tests.namelist() if n.endswith('.class')}
        for dialect in ('mysql', 'h2'):
            path = 'db/'+dialect+'/pekko-snapshot-supervisor-schema.sql'
            assert built.read(path) == frozen.read(path)
        assert 'Main-Class: cn.xuyinyin.magic.PekkoServer' in built.read('META-INF/MANIFEST.MF').decode()
    cbor = runpy.run_path(str(HERE.parent/'2026-10-22-table-scheduling/verify.py'))['cbor']
    outcomes = [validate_case(case, files.__getitem__, cbor) for case in report['cases']]
    assert len(outcomes) == 32 and sum(o[0] == 'safe_pause' for o in outcomes) == 3
    assert sum(o[1] for o in outcomes) == 290
    live = [json.loads(line) for line in files['logs/final-liveness.jsonl'].decode().splitlines()]
    assert len(live) == 3 and all(r['readOnlyObservation'] for r in live)
    for case in (c for c in report['cases'] if c['case'].startswith('S15_')):
        record = next(r for r in live if case['archivePrefix'].endswith('/'+r['case']))
        assert len(record['workers']) == 1
        assert record['workers'][0]['aliveObserved'] == (case['case'] != 'S15_AFTER_PROOF')
    main_case = report['mainCase']['archivePrefix'] + '/'
    original = json.loads(files[main_case+'before-restart.json']); final = json.loads(files[main_case+'final.json'])
    assert equal_rows(final['data']['comparison']) == 10
    assert final['data']['T'] == original['data']['T'] and final['data']['audit'] == original['data']['audit']
    assert json.loads(files[main_case+'invalid-profile-rejected.json'])['exitCode'] != 0
    assert all(not p['alive'] for p in json.loads(files[main_case+'cleanup.json'])['processes'])
    old_probe = runpy.run_path(str(HERE.parent/'2026-10-23-guardian-restart/verify.py'))['verify_case']
    old_takeover = runpy.run_path(str(HERE.parent/'2026-10-23-snapshot-takeover/verify.py'))['validate_case']
    for case in report['probeCases']: old_probe(case, files.__getitem__, cbor)
    for case in report['takeoverCases']: assert old_takeover(case, files.__getitem__, cbor) == 10
    regression_outcomes = {'converged': 0, 'safe_pause': 0}
    for entry in report['dataRegressions']:
        outcome = json.loads(files[entry['archivePrefix']+'/'+entry['outcomeFile']])
        assert all(outcome[k] == v for k, v in entry['outcome'].items())
        negative = outcome.get('classification') == 'safe_pause' or outcome.get('automaticRecovery') == 'SAFE_BLOCK'
        regression_outcomes['safe_pause' if negative else 'converged'] += 1
        if not negative: equal_rows(json.loads(files[entry['archivePrefix']+'/final.json'])['comparison'])
    assert regression_outcomes == {'converged': 36, 'safe_pause': 13}
    backend = files['logs/backend-final.log'].decode(errors='replace')
    assert f"Tests: succeeded {report['backendTests']}, failed 0" in backend and 'All tests passed.' in backend
    assert report['cleanup']['clients'] == report['cleanup']['transactions'] == 0 and report['cleanup']['stopped']
    assert not report['committed'] and not report['pushed']
    case = next(c for c in report['cases'] if c['case'] == 'S6_T_BEFORE_J'); path = case['archivePrefix']+'/final.json'
    rejected = 0
    for mutate in (lambda v: v['data']['comparison']['target'][0].update(label='corrupt'), lambda v: v['data']['T'][0].update(cursor_value='corrupt')):
        value = json.loads(files[path]); mutate(value)
        try: validate_case(case, lambda p: json.dumps(value).encode() if p == path else files[p], cbor)
        except (AssertionError, KeyError, StopIteration): rejected += 1
        else: raise AssertionError('corrupted evidence accepted')
    path = case['archivePrefix']+'/guardian-wire.jsonl'; frames = [json.loads(line) for line in files[path].decode().splitlines()]
    frames[0]['response']['payload'] += ' '
    try: validate_case(case, lambda p: ('\n'.join(json.dumps(r) for r in frames)+'\n').encode() if p == path else files[p], cbor)
    except InvalidSignature: rejected += 1
    else: raise AssertionError('corrupted signature accepted')
    print(json.dumps(dict(archiveIntegrity=True,currentSourceChecked=current,matrixConverged=29,matrixSafePause=3,matrixRows=290,fieldsPerRow=6,
                         actualMain=True,probeCases=10,takeoverCases=22,dataRegressions=regression_outcomes,negativeControlsRejected=rejected,
                         scope='local static snapshot supervisor recovery; not guardian/host restart or CDC multi-owner')))


if __name__ == '__main__': main()
