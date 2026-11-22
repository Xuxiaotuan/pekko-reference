#!/usr/bin/env python3
"""Recompute existing evidence; this does not run MySQL or the CDC application."""
import collections, hashlib, json, pathlib, tarfile
here=pathlib.Path(__file__).resolve().parent
archive=here.parent/'evidence/2026-10-18-cdc-journal-window.tar.gz'
expected=(archive.with_name(archive.name+'.sha256')).read_text().split()[0]
assert hashlib.sha256(archive.read_bytes()).hexdigest()==expected
with tarfile.open(archive) as tar:
 def raw(p):return tar.extractfile('cdc-journal-window/'+p).read()
 def read(p):return json.loads(raw(p))
 hashes=read('files-sha256.json')
 for p,h in hashes.items():assert hashlib.sha256(raw(p)).hexdigest()==h,p
 def checkpoint(j):return [e['checkpoint'] for e in j['journal'] if e['checkpoint'] is not None][-1]
 def diff(c):
  left={r['id']:r for r in c['source']};right={r['id']:r for r in c['target']}
  assert len(left)==len(c['source']) and len(right)==len(c['target'])
  differences=[{'id':k,'field':f,'source':left[k][f],'target':right[k][f]}
    for k in sorted(left.keys()&right.keys()) for f in sorted(left[k]) if left[k][f]!=right[k][f]]
  missing=sorted(left.keys()-right.keys());extra=sorted(right.keys()-left.keys())
  assert differences==c['fieldDifferences'] and missing==c['missingKeys'] and extra==c['extraKeys']
  assert c['equal']==(not differences and not missing and not extra)
  assert set(c['comparedFields'])=={'id','label','amount','note','updated_at','revision'}
  return dict(source_rows=len(left),target_rows=len(right),missing=missing,extra=extra,
    field_differences=len(differences),different_common_rows=len({d['id'] for d in differences}),
    by_field=dict(collections.Counter(d['field'] for d in differences)),equal=c['equal'])
 result=dict(kind='historical_evidence_recalculation_not_database_rerun',archive_sha256=expected,verified_internal_files=len(hashes),cases={})
 for case in read('manifest.json')['verifiedCases']:
  base=read(case+'/baseline.json');cut=read(case+'/cutpoint.json');final=read(case+'/final.json');replay=read(case+'/replay.json')
  old=replay['originalBatch'];new=replay['firstReplayedBatch']
  assert replay['originalPid']!=replay['recoveredPid']
  assert old['batchId']==new['batchId'] and old['sequence']==new['sequence']
  assert old['rows'][0]==new['rows'][0]
  assert int(cut['T'][-1]['batch_sequence'])==checkpoint(cut['J'])['sequence']+1
  assert cut['D']==read(case+'/after-crash-D.json')
  diffs=diff(final['comparison']);assert diff(base['comparison'])['equal']
  row=dict(cut_T=int(cut['T'][-1]['batch_sequence']),cut_J=checkpoint(cut['J'])['sequence'],
    final_T=int(final['T'][-1]['batch_sequence']),final_J=checkpoint(final['J'])['sequence'],
    original_rows=len(old['rows']),replay_rows=len(new['rows']),same_batch_id=True,
    old_cursor=old['cursor'],new_cursor=new['cursor'],first_event_source=old['rows'][0]['source'],
    audit=[len(base['audit']),len(cut['audit']),len(final['audit'])],final_status=final['J']['status'],comparison=diffs,
    journal_writers=len({e['writer'] for e in final['J']['journal']}))
  if 'exact-replay' in case:
   after=read(case+'/after-replay-before-new-data.json');assert after['audit']==cut['audit']
   assert old['rows']==new['rows'] and old['cursor']==new['cursor']
   assert diffs['equal'] and row['final_T']==row['final_J']==9
   assert json.loads(final['D'][0]['offset_val'])==checkpoint(final['J'])['cursor']['offset']
   row['audit'].insert(2,len(after['audit']))
  else:
   assert final['audit']==cut['audit'] and final['D']==cut['D']
   assert row['final_status']=='failed' and row['final_T']==5 and row['final_J']==4
   assert diffs['field_differences']==216 and diffs['missing']==['65'] and diffs['extra']==['64']
  result['cases'][case]=row
 (here/'evidence-review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({'verified_internal_files':len(hashes),'cases':{k:v['comparison'] for k,v in result['cases'].items()}},ensure_ascii=False,indent=2))
