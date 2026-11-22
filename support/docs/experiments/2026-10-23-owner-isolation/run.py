#!/usr/bin/env python3
"""Explicit live experiments; creates isolated random test schemas, never deletes them.
Build Test/fullClasspath first with Java17; credentials are read only from environment.
"""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,time,zipfile
repo=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('suite',choices=['isolation'])
parser.add_argument('--scenario')
args=parser.parse_args()
assert os.environ.get('RUN_MYSQL_OWNER_ISOLATION')=='1','set RUN_MYSQL_OWNER_ISOLATION=1 for isolated MySQL only'
for key in ['CDC_WINDOW_MYSQL_HOST','CDC_WINDOW_MYSQL_PORT','CDC_WINDOW_MYSQL_USER','CDC_WINDOW_MYSQL_PASSWORD']:
    assert key in os.environ,'missing environment variable '+key
cp=(repo/'server/target/streams/test/fullClasspath/_global/streams/export').read_text().strip()
assert 'protobuf-java-4.33.4.jar' in cp,'rebuild/export this repository Test/fullClasspath'
java=Path(os.environ['JAVA_HOME'])/'bin/java' if 'JAVA_HOME' in os.environ else Path(shutil.which('java'))
version=subprocess.run([str(java),'-version'],capture_output=True,text=True)
assert '17.' in version.stderr,'Java17 required'
suites={'isolation':['RealMySQLOwnerIsolationSpec']}
out=repo/'target/owner-isolation-runners'/f'{args.suite}-{time.time_ns()}';out.mkdir(parents=True)
parts=[]
for item in cp.split(os.pathsep):
    path=Path(item)
    if path.is_dir() and path.is_relative_to(repo):
        dest=out/(path.name+'.jar')
        with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as archive:
            for f in sorted(path.rglob('*')):
                if f.is_file():archive.write(f,str(f.relative_to(path)))
        parts.append(str(dest))
    else:parts.append(item)
source=[f for f in (repo/'server/src').rglob('*') if f.is_file()]+[repo/'build.sbt']
with zipfile.ZipFile(out/'source.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for f in sorted(source):archive.write(f,str(f.relative_to(repo)))
(out/'source-manifest.json').write_text(json.dumps({str(f.relative_to(repo)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(source)},indent=2))
cmd=[str(java),'-Xmx1G','-Duser.timezone=UTC','-cp',os.pathsep.join(parts),'org.scalatest.tools.Runner','-o']
for suite in suites[args.suite]:cmd+=['-s','cn.xuyinyin.magic.workflow.integration.'+suite]
if args.scenario:cmd+=['-z',args.scenario]
env=os.environ.copy()
start=time.time()
with (out/'runner.log').open('w') as log:
    result=subprocess.run(cmd,cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT)
(out/'result.json').write_text(json.dumps({'exitCode':result.returncode,'seconds':time.time()-start},indent=2))
print('Results:',out,'exit:',result.returncode)
raise SystemExit(result.returncode)
