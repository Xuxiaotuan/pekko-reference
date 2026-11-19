#!/usr/bin/env python3
"""Test facilities only. freeze on macOS; matrix as root in the dedicated Linux VM.
Never accepts an arbitrary unit/PID/database. All live resources are uniquely test-owned.
"""
from pathlib import Path
import argparse,base64,hashlib,json,os,pwd,shutil,subprocess,sys,time,uuid,zipfile

REPO=Path(__file__).resolve().parents[3]
BASE=Path('/var/lib/pekko-guardian-os-probe')
SCENARIOS=['G5_BEFORE_FORK','G6_FORK_UNRECORDED','G7_START_ACK_LOST','G7_EXECUTED_ACK_LOST',
 'G10_SOURCE','G10_TARGET','G10_JOURNAL','G10_SNAPSHOT','G14_CONTROL_DENIED','G14_SIGNAL_ONLY','G15_LEGACY_UNBOUND','G15_UNIT_MISSING']

def save(path,value):
 path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def load(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def command(args,check=True,timeout=30,**kw):
 p=subprocess.run(args,text=True,capture_output=True,timeout=timeout,**kw)
 if check and p.returncode:raise RuntimeError('command failed: '+repr(args[:4])+' '+p.stderr[-800:])
 return {'command':args,'exitCode':p.returncode,'stdout':p.stdout,'stderr':p.stderr}
def wait(label,predicate,seconds=75):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if predicate():return
  time.sleep(.15)
 raise AssertionError('window not established: '+label)

def cleanup_each(names,stop):
 """One uncertain stop must not prevent attempting the remaining test-owned services."""
 result=[]
 for name in names:
  try:result.append({'unit':name,'confirmedCall':True,'result':stop(name)})
  except Exception as error:result.append({'unit':name,'confirmedCall':False,'errorClass':type(error).__name__,'message':str(error)})
 return result

def freeze():
 cp=(REPO/'server/target/streams/test/fullClasspath/_global/streams/export').read_text().strip().split(os.pathsep)
 out=REPO/'target/guardian-os-runtimes'/('r-'+str(time.time_ns()));out.mkdir(parents=True)
 for entry in cp:
  p=Path(entry)
  if p.is_dir():
   dest=out/(p.name+'.jar')
   with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as z:
    for f in sorted(p.rglob('*')):
     if f.is_file():z.write(f,str(f.relative_to(p)))
  else:
   dest=out/p.name
   if dest.exists():assert sha(dest)==sha(p),'classpath basename collision'
   else:shutil.copyfile(p,dest)
 sources=sorted(p for root in [REPO/'server/src',REPO/'project'] for p in root.rglob('*') if p.is_file() and 'target' not in p.parts and '.bloop' not in p.parts)
 sources += [REPO/'build.sbt',Path(__file__),Path(__file__).with_name('verify.py')]
 sources=sorted(set(p for p in sources if p.exists()))
 with zipfile.ZipFile(out/'source.zip','w',zipfile.ZIP_DEFLATED) as z:
  for f in sources:z.write(f,str(f.relative_to(REPO)))
 save(out/'source-manifest.json',{str(p.relative_to(REPO)):sha(p) for p in sources})
 save(out/'runtime-manifest.json',{p.name:sha(p) for p in sorted(out.glob('*.jar'))})
 print(out)

PROPS=['Id','LoadState','ActiveState','SubState','InvocationID','ControlGroup','Job','Restart','KillMode','ExitType',
 'User','Delegate','ProtectControlGroups','NoNewPrivileges','FragmentPath','Result','ExecMainPID','ExecMainCode','ExecMainStatus']
def unit(name):
 assert name.startswith('pekko-osprobe-') and name.endswith('.service')
 r=command(['systemctl','show',name]+['--property='+p for p in PROPS],check=False)
 values=dict(line.split('=',1) for line in r['stdout'].splitlines() if '=' in line)
 cg=values.get('ControlGroup','');path=Path('/sys/fs/cgroup'+cg) if cg else None
 members=[];events=None
 if path and path.exists():
  events=(path/'cgroup.events').read_text()
  for f in [path/'cgroup.procs',*path.glob('**/cgroup.procs')]:
   if f.exists():members+=f.read_text().split()
 processes=[]
 for pid in sorted(set(members),key=int):
  p=Path('/proc')/pid
  try:processes.append({'pid':int(pid),'stat':(p/'stat').read_text(),'cgroup':(p/'cgroup').read_text(),
   'command':(p/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace'),'status':(p/'status').read_text()})
  except FileNotFoundError:processes.append({'pid':int(pid),'exitedDuringObservation':True})
 return {'properties':values,'events':events,'members':processes,'atNs':time.time_ns(),'query':r}

def matrix(runtime,selected):
 assert os.geteuid()==0 and Path('/proc/1/comm').read_text().strip()=='systemd'
 assert command(['stat','-fc','%T','/sys/fs/cgroup'])['stdout'].strip()=='cgroup2fs'
 assert os.environ.get('RUN_GUARDIAN_OS_PROBE')=='1','explicit probe opt-in required'
 assert runtime.is_relative_to(REPO/'target/guardian-os-runtimes')
 from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
 from cryptography.hazmat.primitives.serialization import Encoding,PublicFormat,PrivateFormat,NoEncryption
 from cryptography.hazmat.primitives.serialization import load_der_public_key
 owner=pwd.getpwnam('pekko-probe');gid=owner.pw_gid;uid=owner.pw_uid
 def owned(p):os.chown(p,uid,gid)
 env=os.environ.copy();env.update(load(BASE/'private/environment.json'))
 cp=':'.join(str(p) for p in sorted(runtime.glob('*.jar')))
 java=['/usr/bin/java','-Xmx256m','-Duser.timezone=UTC','-cp',cp]
 prefix='cn.xuyinyin.magic.workflow.integration.'
 root=BASE/'runs'/('matrix-'+str(time.time_ns()));root.mkdir(parents=True);owned(root)
 key=Ed25519PrivateKey.generate();pin=base64.b64encode(key.public_key().public_bytes(Encoding.DER,PublicFormat.SubjectPublicKeyInfo)).decode()
 private=BASE/'private'/root.name;private.mkdir(mode=0o700)
 (private/'signing.der').write_bytes(key.private_bytes(Encoding.DER,PrivateFormat.PKCS8,NoEncryption()));(private/'signing.der').chmod(0o600)
 save(root/'environment.json',{'uname':command(['uname','-a']),'systemd':command(['systemctl','--version']),
  'java':command(['/usr/bin/java','-version']),'mysql':command(['mysql','-Nse','SELECT VERSION(),@@server_uuid,@@performance_schema_session_connect_attrs_size']),
  'hostBoot':Path('/proc/sys/kernel/random/boot_id').read_text().strip(),'runtime':runtime.name,'publicKey':pin,
  'scope':'isolated OrbStack Linux machine (LXC in its Linux VM); real OS controls; laboratory proof adapter; one physical MySQL; static source; D=N/A',
  'virtualization':command(['systemd-detect-virt']),'globalServiceDropin':Path('/run/systemd/system/service.d/zzz-lxc-service.conf').read_text() if Path('/run/systemd/system/service.d/zzz-lxc-service.conf').exists() else None})
 save(root/'source-manifest.json',load(runtime/'source-manifest.json'));save(root/'runtime-manifest.json',load(runtime/'runtime-manifest.json'))
 results=[]
 for scenario in selected:
  suffix=uuid.uuid4().hex[:12];schema='pekko_test_cdc_window_'+suffix
  directory=root/(scenario+'-'+suffix);directory.mkdir();owned(directory)
  oldboot=str(uuid.uuid4());newboot=str(uuid.uuid4());gate='owner-probe-'+suffix
  case={'scenario':scenario,'schema':schema,'oldBoot':oldboot,'newBoot':newboot,'gate':gate,'runtime':runtime.name}
  save(directory/'case.json',case);owned(directory/'case.json')
  (directory/'public-key').write_text(pin)
  units=[];supervisor=None;log=None;outcome='FAILED'
  def send(text):
   assert supervisor.poll() is None,'supervisor exited before '+text
   supervisor.stdin.write(text+'\n');supervisor.stdin.flush()
  def marker(name):wait(name,lambda:(directory/name).exists())
  def capture(name):
   (directory/(name+'-ready')).unlink(missing_ok=True)
   send('STATE '+name);marker(name+'-ready');return load(directory/(name+'.json'))
  def worker_has(kind):
   p=directory/(oldboot+'.worker.log');return p.exists() and ('"kind":"'+kind+'"') in p.read_text(errors='replace')
  def make_unit(which,boot,mode,prestart=False):
   name='pekko-osprobe-'+suffix+'-'+which+'.service';units.append(name)
   guardian_args=[schema,str(directory),boot,which,scenario,mode,gate]
   main=java+[prefix+'GuardianOsProbeGuardian']+guardian_args
   lines=['[Unit]','Description=Pekko guardian OS isolation probe '+suffix,'[Service]','Type=exec','ExitType=cgroup','Restart=no',
    'KillMode=control-group','KillSignal=SIGKILL','SendSIGKILL=yes','TimeoutStopSec=10s','User=pekko-probe','Group=pekko-probe',
    'NoNewPrivileges=yes','ProtectControlGroups=yes','Delegate=no','MemoryMax=1G','TasksMax=256',
    'EnvironmentFile='+str(BASE/'private/worker.env'),'WorkingDirectory='+str(directory),
    'StandardOutput=append:'+str(directory/(which+'.log')),'StandardError=append:'+str(directory/(which+'.log'))]
   if prestart:lines+=['ExecStartPre='+' '.join(java+[prefix+'GuardianOsProbeGuardian']+[schema,str(directory),boot,which,scenario,'prestart',gate])]
   lines+=['ExecStart='+' '.join(main)]
   text='\n'.join(lines)+'\n';path=Path('/run/systemd/system')/name;path.write_text(text)
   # OrbStack supplies a type-wide LXC drop-in overriding these settings. Override only our own unit.
   dropin=Path('/run/systemd/system')/(name+'.d');dropin.mkdir()
   (dropin/'zzz-lxc-service.conf').write_text('[Service]\nProtectControlGroups=yes\nNoNewPrivileges=yes\n')
   (directory/(which+'-dropin.conf')).write_text((dropin/'zzz-lxc-service.conf').read_text())
   (directory/(which+'.service')).write_text(text)
   command(['systemctl','daemon-reload']);r=command(['systemctl','start','--no-block',name]);save(directory/(which+'-start-request.json'),r)
   return name
  try:
   log=(directory/'supervisor.log').open('w')
   supervisor=subprocess.Popen(java+[prefix+'GuardianOsProbeSupervisor',str(directory),pin],stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,env=env)
   marker('SUPERVISOR_READY');save(directory/'supervisor-process.json',{'pid':supervisor.pid,'cgroup':Path('/proc/'+str(supervisor.pid)+'/cgroup').read_text()})
   prestart=scenario=='G7_START_ACK_LOST'
   old=make_unit('old',oldboot,'fresh',prestart)
   if prestart:marker('old-PRESTART_WAIT.json')
   else:
    marker('old-GUARDIAN_old.json');(directory/'launch-old').write_text('initial test launch')
    if scenario=='G5_BEFORE_FORK':marker('old-BEFORE_FORK.json')
    elif scenario=='G10_JOURNAL':
     wait('page before Journal',lambda:worker_has('BEFORE_JOURNAL_PAGE'));send('ARM_JOURNAL')
    elif scenario not in ('G10_SOURCE','G10_SNAPSHOT'):
     wait('real source page gate',lambda:worker_has('CUTPOINT'))
   if scenario.startswith('G10_'):
    role='source' if scenario=='G10_SOURCE' else 'target' if scenario=='G10_TARGET' else 'journal'
    def waiting():
     state=capture('window')
     return any(r['owner']==oldboot and r['role']==role and r['trx_id'] is not None and 'user lock' in (r['STATE'] or '').lower() for r in state['database'])
    wait('actual server role transaction waits',waiting,40)
   initial=unit(old);save(directory/'old-before.json',initial)
   props=initial['properties'];assert props['InvocationID'] and props['ControlGroup'] and props['Restart']=='no' and props['KillMode']=='control-group'
   assert props['ProtectControlGroups']=='yes' and props['NoNewPrivileges']=='yes','effective process containment policy missing'
   binding={'guardian':'old','unit':old,'invocation':props['InvocationID'],'cgroup':props['ControlGroup'],'hostBoot':load(root/'environment.json')['hostBoot']}
   save(directory/'diagnostic-boundary.json',binding)
   if scenario!='G15_LEGACY_UNBOUND':save(directory/'binding.json',binding)
   cut=capture('cut')
   if scenario=='G6_FORK_UNRECORDED' or scenario=='G7_EXECUTED_ACK_LOST':
    assert cut['tickets'][0]['pid'] is None and len(cut['owners'])==1,'fork identity window not established'
   if scenario=='G5_BEFORE_FORK':
    assert not cut['owners'];save(directory/'sigstop.json',command(['systemctl','kill','--kill-whom=main','--signal=SIGSTOP',old]))
   elif not prestart:
    save(directory/'guardian-crash.json',command(['systemctl','kill','--kill-whom=main','--signal=SIGKILL',old]))
    original_pid=load(directory/'old-GUARDIAN_old.json')['pid']
    def guardian_gone():
     members=unit(old)['members']
     return all(p['pid']!=original_pid for p in members) and any('SupervisorRecoveryWorker' in p.get('command','') for p in members)
    wait('guardian original JVM exited with worker still present',guardian_gone)
   save(directory/'after-guardian-loss.json',unit(old))
   new=make_unit('new',newboot,'recover');marker('new-GUARDIAN_new.json')
   save(directory/'new-recovery-only.json',unit(new));assert not (directory/'launch-new').exists()
   send('SEAL');marker('SEALED');sealed=capture('sealed')
   if scenario=='G15_UNIT_MISSING':
    save(directory/'unaccepted-earlier-stop.json',command(['systemctl','stop',old]))
    definition=Path('/run/systemd/system')/old;definition.unlink()
    dropin=Path('/run/systemd/system')/(old+'.d');(dropin/'zzz-lxc-service.conf').unlink();dropin.rmdir()
    command(['systemctl','reset-failed',old],check=False);command(['systemctl','daemon-reload'])
    wait('old unit identity really missing',lambda:unit(old)['properties'].get('LoadState')=='not-found')
   before=unit(old);save(directory/'isolation-before.json',before)
   matched=(directory/'binding.json').exists() and before['properties'].get('InvocationID')==binding['invocation'] and before['properties'].get('ControlGroup')==binding['cgroup']
   stopped=False;empty=False
   if scenario=='G14_CONTROL_DENIED':
    denied=command(['runuser','-u','pekko-probe','--','systemctl','--no-ask-password','stop',old],check=False)
    save(directory/'control-denied.json',denied);assert denied['exitCode']!=0
   elif scenario=='G14_SIGNAL_ONLY':
    save(directory/'signal-accepted.json',command(['systemctl','kill','--kill-whom=all','--signal=SIGCONT',old]))
   elif scenario in ('G15_LEGACY_UNBOUND','G15_UNIT_MISSING'):pass
   else:
    save(directory/'stop-request.json',command(['systemctl','stop',old],timeout=20));stopped=True
   after=unit(old);save(directory/'isolation-after.json',after)
   if stopped:
    empty=not after['members'] and after['properties'].get('ActiveState') in ('inactive','failed') and after['properties'].get('Job') in ('','0')
    assert empty,'subtree exit unconfirmed'
   proof={'provider':'test-systemd-cgroup-v2','binding':binding,'operation':'isolate-'+oldboot,'startSealed':True,
    'stopCompleted':stopped,'subtreeEmpty':empty,'beforeMatched':matched,'before':before,'after':after,'observedAtNs':time.time_ns()}
   raw=json.dumps(proof,ensure_ascii=False,separators=(',',':'))
   save(directory/'proof.json',{'payload':raw,'signature':base64.b64encode(key.sign(raw.encode())).decode()})
   send('RECOVER')
   negative=scenario.startswith(('G14_','G15_'))
   if negative:
    marker('SAFE_PAUSE');paused=load(directory/'pause.json');assert paused['admissionRejected']
    for field in ('owners','permissions'):
     assert paused['state'][field]==sealed[field],field+' changed on unconfirmed isolation'
    for field in ('T','J','control','audit'):
     assert paused['state']['data'][field]==sealed['data'][field],field+' changed on unconfirmed isolation'
    assert not (directory/'launch-new').exists();outcome='SAFE_PAUSE'
   else:
    if scenario.startswith('G10_'):
     marker('DRAIN_WAIT');blocked=capture('blocked-with-proof')
     assert any(r['owner']==oldboot and r['trx_id'] is not None for r in blocked['database']),'actual residual transaction missing'
     assert blocked['owners'][0]['phase'] in ('fenced','drain_wait')
     assert len(blocked['permissions'])==len(cut['permissions']) and not (directory/'launch-new').exists()
     send('RELEASE_RELAY');marker('RELAY_RELEASED')
    marker('CONVERGED');state=load(directory/'final.json');comparison=state['data']['comparison']
    assert comparison['equal'] and comparison['sourceCount']==comparison['targetCount']==10
    assert len(state['data']['T'])==5 and len(state['data']['audit'])==10
    if prestart:(directory/'prestart.release').write_text('late release after isolation')
    if scenario=='G5_BEFORE_FORK':(directory/'fork.release').write_text('late release after isolation')
    if scenario in ('G6_FORK_UNRECORDED','G7_EXECUTED_ACK_LOST'):(directory/'identity.release').write_text('late release after isolation')
    time.sleep(.3);save(directory/'old-after-late-release.json',unit(old));assert not unit(old)['members']
    outcome='CONVERGED'
   result={**case,'outcome':outcome,'D':'N/A','productionGuardianRestart':False,'proofAuthority':'external test observer + laboratory control adapter'}
   save(directory/'result.json',result);results.append(result);print(scenario,outcome,flush=True)
  except Exception as e:
   save(directory/'failure.json',{'class':type(e).__name__,'message':str(e),'scenario':scenario})
   results.append({**case,'outcome':'FAILED'});print(scenario,'FAILED',str(e),flush=True)
  finally:
   # Cleanup does not authorize recovery and is never included in the accepted proof.
   cleanup=cleanup_each(units,lambda name:command(['systemctl','stop',name],check=False,timeout=20))
   if supervisor and supervisor.poll() is None:
    try:send('RELEASE_RELAY');send('QUIT');supervisor.wait(timeout=110)
    except Exception:
     supervisor.kill();supervisor.wait(timeout=10)
   if log:log.close()
   observations=[]
   for name in units:
    try:observations.append(unit(name))
    except Exception as error:observations.append({'unknown':True,'unit':name,'errorClass':type(error).__name__})
   save(directory/'cleanup.json',{'operations':cleanup,'units':observations,
    'supervisorExit':supervisor.returncode if supervisor else None,'acceptedOutcomeBeforeCleanup':outcome})
   if any(u.get('unknown') or u['members'] or u['properties'].get('ActiveState') not in ('inactive','failed') for u in observations) or (supervisor and supervisor.returncode!=0):
    if results:results[-1]['outcome']='FAILED'
    save(directory/'cleanup-failure.json',{'resourceExitUnconfirmed':True,'acceptedOutcomeBeforeCleanup':outcome})
   known={u['properties'].get('Id') for u in observations if not u.get('unknown') and not u['members'] and u['properties'].get('ActiveState') in ('inactive','failed')}
   for name in units:
    if name not in known:continue # Unknown cleanup retains its service definition and control boundary.
    path=Path('/run/systemd/system')/name
    if path.exists():path.unlink()
    dropin=Path('/run/systemd/system')/(name+'.d')
    if dropin.exists():(dropin/'zzz-lxc-service.conf').unlink();dropin.rmdir()
   command(['systemctl','daemon-reload'])
 save(root/'result.json',{'cases':results,'accepted':sum(r['outcome']!='FAILED' for r in results),'total':len(results),
  'converged':sum(r['outcome']=='CONVERGED' for r in results),'paused':sum(r['outcome']=='SAFE_PAUSE' for r in results)})
 print('RESULT_DIRECTORY',root,flush=True)
 return 0 if all(r['outcome']!='FAILED' for r in results) else 1

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('mode',choices=['freeze','matrix']);p.add_argument('--runtime',type=Path);p.add_argument('--scenario',choices=SCENARIOS)
 a=p.parse_args()
 if a.mode=='freeze':freeze()
 else:sys.exit(matrix(a.runtime,[a.scenario] if a.scenario else SCENARIOS))
