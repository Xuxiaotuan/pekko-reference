#!/usr/bin/env python3
"""Read-only, standard-library validation of archived rows, Journal payloads and source hashes.
Does not connect to a database or rerun any experiment.
"""
from pathlib import Path
import base64
import hashlib
import json
import struct
import tarfile
import io
import bisect

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def cbor(data):
    """Strict decoder for the CBOR value types in these saved Jackson Journal events."""
    pos = 0
    end = object()

    def take(n):
        nonlocal pos
        assert pos + n <= len(data), 'truncated CBOR'
        value = data[pos:pos + n]
        pos += n
        return value

    def value():
        tag = take(1)[0]
        major, info = tag >> 5, tag & 31
        if tag == 255:
            return end
        if major == 7:
            if info in (20, 21, 22):
                return {20: False, 21: True, 22: None}[info]
            if info in (25, 26, 27):
                return struct.unpack({25: '>e', 26: '>f', 27: '>d'}[info], take({25: 2, 26: 4, 27: 8}[info]))[0]
            raise AssertionError('unsupported CBOR simple value')
        n = info if info < 24 else int.from_bytes(take({24: 1, 25: 2, 26: 4, 27: 8}[info]), 'big') if info != 31 else None
        if major in (0, 1):
            assert n is not None
            return n if major == 0 else -1 - n
        if major in (2, 3):
            if n is not None:
                raw = take(n)
                return raw if major == 2 else raw.decode('utf-8')
            parts = []
            while True:
                part = value()
                if part is end:
                    return (b'' if major == 2 else '').join(parts)
                parts.append(part)
        if major == 4:
            result = []
            while n is None or len(result) < n:
                item = value()
                if item is end:
                    assert n is None
                    break
                result.append(item)
            return result
        if major == 5:
            result = {}
            while n is None or len(result) < n:
                key = value()
                if key is end:
                    assert n is None
                    break
                assert key not in result
                result[key] = value()
            return result
        if major == 6:
            return value()
        raise AssertionError('unsupported CBOR major type')

    result = value()
    assert pos == len(data)
    return result


def rows_check(comparison):
    source, target = comparison['source'], comparison['target']
    left, right = {r['id']: r for r in source}, {r['id']: r for r in target}
    assert len(left) == len(source) and len(right) == len(target), 'duplicate keys'
    fields = {'id', 'label', 'amount', 'note', 'updated_at', 'revision'}
    assert all(set(row) == fields for row in source + target)
    equal = left == right
    assert comparison['equal'] == equal
    missing, extra = set(left) - set(right), set(right) - set(left)
    differences = [(key, field) for key in left.keys() & right.keys() for field in fields if left[key][field] != right[key][field]]
    assert missing == set(comparison['missingKeys']) and extra == set(comparison['extraKeys'])
    assert len(differences) == len(comparison['fieldDifferences'])
    return equal


class ArchiveParts(io.RawIOBase):
    """Seekable view over the exact compressed archive split for Git's per-file limit."""
    def __init__(self, paths):
        self.handles=[p.open('rb') for p in paths]
        self.offsets=[0]
        for path in paths:self.offsets.append(self.offsets[-1]+path.stat().st_size)
        self.position=0
    def readable(self):return True
    def seekable(self):return True
    def tell(self):return self.position
    def seek(self, offset, whence=0):
        position=offset if whence==0 else self.position+offset if whence==1 else self.offsets[-1]+offset
        if position<0:raise ValueError('negative seek')
        self.position=position
        return position
    def read(self, size=-1):
        if size<0:size=max(0,self.offsets[-1]-self.position)
        result=[]
        while size and self.position<self.offsets[-1]:
            index=bisect.bisect_right(self.offsets,self.position)-1
            count=min(size,self.offsets[index+1]-self.position)
            handle=self.handles[index];handle.seek(self.position-self.offsets[index])
            block=handle.read(count);assert len(block)==count
            result.append(block);self.position+=count;size-=count
        return b''.join(result)
    def close(self):
        for handle in self.handles:handle.close()
        super().close()


def main(check_current_source=True):
    for line in (HERE / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        assert sha((HERE / name).read_bytes()) == digest, name
    report=json.loads((HERE/'result.json').read_text())
    hashes=json.loads((HERE/'archive-manifest.json').read_text())
    source=json.loads((HERE/'source-manifest.json').read_text())
    historical=json.loads((HERE/'historical-artifact-check.json').read_text())
    assert historical['changed']==[]
    for path,digest in historical['sha256'].items():
        assert sha((REPO/path).read_bytes())==digest,path
    metadata=json.loads((HERE/'evidence-archive.json').read_text())
    paths=[];joined=hashlib.sha256()
    for part in metadata['parts']:
        assert Path(part['file']).name==part['file']
        path=HERE/part['file'];assert path.stat().st_size==part['bytes']
        with path.open('rb') as stream:
            while block:=stream.read(1024*1024):joined.update(block)
        assert sha(path.read_bytes())==part['sha256'];paths.append(path)
    assert joined.hexdigest()==metadata['sha256']
    with ArchiveParts(paths) as stream, tarfile.open(fileobj=stream,mode='r:gz') as archive:
        members={m.name:m for m in archive.getmembers()}
        assert set(members)==set(hashes)
        def read(name):
            member=members[name]
            assert member.isfile() and not Path(name).is_absolute() and '..' not in Path(name).parts
            raw=archive.extractfile(member).read()
            assert sha(raw)==hashes[name],name
            return raw
        def load(name):return json.loads(read(name))
        for name in members:read(name)
        for original,blob in json.loads((HERE/'runtime-jars.json').read_text()).items():
            assert original.startswith('runtime/') and blob.startswith('runtime/blobs/')
            assert sha(read(blob))==Path(blob).stem
        for path,digest in source.items():
            assert sha(read('source/current/'+path))==digest,path
            if check_current_source:assert sha((REPO/path).read_bytes())==digest,path
        counts={'converged':0,'quarantined':0}
        journal_pages=0
        for case in report['multiTable']['scenarios']:
            prefix=case['archivePrefix']+'/'
            final=load(prefix+'final.json');scenario=case['scenario']
            control=final['control'];assert control['maxReaders']<=8 and control['maxAttached']<=4
            assert control['readers']==0 and control['engineRuns']==0
            unknown=scenario=='CLOSE_UNKNOWN'
            assert control['entities']==control['attached']==int(unknown)
            assert control['sourcePoolsClosed'] and control['targetPoolsClosed']
            assert final['D'].startswith('N/A')
            ledger={r['batch_id']:r for r in final['T']};assert len(ledger)==len(final['T'])
            assert set(ledger)=={r['batch_id'] for r in final['digests']}
            assert all(d['source_sha256']==d['target_sha256'] for d in final['digests'])
            assert len(final['comparison'])==16 and sum(c['sourceCount'] for c in final['comparison'])==1167
            for comparison in final['comparison']:
                equal=rows_check(comparison)
                assert equal != (unknown and comparison['table']==0)
                audit=[a for a in final['audit'] if a['table_id']==str(comparison['table'])]
                assert len(audit)==comparison['targetCount'] and all(a['op']=='I' for a in audit)
                assert len({a['row_id'] for a in audit})==len(audit),'duplicate physical target effects'
            plans={};starts={};pages={};retired={};events=[]
            for row in final['J']:
                if not row['persistence_id'].startswith('workflow-table-'):continue
                workflow=row['persistence_id'].removeprefix('workflow-')
                body=cbor(base64.b64decode(row['payload_base64']))
                events.append((workflow,row,body))
                if row['event_ser_manifest'].endswith('$ResumableExecutionStarted'):
                    starts.setdefault(workflow,[]).append(body['executionId'])
                update=body.get('update',{});kind=update.get('_type','').split('.')[-1].rstrip('$')
                if kind=='InitializeSnapshotSplits':plans.setdefault(workflow,set()).add(update['plan']['id'])
                if kind=='RetireSnapshotWindow':retired[workflow]=retired.get(workflow,0)+1
                if kind=='CommitSnapshotPage':
                    cp=update['checkpoint'];t=ledger[cp['batchId']]
                    assert t['workflow_id']==workflow and body['executionId']==t['execution_id']
                    assert t['partition_id']==cp['partitionId'] and int(t['batch_sequence'])==cp['batchSequence']
                    assert t['cursor_value']==cp['cursor']['value']
                    pages[cp['batchId']]=cp;journal_pages+=1
            assert len(starts)==16 and all(len(v)==1 for v in starts.values())
            assert len(plans)==16 and all(len(v)==1 for v in plans.values())
            if unknown:
                assert set(pages)<=set(ledger)
                assert all(ledger[b]['workflow_id']=='table-00' for b in set(ledger)-set(pages))
            else:assert set(pages)==set(ledger),'final durable J/T mismatch'
            for row in control['tasks']:
                workflow=row['workflow_id'];assert row['execution_id']==starts[workflow][0]
                assert row['phase']==('unknown' if unknown and workflow=='table-00' else 'completed')
                assert (row['grant_id'] is not None)==(unknown and workflow=='table-00')
            if not unknown:assert retired['table-00']>=5
            observations=load(prefix+'state-observations.json')
            assert observations and any('physicalConnectionsIncludingObserver' in x for x in observations)
            for state in observations:
                active=[r for r in state['tasks'] if r['phase'] in ('held','running','drained','unknown')]
                assert len(active)<=6 and sum(int(r['parallelism'])*2 for r in active)<=16
                assert sum(int(r['parallelism'])*int(r['page_bytes'])*3 for r in active)<=131072
                assert sum(r['phase']=='held' for r in active)<=2
                owners={r['owner_id'] for r in active if r['owner_id']}
                assert all(sum(r['owner_id']==owner for r in active)<=4 for owner in owners)
                assert state['maxAttached']<=4 and state['maxReaders']<=8
            ev=load(prefix+'events.json');all_events=ev['original']+ev['recovered']
            if scenario=='T_J_CRASH':
                confirm=load(prefix+'confirmed-owner-stop-requeue.json')
                assert confirm['automatic'] is False and confirm['processTerminated'] and confirm['oldConnectionsGone']
                assert any(e['kind']=='T_CONFIRMED' and e['workflow']=='table-00' and e['replay'] for e in ev['recovered'])
            if scenario in ('RELEASE_REPLY_LOST','GRANT_REPLY_LOST','BIND_REPLY_LOST'):
                assert any(e['kind']=='CONTROL_COMMIT_RESULT_LOST' for e in all_events)
            if scenario=='RELEASE_REPLY_HELD':
                start=next(i for i,e in enumerate(ev['original']) if e['kind']=='CUTPOINT')
                end=next(i for i,e in enumerate(ev['original']) if e['kind']=='CONTROL_RELEASE_REPLY_RETURNED')
                assert start<end
                assert ev['original'][start]['otherTablesCompleted']==15
                assert any(e['kind']=='T_CONFIRMED' and e['workflow']=='table-00' for e in ev['original'][start+1:end])
                cut=load(prefix+'cut.json')
                assert not any(r['event_ser_manifest'].endswith('$SnapshotExecutionPaused') for r in cut['J'] if r['persistence_id']=='workflow-table-00')
            if scenario in ('T_J_CRASH','READ_T','STOP_READ_T'):
                cut=load(prefix+'cut.json')
                assert any(t['workflow_id']=='table-00' and t['partition_id'].endswith(':0') and t['batch_sequence']=='0' for t in cut['T'])
                for r in cut['J']:
                    if r['persistence_id']!='workflow-table-00':continue
                    u=cbor(base64.b64decode(r['payload_base64'])).get('update',{})
                    cp=u.get('checkpoint');assert not cp or not cp['partitionId'].endswith(':0')
                    assert u.get('_type','').split('.')[-1].rstrip('$') not in ('RetrySnapshotSplit','BlockSnapshotSplit')
            counts['quarantined' if unknown else 'converged']+=1
        assert counts=={'converged':11,'quarantined':1}
        meta=report['metadata'];load_result=load(meta['archivePrefix']+'/load-result.json');cold=load(meta['archivePrefix']+'/inspect-result.json')
        for result in (load_result,cold):
            assert result['rows']==700000 and result['passed']
            assert {r['phase']:int(r['n']) for r in result['phases']}=={'held':2,'running':1,'unknown':1,'ready':699996}
            assert any(r['key']=='pekko_table_ready' for r in result['explain'])
            assert [q['rows'] for q in result['metadataPages']]==[100,100,100,0]
            assert all(h['heapUsedAfterGc']<=h['heapMax']==268435456 and h['pekkoThreadsObserved']==0 for h in result['heap'])
        assert load_result['claimedInitially']==4 and cold['claimedInitially']==0
        regression_counts={}
        for group in ('snapshotRegression','cdcRegression'):
            cases=report[group]['scenarios'];regression_counts[group]=len(cases)
            assert len(cases)==(22 if group=='snapshotRegression' else 27)
            for case in cases:
                final=load(case['archivePrefix']+'/final.json')
                equal=rows_check(final['comparison'])
                if case['classification']=='safe_pause':assert final['J']['status'] in ('snapshot_blocked','recovery_blocked')
                else:assert equal
                if group=='snapshotRegression':assert final['D'].startswith('N/A')
                elif case['classification']!='safe_pause':
                    checkpoints=[e['checkpoint'] for e in final['J']['journal'] if e.get('checkpoint')]
                    if checkpoints:assert checkpoints[-1]['cursor']['offset'] in [json.loads(r['offset_val']) for r in final['D']]
        for failure in report['retainedFailures']:
            assert load(failure['archivePrefix']+'/failure-message.json')
    print(json.dumps(dict(archiveIntegrity=True,currentSourceChecked=check_current_source,historicalFilesUnchanged=historical['checked'],multiTable=counts,journalPagesChecked=journal_pages,metadataRows=700000,**regression_counts,scope='offline evidence validation, not a new live experiment'),ensure_ascii=False))

if __name__=='__main__':
    import sys
    main('--archived-source' not in sys.argv)
