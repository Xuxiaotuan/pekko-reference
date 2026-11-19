#!/usr/bin/env python3
"""Read-only recomputation from saved SourceRecords/offset observations; never connects to MySQL."""
import json,sys
from pathlib import Path

def verify(root):
 def read(name):return json.loads((root/name).read_text())
 result=read('result.json');env=read('environment.json');batches={};rows={}
 for name in result['actual_callback_sizes']:
  batches[name]=[json.loads(line) for line in (root/name/'records.jsonl').read_text().splitlines() if line]
  selected=[b for b in batches[name] if b['window']]
  assert [len(b['records']) for b in selected]==result['actual_callback_sizes'][name],name
  assert {b['pid'] for b in selected}=={result['pids'][name]}
  rows[name]=[r for b in selected for r in b['records']]
  assert all(r['normalizedEnvelope'] is not None and 'decodeFailure' not in r['normalizedEnvelope'] for r in rows[name])
 def identity(r):
  s=r['value']['source'];return (env['server_uuid'],s['db'],s['table'],s['file'],s['pos'],s['row'],r['value']['op'])
 original=rows['original-16'];assert len(original)==39 and len({identity(r) for r in original})==39
 assert len(result['pids'])==len(set(result['pids'].values()))
 for name,data in rows.items():
  if name=='pk-component-resume':continue
  expected=original[10:] if name.startswith('inside-') or name=='pk-advance-delete' else original
  assert [identity(r) for r in data]==[identity(r) for r in expected],name
  assert [r['normalizedEnvelope'] for r in data]==[r['normalizedEnvelope'] for r in expected],name
  for label,extract in [('sourceOffset_differences',lambda r:r['sourceOffset']),
    ('source_metadata_differences',lambda r:r['value']['source']),
    ('processing_timestamp_differences',lambda r:r['value']['ts_ms'])]:
   assert sum(extract(a)!=extract(b) for a,b in zip(data,expected))==result['comparisons'][name][label]
 baseline=read('baseline-D.json')
 assert baseline==read('original-gate-D.json')==read('original-after-crash-D.json')
 for name in ['restart-16','split-1','merge-64']:
  assert baseline==read(name+'-before-D.json')==read(name+'-after-D.json')
 for name in ['advance-prefix-10','pk-advance-delete']:
  acked=[r for b in batches[name] if b['window'] and b['ackRequested'] for r in b['records']]
  assert len(acked)==(10 if name=='advance-prefix-10' else 27)
  assert read(name+'-after-D.json')==[acked[-1]['sourceOffset']]
  assert read(name+'-before-D.json')!=read(name+'-after-D.json')
 inside=read('advance-prefix-10-after-D.json')
 assert inside[0]['row']==6
 for name in ['inside-split-1','inside-merge-64']:
  assert inside==read(name+'-before-D.json')==read(name+'-after-D.json')
 assert inside==read('pk-advance-delete-before-D.json')
 pkD=read('pk-advance-delete-after-D.json')
 assert pkD==read('pk-component-resume-before-D.json')==read('pk-component-resume-after-D.json')
 pk=original[36:38];assert [r['value']['op'] for r in pk]==['d','c']
 assert pk[0]['sourceOffset']==pk[1]['sourceOffset']==pkD[0]
 assert [identity(r) for r in rows['pk-component-resume']]==[identity(original[38])]
 assert len(result['pk_component_ack_boundary']['missing_logical_records'])==1
 assert original[0]['value']['before']==original[2]['value']['before']
 assert original[0]['value']['after']==original[2]['value']['after']
 assert identity(original[0])!=identity(original[2])
 multi=original[4:36];assert len({identity(r)[3:5] for r in multi})==1
 assert [r['value']['source']['row'] for r in multi]==list(range(32))
 assert len({r['value']['source']['gtid'] for r in original})==1
 assert len({identity(r)[:-1] for r in original})==38
 assert len({identity(r)[:5]+identity(r)[6:] for r in original})==8
 print('VERIFIED: 9 distinct JVMs; original 39 records, full/partial replays; all saved D boundaries; row/op collision controls; PK component omission reproduced. Source-only evidence.')

if __name__=='__main__':
 verify(Path(sys.argv[1]))
