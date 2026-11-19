#!/usr/bin/env python3
"""Re-use immutable historical verifier for seven unchanged windows; check two real release gates."""
import hashlib
import importlib.util
import json
import sys
import tarfile
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parent
OLD=ROOT.parent/'2026-10-20-execution-plan-boundary'/'verify.py'
spec=importlib.util.spec_from_file_location('historical_windows',OLD)
historical=importlib.util.module_from_spec(spec);spec.loader.exec_module(historical)
historical.CASES=('N0','J1','J2','C1','C0','K1','K2')

def verify(read):
    manifest=json.loads(read('manifest.json'))
    for n,sha in manifest['files'].items():assert hashlib.sha256(read(n)).hexdigest()==sha,n
    sub={p.removeprefix('regression/'):sha for p,sha in manifest['files'].items() if p.startswith('regression/')}
    def old_read(n):
        if n=='manifest.json':return json.dumps({'files':sub}).encode()
        return read('regression/'+n)
    report=historical.verify(old_read)
    for case in historical.CASES:
        assert json.loads(old_read(f'{case}/final.json'))['audit']==json.loads(old_read(f'{case}/post-run-audit.json'))
    report.update(scenarioObservations=7,converged=7,automaticRecoveryFailures=0,
        scope='seven historical windows rerun with new production code; old C2 failure artifacts unchanged')
    releases=[]
    for case in ('none','cancel-T'):
        def data(n):return json.loads(read(f'release/{case}/{n}.json'))
        base,final,outcome,released=[data(n) for n in ('baseline','final','outcome','released')]
        diff=historical.differences(final['comparison']);assert not any(diff[n] for n in ('missingKeys','extraKeys','fieldDifferences'))
        assert diff['sourceRows']==diff['targetRows']==64 and released['status']=='Released' and released['liveRunners']==0
        assert outcome['allFieldsEqual'] and outcome['physicalRelease']
        events=outcome['events'];assert sum(e['kind']=='ENGINE_CLOSED' for e in events)==1
        closed=next(i for i,e in enumerate(events) if e['kind']=='ENGINE_CLOSED')
        release_index=next(i for i,e in enumerate(events) if e['kind']=='CANCEL_RESULT' and e['status']=='Released')
        assert closed<release_index
        ack=[e['callback'] for e in events if e['kind']=='RAW_ACK_RETURNED'];assert ack==list(range(len(ack)))
        if case=='cancel-T':
            cut=data('cutpoint');pending=data('cancel-pending')
            assert cut['T']!=base['T'] and historical.checkpoint(cut['J'])==historical.checkpoint(base['J'])
            assert pending['status']=='TimedOut'
            pending_index=next(i for i,e in enumerate(events) if e['kind']=='CANCEL_RESULT' and e['status']=='TimedOut')
            assert pending_index<closed
            assert historical.checkpoint(final['J'])['sequence']>historical.checkpoint(base['J'])['sequence']
        expected=[]
        for name in ('bootstrap.log','session.log'):
            for line in read(f'release/{case}/{name}').decode().splitlines():
                if not line.startswith('WINDOW '):continue
                event=json.loads(line[7:])
                if event['kind']!='RAW_CALLBACK':continue
                for record in event['records']:
                    v=record['value'];before,after=v['before'],v['after'];image=before if v['op']=='d' else after
                    expected.append(dict(op={'r':'I','c':'I','u':'U','d':'D'}[v['op']],row_id=str(image['id']),
                        old_revision=str(before['revision']) if before else None,new_revision=str(after['revision']) if after else None,
                        old_label=before['label'] if before else None,new_label=after['label'] if after else None))
        actual=data('post-run-audit')
        assert expected==[{k:v for k,v in row.items() if k!='audit_id'} for row in actual],'cancellation audit differs from real raw records'
        assert len(actual)==(67 if case=='cancel-T' else 66)
        releases.append(dict(scenario=case,physicalRelease=True,liveRunners=0,auditRows=len(actual),**diff))
    return dict(artifactVerification='PASS',historicalRecovery=report,sessionRelease=releases)

if __name__=='__main__':
    with tarfile.open(ROOT/'evidence.tar.gz','r:gz') as archive:
        files={member.name:archive.extractfile(member).read() for member in archive if member.isfile()}
    print(json.dumps(verify(files.__getitem__),ensure_ascii=False,indent=2))
