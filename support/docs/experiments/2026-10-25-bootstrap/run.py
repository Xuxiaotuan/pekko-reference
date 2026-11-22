#!/usr/bin/env python3
"""Freeze each attempt; execute real disposable source/state MySQL tests, never delete evidence."""
import argparse, hashlib, json, os, subprocess, time, zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser();p.add_argument('suite',choices=['unit','matrix','regression']);p.add_argument('--case');p.add_argument('--settings-file',type=Path);p.add_argument('--wait-for-matrix',type=Path)
a=p.parse_args();env=os.environ.copy()
if a.settings_file:
    settings=json.loads(a.settings_file.read_text());env.update(CDC_WINDOW_MYSQL_USER=settings['user'],CDC_WINDOW_MYSQL_PASSWORD=settings['password'])
env.update(CDC_WINDOW_MYSQL_HOST='127.0.0.1',CDC_WINDOW_MYSQL_PORT='13365',CAPTURE_STATE_PORT='13366',RUN_SHARED_CAPTURE_MATRIX='1',RUN_MYSQL_CDC_RECOVERY_WINDOWS='1',RUN_MYSQL_SNAPSHOT_SPLITS='1')
env['CAPTURE_TEST_URL']='jdbc:mysql://127.0.0.1:13365/pekko_test_shared_wire?useSSL=false&allowPublicKeyRetrieval=true'
env['CAPTURE_STATE_URL']='jdbc:mysql://127.0.0.1:13366/pekko_test_shared_state?useSSL=false&allowPublicKeyRetrieval=true'
java=Path(env['JAVA_HOME'])/'bin/java';assert '17.' in subprocess.run([str(java),'-version'],capture_output=True,text=True).stderr
out=repo/'target/bootstrap-runners'/f'{a.suite}-{a.case or "all"}-{time.time_ns()}';out.mkdir(parents=True)
print('Building:',out,flush=True)
with (out/'build.log').open('w') as log:
    built=subprocess.run(['sbt','project pekko-server','Test / compile','Test / fullClasspath'],cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT)
if built.returncode:
    print('Build failed; see',out/'build.log');raise SystemExit(built.returncode)
cp=(repo/'server/target/streams/test/fullClasspath/_global/streams/export').read_text().strip()
parts=[]
for i,item in enumerate(cp.split(os.pathsep)):
    src=Path(item)
    if src.is_dir() and src.is_relative_to(repo):
        dest=out/f'classes-{i}.jar'
        with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as z:
            for f in sorted(src.rglob('*')):
                if f.is_file():z.write(f,str(f.relative_to(src)))
        parts.append(str(dest))
    else:parts.append(item)
files=sorted(f for f in (repo/'server/src').rglob('*') if f.is_file())+[repo/'build.sbt']
manifest={str(f.relative_to(repo)):hashlib.sha256(f.read_bytes()).hexdigest() for f in files}
(out/'source-manifest.json').write_text(json.dumps(manifest,indent=2))
env['BOOTSTRAP_EVIDENCE_RUN']=out.name
env['BOOTSTRAP_SOURCE_MANIFEST_SHA256']=hashlib.sha256((out/'source-manifest.json').read_bytes()).hexdigest()
with zipfile.ZipFile(out/'source.zip','w',zipfile.ZIP_DEFLATED) as z:
    for f in files:z.write(f,str(f.relative_to(repo)))
suites={'unit':['workflow.bootstrap.BootstrapProtocolSpec','workflow.bootstrap.BootstrapStoreSpec','workflow.bootstrap.BootstrapTargetSpec','workflow.bootstrap.BootstrapSnapshotSpec','workflow.bootstrap.BootstrapJournalSpec','workflow.execution.SnapshotSplitSerializationSpec','workflow.checkpoint.CheckpointModelsSpec','workflow.actors.EventSourcedWorkflowActorRecoverySpec','connectors.mysql.source.BootstrapFenceSpec','connectors.mysql.source.CaptureProtocolSpec','connectors.mysql.source.SharedCaptureStoreSpec','connectors.mysql.source.SharedCaptureReaderSpec','connectors.mysql.sink.MySQLCdcApplySinkNodeSpec'],
        'matrix':['workflow.integration.BootstrapMatrixSpec'],
        'regression':['workflow.integration.SharedCaptureMatrixSpec','workflow.integration.RealMySQLCdcRecoveryWindowsSpec','workflow.integration.RealMySQLCdcAutomaticRecoverySpec','workflow.integration.RealMySQLCdcSessionReleaseSpec']}
base=[str(java),'-Xmx1G','-Duser.timezone=UTC','-cp',os.pathsep.join(parts),'org.scalatest.tools.Runner','-o']
def command(names):
    cmd=list(base)
    for name in names:
        cmd+=['-s','cn.xuyinyin.magic.'+name]
        if a.case:cmd+=['-z',a.case]
    return cmd
parts_to_run=[suites[a.suite]]
if a.suite=='regression' and a.wait_for_matrix:
    # Independent old recovery tests first; shared-capture suite's final purge waits for all bootstrap Readers.
    parts_to_run=[suites['regression'][1:],suites['regression'][:1]]
roots=[repo/'target'/name for name in (['shared-capture-matrix','cdc-recovery-windows','cdc-automatic-recovery','cdc-session-release'] if a.suite=='regression' else ['bootstrap-matrix/'+out.name])]
def directories(): return {p for root in roots if root.exists() for p in root.iterdir() if p.is_dir()}
before=directories();start=time.time()
print('Attempt:',out,flush=True)
codes=[]
with (out/'runner.log').open('w') as log:
    for i,names in enumerate(parts_to_run):
        if i==1 and a.wait_for_matrix:
            print('Waiting for bootstrap Readers to exit before shared capture/binlog retention regressions',flush=True)
            while not a.wait_for_matrix.exists():time.sleep(1)
        completed=subprocess.run(command(names),cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT);codes.append(completed.returncode)
code=next((x for x in codes if x),0)
(out/'result.json').write_text(json.dumps({'exitCode':code,'partExitCodes':codes,'seconds':time.time()-start,'sourceManifestSha256':hashlib.sha256((out/'source-manifest.json').read_bytes()).hexdigest(),'caseDirectories':[str(x.relative_to(repo)) for x in sorted(directories()-before)]},indent=2))
print('Exit:',code,'Evidence:',out,flush=True);raise SystemExit(code)
