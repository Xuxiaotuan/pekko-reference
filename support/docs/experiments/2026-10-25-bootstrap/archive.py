#!/usr/bin/env python3
"""Archive all attempts without changing or deleting original evidence. No credentials are read."""
import argparse,hashlib,json,zipfile
from pathlib import Path
repo=Path(__file__).resolve().parents[3];out=Path(__file__).resolve().parent/'artifacts'
p=argparse.ArgumentParser();p.add_argument('--matrix',type=Path,required=True);p.add_argument('--unit',type=Path,required=True);p.add_argument('--regression',type=Path,required=True);args=p.parse_args()
verification=json.loads((out.parent/'final-summary.json').read_text());assert verification['pass'] and verification['completeMatrix']
for r in [args.matrix,args.unit,args.regression]:
    assert (r/'result.json').exists()
    if r!=args.regression:assert json.loads((r/'result.json').read_text())['exitCode']==0
    assert (r/'source-manifest.json').read_bytes()==(args.matrix/'source-manifest.json').read_bytes()
out.mkdir(exist_ok=True)
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
index=[]
def pack(name,files):
    with zipfile.ZipFile(out/name,'w',zipfile.ZIP_DEFLATED,compresslevel=7) as z:
        for f in files:
            relative=str(f.relative_to(repo));z.write(f,relative)
            index.append({'path':relative,'bytes':f.stat().st_size,'sha256':sha(f),'archive':name})
    assert (out/name).stat().st_size<90*1024*1024,'archive must be split before a later Git upload'
raw=sorted(f for f in (repo/'target/bootstrap-matrix').rglob('*') if f.is_file())
# Regression cases may use legacy roots; include only directories belonging to this regression attempt.
for d in json.loads((args.regression/'result.json').read_text())['caseDirectories']:
    raw+=sorted(f for f in (repo/d).rglob('*') if f.is_file())
for interrupted in (repo/'target/bootstrap-runners').glob('*/interrupted.json'):
    for d in json.loads(interrupted.read_text()).get('caseDirectories',[]):
        raw+=sorted(f for f in (repo/d).rglob('*') if f.is_file())
raw=sorted(set(raw))
raw_groups=[];raw_group=[];raw_size=0
for f in raw:
    n=f.stat().st_size
    if raw_group and raw_size+n>128*1024*1024:raw_groups.append(raw_group);raw_group=[];raw_size=0
    raw_group.append(f);raw_size+=n
if raw_group:raw_groups.append(raw_group)
for i,g in enumerate(raw_groups,1):pack(f'raw-evidence-{i:02d}.zip',g)
files=sorted(f for f in (repo/'target/bootstrap-runners').rglob('*') if f.is_file())
files+=sorted(f for f in (repo/'target').glob('bootstrap-*') if f.is_file() and f.suffix in ['.log','.json','.diff','.txt'])
groups=[];group=[];size=0
for f in files:
    n=f.stat().st_size
    if group and size+n>55*1024*1024:groups.append(group);group=[];size=0
    group.append(f);size+=n
if group:groups.append(group)
for i,g in enumerate(groups,1):pack(f'frozen-runs-{i:02d}.zip',g)
(out/'archive-index.json').write_text(json.dumps(index,ensure_ascii=False,indent=2)+'\n')
(out/'SHA256SUMS').write_text(''.join(sha(f)+'  '+f.name+'\n' for f in sorted(out.iterdir()) if f.is_file() and f.name!='SHA256SUMS'))
print(json.dumps({'files':len(index),'archives':len(groups)+len(raw_groups),'sha256':str(out/'SHA256SUMS')},indent=2))
