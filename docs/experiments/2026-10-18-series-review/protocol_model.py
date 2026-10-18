#!/usr/bin/env python3
"""Finite abstract model of event deduplication under re-batching. NOT production CDC."""
import collections, json, pathlib
# IDs are provided by the model: this does NOT derive IDs from real SourceRecords.
events=[('e1','put','a',1),('e2','put','a',2),('e3','put','b',7),
        ('e4','delete','a',None),('e5','put','a',3),('e6','put','b',8)]
def partitions(xs):
 if not xs: yield []; return
 for mask in range(1 << (len(xs)-1)):
  groups=[];start=0
  for i in range(len(xs)-1):
   if mask & (1<<i):groups.append(xs[start:i+1]);start=i+1
  yield groups+[xs[start:]]
def effect(state,e):
 _,op,key,value=e
 if op=='put':state[key]=value
 else:state.pop(key,None)
def commit(state,ledger,audit,batch):
 # Copies model an atomic transaction: conflict rolls the whole batch back.
 s=dict(state);l=dict(ledger);a=list(audit)
 for e in batch:
  identity=e[0];payload=e[1:]
  if identity in l:
   if l[identity]!=payload:raise ValueError('same event identity with different payload')
  else:l[identity]=payload;effect(s,e);a.append(identity)
 return s,l,a
expected={}
for e in events:effect(expected,e)
cases=0
for original in partitions(events):
 for committed_batches in range(len(original)+1):
  prefix=sum(original[:committed_batches],[]);k=len(prefix)
  state,ledger,audit={},{},[]
  for batch in original[:committed_batches]:state,ledger,audit=commit(state,ledger,audit,batch)
  # D can lag behind T: d is the length of a safely covered prefix, not a binlog pos.
  for d in range(k+1):
   for regrouped in partitions(events[d:]):
    s,l,a=dict(state),dict(ledger),list(audit)
    for batch in regrouped:s,l,a=commit(s,l,a,batch)
    assert s==expected and len(l)==len(events)
    assert collections.Counter(a)==collections.Counter(e[0] for e in events)
    cases+=1
# Counterexample: upsert is locally idempotent, but an older after-image rolls back a newer value.
s={}
for e in [events[0],events[1],events[0]]:effect(s,e)
assert s['a']==1
# Counterexample: maximum completed index skips an earlier unfinished event.
completed={2};unsafe_frontier=max(completed);assert 1 not in completed and unsafe_frontier==2
# Counterexample: deleting receipts before old recovery points expire admits duplicates.
s,l,a=commit({},{},[],events[:2]);l.pop('e1');s,l,a=commit(s,l,a,[events[0]])
assert s['a']==1 and collections.Counter(a)['e1']==2
# Conflict detection uses provided stable identity and canonical payload, not a real CDC codec.
try:commit({}, {'e1':events[0][1:]}, [], [('e1','put','a',999)])
except ValueError:conflict=True
else:conflict=False
assert conflict
out=dict(evidence_level='finite_abstract_model_not_runtime_protocol',events=6,original_partitions=32,
         checked_schedules=cases,final_state=expected,all_schedules_pass=True,
         counterexamples=['old_after_image_regresses_new_value','max_completed_skips_gap','receipt_gc_admits_old_duplicate'],
         same_identity_payload_conflict_rejected=True,
         assumptions=['stable unique supplied IDs','atomic target transactions','single ordered writer','retained receipts','replay starts at d <= committed prefix k'],
         not_modeled=['real SourceRecord identity extraction','J persistence and reply loss','D storage','concurrency/fencing','DDL/snapshot','MySQL/Actor runtime'])
pathlib.Path(__file__).with_name('protocol-model-result.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(out,ensure_ascii=False,indent=2))
