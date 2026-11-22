#!/usr/bin/env python3
"""Offline checks of actual field sets, immutable proofs, raw ACK prefixes and decoded JDBC Journal.

This validates saved artifacts, never re-runs a database or promotes a safe pause to convergence.
"""
import argparse
import hashlib
import json
from pathlib import Path


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def reconcile(left, right):
    a, b = {canonical(r['id']): r for r in left}, {canonical(r['id']): r for r in right}
    require(len(a) == len(left) and len(b) == len(right), 'duplicate primary key in saved rows')
    differences = [(k, f) for k in a.keys() & b.keys() for f in a[k].keys() | b[k].keys()
                   if f not in a[k] or f not in b[k] or a[k][f] != b[k][f]]
    missing, extra = sorted(a.keys() - b.keys()), sorted(b.keys() - a.keys())
    return dict(equal=not missing and not extra and not differences, rows=len(a),
                fields=sum(len(r) for r in a.values()), missing=missing, extra=extra, differences=differences)


def verify_log(evidence):
    frames = {int(f['seq']): f for f in evidence['log']}
    require(len(frames) == len(evidence['log']), 'duplicate L sequence')
    acknowledgements = 0
    for sequence, frame in frames.items():
        expected = digest(canonical([frame[k] for k in ['kind', 'table_name', 'subscription_id', 'schema_id', 'payload']]
                                    + [frame.get('point_json') or '']))
        require(expected == frame['digest'], 'L digest mismatch')
        if frame['kind'] == 'ACK':
            acknowledgements += 1
            proof = json.loads(frame['payload'])
            for ref in proof['references'] + ([proof['parent']] if proof['parent'] else []):
                old = frames.get(int(ref['sequence']))
                require(old is not None and int(ref['sequence']) < sequence and old['digest'] == ref['digest'],
                        'ACK references a missing, changed or future frame')
    return dict(frames=len(frames), acknowledgements=acknowledgements)


def verify_spool(spool):
    windows = {w['window_id']: w for w in spool['windows']}
    for page in spool['pages']:
        require(digest(page['payload']) == page['digest'], 'spool page changed')
    for window in windows.values():
        require(digest(window['spec_json']) == window['spec_hash'], 'window range changed')
        if window['manifest_json'] is not None:
            require(digest(window['manifest_json']) == window['manifest_hash'], 'sealed manifest changed')
    root = ''
    for index, completion in enumerate(spool['completed']):
        window = windows[completion['window_id']]
        expected = digest(root + '|' + str(index) + '|' + completion['window_id'] + '|' + window['manifest_hash'])
        require(int(completion['ordinal']) == index and completion['parent_root'] == root and completion['root'] == expected,
                'completed-window chain changed')
        root = expected
    return root, len(spool['completed'])


def verify_ack_file(path):
    prefixes = {}
    for line in Path(path).read_text(errors='replace').splitlines():
        if not line.startswith('WINDOW '):
            continue
        event = json.loads(line[7:]); kind = event.get('kind')
        if kind in ['RAW_ACK_BEGIN', 'RAW_ACK_RETURNED']:
            entry = prefixes.setdefault(event.get('captureId', 'one-reader'), dict(begin=[], returned=[]))
            entry['begin' if kind == 'RAW_ACK_BEGIN' else 'returned'].append(event['id'])
    for entry in prefixes.values():
        require(entry['begin'] == list(range(len(entry['begin']))), 'ACK begins are not a monotone original-callback prefix')
        require(entry['returned'] == entry['begin'][:len(entry['returned'])], 'ACK returned outside original prefix')
    return prefixes


def canonical_progress(value):
    # Spray Option serialization omits None; the offline Product decoder emits null.
    option_fields = {"window", "manifest", "fence", "upper", "nextLower", "failure"}
    return {k: v for k, v in value.items() if not (k in option_fields and v is None)}


def verify_platform(path, decoded):
    value = json.loads(Path(path).read_text()); worker = value['worker']
    proof = verify_log(worker['L'])
    by_journal = {}
    for entry in decoded:
        if Path(entry['file']).parts[-4:] == Path(path).parts[-4:]:
            by_journal[(entry['persistenceId'], int(entry['state']['revision']))] = entry['state']
    receipts = {(r['bootstrap_id'], r['operation_id']): r for r in (worker.get('baselineT') or [])}
    for receipt in receipts.values():
        require(digest(receipt['payload']) == receipt['digest'], 'target receipt changed')
    comparisons, matched_journal = {}, 0
    for table, saved in value['comparisons'].items():
        result = reconcile(saved['source'], saved['target'])
        require(result['equal'] == saved['equal'], 'reported row equality disagrees with raw fields')
        comparisons[table] = result | dict(required=False)
    for task in worker['tasks']:
        ident = task['task_id']; revision = int(task['journal_revision'])
        journal = by_journal.get(('bootstrap-v1-' + task['capture_id'] + '-' + ident, revision))
        if revision:
            require(journal is not None, 'control projection has no decoded persisted Journal revision')
            matched_journal += 1
            require(journal['executionId'] == task['execution_id'], 'execution identity differs from actual Journal')
            if task['continuation_json']:
                require(canonical_progress(json.loads(task['continuation_json'])) == canonical_progress(journal), 'clean continuation differs from actual Journal')
        if ident in worker['spools']:
            root, count = verify_spool(worker['spools'][ident])
            # STREAMING is sealed; partial canceled/in-flight snapshots can legitimately lag a spool completion.
            if task['phase'] == 'STREAMING':
                require(journal['completionRoot'] == root and journal['index'] == count, 'STREAMING baseline root lacks Journal evidence')
        if task['phase'] == 'STREAMING':
            require(journal is not None and journal['phase'] == 'STREAMING', 'STREAMING lacks persisted phase')
            activation = receipts.get((ident, 'ACTIVATE'))
            require(activation is not None and activation['payload'] == task['initialization_evidence'], 'STREAMING lacks matching target activation')
            accepted = json.loads(activation['payload'])
            fence = next((f for f in worker['L']['fences'] if f['fence_id'] == journal['fence']), None)
            sub = next((s for s in worker['L']['subscriptions'] if s['request_id'] == ident), None)
            require(fence is not None and fence['covered_seq'] is not None and sub is not None,
                    'activation has no covered retained B/F')
            require(accepted['bootstrap'] == ident and accepted['definition'] == journal['definition']
                    and accepted['baselineRoot'] == journal['completionRoot'] and accepted['windows'] == journal['index']
                    and accepted['B'] == json.loads(sub['b_point']) and accepted['F'] == json.loads(fence['point_json'])
                    and accepted['fenceSequence'] == int(fence['seq']) and accepted['schema'] == sub['schema_id'],
                    'activation differs from the retained boundaries and accepted Journal')
            comparisons[task['table_name']]['required'] = True
            require(comparisons[task['table_name']]['equal'], 'published table has unequal fields')
        if task.get('cancel_evidence') == 'PREEXEC_CANCEL':
            require(task['phase'] == 'CANCELED' and revision == 0 and task['execution_id'] is None
                    and task['initialization_evidence'] is None, 'pre-execution cancellation invents initialization')
            require(not any(s['request_id'] == ident for s in worker['L']['subscriptions']), 'pre-execution cancellation has a source B')
    return dict(case=value['case'], resultKind=value['resultKind'], proofs=proof, matchedJournalTasks=matched_journal,
                comparisons=comparisons, requiredEqualFields=sum(c['fields'] for c in comparisons.values() if c['required']),
                rawAck={p.name: verify_ack_file(p) for p in Path(path).parent.glob('*.log')})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path); parser.add_argument('--decoded', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); decoded = json.loads(args.decoded.read_text())
    cases, failures = [], []
    for path in sorted(args.root.glob('platform-*/cases/P*/final.json')):
        receipt = path.parents[2] / 'receipt.json'
        if not receipt.exists() or json.loads(receipt.read_text())['exit_code'] != 0:
            continue
        try:
            cases.append(verify_platform(path, decoded) | dict(path=str(path)))
        except (ValueError, KeyError, TypeError) as error:
            failures.append(dict(path=str(path), error=str(error)))
    result = dict(scope='saved platform artifacts only', cases=cases, failures=failures,
                  independentDatabaseRerun=False, allEightPlatformCases={c['case'][:3] for c in cases} == {'P0'+str(n) for n in range(1,9)})
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(cases=len(cases), failures=len(failures), allEightPlatformCases=result['allEightPlatformCases'])))
    raise SystemExit(0 if not failures and result['allEightPlatformCases'] else 1)


if __name__ == '__main__':
    main()
