#!/usr/bin/env python3
"""Use this checkout's SBT dependency export; compile the bridge freshly, without a database."""
import datetime, hashlib, json, os, pathlib, platform, subprocess, tempfile
here = pathlib.Path(__file__).resolve().parent
repo = here.parents[2]
java_home = pathlib.Path(os.environ.get('PEKKO_PROBE_JAVA_HOME', '/Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home'))
cp_file = repo / 'pekko-server/target/streams/compile/dependencyClasspath/_global/streams/export'
cp = cp_file.read_text().strip()
compiler = next(pathlib.Path.home().glob('Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-compiler/2.13.12/scala-compiler-2.13.12.jar'))
reflect = compiler.parent.parent.parent / 'scala-reflect/2.13.12/scala-reflect-2.13.12.jar'
bridge = repo / 'pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumBatchBridge.scala'
marker = repo / 'pekko-server/src/main/scala/cn/xuyinyin/magic/common/CborSerializable.scala'
with tempfile.TemporaryDirectory(prefix='pekko-actor-probe-') as out:
    subprocess.run([str(java_home/'bin/java'), '-cp', f'{compiler}:{reflect}:{cp}', 'scala.tools.nsc.Main', '-classpath', cp, '-d', out, str(bridge), str(marker)], check=True)
    subprocess.run([str(java_home/'bin/javac'), '-cp', f'{out}:{cp}', '-d', out, str(here/'ActorExecutionProbe.java')], check=True)
    logback = pathlib.Path(out)/'logback.xml'
    logback.write_text('<configuration><root level="OFF"/></configuration>')
    runtime_cp = f'{out}:{repo}/pekko-server/src/main/resources:{cp}'
    proc = subprocess.run([str(java_home/'bin/java'), f'-Dlogback.configurationFile={logback}', '-cp', runtime_cp, 'ActorExecutionProbe'], text=True, capture_output=True, timeout=120)
    (here/'result.txt').write_text(proc.stdout + proc.stderr)
    metadata = {
        'run_finished_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'platform': platform.platform(),
        'java': subprocess.run([str(java_home/'bin/java'), '-version'],text=True,capture_output=True).stderr.strip(),
        'project_head': subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),
        'marker_sha256': hashlib.sha256(marker.read_bytes()).hexdigest(),
        'bridge_sha256': hashlib.sha256(bridge.read_bytes()).hexdigest(),
        'probe_sha256': hashlib.sha256((here/'ActorExecutionProbe.java').read_bytes()).hexdigest(),
        'configs_sha256': {f: hashlib.sha256((repo/'pekko-server/src/main/resources'/f).read_bytes()).hexdigest() for f in ['application.conf','application-dev.conf','application-prod.conf','application-jdbc.conf','reference.conf']},
        'dependency_export_sha256': hashlib.sha256(cp_file.read_bytes()).hexdigest(),
        'pekko_jars': [pathlib.Path(p).name for p in cp.split(':') if '/pekko-' in p],
        'exit_code': proc.returncode,
    }
    (here/'environment.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
    print(proc.stdout + proc.stderr)
    raise SystemExit(proc.returncode)
