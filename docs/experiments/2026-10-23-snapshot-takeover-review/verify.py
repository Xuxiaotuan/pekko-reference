#!/usr/bin/env python3
"""Offline evidence verification. Does not connect to MySQL or run recovery."""
from pathlib import Path
import hashlib, io, json, runpy, sys, tarfile, zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sha = lambda data: hashlib.sha256(data).hexdigest()


def main():
    for line in (HERE / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        assert sha((HERE / name).read_bytes()) == digest, name
    report = json.loads((HERE / 'result.json').read_text())
    manifest = json.loads((HERE / 'archive-manifest.json').read_text())
    files = {}
    with tarfile.open(HERE / 'evidence.tar.gz', 'r:gz') as archive:
        for item in archive.getmembers():
            assert item.isfile() and not Path(item.name).is_absolute() and '..' not in Path(item.name).parts
            data = archive.extractfile(item).read()
            assert item.name not in files and sha(data) == manifest[item.name], item.name
            files[item.name] = data
    assert set(files) == set(manifest)
    current = '--archived-source' not in sys.argv
    final_source = json.loads((HERE / 'source-manifest.json').read_text())
    for runtime in report['runtimes']:
        root = 'runners/' + runtime + '/'
        source = json.loads(files[root + 'source-manifest.json'])
        with zipfile.ZipFile(io.BytesIO(files[root + 'source.zip'])) as archive:
            assert set(archive.namelist()) == set(source)
            for path, digest in source.items():
                assert sha(archive.read(path)) == digest, path
        # Later additions only strengthen the test facility; production code must match every live run.
        assert {p: h for p, h in source.items() if p.startswith('server/src/main/') or p == 'build.sbt'} == {
            p: h for p, h in final_source.items() if p.startswith('server/src/main/') or p == 'build.sbt'}
    if current:
        for path, digest in final_source.items():
            assert sha((REPO / path).read_bytes()) == digest, path
    validate = runpy.run_path(str(HERE.parent / '2026-10-23-snapshot-takeover/verify.py'))['validate_case']
    cbor = runpy.run_path(str(HERE.parent / '2026-10-22-table-scheduling/verify.py'))['cbor']
    assert len(report['cases']) == 22 and len({c['case'] for c in report['cases']}) == 22
    rows = sum(validate(case, files.__getitem__, cbor) for case in report['cases'])
    for case in report['cases']:
        value = json.loads(files[case['archivePrefix'] + '/result.json'])
        assert value['runtime'] == case['runtime']
    expected = {'reject','commit_reply_lost','commit_not_sent','readback_unavailable','stale_reject','stale_release','yield','delayed_release'}
    assert {c['case'] for c in report['triggerCases']} == expected
    for case in report['triggerCases']:
        root = case['archivePrefix'] + '/'
        result = json.loads(files[root + 'result.json'])
        assert result['runtime'] == report['triggerRuntime'] and result['scenario'] == case['case']
        assert result['triggerCacheEmpty'] and result['durableTriggerPreserved'] and result['duplicateRejected']
        task = json.loads(files[root + 'pekko_table_tasks.json'])
        permit = json.loads(files[root + 'pekko_snapshot_writer_permissions.json'])
        trigger = json.loads(files[root + 'pekko_snapshot_schedule_triggers.json'])
        assert len(task) == len(permit) == len(trigger) == 1
        assert task[0]['phase'] == 'blocked' and task[0]['grant_id'] is None and task[0]['owner_id'] is None
        assert permit[0]['phase'] == 'parked'
        assert trigger[0]['disposition'] == 'accepted' and trigger[0]['scheduled_at'] == '2000'
        assert trigger[0]['trigger_id'] == 'cron-2000' and trigger[0]['execution_id'] == task[0]['execution_id']
        assert result['contextRetainedOnUnknown'] == (case['case'] in {'commit_not_sent','readback_unavailable'})
    assert json.loads(files['runners/' + report['triggerRuntime'] + '/result.json'])['exitCode'] == 0
    # Preserve the failed batch; successful selected cases may come from a separately labelled rerun.
    for case in report['cases']:
        runtime = json.loads(files['runners/' + case['runtime'] + '/result.json'])
        assert case['directory'] in runtime['caseDirectories']
    log = files['logs/backend-final.log'].decode(errors='replace')
    assert f"Tests: succeeded {report['backendTests']}, failed 0, canceled 0, ignored 0, pending 0" in log
    assert 'All tests passed.' in log
    assert report['cleanup']['clients'] == report['cleanup']['transactions'] == report['cleanup']['temporaryAccounts'] == 0
    assert report['cleanup']['stopped'] and report['cleanup']['schemasRetained']
    print(json.dumps(dict(archiveIntegrity=True,currentSourceChecked=current,scenarios=22,comparedRows=rows,
                         fieldsPerRow=6,triggerBoundaries=8,scope='offline evidence verification; not a live rerun')))


if __name__ == '__main__':
    main()
