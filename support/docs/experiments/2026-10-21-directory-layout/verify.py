#!/usr/bin/env python3
"""Read-only verification of 27 captured real cases. No database or application access."""
import hashlib
import importlib.util
import json
import sys
import tarfile
from pathlib import Path
sys.dont_write_bytecode = True
ROOT=Path(__file__).resolve().parent

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

automatic=module('checkpoint_rejection',ROOT.parent/'2026-10-21-checkpoint-rejection/verify.py')
regressions=module('checkpoint_regressions',ROOT.parent/'2026-10-21-checkpoint-rejection/verify-regressions.py')

def verify(read):
    manifest=json.loads(read('manifest.json'))['files']
    for name,sha in manifest.items():assert hashlib.sha256(read(name)).hexdigest()==sha,name
    a=automatic.verify(read);g=regressions.verify(read)
    prefix='release/stop-T/'
    def data(name):return json.loads(read(prefix+name+'.json'))
    base,cut,pending,resumed,final=[data(n) for n in ('baseline','cutpoint','stop-pending','after-owner-restart','final')]
    outcome=data('outcome');release=data('released');stopped=data('owner-stopped')
    diff=automatic.differences(final['comparison'])
    assert final['comparison']['equal'] and diff['sourceRows']==diff['targetRows']==64
    assert pending['J']['status']=='stopping'
    assert cut['T']!=base['T'] and pending['T']==cut['T']
    assert automatic.checkpoint(cut['J'])==automatic.checkpoint(pending['J'])==automatic.checkpoint(base['J'])
    assert cut['D']==pending['D']==base['D']
    assert resumed['J']['executionId']==base['J']['executionId']
    assert automatic.checkpoint(resumed['J'])['sequence']>automatic.checkpoint(base['J'])['sequence']
    assert not any(e['eventType'] in ('ExecutionCompleted','ExecutionFailed') for e in resumed['J']['journal'])
    assert stopped['liveRunners']==0 and release['status']=='Released' and release['liveRunners']==0
    assert sum(e['eventType']=='ExecutionRecoveryAttemptStarted' for e in final['J']['journal'])==1
    assert any(json.loads(row['offset_val'])==automatic.checkpoint(final['J'])['cursor']['offset'] for row in final['D'])
    assert outcome['physicalRelease'] and outcome['allFieldsEqual']
    events={};orders={};expected=[];seen=set();members={};committed={r['group_id'] for r in final['T']}
    identity=json.loads(resumed['J']['boundary']['upperBound'])['groupProtocol']
    protocol={k:identity[k] for k in ('version','replicationId','generation','sourceLineage')}
    for process in ('bootstrap','session','recovered'):
        current=[json.loads(line[7:]) for line in read(prefix+process+'.log').decode().splitlines() if line.startswith('WINDOW ')]
        events[process]=current;raw={};batches={};confirmed=set();covered=set()
        for reader in sorted({e['reader'] for e in current if e['kind']=='ENGINE_CONFIG'}):
            ack=[e['callback'] for e in current if e['kind']=='RAW_ACK_RETURNED' and e['reader']==reader]
            assert ack==list(range(len(ack))),f'{process}/{reader}: not an ACK prefix'
            orders[f'{process}/{reader}']=ack
        for e in current:
            kind=e['kind']
            if kind=='RAW_CALLBACK':
                keys=[]
                for record in e['records']:
                    v=record['value'];s=v['source'];before,after=v['before'],v['after'];image=before if v['op']=='d' else after
                    physical=(s['db'],s['table'],s.get('file'),s.get('pos'),s.get('row'),v['op'])
                    if v['op']=='r' or physical not in seen:
                        expected.append(dict(op={'r':'I','c':'I','u':'U','d':'D'}[v['op']],row_id=str(image['id']),
                            old_revision=str(before['revision']) if before else None,new_revision=str(after['revision']) if after else None,
                            old_label=before['label'] if before else None,new_label=after['label'] if after else None))
                        seen.add(physical)
                    if process!='bootstrap':
                        headers={h['key'] for h in record['headers']}
                        role=('pk.' if headers & {'__debezium.newkey','__debezium.oldkey'} else 'single.')+v['op']
                        gid=automatic.digest([protocol,s['db'],s['table'],s['file'],s['pos'],s['row']]);key=(gid,role)
                        n=record['normalizedEnvelope'];assert n is not None
                        if key in members:assert members[key]==n
                        members[key]=n;keys.append(key)
                raw[(e['reader'],e['callback'])]=keys
            elif kind=='SINK_COMMITTED':batches[e['batch']['batchId']]=e['batch']
            elif kind=='JOURNAL_CONFIRMED':
                bid=e['checkpoint']['batchId'];assert bid in batches;confirmed.add(bid)
                if process!='bootstrap':covered.update((group['groupId'],m['role']) for group in batches[bid]['rows'] for m in group['members'])
            elif kind=='ACK_BEGIN':assert e['batchId'] in confirmed
            elif kind=='RAW_ACK_BEGIN' and process!='bootstrap':assert all(k in covered for k in raw[(e['reader'],e['callback'])])
    actual=data('post-run-audit')
    assert len(actual)==68 and expected==[{k:v for k,v in row.items() if k!='audit_id'} for row in actual]
    ledger={}
    for row in final['T']:
        group=json.loads(row['evidence'])['group'];assert row['group_id']==group['groupId']
        for member in group['members']:
            key=(group['groupId'],member['role']);assert key not in ledger
            assert member['eventId']==automatic.digest(list(key)) and member['envelope']==members[key]
            ledger[key]=member['envelope']
    assert set(ledger)==set(members) and len(committed)==3
    sequence=events['session']
    def first(kind,start=0):return next(i for i,e in enumerate(sequence[start:],start) if e['kind']==kind)
    cut_i=first('CUTPOINT');stop_i=first('OWNER_STOP_REQUESTED');j=first('JOURNAL_CONFIRMED',cut_i)
    ack=first('ACK_RETURNED',j);close=first('ENGINE_CLOSED');end=first('OWNER_STOPPED')
    assert cut_i<stop_i<j<ack<close<end
    assert sum(e['kind']=='ENGINE_CLOSED' for e in sequence)==1
    return dict(artifactVerification='PASS',formalCases=27,automatic=a,regressions=g,
        ownerStop=dict(scenario='stop-T',classification='CONVERGED',sameExecution=True,admittedCheckpointConfirmed=True,
            actualReaderClosedBeforeOwnerStopped=True,targetGroups=len(final['T']),auditRows=len(actual),recoveryAttemptsStarted=1,
            rawAckOrders=orders,order=['T_COMMITTED','STOP_REQUESTED','J_CONFIRMED','ACK_RETURNED','READER_CLOSED','OWNER_STOPPED'],**diff))

if __name__=='__main__':
    with tarfile.open(ROOT/'evidence.tar.gz','r:gz') as archive:
        files={m.name:archive.extractfile(m).read() for m in archive if m.isfile()}
    assert set(files)==set(json.loads(files['manifest.json'])['files'])|{'manifest.json'}
    print(json.dumps(verify(files.__getitem__),ensure_ascii=False,indent=2))
