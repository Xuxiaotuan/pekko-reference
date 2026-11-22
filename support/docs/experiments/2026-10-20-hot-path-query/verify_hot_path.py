#!/usr/bin/env python3
"""Check captured optimization evidence. Does not restart any database or job."""
from pathlib import Path
import hashlib
import json
import tarfile

ROOT = Path(__file__).resolve().parent


def verify(read):
    manifest = json.loads(read('manifest.json'))
    for name, digest in manifest['files'].items():
        assert hashlib.sha256(read(name)).hexdigest() == digest, name
    focused = read('checks/focused-latest.log').decode()
    full = read('checks/full-module.log').decode()
    baseline = read('checks/red-statement-count.log').decode()
    assert '64 was not equal to 1' in baseline and 'Tests: succeeded 0, failed 1' in baseline
    expected = 'CDC_STATEMENT_REUSE_OBSERVED events=64 targetPrepares=1 groupSelectPrepares=1 groupInsertPrepares=1'
    assert expected in focused
    assert 'Tests: succeeded 67, failed 0' in focused and 'Suites: completed 8, aborted 0' in focused
    assert 'Tests: succeeded 462, failed 0' in full and 'Suites: completed 76, aborted 0' in full
    assert '*** FAILED ***' not in focused and '*** FAILED ***' not in full and '*** ABORTED ***' not in full
    for name in ["keep timed-out requests' outstanding asks inside the global budget",
                 'read actual JDBC persistence IDs and return persisted workflow revisions',
                 'return explicit overload and deadline statuses for list queries']:
        assert name in focused
    dep = json.loads(read('checks/dependency.json'))
    assert dep['protobufVersion'] == '4.33.4' and dep['resolvedRuntimeCount'] == 1 and dep['legacyShimsPresent'] is True
    source = json.loads(read('source-manifest.json'))
    for name, digest in source['sourceSha256'].items():
        assert hashlib.sha256(read('source/' + name)).hexdigest() == digest
    return dict(verification='PASS', evidenceLayer='Local H2/JDBC and fixed query windows',
                rows=64, targetPrepareCallsBefore=64, targetPrepareCallsAfter=1,
                groupSelectPrepareCallsAfter=1, groupInsertPrepareCallsAfter=1,
                focusedTests=67, defaultModuleTests=462,
                abortedSuites=0, queryBudgetTests='PASS', protobufCompatibility='PASS',
                boundary='No throughput speedup, MySQL network-roundtrip reduction, or 700k-task capacity measurement.')


if __name__ == '__main__':
    with tarfile.open(ROOT / 'evidence.tar.gz', 'r:gz') as archive:
        print(json.dumps(verify(lambda name: archive.extractfile(name).read()), indent=2))
