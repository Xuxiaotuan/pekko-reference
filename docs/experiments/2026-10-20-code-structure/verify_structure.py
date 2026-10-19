#!/usr/bin/env python3
"""Check extraction against captured pre-refactor source; no database is accessed."""
import hashlib
import json
import re
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BASE = 'pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/'


def normalized(value):
    return re.sub(r'\s+', '', value)


def verify(read):
    manifest = json.loads(read('manifest.json'))
    for name, digest in manifest['files'].items():
        assert hashlib.sha256(read(name)).hexdigest() == digest, name
    def text(name):
        return read(name).decode()
    old = text('before/WorkflowExecutionEngine.scala')
    new = text('source/' + BASE + 'engine/WorkflowExecutionEngine.scala')
    batch = lambda s: s[s.index('  private def processBatch('):s.index('  private def checkpoint(')]
    assert batch(old) == batch(new), 'T/J/ACK batch body changed'
    previous = old[old.index('  private final class ReliableExecutionControl'):old.index('  private def reportedRowsProcessed(')]
    control = text('source/' + BASE + 'engine/ReliableExecutionControl.scala')
    control = control[control.index('private[engine] final class ReliableExecutionControl'):]
    assert normalized(previous.split('{', 1)[1]) == normalized(control.split('{', 1)[1])
    sessions = text('source/' + BASE + 'engine/ReliableExecutionSessions.scala')
    # Preserve dynamic dispatch: cancelWithin must use the Engine's cancel hook,
    # not call the registry's cancel directly. Existing recovery tests cover it.
    assert 'reliableExecutions.cancelWithin(executionId, timeout)(cancel(executionId))' in new
    assert 'requestCancel: => Future[Done]' in sessions
    assert 'requestCancel.onComplete {' in sessions
    assert 'cancel(executionId).onComplete {' not in sessions
    old_resource = text('before/BatchSourceSession.scala')
    scope = text('source/' + BASE + 'nodes/base/SourceResourceScope.scala')
    start = '/** Reserves ownership'
    assert old_resource[old_resource.index(start):] == scope[scope.index(start):]
    return dict(verification='PASS', processBatchUnchanged=True,
                controlBodyUnchanged=True, resourceScopeUnchanged=True,
                engineCancellationHookPreserved=True,
                boundary='Source comparison only; actual runtime results are recorded separately.')


if __name__ == '__main__':
    with tarfile.open(ROOT / 'evidence.tar.gz', 'r:gz') as archive:
        print(json.dumps(verify(lambda name: archive.extractfile(name).read()), indent=2))
