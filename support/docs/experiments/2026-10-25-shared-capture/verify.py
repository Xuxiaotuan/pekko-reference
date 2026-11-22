#!/usr/bin/env python3
"""Read-only verification of exported phase-1 evidence (Python standard library)."""
import argparse, hashlib, json
from pathlib import Path

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

def normalized(offset):
    value = json.loads(offset) if isinstance(offset, str) else offset
    value = value.get('offset', value)
    return {k: v for k, v in value.items() if k in {'file', 'pos', 'event', 'row', 'gtids', 'snapshot'}}

def validate_log(state, broken=False):
    frames = {int(r['seq']): r for r in state['L']['log']}
    for sequence, r in frames.items():
        assert digest(canonical([r['kind'], r['table_name'], r['subscription_id'], r['schema_id'], r['payload'], r['point_json'] or ''])) == r['digest'], ('frame', sequence)
    for row in state['L']['schemas']:
        assert digest(row['definition']) == row['schema_id']
    if broken == 'R16-log':
        refs = [ref['sequence'] for r in frames.values() if r['kind'] == 'ACK' for ref in json.loads(r['payload'])['references']]
        assert any(n not in frames for n in refs), 'negative fixture did not remove covered L'
        return len(frames)
    subscriptions = {s['request_id']: s for s in state['L']['subscriptions']}
    orphaned = [r for r in frames.values() if r['kind'] == 'BARRIER' and r['subscription_id'] not in subscriptions]
    if broken == 'R16-registry':
        assert orphaned, 'negative fixture did not remove registry binding'
        return len(frames)
    assert not orphaned, 'barrier has no subscription registry'
    for sequence, r in frames.items():
        if r['kind'] != 'ACK':
            continue
        proof = json.loads(r['payload'])
        for ref in proof['references'] + ([proof['parent']] if proof['parent'] else []):
            n = ref['sequence']
            assert n < sequence and frames[n]['digest'] == ref['digest'], ('proof', sequence, n)
    for d in state.get('D', []):
        offset = normalized(d['offset_val'])
        assert any(r['kind'] == 'ACK' and json.loads(r['payload'])['offset'] == offset for r in frames.values()), ('D-without-L', offset)
    for s in state['L']['subscriptions']:
        if s['b_point']:
            frame = frames[int(s['b_seq'])]
            assert frame['kind'] == 'BARRIER' and frame['schema_id'] == s['schema_id']
            proof = json.loads(frame['payload'])
            assert proof['requestId'] == s['request_id'] and proof['generation'] == s['generation'] and not proof['stop']
            assert proof['point'] == json.loads(s['b_point'])
        if s['k_point']:
            assert any(r['source_key'] == digest('K:' + s['request_id']) and json.loads(r['payload'])['point'] == json.loads(s['k_point']) for r in frames.values())
    for actor in state.get('actors', {}).values():
        for cp in actor['checkpoints']:
            cursor = cp['cursor']
            r = frames[cursor['sequence']]
            assert r['kind'] == 'GROUP' and r['subscription_id'] == cursor['subscriptionId']
            assert any(j.get('checkpoint') == cp for j in state['J']), 'J cursor lacks serialized Journal evidence'
    return len(frames)

def validate_target(value):
    state = value['worker']
    ledger = {r['group_id']: json.loads(r['evidence']) for r in value['T']}
    assert len(ledger) == len(value['T']), 'duplicate target evidence identity'
    subscriptions = {s['request_id']: s for s in state['L']['subscriptions']}
    lineage = state['L']['reader'][0]['lineage']
    checked = 0
    for sid, actor in state['actors'].items():
        for checkpoint in actor['checkpoints']:
            end = checkpoint['cursor']['sequence']
            sub = subscriptions[sid]
            protocol = {'version': 1, 'replicationId': sid, 'generation': sub['generation'], 'sourceLineage': lineage}
            for frame in state['L']['log']:
                if frame['kind'] != 'GROUP' or frame['subscription_id'] != sid or int(frame['seq']) > end:
                    continue
                group = json.loads(frame['payload'])
                source = group['members'][0]['envelope']['source']
                gid = digest(canonical([protocol, source['database'], source['table'], source['file'], source['position'], source['row']]))
                group['groupId'] = gid
                for member in group['members']:
                    member['eventId'] = digest(canonical([gid, member['role']]))
                evidence = ledger[gid]
                assert evidence['group'] == group and evidence['identity']['schemaFingerprint'] == sub['schema_id'], ('T/J coverage', sid, end)
                checked += 1
    return checked

def compare(value):
    fields = {'id', 'label', 'amount', 'note', 'updated_at', 'revision'}
    assert all(set(row) == fields for row in value['source'] + value['target'])
    source = {r['id']: r for r in value['source']}
    target = {r['id']: r for r in value['target']}
    assert len(source) == len(value['source']) and len(target) == len(value['target']), 'duplicate exported primary key'
    missing, extra = sorted(source.keys() - target.keys()), sorted(target.keys() - source.keys())
    differences = [(key, field) for key in source.keys() & target.keys() for field, v in source[key].items() if target[key].get(field) != v]
    assert value['equal'] == (not missing and not extra and not differences)
    assert sorted(value['missing']) == missing and sorted(value['extra']) == extra
    assert len(value['differences']) == len(differences)
    return {'equal': value['equal'], 'rows': len(source), 'fields': sum(map(len, source.values())), 'missing': missing, 'extra': extra, 'fieldDifferences': len(differences)}

def check(directory):
    result = json.loads((directory / 'result.json').read_text())
    assert result['status'] == 'passed'
    final = json.loads((directory / 'final.json').read_text())
    counts = []
    for path in directory.glob('*.json'):
        value = json.loads(path.read_text())
        state = value.get('worker')
        if state and 'L' in state:
            counts.append(validate_log(state))
            if 'T' in value:
                validate_target(value)
        if path.name == 'final.json' and 'L' in value:
            counts.append(validate_log(value, broken=result['variant'] if result['variant'] in {'R16-log', 'R16-registry'} else False))
    comparisons = {k: compare(v) for k, v in final.get('comparisons', {}).items()}
    if 'comparison' in final:
        comparisons['a'] = compare(final['comparison'])
    terminal = json.loads((directory / 'ddl-pause.json').read_text()) if (directory / 'ddl-pause.json').exists() else final
    ddl_comparison = None
    if terminal is not final:
        assert terminal['worker']['captureFailure'], 'DDL did not pause'
        observed = terminal['comparison']
        ddl_comparison = observed if observed.get('sourceTableMissing') else compare(observed)
    expected_divergence = result['variant'] in {'R14-capacity', 'R15'}
    if not expected_divergence:
        assert all(c['equal'] for c in comparisons.values()), result['variant']
    cleanup = json.loads((directory / 'cleanup.json').read_text())
    assert cleanup['workersExited'] and cleanup['sourceConnections'] == [] and cleanup['stateConnections'] == []
    ack_counts = {}
    for path in directory.glob('*.log'):
        events = [json.loads(line[7:]) for line in path.read_text().splitlines() if line.startswith('WINDOW ')]
        begin = [e['id'] for e in events if e['kind'] == 'RAW_ACK_BEGIN']
        returned = [e['id'] for e in events if e['kind'] == 'RAW_ACK_RETURNED']
        assert begin == list(range(len(begin))) and returned == list(range(len(returned))), (path, 'ACK prefix')
        assert len(returned) <= len(begin) <= len(returned) + 1
        ack_counts[path.name] = {'begun': len(begin), 'returned': len(returned)}
    return {'variant': result['variant'], 'frameSnapshots': len(counts), 'comparisons': comparisons, 'ackPrefixes': ack_counts, 'cleanup': 'confirmed', 'expectedDivergence': expected_divergence, 'safePause': bool(terminal.get('safePause') or terminal.get('worker', {}).get('captureFailure') or result['variant'] == 'R13-reject'), 'terminalObservation': 'ddl-pause.json' if terminal is not final else 'final.json', 'postDdlComparison': ddl_comparison}

def main():
    p = argparse.ArgumentParser()
    p.add_argument('runner', type=Path, help='extracted runner directory containing result.json')
    p.add_argument('--repo', type=Path, default=Path.cwd(), help='root containing target/shared-capture-matrix')
    p.add_argument('--output', type=Path)
    args = p.parse_args()
    result = json.loads((args.runner / 'result.json').read_text())
    assert result['exitCode'] == 0
    cases = [check(args.repo / name) for name in result['caseDirectories']]
    assert len(cases) == 31 and len({c['variant'] for c in cases}) == 31
    report = {'status': 'passed', 'cases': len(cases), 'sourceManifestSha256': result['sourceManifestSha256'], 'results': cases,
              'boundary': 'Saved-evidence recomputation; no database rerun, no LOW/HIGH initialization or 700k-table capacity proof.'}
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    print('Verified 31 cases, frame hashes, proof references, actual D coverage, T coverage of serialized J cursors, raw ACK prefixes, full fields and cleanup.')
if __name__ == '__main__':
    main()
