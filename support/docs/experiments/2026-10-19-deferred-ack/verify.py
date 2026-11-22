#!/usr/bin/env python3
"""Read-only verification of raw callbacks, trace ordering and independent actual-D snapshots."""
import json,sys
from pathlib import Path

def verify(root):
 def read(p):return json.loads(p.read_text())
 def lines(p):return [json.loads(s) for s in p.read_text().splitlines()]
 def gid(r):
  s=r['value']['source'];return json.dumps([s[k] for k in ['db','table','file','pos','row']],separators=(',',':'))
 allpids=[];summary=[]
 for name,size in [('split-pair',1),('chained-pairs',2)]:
  d=root/name;result=read(d/'result.json');allpids.extend(result['pids'])
  baseline=read(d/'baseline-D.json')
  assert baseline==read(d/'before-crash-D.json')==read(d/'after-crash-D.json')==read(d/'assembled-before-confirm-D.json')
  raw=lines(d/'recovered/callbacks.jsonl');trace=lines(d/'recovered/trace.jsonl')
  assert all(b['window'] for b in raw)
  assert [len(b['records']) for b in raw]==([1,1] if size==1 else [2,2,2,2])
  assert [e['seq'] for e in trace]==list(range(len(trace)))
  assert {e['pid'] for e in trace}=={result['pids'][1]}
  entered={e['callback']:e for e in trace if e['kind']=='CALLBACK_ENTER'}
  returned={e['callback']:e for e in trace if e['kind']=='CALLBACK_RETURNING'}
  coverage={}; delivered={};last_ack=-1;begun=[];ack_returns=[];pairs=0
  for e in trace:
   if e['kind']=='PENDING_CALLBACKS':assert e['count']<=2
   elif e['kind']=='PAIR_ASSEMBLED':pairs+=1
   elif e['kind']=='DELIVERY_READY':
    delivered[e['delivery']]=e['groups']
    for group in e['groups']:
     members=group['members'];assert all(gid(r)==group['groupId'] for r in members)
     if len(members)==2:
      a,b=members;assert [r['value']['op'] for r in members]==['d','c']
      assert a['sourceOffset']==b['sourceOffset']
      headers=lambda r:{h['key']:h['value'] for h in r['headers']}
      assert headers(a)['__debezium.newkey']==b['key']
      assert headers(b)['__debezium.oldkey']==a['key']
     else:assert len(members)==1
   elif e['kind']=='TEST_COVERAGE_CONFIRMED':
    assert e['groupIds']==[g['groupId'] for g in delivered[e['delivery']]]
    for g in e['groupIds']:assert g not in coverage;coverage[g]=e['seq']
   elif e['kind']=='RAW_ACK_BEGIN':
    cb=e['callback'];assert cb==last_ack+1;last_ack=cb;begun.append(cb)
    batch=next(b for b in raw if b['callback']==cb)
    assert all(gid(r) in coverage and coverage[gid(r)]<e['seq'] for r in batch['records'])
    assert e['thread']==entered[e['currentCallback']]['thread']
    assert entered[e['currentCallback']]['seq']<e['seq']
    if cb<e['currentCallback']:assert returned[cb]['seq']<entered[e['currentCallback']]['seq']
   elif e['kind']=='RAW_ACK_RETURNED':ack_returns.append(e['callback'])
  assert begun==ack_returns==list(range(len(raw)))
  assert pairs==(1 if size==1 else 3)
  assert len(delivered)==(1 if size==1 else 4)
  first_raw=raw[0]['records'][-1]['sourceOffset']
  assert read(d/'after-delayed-A-before-B-D.json')==[first_raw]
  if size==2:
   assert read(d/'after-delayed-A-before-B-D.json')!=[raw[1]['records'][-1]['sourceOffset']]
   assert read(d/'before-pair-2-confirm-D.json')==[first_raw]
  assert read(d/'final-D.json')==[raw[-1]['records'][-1]['sourceOffset']]
  before=lines(d/'before-coverage-crash/trace.jsonl')
  assert not [e for e in before if e['kind']=='RAW_ACK_BEGIN']
  # Crash was after the pair was assembled and made visible, but before its manual coverage signal.
  pair_ready=[e for e in before if e['kind']=='DELIVERY_READY' and len(e['groups'][0]['members'])==2]
  assert len(pair_ready)==1
  assert not [e for e in before if e['kind']=='TEST_COVERAGE_CONFIRMED' and e['delivery']==pair_ready[0]['delivery']]
  follow=lines(d/'after-commit-restart/callbacks.jsonl')
  records=[r for b in follow for r in b['records']]
  assert len(records)==1 and records[0]['key']=={'id':6} and records[0]['value']['after']['revision']==1000
  assert read(d/'followup-before-confirm-D.json')==read(d/'final-D.json')
  assert read(d/'followup-final-D.json')==[records[0]['sourceOffset']]
  summary.append({'scenario':name,'actual_callbacks':[len(b['records']) for b in raw],
   'assembled_PK_groups':pairs,'raw_ACK_order':ack_returns,'max_retained_callbacks':max(e['count'] for e in trace if e['kind']=='PENDING_CALLBACKS')})
 assert len(allpids)==len(set(allpids))==6
 print(json.dumps({'verified':True,'processes':6,'scenarios':summary,'scope':'manual coverage signals; no T/J or end-to-end target proof'},ensure_ascii=False,indent=2))
if __name__=='__main__':verify(Path(sys.argv[1]))
