#!/usr/bin/env python3
"""Isolated real MySQL/production Debezium SourceRecord probe; Python standard library only.
Creates and stops its OWN disposable container, retains its database and evidence.
Does not restore/edit offset rows, synthesize SourceRecords, or run an Actor/Sink.
Run Test/compile and export Test/fullClasspath first. See README for scope.
"""
import hashlib,json,os,queue,subprocess,threading,time,uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]
JAVA=Path(os.environ.get('IDENTITY_JAVA_HOME','/Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home'))/'bin/java'
RUN=uuid.uuid4().hex[:12]
OUT=Path(os.environ.get('IDENTITY_EVIDENCE_DIR',str(ROOT/'target/source-record-identity')))/RUN
OUT.mkdir(parents=True,exist_ok=False)
CONTAINER='pekko-cdc-identity-'+RUN
PASSWORD='local-identity-fixture-only'
SCHEMA='pekko_test_identity_'+RUN
CONNECTOR='identity_'+RUN
OFFSET='identity_offset_'+hashlib.sha256(CONNECTOR.encode()).hexdigest()[:32]
CP=(ROOT/'pekko-server/target/streams/test/fullClasspath/_global/streams/export').read_text().strip()
CHILDREN=[]

def write(name,obj): (OUT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
def run(args,**kw):return subprocess.run(args,check=True,text=True,capture_output=True,**kw).stdout.strip()
def sql(statement):
 return run(['docker','exec','-e','MYSQL_PWD='+PASSWORD,CONTAINER,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','--default-character-set=utf8mb4','--batch','--raw','--skip-column-names','-e',statement])
def offset():
 return [json.loads(s) for s in sql(f'SELECT offset_val FROM `{SCHEMA}`.`{OFFSET}` ORDER BY record_insert_ts,record_insert_seq').splitlines()]
def await_condition(description,body,seconds=180):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if body():return
  time.sleep(.2)
 raise AssertionError('deadline without observation: '+description)

class Child:
 def __init__(self,name,size,ack=0,bootstrap=False):
  self.name=name;self.path=OUT/name;self.path.mkdir();self.events=queue.Queue();self.log=open(self.path/'process.log','w')
  env=os.environ.copy();env.update(RUN_MYSQL_IDENTITY_PROBE='1',IDENTITY_MYSQL_PASSWORD=PASSWORD)
  self.p=subprocess.Popen([str(JAVA),'-Xmx512m','-Duser.timezone=UTC','-cp',CP,
    'cn.xuyinyin.magic.workflow.nodes.sources.RealMySQLSourceRecordIdentityProbe',SCHEMA,CONNECTOR,str(PORT),str(size),str(ack),str(bootstrap).lower(),str(self.path)],
    stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1,env=env,cwd=ROOT)
  def reader():
   for line in self.p.stdout:
    self.log.write(line);self.log.flush()
    if line.startswith('IDENTITY '):self.events.put(json.loads(line[9:]))
   self.events.put({'kind':'EXIT','returncode':self.p.wait()})
  self.reader=threading.Thread(target=reader,daemon=True);self.reader.start();CHILDREN.append(self)
  self.wait('READY')
 def send(self,text): self.p.stdin.write(text+'\n');self.p.stdin.flush()
 def wait(self,kind,seconds=180):
  end=time.monotonic()+seconds
  while time.monotonic()<end:
   try:e=self.events.get(timeout=min(1,end-time.monotonic()))
   except queue.Empty:continue
   if e['kind']==kind:return e
   if e['kind'] in ['EXIT','ENGINE_DONE']:raise AssertionError((self.name,kind,e))
  raise AssertionError((self.name,'missing',kind))
 def batches(self):
  p=self.path/'records.jsonl'
  return [json.loads(s) for s in p.read_text().splitlines()] if p.exists() else []
 def crash(self):
  self.send('CRASH');assert self.p.wait(timeout=15)==86
  self.reader.join(timeout=3);self.log.close()

def marker_seen(child):
 return any(r['value'] and r['value'].get('after',{} ) and r['value']['after'].get('revision')==-1
   for b in child.batches() for r in b['records'])
def marker_offset(child):
 return next(r['sourceOffset'] for b in child.batches() for r in b['records']
   if r['value'] and r['value'].get('after') and r['value']['after'].get('revision')==-1)

def read_capture(child):
 return [r for b in child.batches() if b['window'] for r in b['records']]
def candidate(record,op=True,row=True):
 s=record['value']['source']
 # source lineage is observed server_uuid + fixed file history in this fixture only.
 fields=[SERVER_UUID,s['db'],s['table'],s['file'],s['pos']]
 if row:fields.append(s['row'])
 if op:fields.append(record['value']['op'])
 return fields

def evaluate(captures):
 base=captures['original-16'];assert len(base)==39,('unexpected logical record count',len(base))
 ids=[candidate(r) for r in base]
 assert len({json.dumps(x) for x in ids})==39,'candidate collision'
 comparisons={}
 for name,records in captures.items():
  if name=='pk-component-resume':continue
  expected=base[10:] if name.startswith('inside-') or name=='pk-advance-delete' else base
  assert len(records)==len(expected),(name,len(records),len(expected))
  assert [candidate(r) for r in records]==[candidate(r) for r in expected],(name,'identity mismatch')
  assert [r['normalizedEnvelope'] for r in records]==[r['normalizedEnvelope'] for r in expected],(name,'normalized content mismatch')
  # Compare actual source coordinates independently from restart offsets and processing timestamps.
  comparisons[name]={'records':len(records),'identities_and_normalized_content_equal':True,
   'sourceOffset_differences':sum(a['sourceOffset']!=b['sourceOffset'] for a,b in zip(records,expected)),
   'source_metadata_differences':sum(a['value']['source']!=b['value']['source'] for a,b in zip(records,expected)),
   'processing_timestamp_differences':sum(a['value'].get('ts_ms')!=b['value'].get('ts_ms') for a,b in zip(records,expected))}
 same_key=[r for r in base if r['key']['id']==1];assert len(same_key)==4
 assert len({json.dumps(candidate(r)) for r in same_key})==4
 assert same_key[0]['value']['before']==same_key[2]['value']['before']
 assert same_key[0]['value']['after']==same_key[2]['value']['after']
 multi=[r for r in base if 2<=r['key']['id']<=33]
 assert len(multi)==32
 assert len({(r['value']['source']['file'],r['value']['source']['pos']) for r in multi})==1
 assert len({r['value']['source']['row'] for r in multi})==32
 pk=[r for r in base if r['key']['id'] in (50,150)]
 assert len(pk)==2 and [r['value']['op'] for r in pk]==['d','c']
 assert candidate(pk[0],op=False)==candidate(pk[1],op=False),'unexpected PK component coordinates'
 gtids={r['value']['source']['gtid'] for r in base};assert len(gtids)==1
 pk_replay=captures['pk-component-resume']; expected_tail=base[37:]
 observed_ids=[candidate(r) for r in pk_replay]
 assert observed_ids==[candidate(r) for r in expected_tail] or observed_ids==[candidate(base[38])]
 missing=[{'key':r['key'],'identity':candidate(r)} for r in expected_tail if candidate(r) not in observed_ids]
 pk_boundary={'only_delete_component_acknowledged':True,'delete_create_offsets_equal':pk[0]['sourceOffset']==pk[1]['sourceOffset'],
  'expected_unacknowledged_logical_records':2,'actual_replayed_records':len(pk_replay),'missing_logical_records':missing,
  'scope':'source-only ACK boundary probe, not target data-loss or repaired-protocol validation'}
 no_op_ids=[json.dumps(candidate(r,op=False)) for r in base]
 no_row_ids=[json.dumps(candidate(r,row=False)) for r in base]
 groups={name:[len(b['records']) for b in child.batches() if b['window']] for name,child in NAMED.items()}
 assert max(groups['original-16'])==16 and all(n==1 for n in groups['split-1'])
 assert max(groups['merge-64'])>1 and max(groups['inside-merge-64'])>1
 assert len({c.p.pid for c in NAMED.values()})==len(NAMED)
 return {'candidate':'fixed source lineage + db + table + file + source.pos + source.row + op',
  'candidate_status':'passed_within_fixture_not_a_production_identity_spec',
  'comparisons':comparisons,'actual_callback_sizes':groups,'pids':{k:c.p.pid for k,c in NAMED.items()},
  'same_transaction_gtid':list(gtids),'same_key_updates':4,'multi_row_event_rows':32,
  'pk_component_ack_boundary':pk_boundary,
  'pk_change_components':[{'key':r['key'],'op':r['value']['op'],'source':r['value']['source'],'headers':r['headers']} for r in pk],
  'unique_ids_without_op':len(set(no_op_ids)),'unique_ids_without_row':len(set(no_row_ids)),
  'identical_before_after_payloads_have_distinct_positions':True,
  'scope':'Real MySQL SourceRecord observation using production engine/config. No Actor, Journal, Sink, new protocol or end-to-end correctness proof.'}

NAMED={}
try:
 run(['docker','run','-d','--name',CONTAINER,'-p','127.0.0.1::3306','-e','MYSQL_ROOT_PASSWORD='+PASSWORD,'-e','MYSQL_ROOT_HOST=%',
  'mysql:8.4','--server-id=186019','--log-bin=mysql-bin','--binlog-format=ROW','--binlog-row-image=FULL','--gtid-mode=ON','--enforce-gtid-consistency=ON'])
 PORT=int(run(['docker','port',CONTAINER,'3306/tcp']).rsplit(':',1)[1])
 def ready():
  try:return sql('SELECT 1')=='1'
  except subprocess.CalledProcessError:return False
 await_condition('new isolated MySQL readiness',ready)
 SERVER_UUID=sql('SELECT @@server_uuid')
 write('environment.json',{'container':CONTAINER,'port':PORT,'schema':SCHEMA,'connector':CONNECTOR,'server_uuid':SERVER_UUID,
  'mysql':sql('SELECT VERSION(),@@binlog_format,@@binlog_row_image,@@gtid_mode'),
  'image_id':run(['docker','inspect','--format','{{.Image}}',CONTAINER]),'project_commit':run(['git','rev-parse','HEAD'],cwd=ROOT),
  'java':subprocess.run([str(JAVA),'-version'],capture_output=True,text=True).stderr,
  'scope':'new isolated container, source-only Engine callback observer, real JDBC offset/history',
  'production_source_diff':run(['git','diff','HEAD','--','pekko-server/src/main','build.sbt'],cwd=ROOT)})
 sql(f'CREATE DATABASE `{SCHEMA}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE TABLE `{SCHEMA}`.source_rows '
     '(id BIGINT PRIMARY KEY,label VARCHAR(100),revision INT NOT NULL) ENGINE=InnoDB; INSERT INTO '+SCHEMA+'.source_rows VALUES '+
     ','.join(f"({i},'base-{i}',0)" for i in range(1,65)))
 original=Child('original-16',16,bootstrap=True);NAMED[original.name]=original
 await_condition('all snapshot rows observed',lambda:sum(len(b['records']) for b in original.batches())>=64)
 sql(f"UPDATE {SCHEMA}.source_rows SET label='streaming-ready',revision=-1 WHERE id=64")
 await_condition('real streaming marker observed',lambda:marker_seen(original))
 await_condition('marker offset durably persisted',lambda:marker_offset(original) in offset())
 baseline=offset();write('baseline-D.json',baseline)
 original.send('ARM');original.wait('ARMED')
 workload=f'''USE {SCHEMA};
START TRANSACTION;
UPDATE source_rows SET label='repeat',revision=1 WHERE id=1;
UPDATE source_rows SET label='base-1',revision=0 WHERE id=1;
UPDATE source_rows SET label='repeat',revision=1 WHERE id=1;
UPDATE source_rows SET label='finish',revision=2 WHERE id=1;
UPDATE source_rows SET label=CONCAT('multi-',id),revision=10 WHERE id BETWEEN 2 AND 33;
UPDATE source_rows SET id=150,label='pk-moved',revision=20 WHERE id=50;
UPDATE source_rows SET label='sentinel',revision=999 WHERE id=60;
COMMIT;'''
 (OUT/'workload.sql').write_text(workload+'\n');sql(workload)
 original.wait('FIRST_WINDOW_GATE');write('original-gate-D.json',offset());assert offset()==baseline
 original.send('RELEASE');original.wait('SENTINEL');original.crash()
 assert offset()==baseline;write('original-after-crash-D.json',offset())
 print('Original captured; fixed D preserved:',OUT,flush=True)
 for name,size in [('restart-16',16),('split-1',1),('merge-64',64),('advance-prefix-10',1),('inside-split-1',1),('inside-merge-64',64),('pk-advance-delete',1),('pk-component-resume',1)]:
  before=offset();write(name+'-before-D.json',before)
  ack=10 if name=='advance-prefix-10' else 27 if name=='pk-advance-delete' else 0
  c=Child(name,size,ack=ack);NAMED[name]=c
  c.wait('FIRST_WINDOW_GATE');c.send('RELEASE');c.wait('SENTINEL')
  if ack:
   expected=read_capture(c)[ack-1]['sourceOffset']
   await_condition('intra-event prefix persisted',lambda:expected in offset())
   assert offset()!=baseline
  c.crash();after=offset();write(name+'-after-D.json',after)
  if not ack:assert before==after,(name,'unexpected D mutation')
  print('Captured',name,'records',len(read_capture(c)),flush=True)
 captures={name:read_capture(c) for name,c in NAMED.items()}
 write('result.json',evaluate(captures))
 write('source-final.json',[json.loads(line) for line in sql(f"SELECT JSON_OBJECT('id',id,'label',label,'revision',revision) FROM {SCHEMA}.source_rows ORDER BY id").splitlines()])
 print('IDENTITY_PROBE_PASS',OUT,flush=True)
except BaseException as e:
 write('failure.json',{'type':type(e).__name__,'error':str(e),'stderr':getattr(e,'stderr',None)})
 raise
finally:
 for c in CHILDREN:
  if c.p.poll() is None:c.p.kill();c.p.wait(timeout=15)
  c.reader.join(timeout=3)
  if not c.log.closed:c.log.close()
 subprocess.run(['docker','stop','-t','10',CONTAINER],capture_output=True,text=True)
 print('Evidence and stopped fixture retained:',OUT,CONTAINER,flush=True)
