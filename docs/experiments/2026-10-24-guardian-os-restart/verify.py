#!/usr/bin/env python3
"""Offline semantic verification. Never connects to MySQL, signals a process or changes an ACK."""
from pathlib import Path
import argparse,base64,copy,hashlib,io,json,os,runpy,tarfile,zipfile
from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.exceptions import InvalidSignature

HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
LIB=runpy.run_path(str(HERE.parent/'2026-10-23-supervisor-jvm-recovery/verify.py'))
CBOR=runpy.run_path(str(HERE.parent/'2026-10-22-table-scheduling/verify.py'))['cbor']
digest=lambda data:hashlib.sha256(data).hexdigest()

def case(read,public_key):
 load=lambda p:json.loads(read(p))
 result=load('result.json');name=result['scenario'];boot=result['oldBoot']
 assert not result['productionGuardianRestart'] and result['D']=='N/A'
 env=load('proof.json');load_der_public_key(base64.b64decode(public_key)).verify(base64.b64decode(env['signature']),env['payload'].encode())
 proof=json.loads(env['payload']);binding=load('diagnostic-boundary.json')
 if name=='G15_LEGACY_UNBOUND':
  try:read('binding.json')
  except (KeyError,FileNotFoundError):pass
  else:raise AssertionError('legacy case has a persisted predecessor binding')
 else:assert load('binding.json')==binding
 assert proof['provider']=='test-systemd-cgroup-v2' and proof['binding']==binding and proof['operation']=='isolate-'+boot
 old=load('old-before.json');assert old['properties']['InvocationID']==binding['invocation']
 assert old['properties']['Id']==binding['unit'] and old['properties']['ControlGroup']==binding['cgroup']
 assert old['properties']['Restart']=='no' and old['properties']['KillMode']=='control-group' and old['properties']['ExitType']=='cgroup'
 assert old['properties']['User']=='pekko-probe' and old['properties']['Delegate']=='no'
 assert old['properties']['ProtectControlGroups']=='yes' and old['properties']['NoNewPrivileges']=='yes'
 for member in old['members']:
  if not member.get('exitedDuringObservation'):assert 'NoNewPrivs:\t1' in member['status']
 new=load('new-recovery-only.json');assert new['properties']['InvocationID']!=binding['invocation'] and new['properties']['ControlGroup']!=binding['cgroup']
 cleanup=load('cleanup.json');assert cleanup['supervisorExit']==0 and all(not u['members'] and u['properties']['ActiveState'] in ('inactive','failed') for u in cleanup['units'])
 cut=load('cut.json');sealed=load('sealed.json')
 assert len(cut['data']['comparison']['source'])==10
 for owner in cut['owners']:
  member=next(p for p in old['members'] if str(p['pid'])==owner['process_id'])
  assert 'SupervisorRecoveryWorker' in member['command'] and member['cgroup'].strip()=='0::'+binding['cgroup']
 assert load('lost-process-capability.json')['freshManagerHasOriginalProcess'] is False
 if name=='G7_START_ACK_LOST':
  assert old['properties']['ActiveState']=='activating' and old['properties']['Job'] not in ('','0')
  assert not any('SupervisorRecoveryWorker' in p.get('command','') for p in old['members'])
 elif name!='G5_BEFORE_FORK':
  loss=load('after-guardian-loss.json');pid=load('old-GUARDIAN_old.json')['pid']
  assert all(p['pid']!=pid for p in loss['members']) and any('SupervisorRecoveryWorker' in p.get('command','') for p in loss['members'])
 if result['outcome']=='SAFE_PAUSE':
  assert name.startswith(('G14_','G15_')) and not all(proof[k] for k in ('startSealed','stopCompleted','subtreeEmpty','beforeMatched'))
  pause=load('pause.json');assert pause['admissionRejected']
  for key in ('owners','permissions'):assert pause['state'][key]==sealed[key]
  for key in ('T','J','control','audit'):assert pause['state']['data'][key]==sealed['data'][key]
  comp=pause['state']['data']['comparison'];assert not comp['equal'] and comp['targetCount']==0 and len(comp['missingKeys'])==10
  assert len(pause['state']['tickets'])==1 and pause['state']['tickets'][0]['phase']=='sealed'
  if name=='G14_CONTROL_DENIED':assert load('control-denied.json')['exitCode']!=0
  if name=='G14_SIGNAL_ONLY':assert load('signal-accepted.json')['exitCode']==0 and load('isolation-after.json')['members']
  if name=='G15_UNIT_MISSING':assert load('isolation-before.json')['properties']['LoadState']=='not-found' and not proof['beforeMatched']
  return 'SAFE_PAUSE',0
 assert result['outcome']=='CONVERGED' and not name.startswith(('G14_','G15_'))
 assert all(proof[k] is True for k in ('startSealed','stopCompleted','subtreeEmpty','beforeMatched'))
 assert proof['before']==load('isolation-before.json') and proof['after']==load('isolation-after.json')
 assert not proof['after']['members'] and proof['after']['properties']['ActiveState'] in ('inactive','failed')
 assert load('stop-request.json')['exitCode']==0 and proof['after']['atNs']>=proof['before']['atNs']
 assert not load('old-after-late-release.json')['members']
 final=load('final.json');data=final['data'];assert LIB['equal_rows'](data['comparison'])==10
 execution=cut['data']['control'][0]['execution_id'];assert data['control'][0]['execution_id']==execution and data['control'][0]['phase']=='completed'
 LIB['check_ledger'](data,execution,CBOR)
 assert len(data['audit'])==10 and all(r['op']=='I' for r in data['audit'])
 tickets={r['boot']:r for r in final['tickets']};assert tickets[boot]['phase']=='retired' and tickets[boot]['proof']
 assert len(tickets)==2 and tickets[result['newBoot']]['phase'] in ('prepared','launched')
 seq=lambda r:int(r['event_sequence'])
 audit=final['audit'];permits=[r for r in audit if r['event_type']=='recovery_permitted']
 for row in final['journalWrites']:
  permit=next(r for r in permits if r['owner_id']==row['owner_id'] and json.loads(r['details'])['permit']==row['permit_id'])
  assert int(row['generation'])==json.loads(permit['details'])['generation'] and int(row['admission_sequence'])>=seq(permit)
 assert len(final['journalWrites'])==len([r for r in data['J'] if r['persistence_id']=='workflow-table-00'])
 oldowners=[r for r in final['owners'] if r['owner_id']==boot]
 if oldowners:
  owner=oldowners[0];assert owner['phase']=='db_drained' and owner['manifest_version']==owner['sealed_version']
  oldevents=[r for r in audit if r['owner_id']==boot]
  fence=next(r for r in oldevents if r['event_type']=='fenced');drain=next(r for r in oldevents if r['event_type']=='db_drained');release=next(r for r in oldevents if r['event_type']=='old_quota_released')
  newpermit=next(r for r in permits if r['owner_id']==result['newBoot'])
  assert seq(fence)<seq(drain)<seq(release)<seq(newpermit)
  assert json.loads(fence['details'])['provider']=='test-systemd-cgroup-v2'
  observations=[json.loads(r['details']) for r in oldevents if r['event_type'] in ('database_observed','db_drained')]
  original_instances={r['instance_id']:r for r in load('after-proof.json')['instances'] if r['owner_id']==boot}
  final_instances={r['instance_id']:r for r in final['instances'] if r['owner_id']==boot}
  assert original_instances and set(original_instances)==set(final_instances)
  for key,instance in final_instances.items():
   assert str(instance['complete']).lower() in ('true','1') and int(instance['empty_rounds'])==2
   for field in ('server_uuid','server_started'):assert instance[field]==original_instances[key][field]
   rounds=[r for r in observations if r['instance']==key and r['complete'] and all(r[k]==0 for k in ('sessions','transactions','unattributed'))]
   first=next((r for r in rounds if r['emptyRounds']==1),None);second=next((r for r in rounds if r['emptyRounds']==2),None)
   assert first is not None and second is not None,'missing complete empty observation round'
   assert second['evidence']['observedAt']-first['evidence']['observedAt']>=250
  for event in oldevents:
   if event['event_type']=='db_drained':
    value=json.loads(event['details']);assert value['complete'] is True and value['emptyRounds']==2
    assert all(value[k]==0 for k in ('sessions','transactions','unattributed'))
  assert int(final['permissions'][0]['generation'])==2
 else:assert name in ('G5_BEFORE_FORK','G7_START_ACK_LOST') and int(final['permissions'][0]['generation'])==1
 if name in ('G6_FORK_UNRECORDED','G7_EXECUTED_ACK_LOST'):
  assert cut['tickets'][0]['pid'] is None and len(cut['owners'])==1
 if name.startswith('G10_'):
  held=load('blocked-with-proof.json');pairs=lambda state:{(r['ID'],r['trx_id']) for r in state['database'] if r['owner']==boot and r['trx_id'] is not None}
  assert pairs(cut)&pairs(held),'same original server transaction not retained after OS isolation'
  assert held['permissions']==cut['permissions'] and held['data']['control'][0]['grant_id']==cut['data']['control'][0]['grant_id']
  assert len(held['tickets'])==1 and held['owners'][0]['phase'] in ('fenced','drain_wait')
 return 'CONVERGED',10

def verify_directory(root):
 report=json.loads((root/'result.json').read_text());pin=json.loads((root/'environment.json').read_text())['publicKey']
 outcomes=[]
 for entry in report['cases']:
  directory=next(root.glob(entry['scenario']+'-*'));outcomes.append(case(lambda p:(directory/p).read_bytes(),pin))
 assert len(outcomes)==report['total']==report['accepted']
 assert sum(o[0]=='CONVERGED' for o in outcomes)==report['converged'] and sum(o[0]=='SAFE_PAUSE' for o in outcomes)==report['paused']
 return {'cases':len(outcomes),'converged':report['converged'],'safePause':report['paused'],'rows':sum(o[1] for o in outcomes),'fieldsPerRow':6}

def main():
 p=argparse.ArgumentParser();p.add_argument('--directory',type=Path);p.add_argument('--archived-source',action='store_true');args=p.parse_args()
 if args.directory:print(json.dumps(verify_directory(args.directory)));return
 if (HERE/'SHA256SUMS').exists():
  for line in (HERE/'SHA256SUMS').read_text().splitlines():
   expected,name=line.split('  ',1);assert digest((HERE/name).read_bytes())==expected,name
 report=json.loads((HERE/'result.json').read_text());files={}
 raw=b''.join((HERE/n).read_bytes() for n in report['archive']['parts']);assert digest(raw)==report['archive']['sha256']
 manifest=json.loads((HERE/'archive-manifest.json').read_text())
 with tarfile.open(fileobj=io.BytesIO(raw),mode='r:gz') as archive:
  for member in archive.getmembers():
   assert member.isfile() and not Path(member.name).is_absolute() and '..' not in Path(member.name).parts
   data=archive.extractfile(member).read();assert digest(data)==manifest[member.name];files[member.name]=data
 assert set(files)==set(manifest)
 source=json.loads((HERE/'source-manifest.json').read_text())
 for name,expected in source.items():
  if not args.archived_source:
   current=digest((REPO/name).read_bytes())
   if current!=expected:
    assert name==str(Path(__file__).relative_to(REPO)) and current==report['offlineVerifierSha256'],name
 for runtime in report['runtimes']:
  prefix='runtimes/'+runtime+'/'
  saved=json.loads(files[prefix+'source-manifest.json'])
  with zipfile.ZipFile(io.BytesIO(files[prefix+'source.zip'])) as sourcezip:
   for name,expected in saved.items():assert digest(sourcezip.read(name))==expected
 boundary=json.loads((HERE/'source-boundary.json').read_text())
 if not args.archived_source:
  for name,expected in boundary['preservedTrackedFiles'].items():assert digest((REPO/name).read_bytes())==expected,name
  for name,target in boundary['preservedSymlinks'].items():assert os.readlink(REPO/name)==target,name
 reference=report['liveSourceReference'];prefix='runtimes/'+reference+'/'
 package=report['backend']['package'];built_bytes=files['build/'+package['file']];assert digest(built_bytes)==package['sha256']
 with zipfile.ZipFile(io.BytesIO(built_bytes)) as built,zipfile.ZipFile(io.BytesIO(files[prefix+'classes.jar'])) as frozen,zipfile.ZipFile(io.BytesIO(files[prefix+'test-classes.jar'])) as tests:
  classes=[n for n in frozen.namelist() if n.endswith('.class')]
  assert len(classes)==package['productionClassesMatchedFrozen'] and all(built.read(n)==frozen.read(n) for n in classes)
  assert not set(built.namelist()) & {n for n in tests.namelist() if n.endswith('.class')}
 backend=files['logs/pekko-os-backend-3g.log'].decode(errors='replace')
 assert 'Tests: succeeded 586, failed 0' in backend and 'Suites: completed 110, aborted 1' in backend and 'cn.xuyinyin.magic.actor.ActorSpec *** ABORTED ***' in backend
 assert report['backendFullSuite']=='evidence_incomplete' and report['sameVersionFullMatrix']=='external_blocked' and report['resourceExitConfirmed'] is False
 outcomes=[]
 for entry in report['selectedCases']:
  prefix=entry['archivePrefix']+'/'
  pin=json.loads(files['runs/'+entry['run']+'/environment.json'])['publicKey']
  actual=json.loads(files[prefix+'result.json']);assert actual['scenario']==entry['scenario'] and actual['runtime']==entry['runtime']
  outcomes.append(case(lambda n:files[prefix+n],pin))
 assert len(outcomes)==12 and sum(o[0]=='CONVERGED' for o in outcomes)==8 and sum(o[1] for o in outcomes)==80
 positive_case=next(c for c in report['selectedCases'] if c['scenario']=='G6_FORK_UNRECORDED')
 positive=positive_case['archivePrefix']+'/'
 pin=json.loads(files['runs/'+positive_case['run']+'/environment.json'])['publicKey']
 checks=[]
 def corrupt_instances(value):
  old=json.loads(files[positive+'result.json'])['oldBoot']
  for row in value['instances']:
   if row['owner_id']==old:row['complete']='0';row['empty_rounds']='0'
 def corrupt_drain_event(value):
  for row in value['audit']:
   if row['event_type']=='db_drained':
    detail=json.loads(row['details']);detail['complete']=False;detail['emptyRounds']=0;row['details']=json.dumps(detail)
 for filename,mutate in [('final.json',lambda v:v['data']['comparison']['target'][0].update(label='CORRUPT')),
                         ('final.json',lambda v:v['data']['T'][0].update(cursor_value='CORRUPT')),
                         ('proof.json',lambda v:v.update(signature=base64.b64encode(b'\0'*64).decode())),
                         ('final.json',corrupt_instances),('final.json',corrupt_drain_event)]:
  value=json.loads(files[positive+filename]);mutate(value)
  try:case(lambda n:json.dumps(value).encode() if n==filename else files[positive+n],pin)
  except (AssertionError,InvalidSignature):checks.append(filename)
  else:raise AssertionError('corruption accepted: '+filename)
 result={'validatedSelectedCases':12,'converged':8,'safePause':4,'rows':80,'fieldValues':480,
  'corruptionControlsRejected':len(checks),'sameVersionFullMatrix':report['sameVersionFullMatrix'],
  'backendFullSuite':report['backendFullSuite'],'resourceExitConfirmed':report['resourceExitConfirmed'],'noLiveOperations':True}
 print(json.dumps(result))

if __name__=='__main__':main()
