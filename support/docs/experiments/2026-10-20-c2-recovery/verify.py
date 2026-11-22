#!/usr/bin/env python3
"""Read-only recheck of captured records, JDBC journal, group ledger, audit, ACKs and all fields.
No database or application access. SAFE_BLOCK is not successful automatic recovery.
"""
import base64
import hashlib
import json
import tarfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent
CASES=('AUTO-A','AUTO-B','LATE_COMMIT','LATE_ROLLBACK','AUTO-D','AUTO-E','AUTO-F','R1','R2','R3','R4','R5','R6','R7-PK','R7-D')
FIELDS={'id','label','amount','note','updated_at','revision'}

def canonical(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def digest(v):return hashlib.sha256(canonical(v).encode()).hexdigest()
def checkpoint(j):return [e['checkpoint'] for e in j['journal'] if e['checkpoint'] is not None][-1]
def differences(c):
    def index(rows):
        assert all(set(r)==FIELDS for r in rows)
        result={r['id']:r for r in rows};assert len(result)==len(rows)
        return result
    a,b=index(c['source']),index(c['target'])
    missing,extra=sorted(a.keys()-b.keys()),sorted(b.keys()-a.keys())
    diff=[dict(id=k,field=f,source=a[k][f],target=b[k][f]) for k in sorted(a.keys()&b.keys()) for f in sorted(FIELDS) if a[k][f]!=b[k][f]]
    assert c['missingKeys']==missing and c['extraKeys']==extra and c['fieldDifferences']==diff
    assert c['equal']==(not missing and not extra and not diff)
    return dict(sourceRows=len(a),targetRows=len(b),missing=len(missing),extra=len(extra),fieldDifferences=len(diff))

def verify(read):
    manifest=json.loads(read('manifest.json'))
    for p,sha in manifest['files'].items():assert hashlib.sha256(read(p)).hexdigest()==sha,p
    reports=[]
    for case in CASES:
        def data(n):return json.loads(read(f'automatic/{case}/{n}.json'))
        env,base,cut,final=[data(n) for n in ('environment','baseline','cutpoint','final')]
        outcome=data('outcome');result=differences(final['comparison'])
        assert final['audit']==data('post-run-audit'),'JDBC audit and UTF8 SQL export differ'
        assert base['comparison']['equal'];differences(base['comparison']);differences(cut['comparison'])
        j=final['J'];r=j['recovery'];assert j['executionId']==base['J']['executionId']
        assert 0<=r['attemptsStarted']<=3
        starts=[e['recovery']['attempt'] for e in j['journal'] if e['eventType']=='ExecutionRecoveryAttemptStarted']
        assert starts==list(range(1,r['attemptsStarted']+1)),starts
        assert not any(e['eventType']=='ExecutionFailed' for e in j['journal'])
        for event in j['journal']:assert base64.b64decode(event['eventPayloadBase64'],validate=True)
        observed={};order=[];processes={};ack_orders={}
        for name in ('original','restarted'):
            path=f'automatic/{case}/{name}.log'
            try:lines=read(path).decode().splitlines()
            except (KeyError,FileNotFoundError):continue
            events=[json.loads(l[7:]) for l in lines if l.startswith('WINDOW ')]
            processes[name]=events
            readers=sorted({e['reader'] for e in events if e['kind']=='ENGINE_CONFIG'})
            resumes=[i for i,e in enumerate(events) if e['kind']=='READER_RESUME']
            for previous,resumed in enumerate(resumes[1:],1):
                closed=next(i for i,e in enumerate(events) if e['kind']=='ENGINE_CLOSED' and e['reader']==previous)
                assert closed<resumed,'source reopened before predecessor release'
            for previous,current in zip(readers,readers[1:]):
                closed=next(i for i,e in enumerate(events) if e['kind']=='ENGINE_CLOSED' and e['reader']==previous)
                opened=next(i for i,e in enumerate(events) if e['kind']=='ENGINE_CONFIG' and e['reader']==current)
                assert closed<opened,'overlapping physical readers'
            for reader in readers:
                ack=[e['callback'] for e in events if e['kind']=='RAW_ACK_RETURNED' and e['reader']==reader]
                assert ack==list(range(len(ack))),f'{case}/{name}/{reader} raw ACK not prefix'
                ack_orders[f'{name}/{reader}']=ack
            # Reconstruct group membership from raw MySQL SourceRecord metadata independently.
            raw={};batches={};confirmed=set();covered=set()
            protocol=dict(version=1,replicationId=env['connector'],generation='initialized-v1',sourceLineage=env['lineage'])
            for e in events:
                kind=e['kind']
                if kind=='RAW_CALLBACK':
                    keys=[]
                    for record in e['records']:
                        v,n=record['value'],record['normalizedEnvelope'];assert n is not None
                        headers={h['key'] for h in record['headers']}
                        role=('pk.' if headers&{'__debezium.newkey','__debezium.oldkey'} else 'single.')+v['op']
                        s=v['source'];gid=digest([protocol,s['db'],s['table'],s['file'],s['pos'],s['row']]);key=(gid,role)
                        keys.append(key)
                        if key in observed:assert observed[key][0]==n,'replayed member differs'
                        else:observed[key]=(n,v);order.append(key)
                    raw[(e['reader'],e['callback'])]=keys
                elif kind=='SINK_COMMITTED':batches[e['batch']['batchId']]=e['batch']
                elif kind=='JOURNAL_CONFIRMED':
                    bid=e['checkpoint']['batchId'];assert bid in batches
                    confirmed.add(bid)
                    covered.update((g['groupId'],m['role']) for g in batches[bid]['rows'] for m in g['members'])
                elif kind=='ACK_BEGIN':assert e['batchId'] in confirmed,'source ACK before durable J confirmation'
                elif kind=='RAW_ACK_BEGIN':
                    assert all(k in covered for k in raw[(e['reader'],e['callback'])]),'original ACK crosses unconfirmed group'
        ledger={};tampered=[]
        for row in final['T']:
            gid=row['group_id']
            if row['evidence']=='tampered-for-negative-test':tampered.append(gid);continue
            p=json.loads(row['evidence']);g=p['group'];assert gid==g['groupId']
            roles=[m['role'] for m in g['members']]
            assert roles==['pk.d','pk.c'] or (len(roles)==1 and roles[0] in ('single.c','single.u','single.d'))
            for m in g['members']:
                key=(gid,m['role']);assert key not in ledger
                assert m['eventId']==digest([gid,m['role']]);assert observed[key][0]==m['envelope']
                ledger[key]=m['envelope']
        assert len(tampered)==(1 if case=='R6' else 0)
        committed={row['group_id'] for row in final['T']}
        expected=[{k:v for k,v in a.items() if k!='audit_id'} for a in data('trusted-baseline')['audit']]
        for key in order:
            if key[0] not in committed:continue
            v=observed[key][1];before,after=v['before'],v['after'];image=before if v['op']=='d' else after
            expected.append(dict(op={'c':'I','u':'U','d':'D'}[v['op']],row_id=str(image['id']),
                old_revision=str(before['revision']) if before else None,new_revision=str(after['revision']) if after else None,
                old_label=before['label'] if before else None,new_label=after['label'] if after else None))
        assert expected==[{k:v for k,v in a.items() if k!='audit_id'} for a in final['audit']],f'{case}: audit not once in raw source order'
        assert final['audit'][:len(cut['audit'])]==cut['audit']
        events=processes['original']
        if case.startswith('LATE_'):
            waits=data('old-transaction-lock-wait')['transactions']
            assert any(x['trx_state']=='LOCK WAIT' for x in waits)
            assert any(x['trx_state']=='RUNNING' for x in waits)
            assert data('old-transaction-ended')['transactions']==[]
            assert any(e['kind']==('PROXY_LATE_COMMIT_OK' if case=='LATE_COMMIT' else 'PROXY_LATE_ROLLBACK_DISCONNECTED') for e in events)
        elif case!='R4':
            assert any(e['kind']==('PROXY_COMMIT_NOT_FORWARDED' if case=='AUTO-B' else 'PROXY_COMMIT_OK_HELD') for e in events)
        if case=='AUTO-D':
            old={x['group_id'] for x in cut['T']}
            assert any((ids:={g['groupId'] for g in e['batch']['rows']})&old and ids-old for e in events if e['kind']=='SOURCE_BATCH' and e['reader']>1),'mixed replay/new groups not observed'
        if case=='AUTO-F':
            deletion=next(e for e in events if e['kind']=='RAW_CALLBACK' and '__debezium.newkey' in canonical(e['records']))
            create=next(e for e in events if e['kind']=='RAW_CALLBACK' and e['reader']==deletion['reader'] and e['callback']==deletion['callback']+1)
            assert '__debezium.oldkey' in canonical(create['records'])
        if case=='R1':assert data('before-restart')['J']['recovery']['phase']=='waiting'
        if case=='R2':assert data('before-restart')['J']['recovery']['attemptsStarted']==1 and r['attemptsStarted']==2
        if case=='R3':assert r['attemptsStarted']==3 and not any(e['kind']=='READER_RESUME' for e in processes['restarted'])
        if case=='R4':assert any(e['kind']=='STALE_PROGRESS_REJECTED' for e in events) and not any(e['kind']=='STALE_PROGRESS_ACCEPTED' for e in events)
        if case in ('R5','R7-PK','R7-D'):assert sum(e['kind']=='ENGINE_CONFIG' for e in events)==1
        if outcome['automaticRecovery']=='PASS':
            assert final['comparison']['equal'] and result['sourceRows']==result['targetRows']==64
            assert set(ledger)==set(observed),'successful raw stream not fully represented in ledger'
            assert len(final['audit'])==(69 if case=='AUTO-F' else 137)
            assert any(json.loads(d['offset_val'])==checkpoint(j)['cursor']['offset'] for d in final['D'])
        else:
            assert outcome['automaticRecovery']=='SAFE_BLOCK' and j['status']=='recovery_blocked' and r['phase']=='blocked'
            assert not final['comparison']['equal'],'negative case unexpectedly converged; investigate classification'
            if case!='R7-D':assert final['D']==base['D'],'negative case advanced durable D'
            else:assert final['D']==[]
        reports.append(dict(scenario=case,classification=outcome['automaticRecovery'],attemptsStarted=r['attemptsStarted'],reason=r['reasonCode'],
            targetGroups=len(final['T']),cutTargetGroups=len(cut['T']),cutJSequence=checkpoint(cut['J'])['sequence'],
            finalJSequence=checkpoint(j)['sequence'],cutDUnchanged=cut['D']==base['D'],
            finalDMatchesJ=any(json.loads(d['offset_val'])==checkpoint(j)['cursor']['offset'] for d in final['D']),
            auditRows=len(final['audit']),rawAckOrders=ack_orders,**result))
    return dict(artifactVerification='PASS',automaticCases=15,converged=sum(r['classification']=='PASS' for r in reports),
        safeBlocked=sum(r['classification']=='SAFE_BLOCK' for r in reports),scenarios=reports)

if __name__=='__main__':
    with tarfile.open(ROOT/'evidence.tar.gz','r:gz') as archive:
        files={member.name:archive.extractfile(member).read() for member in archive if member.isfile()}
    print(json.dumps(verify(files.__getitem__),ensure_ascii=False,indent=2))
