#!/usr/bin/env python3
"""Verify captured session evidence without connecting to the database or restarting a job."""
import json
import tarfile
from pathlib import Path
import verify as windows

ROOT = Path(__file__).resolve().parent


def verify(read):
    reports = []
    for case in ('none', 'cancel-T'):
        def data(name):
            return json.loads(read(f'sessions/{case}/{name}.json'))
        baseline, final, released, outcome = [data(n) for n in ('baseline', 'final', 'released', 'outcome')]
        comparison = windows.differences(final['comparison'])
        assert comparison == dict(sourceRows=64, targetRows=64, missingKeys=0, extraKeys=0, fieldDifferences=0)
        assert released['status'] == 'Released' and released['liveRunners'] == 0
        assert outcome['physicalRelease'] is True and outcome['allFieldsEqual'] is True
        events = outcome['events']
        closed = [i for i, e in enumerate(events) if e['kind'] == 'ENGINE_CLOSED']
        assert len(closed) == 1
        releases = [i for i, e in enumerate(events) if e['kind'] == 'CANCEL_RESULT' and e['status'] == 'Released']
        assert len(releases) == 1 and closed[0] < releases[0]
        begun = [e['callback'] for e in events if e['kind'] == 'RAW_ACK_BEGIN']
        returned = [e['callback'] for e in events if e['kind'] == 'RAW_ACK_RETURNED']
        assert begun == list(range(len(begun))) and returned == list(range(len(returned)))
        assert set(returned) <= set(begun)
        assert not any(e['kind'] == 'RAW_ACK_BEGIN' for e in events[closed[0] + 1:])
        if case == 'cancel-T':
            cut, pending = data('cutpoint'), data('cancel-pending')
            assert pending['status'] == 'TimedOut'
            assert cut['T'] != baseline['T']
            assert windows.checkpoint(cut['J']) == windows.checkpoint(baseline['J'])
            assert windows.checkpoint(final['J'])['sequence'] > windows.checkpoint(baseline['J'])['sequence']
            timeout = [i for i, e in enumerate(events) if e['kind'] == 'CANCEL_RESULT' and e['status'] == 'TimedOut']
            resume = [i for i, e in enumerate(events) if e['kind'] == 'T_RELEASED']
            assert len(timeout) == len(resume) == 1 and timeout[0] < resume[0] < closed[0]
        reports.append(dict(case=case, physicalRelease=True, liveRunners=0, **comparison))
    return reports


if __name__ == '__main__':
    with tarfile.open(ROOT / 'evidence.tar.gz', 'r:gz') as archive:
        reports = verify(lambda name: archive.extractfile(name).read())
    result = dict(verification='PASS', cases=reports,
                  boundary='Two fixed real MySQL cancellation windows; no general driver termination deadline.')
    (ROOT / 'session-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
