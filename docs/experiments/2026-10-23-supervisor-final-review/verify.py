#!/usr/bin/env python3
"""Offline final-review evidence verification. No database, ACK, recovery or process control."""
from pathlib import Path
import hashlib, io, json, runpy, subprocess, sys, tarfile, zipfile
from cryptography.exceptions import InvalidSignature
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
sha=lambda b:hashlib.sha256(b).hexdigest()

def main():
    for line in (HERE/'SHA256SUMS').read_text().splitlines():
        digest,name=line.split('  ',1);assert sha((HERE/name).read_bytes())==digest,name
    # The previous six-suite snapshot remains immutable, including all failed attempts.
    subprocess.run([sys.executable,str(HERE.parent/'2026-10-23-supervisor-jvm-recovery/verify.py'),'--archived-source'],check=True)
    report=json.loads((HERE/'result.json').read_text())
    parts=json.loads((HERE/'archive-parts.json').read_text())
    raw=b''.join((HERE/p).read_bytes() for p in parts['parts'])
    assert sha(raw)==parts['sha256'] and len(raw)==parts['bytes']
    manifest=json.loads((HERE/'archive-manifest.json').read_text());files={}
    with tarfile.open(fileobj=io.BytesIO(raw),mode='r:gz') as archive:
        for item in archive.getmembers():
            assert item.isfile() and not Path(item.name).is_absolute() and '..' not in Path(item.name).parts
            data=archive.extractfile(item).read();assert item.name not in files and sha(data)==manifest[item.name]
            files[item.name]=data
    assert set(files)==set(manifest)
    source=json.loads((HERE/'source-manifest.json').read_text())
    previous=json.loads((HERE.parent/'2026-10-23-supervisor-jvm-recovery/source-manifest.json').read_text())
    delta={p for p in source if source[p]!=previous[p]}
    assert set(source)==set(previous) and delta==set(report['sourceDelta'])
    assert delta=={'server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/ManagedJvmIsolation.scala',
                   'server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotGuardianService.scala',
                   'server/src/test/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotSupervisorStoreSpec.scala'}
    boundary=json.loads((HERE.parent/'2026-10-23-supervisor-jvm-recovery/source-boundary.json').read_text())
    for p,digest in {**boundary['unchangedDataAndPersistenceSources'],**boundary['previousProbeSourcesUnchanged']}.items():assert source[p]==digest
    for run in report['runtimes']:
        prefix='runners/'+run['runtime']+'/'
        assert json.loads(files[prefix+'result.json'])['exitCode']==0
        assert json.loads(files[prefix+'source-manifest.json'])==source
    selected=report['runtimes'][0]['runtime']
    with zipfile.ZipFile(io.BytesIO(files['runners/'+selected+'/source.zip'])) as z:
        assert set(z.namelist())==set(source)
        for p,digest in source.items():
            assert sha(z.read(p))==digest
            if '--archived-source' not in sys.argv:assert sha((REPO/p).read_bytes())==digest,p
    package=report['package'];data=files['build/'+package['file']]
    assert sha(data)==package['sha256']
    with zipfile.ZipFile(io.BytesIO(data)) as built,zipfile.ZipFile(io.BytesIO(files['runners/'+selected+'/classes.jar'])) as frozen,zipfile.ZipFile(io.BytesIO(files['runners/'+selected+'/test-classes.jar'])) as tests:
        names=[p for p in frozen.namelist() if p.endswith('.class')]
        assert len(names)==package['classesMatchedFrozen'] and all(built.read(p)==frozen.read(p) for p in names)
        assert not set(built.namelist()) & {p for p in tests.namelist() if p.endswith('.class')}
        for dialect in ['mysql','h2']:
            p='db/'+dialect+'/pekko-snapshot-supervisor-schema.sql';assert built.read(p)==frozen.read(p)
        assert 'Main-Class: cn.xuyinyin.magic.PekkoServer' in built.read('META-INF/MANIFEST.MF').decode()
    library=runpy.run_path(str(HERE.parent/'2026-10-23-supervisor-jvm-recovery/verify.py'))
    cbor=runpy.run_path(str(HERE.parent/'2026-10-22-table-scheduling/verify.py'))['cbor']
    outcomes=[library['validate_case'](case,files.__getitem__,cbor) for case in report['cases']]
    assert len(outcomes)==32 and sum(o[0]=='safe_pause' for o in outcomes)==3 and sum(o[1] for o in outcomes)==290
    live=[json.loads(line) for line in files['logs/liveness.jsonl'].decode().splitlines()]
    assert len(live)==3 and all(r['readOnlyObservation'] for r in live)
    for case in (c for c in report['cases'] if c['case'].startswith('S15_')):
        row=next(r for r in live if case['archivePrefix'].endswith('/'+r['case']))
        assert len(row['workers'])==1 and row['workers'][0]['aliveObserved']==(case['case']!='S15_AFTER_PROOF')
    prefix=report['mainCase']['archivePrefix']+'/'
    before=json.loads(files[prefix+'before-restart.json']);final=json.loads(files[prefix+'final.json'])
    assert library['equal_rows'](final['data']['comparison'])==10
    assert final['data']['T']==before['data']['T'] and final['data']['audit']==before['data']['audit']
    assert json.loads(files[prefix+'invalid-profile-rejected.json'])['exitCode']!=0
    assert all(not p['alive'] for p in json.loads(files[prefix+'cleanup.json'])['processes'])
    takeover=runpy.run_path(str(HERE.parent/'2026-10-23-snapshot-takeover/verify.py'))['validate_case']
    historical=report['historicalAttempts'];assert len(historical)==4
    failed=next(r for r in historical if r['exitCode']==1 and r['runtime'].startswith('takeover-'))
    failed_log=files['runners/'+failed['runtime']+'/runner.log'].decode(errors='replace')
    assert 'Tests: succeeded 21, failed 1' in failed_log and 'no actual residual old transaction after process exit' in failed_log
    directory=next(p for p in failed['caseDirectories'] if '/KILL_DB_TARGET_WAIT-' in p)
    prefix='cases/snapshot-takeover/'+Path(directory).name+'/'
    assert prefix+'result.json' not in files
    before=json.loads(files[prefix+'gate-before-kill.json']);after=json.loads(files[prefix+'post-exit-observations.json'])
    assert any(not o['sessions'] and o['transactions'] for o in after)
    assert {t['trx_id'] for o in before for t in o['transactions']} & {t['trx_id'] for o in after for t in o['transactions']}
    recheck=next(r for r in historical if r['exitCode']==0 and r['runtime'].startswith('takeover-'));assert len(recheck['caseDirectories'])==1
    directory=recheck['caseDirectories'][0]
    assert takeover(dict(archivePrefix='cases/snapshot-takeover/'+Path(directory).name),files.__getitem__,cbor)==10
    failed_split=next(r for r in historical if r['exitCode']==1 and r['runtime'].startswith('snapshot-'))
    assert 'Tests: succeeded 21, failed 1' in files['runners/'+failed_split['runtime']+'/runner.log'].decode(errors='replace')
    directory=next(p for p in failed_split['caseDirectories'] if '/QUERY_TIMEOUT-' in p)
    prefix='cases/snapshot-splits/'+Path(directory).name+'/'
    stderr=files[prefix+'original.stderr'].decode()
    assert prefix+'result.json' not in files and 'Connection attempt exceeded defined timeout' in stderr and 'withConnection' in stderr
    health=json.loads(files['logs/jdbc-health.json']);assert health['success'] and len(health['connectionMillis'])==10
    recheck_split=next(r for r in historical if r['exitCode']==0 and r['runtime'].startswith('snapshot-'))
    assert len(recheck_split['caseDirectories'])==1
    directory=recheck_split['caseDirectories'][0]
    assert library['equal_rows'](json.loads(files['cases/snapshot-splits/'+Path(directory).name+'/final.json'])['comparison'])==65
    assert len(report['takeoverCases'])==22
    for case in report['takeoverCases']:assert takeover(case,files.__getitem__,cbor)==10
    probe=runpy.run_path(str(HERE.parent/'2026-10-23-guardian-restart/verify.py'))['verify_case']
    probes=[probe(case,files.__getitem__,cbor) for case in report['probeCases']]
    assert len(probes)==10 and probes.count('safe_pause')==2
    splits={'converged':0,'safe_pause':0}
    for case in report['splitCases']:
        result=json.loads(files[case['archivePrefix']+'/'+case['outcomeFile']])
        assert result==case['outcome']
        negative=result.get('classification')=='safe_pause'
        splits['safe_pause' if negative else 'converged']+=1
        if not negative:library['equal_rows'](json.loads(files[case['archivePrefix']+'/final.json'])['comparison'])
    assert splits=={'converged':16,'safe_pause':6}
    log=files['logs/backend-final.log'].decode(errors='replace')
    assert 'Tests: succeeded 580, failed 0' in log and 'Suites: completed 110, aborted 0' in log and 'All tests passed.' in log
    red=files['logs/identity-red.log'].decode();green=files['logs/identity-green.log'].decode()
    assert '"NOT_STARTED" was not equal to "UNKNOWN"' in red and 'Tests: succeeded 0, failed 1' in red
    assert 'Tests: succeeded 16, failed 0' in green
    cleanup=report['cleanup'];assert cleanup['clients']==cleanup['transactions']==0 and cleanup['stopped'] and cleanup['volumePreserved']
    # Independent corruption controls also guard the reused semantic assertions.
    case=next(c for c in report['cases'] if c['case']=='S6_T_BEFORE_J');path=case['archivePrefix']+'/final.json';rejected=0
    for change in [lambda v:v['data']['comparison']['target'][0].update(label='corrupt'),lambda v:v['data']['T'][0].update(cursor_value='corrupt')]:
        value=json.loads(files[path]);change(value)
        try:library['validate_case'](case,lambda p:json.dumps(value).encode() if p==path else files[p],cbor)
        except (AssertionError,KeyError,StopIteration):rejected+=1
        else:raise AssertionError('corrupted final evidence accepted')
    path=case['archivePrefix']+'/guardian-wire.jsonl';frames=[json.loads(line) for line in files[path].decode().splitlines()];frames[0]['response']['payload']+=' '
    try:library['validate_case'](case,lambda p:('\n'.join(json.dumps(r) for r in frames)+'\n').encode() if p==path else files[p],cbor)
    except InvalidSignature:rejected+=1
    else:raise AssertionError('corrupted signature accepted')
    print(json.dumps(dict(archiveIntegrity=True,currentSourceChecked='--archived-source' not in sys.argv,backendTests=580,matrixConverged=29,matrixSafePause=3,matrixRows=290,fieldsPerRow=6,actualMain=True,takeoverCases=22,probeCases=10,splitOutcomes=splits,identityFailureRedGreen=True,historicalTargetWindowFailurePreserved=True,negativeControlsRejected=rejected,previousCdcEvidence='archived unchanged data protocol; not rerun after Process-identity guard')))

if __name__=='__main__':main()
