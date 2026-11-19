#!/usr/bin/env python3
"""Offline evidence verifier. No MySQL, process signals, recovery or private keys."""
from pathlib import Path
import base64, copy, hashlib, io, json, runpy, sys, tarfile, zipfile
from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.exceptions import InvalidSignature

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sha = lambda b: hashlib.sha256(b).hexdigest()


def verify_case(case, read, cbor):
    root = case['archivePrefix'] + '/'
    load = lambda name: json.loads(read(root + name))
    result = load('result.json')
    assert result['scenario'] == case['case'] and not result['productionSupervisorProtocol']
    assert result['D'] == 'N/A'
    old_boot = read(root + 'old-boot').decode().strip()
    pin = load('guardian-pin.json')
    public = load_der_public_key(base64.b64decode(pin['publicKey']))
    frames = [json.loads(line) for line in read(root + 'guardian-wire.jsonl').decode().splitlines()]
    decoded = []
    for frame in frames:
        response = frame['response']
        public.verify(base64.b64decode(response['signature']), response['payload'].encode())
        request = json.loads(frame['request']['payload'])
        body = json.loads(response['payload'])
        assert body['nonce'] == request['nonce'] and body['guardian'] == pin['guardian']
        decoded.append((request, body))
    assert all(not p['alive'] for p in load('cleanup.json')['processes'])
    assert load('cleanup.json')['cleanupIsNotIsolationProof'] and load('cleanup.json')['scenarioCompleted']
    assert load('database-cleanup.json') == [{'clients': '0'}]
    dead = load('supervisor-dead.json')
    assert not dead['supervisorAlive']
    assert dead['guardianAlive'] == (result['scenario'] != 'S15_GUARDIAN_DEAD')
    cut = load('cut.json')
    negative = result['outcome'] == 'SAFE_PAUSE'
    final = load('paused.json' if negative else 'final.json')
    if result['scenario'] not in {'S2_PREPARED', 'S2_FORK_PENDING'}:
        alive = load('after-supervisor-death-worker-alive.json')
        identity = load(old_boot + '.identity.json')
        assert alive['alive'] and alive['pid'] == identity['pid'] and alive['start'] == identity['startIdentity']
    data = final['data']
    comparison = data['comparison']
    fields = {'id','label','amount','note','updated_at','revision'}
    source, target = comparison['source'], comparison['target']
    assert len(source) == 10 and all(set(r) == fields for r in source + target)
    assert {r['id'] for r in source} == {'-9','1','2','3','4','5','6','7','8','9007199254740993'}
    assert data['D'].startswith('N/A')
    journal = [r for r in data['J'] if r['persistence_id'] == 'workflow-table-00']
    assert [int(r['sequence_number']) for r in journal] == list(range(1,len(journal)+1))
    events = [cbor(base64.b64decode(r['payload_base64'])) for r in journal]
    pages = [r for r in events if r.get('update',{}).get('_type','').endswith('.CommitSnapshotPage')]
    if negative:
        for k in ['owners','permissions','launches']:
            assert final[k] == dead['state'][k]
        for k in ['T','J','control','audit']:
            assert data[k] == dead['state']['data'][k]
        assert len(target) == len(data['T']) == len(pages) == 0
        assert len(comparison['missingKeys']) == 10 and not comparison['equal']
        assert not comparison['extraKeys'] and not comparison['fieldDifferences']
        assert len(final['launches']) == 1 and final['launches'][0]['phase'] != 'retired'
        assert final['permissions'][0]['owner_id'] == old_boot
        assert final['data']['control'][0]['owner_id'] == old_boot
        if result['scenario'] == 'S15_CAPABILITY_LOST':
            assert load('reconstructed.json')[old_boot]['status'] == 'UNKNOWN'
        else:
            assert load('B-pause.json')['errorClass'].endswith(('ConnectException','SocketException'))
        return 'safe_pause'
    left, right = ({r['id']:r for r in values} for values in [source,target])
    assert len(target) == 10 and left == right and comparison['equal']
    assert not any(comparison[k] for k in ['missingKeys','extraKeys','fieldDifferences'])
    assert len(data['audit']) == 10 and all(r['op'] == 'I' for r in data['audit'])
    ledger = {r['batch_id']:r for r in data['T']}
    assert len(ledger) == len(data['T']) == len(pages) == len(data['digests']) == 5
    assert set(ledger) == {r['batch_id'] for r in data['digests']}
    for page in pages:
        cp = page['update']['checkpoint']; row = ledger[cp['batchId']]
        assert page['executionId'] == row['execution_id'] == result['originalExecution']
        assert cp['batchId'] == sha(f"{page['executionId']}|{cp['sourceNodeId']}|{cp['partitionId']}|{cp['batchSequence']}".encode())
        assert (cp['cursor']['value'], cp['cursor']['upperBound']) == (row['cursor_value'],row['upper_bound'])
        assert cp['sourceRowsScanned'] == cp['targetRowsWritten'] == int(row['source_rows']) == int(row['target_rows']) == 2
    audit = final['audit']; seq = lambda r: int(r['event_sequence'])
    permits = [r for r in audit if r['event_type'] == 'recovery_permitted']
    writes = load('journal-writer-permits.json')
    assert writes and len(writes) == len(journal)
    for write in writes:
        permit = next(r for r in permits if r['owner_id'] == write['owner_id'] and json.loads(r['details'])['permit'] == write['permit_id'])
        assert int(write['admission_sequence']) >= seq(permit)
        assert int(write['generation']) == json.loads(permit['details'])['generation']
    final_permit = final['permissions'][0]
    accepted = next(r for r in permits if json.loads(r['details'])['permit'] == final_permit['permit_id'])
    old_returns = [r for r in audit if r['event_type'] == 'old_quota_released']
    if old_returns:
        assert seq(accepted) > max(map(seq,old_returns))
    assert final['data']['control'][0]['phase'] == 'completed'
    assert final['data']['control'][0]['execution_id'] == result['originalExecution']
    assert len([r for r in final['launches'] if r['phase'] != 'retired']) == 1
    if result['scenario'] == 'S2_PREPARED':
        assert any(q['method']=='CANCEL' and q['args']['boot']==old_boot and r['ok'] for q,r in decoded)
        assert not any(q['method']=='LAUNCH' and q['args']['boot']==old_boot and r['ok'] for q,r in decoded)
    else:
        receipt = load('exit-receipt.json')
        matching = [r for q,r in decoded if q['method']=='STOP' and r['ok'] and r['result']['receipt']==receipt]
        assert matching and receipt['identity']['bootId'] == old_boot
        assert json.loads(receipt['details'])['alive'] is False
        owner = next(r for r in final['owners'] if r['owner_id']==old_boot)
        assert owner['process_id'] == str(receipt['identity']['pid']) and owner['start_identity'] == receipt['identity']['startIdentity']
        assert owner['operation_id'] == receipt['operationId']
        if result['scenario'] == 'S4_PRE_BOOTSTRAP':
            assert owner['phase'] == 'startup_failed' and not cut['owners']
        else:
            assert owner['phase'] == 'db_drained'
            old_audit = [r for r in audit if r['owner_id']==old_boot]
            get = lambda kind: next(r for r in old_audit if r['event_type']==kind)
            assert seq(get('fenced')) < seq(get('db_drained')) < seq(get('old_quota_released'))
            before_return = load('drained-before-quota-return.json')
            assert before_return['data']['control'][0]['owner_id'] == old_boot
            assert before_return['data']['control'][0]['grant_id'] is not None
            assert load('after-quota-return.json')['data']['control'][0]['phase'] == 'ready'
    if result['scenario'].startswith('S13_'):
        blocked = load('contender-blocked.json')
        assert blocked['controller'] == cut['controller'] and blocked['data']['T'] == cut['data']['T']
        assert 'LOCK_BLOCKED' in read(root+'C.log').decode()
    if result['scenario'] == 'S14_AUTH_IDENTITY':
        assert load('foreign-process-survived.json')['alive']
        assert sum(not r['ok'] for q,r in decoded) >= 4  # replay, bad authenticator, wrong process, stale epoch
        assert load('wrong-pin-rejected.json')['publicKey'] != pin['publicKey']
    return 'converged'


def main():
    for line in (HERE/'SHA256SUMS').read_text().splitlines():
        digest,name=line.split('  ',1);assert sha((HERE/name).read_bytes())==digest,name
    report=json.loads((HERE/'result.json').read_text());manifest=json.loads((HERE/'archive-manifest.json').read_text());files={}
    with tarfile.open(HERE/'evidence.tar.gz','r:gz') as archive:
        for item in archive.getmembers():
            assert item.isfile() and not Path(item.name).is_absolute() and '..' not in Path(item.name).parts
            data=archive.extractfile(item).read();assert item.name not in files and sha(data)==manifest[item.name]
            files[item.name]=data
    assert set(files)==set(manifest)
    source=json.loads((HERE/'source-manifest.json').read_text());current='--archived-source' not in sys.argv
    root='runners/'+report['selectedRuntime']+'/'
    assert json.loads(files[root+'source-manifest.json'])==source
    assert json.loads(files[root+'result.json'])['exitCode']==0
    with zipfile.ZipFile(io.BytesIO(files[root+'source.zip'])) as archive:
        assert set(archive.namelist())==set(source)
        for path,digest in source.items():
            assert sha(archive.read(path))==digest
            if current:assert sha((REPO/path).read_bytes())==digest,path
    cbor=runpy.run_path(str(HERE.parent/'2026-10-22-table-scheduling/verify.py'))['cbor']
    assert len(report['cases'])==10 and len({c['case'] for c in report['cases']})==10
    outcomes=[verify_case(c,files.__getitem__,cbor) for c in report['cases']]
    assert outcomes.count('converged')==8 and outcomes.count('safe_pause')==2
    backend=files['logs/backend-final.log'].decode(errors='replace')
    assert 'Tests: succeeded 564, failed 0' in backend and 'All tests passed.' in backend
    assert report['productionSourceUnchanged'] and report['headUnchanged'] and not report['committed'] and not report['pushed']
    assert report['cleanup']['clients']==report['cleanup']['transactions']==0 and report['cleanup']['stopped']
    case=next(c for c in report['cases'] if c['case']=='S1_READ');path=case['archivePrefix']+'/final.json'
    negatives=0
    for mutate in [lambda v:v['data']['comparison']['target'][0].update(label='corrupt'),
                   lambda v:v['data']['T'][0].update(cursor_value='corrupt')]:
        value=json.loads(files[path]);mutate(value)
        try:verify_case(case,lambda p:json.dumps(value).encode() if p==path else files[p],cbor)
        except (AssertionError,KeyError,StopIteration):negatives+=1
        else:raise AssertionError('corrupted evidence was accepted')
    path=case['archivePrefix']+'/guardian-wire.jsonl'
    rows=[json.loads(line) for line in files[path].decode().splitlines()]
    rows[0]['response']['payload']=rows[0]['response']['payload'].replace('"ok":true','"ok":false')
    corrupted=('\n'.join(json.dumps(row) for row in rows)+'\n').encode()
    try:verify_case(case,lambda p:corrupted if p==path else files[p],cbor)
    except InvalidSignature:negatives+=1
    else:raise AssertionError('altered signed response was accepted')
    print(json.dumps(dict(archiveIntegrity=True,currentSourceChecked=current,converged=8,safePause=2,comparedCompletedRows=80,
                         fieldsPerRow=6,pausedMissingRows=20,negativeControlsRejected=negatives,
                         scope='offline verification; test-only control protocol, not production supervisor recovery')))


if __name__=='__main__':main()
