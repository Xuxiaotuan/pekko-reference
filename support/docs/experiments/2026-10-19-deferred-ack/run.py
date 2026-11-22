#!/usr/bin/env python3
"""Real Engine + unchanged Bridge, TEST coverage signals, no Actor/Sink. Own isolated container only."""
import hashlib,json,os,queue,subprocess,threading,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
JAVA=Path(os.environ.get('ACK_PROBE_JAVA_HOME','/Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home'))/'bin/java'
OUT=Path(os.environ.get('ACK_PROBE_EVIDENCE_DIR',str(ROOT/'target/deferred-ack')))/uuid.uuid4().hex[:12]
OUT.mkdir(parents=True,exist_ok=False)
CONTAINER='pekko-cdc-deferred-ack-'+OUT.name
PASSWORD='local-deferred-ack-fixture-only'
CP=(ROOT/'pekko-server/target/streams/test/fullClasspath/_global/streams/export').read_text().strip()
CHILDREN=[]
def command(args,**kw):return subprocess.run(args,check=True,capture_output=True,text=True,**kw).stdout.strip()
def sql(text):return command(['docker','exec','-e','MYSQL_PWD='+PASSWORD,CONTAINER,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','--batch','--raw','--skip-column-names','--default-character-set=utf8mb4','-e',text])
def write(path,data):path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def until(label,f,seconds=180):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if f():return
  time.sleep(.15)
 raise AssertionError('missing observation: '+label)
class Child:
 def __init__(self,directory,schema,connector,size,bootstrap=False):
  self.path=directory;directory.mkdir();self.events=queue.Queue();self.log=open(directory/'process.log','w')
  env=os.environ.copy();env.update(RUN_MYSQL_DEFERRED_ACK_PROBE='1',ACK_PROBE_MYSQL_PASSWORD=PASSWORD)
  self.p=subprocess.Popen([str(JAVA),'-Xmx512m','-Duser.timezone=UTC','-cp',CP,
   'cn.xuyinyin.magic.workflow.nodes.sources.RealMySQLDeferredAckProbe',schema,connector,str(PORT),str(size),str(bootstrap).lower(),str(directory)],
   cwd=ROOT,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
  def read():
   for line in self.p.stdout:
    self.log.write(line);self.log.flush()
    if line.startswith('ACKPROBE '):self.events.put(json.loads(line[9:]))
   self.events.put({'kind':'EXIT','code':self.p.wait()})
  self.reader=threading.Thread(target=read,daemon=True);self.reader.start();CHILDREN.append(self);self.wait('READY')
 def send(self,text):self.p.stdin.write(text+'\n');self.p.stdin.flush()
 def wait(self,kind,seconds=180):
  end=time.monotonic()+seconds
  while time.monotonic()<end:
   try:e=self.events.get(timeout=min(1,end-time.monotonic()))
   except queue.Empty:continue
   if e['kind']==kind:return e
   if e['kind'] in ['EXIT','ENGINE_DONE','BRIDGE_READER_STOPPED']:raise AssertionError((self.path.name,kind,e))
  raise AssertionError((self.path.name,'deadline',kind))
 def traces(self):return [json.loads(x) for x in (self.path/'trace.jsonl').read_text().splitlines()]
 def batches(self):return [json.loads(x) for x in (self.path/'callbacks.jsonl').read_text().splitlines()]
 def windows(self):return [b for b in self.batches() if b['window']]
 def crash(self):self.send('CRASH');assert self.p.wait(timeout=15)==86;self.reader.join(timeout=3);self.log.close()
def marker_offset(child):
 for b in child.batches():
  for r in b['records']:
   if r['value'] and r['value'].get('after') and r['value']['after'].get('revision')==-1:return r['sourceOffset']
 return None

def scenario(name,size):
 suffix=uuid.uuid4().hex[:12];schema='pekko_test_ack_'+suffix;connector='ack_'+suffix
 d=OUT/name;d.mkdir()
 table='ack_offset_'+hashlib.sha256(connector.encode()).hexdigest()[:32]
 def offset():return [json.loads(x) for x in sql(f'SELECT offset_val FROM `{schema}`.`{table}` ORDER BY record_insert_ts,record_insert_seq').splitlines()]
 sql(f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4; CREATE TABLE `{schema}`.source_rows '
  '(id BIGINT PRIMARY KEY,label VARCHAR(100),revision INT NOT NULL) ENGINE=InnoDB; INSERT INTO '+schema+'.source_rows VALUES '+
  ','.join(f"({i},'base-{i}',0)" for i in range(1,9)))
 write(d/'environment.json',{'schema':schema,'connector':connector,'size':size,'port':PORT})
 first=Child(d/'before-coverage-crash',schema,connector,size,True)
 until('snapshot of 8 rows',lambda:sum(len(b['records']) for b in first.batches())>=8)
 sql(f"UPDATE {schema}.source_rows SET revision=-1,label='streaming-ready' WHERE id=8")
 until('marker and durable D',lambda:marker_offset(first) is not None and marker_offset(first) in offset())
 baseline=offset();write(d/'baseline-D.json',baseline)
 first.send('ARM');first.wait('ARMED')
 mutations=["UPDATE source_rows SET id=101,label='moved-1',revision=1 WHERE id=1"] if size==1 else [
  "UPDATE source_rows SET label='prefix',revision=2 WHERE id=8",
  "UPDATE source_rows SET id=101,label='moved-1',revision=1 WHERE id=1",
  "UPDATE source_rows SET id=102,label='moved-2',revision=1 WHERE id=2",
  "UPDATE source_rows SET id=103,label='moved-3',revision=1 WHERE id=3",
  "UPDATE source_rows SET label='sentinel',revision=999 WHERE id=7"]
 workload='USE '+schema+'; START TRANSACTION;\n'+';\n'.join(mutations)+';\nCOMMIT;\n'
 (d/'workload.sql').write_text(workload);sql(workload)
 def first_pair(child):
  ready=child.wait('DELIVERY_READY')
  if size==2:
   assert [len(g['members']) for g in ready['groups']]==[1]
   child.send('CONFIRM');ready=child.wait('DELIVERY_READY')
  assert [len(g['members']) for g in ready['groups']]==[2]
  return ready
 first_pair(first)
 assert not [e for e in first.traces() if e['kind']=='RAW_ACK_BEGIN']
 write(d/'before-crash-D.json',offset());assert offset()==baseline
 first.crash();write(d/'after-crash-D.json',offset());assert offset()==baseline

 recovered=Child(d/'recovered',schema,connector,size)
 first_pair(recovered);write(d/'assembled-before-confirm-D.json',offset());assert offset()==baseline
 recovered.send('CONFIRM');gate=recovered.wait('AFTER_DELAYED_ACK_GATE')
 a=next(b for b in recovered.windows() if b['callback']==gate['callback'])
 b=next(b for b in recovered.windows() if b['callback']==gate['currentCallback'])
 until('delayed callback A offset durably persisted',lambda:a['records'][-1]['sourceOffset'] in offset())
 middle=offset();write(d/'after-delayed-A-before-B-D.json',middle)
 assert middle==[a['records'][-1]['sourceOffset']]
 if size==2:assert middle!=[b['records'][-1]['sourceOffset']]
 recovered.send('RELEASE_ACK')
 if size==2:
  for n in [2,3]:
   delivery=recovered.wait('DELIVERY_READY')
   expected=1 if n==2 else 2
   assert len(delivery['groups'])==expected
   write(d/f'before-pair-{n}-confirm-D.json',offset())
   if n==2:assert offset()==middle
   recovered.send('CONFIRM')
 expected_callbacks=2 if size==1 else 4
 until('all real callbacks ACKed',lambda:len([e for e in recovered.traces() if e['kind']=='RAW_ACK_RETURNED'])==expected_callbacks)
 last=recovered.windows()[-1]['records'][-1]['sourceOffset']
 until('final D',lambda:offset()==[last]);write(d/'final-D.json',offset())
 callbacks=recovered.windows();assert [len(b['records']) for b in callbacks]==([1,1] if size==1 else [2,2,2,2])
 recovered.crash()

 follow=Child(d/'after-commit-restart',schema,connector,size)
 sql(f"UPDATE {schema}.source_rows SET label='after-restart',revision=1000 WHERE id=6")
 delivery=follow.wait('DELIVERY_READY')
 assert len(delivery['groups'])==1 and len(delivery['groups'][0]['members'])==1
 member=delivery['groups'][0]['members'][0];assert member['key']=={'id':6} and member['value']['op']=='u'
 assert offset()==[last];write(d/'followup-before-confirm-D.json',offset())
 follow.send('CONFIRM');until('new data durable D',lambda:offset()==[member['sourceOffset']])
 write(d/'followup-final-D.json',offset());follow.crash()
 result={'scenario':name,'pids':[first.p.pid,recovered.p.pid,follow.p.pid],
  'callback_sizes':[len(b['records']) for b in callbacks],'delayed_ack_gate':gate,
  'assembly_before_confirmation_D_unchanged':True,'delayed_old_handle_called_on_later_callback':True,
  'all_raw_callbacks_ACKed_in_order':True,'post_ACK_restart_delivers_new_change':True,
  'scope':'TEST assembler/coverage controller + real Engine/Bridge/JDBC D; no production Source Stream, Actor, Journal, Sink or target protocol'}
 write(d/'result.json',result);print('SCENARIO_OBSERVED',name,'callbacks',result['callback_sizes'],flush=True)
 return result

try:
 command(['docker','run','-d','--name',CONTAINER,'-p','127.0.0.1::3306','-e','MYSQL_ROOT_PASSWORD='+PASSWORD,'-e','MYSQL_ROOT_HOST=%',
  'mysql:8.4','--server-id=186020','--log-bin=mysql-bin','--binlog-format=ROW','--binlog-row-image=FULL','--gtid-mode=ON','--enforce-gtid-consistency=ON'])
 PORT=int(command(['docker','port',CONTAINER,'3306/tcp']).rsplit(':',1)[1])
 def ready():
  try:return sql('SELECT 1')=='1'
  except subprocess.CalledProcessError:return False
 until('formal TCP MySQL ready',ready)
 write(OUT/'environment.json',{'container':CONTAINER,'mysql':sql('SELECT VERSION(),@@server_uuid,@@binlog_format,@@binlog_row_image,@@gtid_mode'),
  'image':command(['docker','inspect','--format','{{.Image}}',CONTAINER]),'commit':command(['git','rev-parse','HEAD'],cwd=ROOT),
  'production_diff':command(['git','diff','HEAD','--','pekko-server/src/main','build.sbt'],cwd=ROOT),
  'debezium':'3.6.1.Final','java':subprocess.run([str(JAVA),'-version'],capture_output=True,text=True).stderr})
 results=[scenario('split-pair',1),scenario('chained-pairs',2)]
 write(OUT/'result.json',{'scenarios':results,'coverage_source':'manual test signal, not a target commit or Actor Journal',
  'production_modified':False,'scope':'source/Bridge probe only'})
 print('DEFERRED_ACK_PROBE_OBSERVED',OUT,flush=True)
except BaseException as e:
 write(OUT/'failure.json',{'error':repr(e),'stderr':getattr(e,'stderr',None)});raise
finally:
 for c in CHILDREN:
  if c.p.poll() is None:c.p.kill();c.p.wait(timeout=15)
  c.reader.join(timeout=3)
  if not c.log.closed:c.log.close()
 logs=subprocess.run(['docker','logs',CONTAINER],capture_output=True,text=True)
 (OUT/'mysql-container.log').write_text(logs.stdout+logs.stderr)
 subprocess.run(['docker','stop','-t','10',CONTAINER],capture_output=True,text=True)
 print('Evidence retained:',OUT,flush=True)
