#!/usr/bin/env python3
"""Freeze each attempt; execute real disposable source/state MySQL tests, never delete evidence."""
import argparse, hashlib, json, os, subprocess, time, zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser();p.add_argument('suite',choices=['unit','matrix','regression']);p.add_argument('--case');p.add_argument('--settings-file',type=Path)
a=p.parse_args();env=os.environ.copy()
if a.settings_file:
    settings=json.loads(a.settings_file.read_text());env.update(CDC_WINDOW_MYSQL_USER=settings['user'],CDC_WINDOW_MYSQL_PASSWORD=settings['password'])
env.update(CDC_WINDOW_MYSQL_HOST='127.0.0.1',CDC_WINDOW_MYSQL_PORT='13365',CAPTURE_STATE_PORT='13366',RUN_SHARED_CAPTURE_MATRIX='1',RUN_MYSQL_CDC_RECOVERY_WINDOWS='1',RUN_MYSQL_SNAPSHOT_SPLITS='1')
env['CAPTURE_TEST_URL']='jdbc:mysql://127.0.0.1:13365/pekko_test_shared_wire?useSSL=false&allowPublicKeyRetrieval=true'
env['CAPTURE_STATE_URL']='jdbc:mysql://127.0.0.1:13366/pekko_test_shared_state?useSSL=false&allowPublicKeyRetrieval=true'
java=Path(env['JAVA_HOME'])/'bin/java';assert '17.' in subprocess.run([str(java),'-version'],capture_output=True,text=True).stderr
out=repo/'target/shared-capture-runners'/f'{a.suite}-{a.case or "all"}-{time.time_ns()}';out.mkdir(parents=True)
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
with zipfile.ZipFile(out/'source.zip','w',zipfile.ZIP_DEFLATED) as z:
    for f in files:z.write(f,str(f.relative_to(repo)))
suites={'unit':['connectors.mysql.source.CaptureProtocolSpec','connectors.mysql.source.SharedCaptureStoreSpec','connectors.mysql.source.SharedCaptureReaderSpec','connectors.mysql.sink.MySQLCdcApplySinkNodeSpec'],
        'matrix':['workflow.integration.SharedCaptureMatrixSpec'],
        'regression':['workflow.integration.RealMySQLCdcRecoveryWindowsSpec','workflow.integration.RealMySQLCdcAutomaticRecoverySpec','workflow.integration.RealMySQLCdcSessionReleaseSpec']}
cmd=[str(java),'-Xmx1G','-Duser.timezone=UTC','-cp',os.pathsep.join(parts),'org.scalatest.tools.Runner','-o']
for s in suites[a.suite]:
    cmd+=['-s','cn.xuyinyin.magic.'+s]
    if a.case:cmd+=['-z',a.case]
roots=[repo/'target'/name for name in (['cdc-recovery-windows','cdc-automatic-recovery','cdc-session-release'] if a.suite=='regression' else ['shared-capture-matrix'])]
def directories(): return {p for root in roots if root.exists() for p in root.iterdir() if p.is_dir()}
before=directories();start=time.time()
print('Attempt:',out,flush=True)
with (out/'runner.log').open('w') as log:r=subprocess.run(cmd,cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT)
(out/'result.json').write_text(json.dumps({'exitCode':r.returncode,'seconds':time.time()-start,'sourceManifestSha256':hashlib.sha256((out/'source-manifest.json').read_bytes()).hexdigest(),'caseDirectories':[str(x.relative_to(repo)) for x in sorted(directories()-before)]},indent=2))
print('Exit:',r.returncode,'Evidence:',out,flush=True);raise SystemExit(r.returncode)
