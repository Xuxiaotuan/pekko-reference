#!/usr/bin/env python3
"""Read-only verification of captured real runs. PASS means evidence is consistent, NOT C2 recovery succeeds."""
import base64
import hashlib
import json
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CASES = ('N0', 'J1', 'J2', 'C1', 'C2', 'C0', 'K1', 'K2')
FIELDS = {'id', 'label', 'amount', 'note', 'updated_at', 'revision'}

def canonical(x):
    return json.dumps(x, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

def digest(x):
    return hashlib.sha256(canonical(x).encode()).hexdigest()

def checkpoint(state):
    return [e['checkpoint'] for e in state['journal'] if e['checkpoint'] is not None][-1]

def differences(comparison):
    def indexed(rows):
        assert all(set(r) == FIELDS for r in rows)
        result = {r['id']: r for r in rows}
        assert len(result) == len(rows), 'duplicate primary key in captured rows'
        return result
    left, right = indexed(comparison['source']), indexed(comparison['target'])
    missing, extra = sorted(left.keys()-right.keys()), sorted(right.keys()-left.keys())
    changed = [dict(id=k, field=f, source=left[k][f], target=right[k][f])
        for k in sorted(left.keys() & right.keys()) for f in sorted(FIELDS) if left[k][f] != right[k][f]]
    assert missing == comparison['missingKeys'] and extra == comparison['extraKeys']
    assert changed == comparison['fieldDifferences']
    assert comparison['equal'] == (not missing and not extra and not changed)
    return dict(sourceRows=len(left), targetRows=len(right), missingKeys=len(missing), extraKeys=len(extra), fieldDifferences=len(changed))

def verify(read):
    manifest = json.loads(read('manifest.json'))
    for name, sha in manifest['files'].items():
        assert hashlib.sha256(read(name)).hexdigest() == sha, name
    reports = []
    for case in CASES:
        def data(name): return json.loads(read(f'{case}/{name}.json'))
        env, baseline, cut, final = [data(n) for n in ('environment', 'baseline', 'cutpoint', 'final')]
        event, outcome, replay = [data(n) for n in ('cut-event', 'outcome', 'replay')]
        assert differences(baseline['comparison'])['fieldDifferences'] == 0
        result = differences(final['comparison'])
        assert result['sourceRows'] == result['targetRows'] == 64
        jb, jc, jf = (checkpoint(s['J']) for s in (baseline, cut, final))
        assert jb == replay['checkpointBefore'] and jc == replay['checkpointAtCut']
        assert replay['originalPid'] != replay['recoveredPid']
        if case in ('J1','J2','K1','K2'): assert jc['sequence'] > jb['sequence']
        if case in ('C0','C1','C2'): assert jc == jb
        if case not in ('N0','K1'): assert cut['D'] == baseline['D']
        if case == 'K1': assert cut['D'] != baseline['D']
        if case == 'C0': assert cut['T'] == baseline['T'] and cut['audit'] == baseline['audit']
        else: assert len(cut['T']) > len(baseline['T'])
        if case in ('J2','K2'):
            waits = data('offset-write-waits')['waitingTransactions']
            assert any(r['trx_state'] == 'LOCK WAIT' and 'debezium_offset_storage_' in r['INFO'] for r in waits)
            assert 'Flush of the offsets failed, canceling the flush.' in read(f'{case}/original.log').decode()
            assert 'java.util.concurrent.TimeoutException' in read(f'{case}/original.log').decode()
        processes, observed, raw_order = {}, {}, []
        for process in ('original', 'recovered'):
            events = [json.loads(l[7:]) for l in read(f'{case}/{process}.log').decode().splitlines() if l.startswith('WINDOW ')]
            processes[process] = events
            acked = [e['callback'] for e in events if e['kind'] == 'RAW_ACK_RETURNED']
            assert acked == list(range(len(acked))), 'non-prefix raw ACK'
            if process == 'original' and case in ('K1','K2'):
                a = event['callback']; b = a+1
                idx = lambda kind, cb: next(i for i,e in enumerate(events) if e['kind']==kind and e.get('callback')==cb)
                assert idx('RAW_CALLBACK_RETURNED',a) < idx('RAW_CALLBACK',b) < idx('RAW_ACK_BEGIN',a) < idx('RAW_ACK_RETURNED',a)
                assert not any(e['kind']=='RAW_ACK_BEGIN' and e.get('callback')==b for e in events)
                pair = [next(e for e in events if e['kind']=='RAW_CALLBACK' and e['callback']==c)['records'] for c in (a,b)]
                assert [[r['value']['op'] for r in cb] for cb in pair] == [['d'],['c']]
                assert pair[0][0]['sourceOffset'] == pair[1][0]['sourceOffset']
            for e in events:
                if e['kind'] != 'RAW_CALLBACK': continue
                for r in e['records']:
                    v, n = r['value'], r['normalizedEnvelope']
                    assert n is not None
                    headers = {h['key'] for h in r['headers']}
                    role = ('pk.' if headers & {'__debezium.newkey','__debezium.oldkey'} else 'single.') + v['op']
                    s = v['source']
                    protocol = dict(version=1, replicationId=env['connector'], generation='initialized-v1', sourceLineage=env['lineage'])
                    gid = digest([protocol,s['db'],s['table'],s['file'],s['pos'],s['row']])
                    key = (gid,role)
                    if key in observed: assert observed[key][0] == n, 'replayed member changed'
                    else: observed[key] = (n,v); raw_order.append(key)
        ledger = {}
        for row in final['T']:
            proof = json.loads(row['evidence']); group = proof['group']; gid = row['group_id']
            assert gid == group['groupId']
            roles = [m['role'] for m in group['members']]
            assert roles == ['pk.d','pk.c'] or (len(roles)==1 and roles[0] in ('single.c','single.u','single.d'))
            for m in group['members']:
                k=(gid,m['role']); assert k not in ledger
                assert m['eventId'] == digest([gid,m['role']])
                ledger[k] = m['envelope']
        assert ledger == {k:v[0] for k,v in observed.items()}, 'raw records and target group evidence differ'
        # Audit fields come from original SourceRecord Struct values, not production envelopes or Checkpoint counters.
        expected_audit = [{k:v for k,v in a.items() if k!='audit_id'} for a in data('trusted-baseline')['audit']]
        for k in raw_order:
            v=observed[k][1]; op=v['op']; before,after=v['before'],v['after']; image=before if op=='d' else after
            expected_audit.append(dict(op={'c':'I','u':'U','d':'D'}[op], row_id=str(image['id']),
                old_revision=str(before['revision']) if before else None, new_revision=str(after['revision']) if after else None,
                old_label=before['label'] if before else None, new_label=after['label'] if after else None))
        assert expected_audit == [{k:v for k,v in a.items() if k!='audit_id'} for a in final['audit']], 'audit is not exactly once in source order'
        assert final['audit'][:len(cut['audit'])] == cut['audit']
        for state in (baseline,cut,final):
            for e in state['J']['journal']:
                assert base64.b64decode(e['eventPayloadBase64'],validate=True)
                assert isinstance(e['eventSerializerId'],int)
        originals=processes['original']
        if case in ('C0','C1','C2'):
            required='PROXY_COMMIT_NOT_FORWARDED' if case=='C0' else 'PROXY_COMMIT_OK_HELD'
            assert any(e['kind']==required and e['batch']==event['batch'] for e in originals)
            assert not any(e['kind']=='SINK_COMMITTED' and e['batch']['batchId']==event['batch']['batchId'] for e in originals)
        if case == 'J1': assert not any(e['kind']=='ACK_RETURNED' and e['batchId']==event['batch']['batchId'] for e in originals)
        if case == 'C2':
            assert outcome['automaticRecovery']=='FAIL_TERMINAL' and not final['comparison']['equal']
            assert final['J']['status']=='failed' and final['J']['executionId'] is None
            assert any(e['eventType']=='ExecutionFailed' for e in final['J']['journal'])
            assert any(e['kind']=='PROXY_COMMIT_OK_DROPPED' for e in originals)
            assert any(e['kind']=='SINK_FAILED' and any('CommunicationsException' in c for c in e['causes']) for e in originals)
            assert not any(e['kind']=='READER_RESUME' for e in processes['recovered'])
            assert final['D']==cut['D'] and final['T']==cut['T'] and final['audit']==cut['audit']
            assert result['missingKeys']==result['extraKeys']==2 and result['fieldDifferences']>0
        else:
            assert final['comparison']['equal'] and outcome['automaticRecovery']=='PASS'
            assert len(final['audit'])==(72 if case.startswith('K') else 140)
            assert any(json.loads(r['offset_val'])==jf['cursor']['offset'] for r in final['D'])
            assert replay['firstReplayBatch']['sequence']==jc['sequence']+1
            assert jf['sequence']>jc['sequence']
        reports.append(dict(scenario=case, **result, cutTargetGroups=len(cut['T']), cutJSequence=jc['sequence'],
            finalTargetGroups=len(final['T']), finalJSequence=jf['sequence'], finalAuditRows=len(final['audit']),
            firstRecoveryGroups=len(replay['firstReplayBatch']['rows']) if replay['firstReplayBatch'] else None,
            originalRawAckOrder=[e['callback'] for e in originals if e['kind']=='RAW_ACK_RETURNED'],
            automaticRecovery=outcome['automaticRecovery'], evidenceConsistent=True))
    return dict(artifactVerification='PASS', scenarioObservations=8, converged=7, automaticRecoveryFailures=1,
        scope='fixed-schema single-writer real MySQL/Actor JDBC Journal/production Source and Sink; C2 is a retained recovery failure',scenarios=reports)

if __name__=='__main__':
    with tarfile.open(ROOT/'evidence.tar.gz','r:gz') as archive:
        print(json.dumps(verify(lambda name: archive.extractfile(name).read()),ensure_ascii=False,indent=2))
