#!/usr/bin/env python3
"""Independently recompute archived JSON digests, ACK links and every compared field.
Usage: verify.py RUNNER_DIR [OUTPUT]. Corrupt-input cases deliberately retain failed proofs.
"""
import hashlib,json,sys,re,zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[3]
def canonical(x):return json.dumps(x,sort_keys=True,separators=(',',':'),ensure_ascii=False)
def digest(x):return hashlib.sha256(x.encode()).hexdigest()
run=Path(sys.argv[1]); result=json.loads((run/'result.json').read_text())
summaries=[]
for name in result['caseDirectories']:
    d=repo/name; env=json.loads((d/'environment.json').read_text()); case=env['case']
    corrupt=case.startswith('I15') or case=='I18'
    errors=[]; comparisons=[]; proof_errors=[]
    if env.get("sourceManifestSha256")!=result["sourceManifestSha256"]:errors.append("source manifest mismatch")
    finals=list(d.glob('final.json'))
    for p in finals:
        v=json.loads(p.read_text()); worker=v['worker']; frames={int(x['seq']):x for x in worker['L']['log']}
        for n,f in frames.items():
            calculated=digest(canonical([f['kind'],f['table_name'],f['subscription_id'],f['schema_id'],f['payload'],f.get('point_json') or '']))
            if calculated!=f['digest']:proof_errors.append(f'L digest {n}')
            if f['kind']=='ACK':
                proof=json.loads(f['payload'])
                for ref in proof['references']+([proof['parent']] if proof['parent'] else []):
                    old=frames.get(ref['sequence'])
                    if not old or old['digest']!=ref['digest'] or ref['sequence']>=n:proof_errors.append(f'ACK reference {n}')
        for ident,spool in worker.get('spools',{}).items():
            for page in spool['pages']:
                if digest(page['payload'])!=page['digest']:proof_errors.append(f'{ident} {page["window_id"]} page {page["page_kind"]}:{page["page_index"]}')
            for w in spool['windows']:
                if digest(w['spec_json'])!=w['spec_hash']:proof_errors.append('window spec')
                if w['manifest_json'] and digest(w['manifest_json'])!=w['manifest_hash']:proof_errors.append('window manifest')
            root=''
            for i,w in enumerate(spool['completed']):
                manifest=next(x for x in spool['windows'] if x['window_id']==w['window_id'])['manifest_hash']
                expected=digest(root+'|'+str(i)+'|'+w['window_id']+'|'+manifest)
                if int(w['ordinal'])!=i or w['parent_root']!=root or w['root']!=expected:proof_errors.append('completion chain')
                root=expected
            actor=worker['actors'][ident]
            if actor['completionRoot']!=root or actor['index']!=len(spool['completed']):proof_errors.append('Journal completion root')
        for receipt in v['baselineT']:
            if digest(receipt['payload'])!=receipt['digest']:proof_errors.append('target receipt digest')
        for table,c in v['comparisons'].items():
            left={canonical(r['id']):r for r in c['source']};right={canonical(r['id']):r for r in c['target']}
            missing=sorted(left.keys()-right.keys());extra=sorted(right.keys()-left.keys())
            differences=[(k,f) for k in left.keys() & right.keys() for f in left[k].keys() | right[k].keys() if f not in left[k] or f not in right[k] or left[k][f]!=right[k][f]]
            equal=not missing and not extra and not differences
            if equal!=c['equal']:errors.append('reported comparison disagrees')
            required=worker['actors'].get(table,{}).get('phase')=='STREAMING'
            if required and not equal:errors.append('published unequal table '+table)
            comparisons.append({'table':table,'equal':equal,'fieldsCompared':sum(len(x) for x in left.values()),'missing':missing,'extra':extra,'differentFields':differences,'requiredEqual':required})
        # Confirm immutable accepted boundaries/effects survived the injected window.
        for previous in d.glob('*.json'):
            if previous.name in ['final.json','environment.json','release.json','failure.json','new-generation.json']:continue
            old=json.loads(previous.read_text())
            if 'worker' not in old:continue
            old_worker=old['worker']
            final_fences={x['fence_id']:x for x in worker['L']['fences']}
            for fence in old_worker['L']['fences']:
                if fence['point_json'] and (fence['fence_id'] not in final_fences or final_fences[fence['fence_id']]['point_json']!=fence['point_json']):proof_errors.append('accepted fence moved')
            for ident,actor in old_worker['actors'].items():
                latest=worker['actors'].get(ident)
                if latest and actor['executionId'] and actor['executionId']!=latest['executionId']:proof_errors.append('execution changed on recovery')
                if latest and latest['recoveryAttempts']<actor['recoveryAttempts']:proof_errors.append('recovery budget decreased')
            receipts={(r['bootstrap_id'],r['operation_id']):r['digest'] for r in v['baselineT']}
            for r in old['baselineT']:
                if receipts.get((r['bootstrap_id'],r['operation_id']))!=r['digest']:proof_errors.append('previous T receipt disappeared/changed')
            groups={r['group_id']:r['evidence'] for r in v['T']}
            for r in old['T']:
                if groups.get(r['group_id'])!=r['evidence']:proof_errors.append('previous original group T changed')
        # Prefix order is checked within each original Reader process; SDK ids restart at zero.
        for log in d.glob('*.log'):
            begins=[];returns=[]
            for line in log.open():
                if line.startswith('WINDOW '):
                    event=json.loads(line[7:])
                    if event.get('kind')=='RAW_ACK_BEGIN':begins.append(event['id'])
                    if event.get('kind')=='RAW_ACK_RETURNED':returns.append(event['id'])
            if begins!=sorted(set(begins)) or returns!=begins[:len(returns)]:errors.append('non-prefix original ACK '+log.name)
    if (d/'new-generation.json').exists():
        generation=json.loads((d/'new-generation.json').read_text())
        if generation['source']!=generation['target'] or generation['equal']!=True:errors.append('new generation fields differ')
    activation_d_lag=None
    if case=='I13' and (d/'streaming-with-D-held.json').exists():
        held=json.loads((d/'streaming-with-D-held.json').read_text())['worker']
        f=next(x for x in held['L']['fences'] if x['action']=='F');point=json.loads(f['point_json'])
        offsets=[json.loads(x['offset_val']) for x in held['D']]
        def before(offset):
            if offset['file']==point['file']:return offset['pos']<point['position']
            a,n=offset['file'].rsplit('.',1);b,m=point['file'].rsplit('.',1)
            return a==b and int(n)<int(m)
        activation_d_lag=held['actors']['a']['phase']=='STREAMING' and f['covered_seq'] is not None and bool(offsets) and all(before(x) for x in offsets)
        if not activation_d_lag:errors.append('D lag at durable activation not proven')
    if not corrupt:errors+=proof_errors
    if not (d/'release.json').exists():errors.append('resource completion evidence missing')
    if not finals and not (d/'safe-start-refusal.json').exists():errors.append('final state evidence missing')
    summaries.append({'case':case,'directory':name,'status':'FAIL' if errors else 'PASS','errors':errors,'intentionalProofFailures':proof_errors if corrupt else [],'comparisons':comparisons,'hasFinal':bool(finals),'DlagAtActivation':activation_d_lag,'startupRefused':(d/'safe-start-refusal.json').exists(),'resourcesReleased':(d/'release.json').exists()})
with zipfile.ZipFile(run/'source.zip') as z:
    code=z.read('server/src/test/scala/cn/xuyinyin/magic/workflow/integration/BootstrapMatrixSpec.scala').decode()
    head=code[code.index('for(variant<-Vector('):];head=head[:head.index(')) {')]
    expected=set(re.findall(r'"(I[^"\n]+)"',head))
actual={c['case'] for c in summaries}
# A filtered targeted run is useful diagnostically; complete acceptance requires all expected cases.
complete=actual==expected and len(summaries)==len(expected)
output={'scope':'bootstrap-I01-I24-only','completeMatrix':complete,'runner':str(run),'testExitCode':result['exitCode'],'sourceManifestSha256':result['sourceManifestSha256'],'cases':summaries,'pass':complete and result['exitCode']==0 and all(x['status']=='PASS' and x['resourcesReleased'] and (x['hasFinal'] or x['startupRefused']) for x in summaries)}
text=json.dumps(output,ensure_ascii=False,indent=2)
if len(sys.argv)>2:Path(sys.argv[2]).write_text(text+'\n')
else:print(text)
raise SystemExit(0 if output['pass'] else 1)
