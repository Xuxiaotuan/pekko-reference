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


def main(check_current_source=True):
    for line in (HERE / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        assert sha((HERE / name).read_bytes()) == digest, name
    report = json.loads((HERE / 'result.json').read_text())
    archive_hashes = json.loads((HERE / 'archive-manifest.json').read_text())
    with tarfile.open(HERE / 'evidence.tar.gz', 'r:gz') as archive:
        members = {m.name: m for m in archive.getmembers()}
        assert set(members) == set(archive_hashes)
        assert all(not Path(n).is_absolute() and '..' not in Path(n).parts and m.isfile() for n, m in members.items())

        def read(name):
            raw = archive.extractfile(members[name]).read()
            assert sha(raw) == archive_hashes[name], name
            return raw

        def load(name):
            return json.loads(read(name))

        for name in members:
            read(name)
        source = json.loads((HERE / 'source-manifest.json').read_text())
        for path, expected in source.items():
            assert sha(read('source/current/' + path)) == expected
            if check_current_source:
                assert sha((REPO / path).read_bytes()) == expected, 'current source differs: ' + path
        historical = json.loads((HERE / 'historical-artifact-check.json').read_text())
        assert historical['changed'] == []
        for path, expected in historical['sha256'].items():
            assert sha((REPO / path).read_bytes()) == expected, 'historical artifact changed: ' + path
        counts = {'converged': 0, 'safe_pause': 0}
        for scenario in report['snapshot']['scenarios']:
            prefix = 'experiments/snapshot/' + scenario['directory'] + '/'
            final = load(prefix + 'final.json')
            equal = rows_check(final['comparison'])
            assert final['D'].startswith('N/A')
            t = {r['batch_id']: r for r in final['T']}
            assert len(t) == len(final['T'])
            assert set(t) == {r['batch_id'] for r in final['digests']}
            # Read actual serialized Journal payloads, rather than trusting application summary text.
            plan_ids = set()
            for event in final['J']['journal']:
                body = cbor(base64.b64decode(event['payloadBase64']))
                update = body.get('update', {})
                kind = update.get('_type', '').split('.')[-1]
                if kind == 'InitializeSnapshotSplits':
                    plan_ids.add(update['plan']['id'])
                if kind == 'CommitSnapshotPage':
                    cp = update['checkpoint']
                    assert cp['batchId'] in t
                    assert t[cp['batchId']]['partition_id'] == cp['partitionId']
                    assert str(cp['batchSequence']) == t[cp['batchId']]['batch_sequence']
                    assert cp['cursor']['value'] == t[cp['batchId']]['cursor_value']
            assert len(plan_ids) == 1
            if scenario['classification'] == 'converged':
                assert equal and final['J']['status'] == 'completed'
                assert len(final['audit']) == len(final['comparison']['source'])
                assert all(r['op'] == 'I' for r in final['audit'])
            else:
                assert final['J']['status'] == 'snapshot_blocked' and final['J']['executionId']
            if scenario['scenario'] == 'T_J':
                cut = load(prefix + 'cut.json')
                target_zero = [r for r in cut['T'] if r['partition_id'].endswith(':0')]
                assert any(r['batch_sequence'] == '0' for r in target_zero)
                for event in cut['J']['journal']:
                    update = cbor(base64.b64decode(event['payloadBase64'])).get('update', {})
                    cp = update.get('checkpoint')
                    assert not cp or not cp['partitionId'].endswith(':0'), 'held split J advanced at T/J cut'
            counts[scenario['classification']] += 1
        assert counts == {'converged': 14, 'safe_pause': 6}
        assert len(report['cdcRegression']['scenarios']) == 27
        for scenario in report['cdcRegression']['scenarios']:
            final = load(scenario['archivePrefix'] + '/final.json')
            equal = rows_check(final['comparison'])
            if scenario['classification'] == 'safe_pause':
                assert final['J']['status'] == 'recovery_blocked'
            else:
                assert equal
            # Positive convergence evidence also requires the actual durable D to match J.
            if scenario['classification'] != 'safe_pause':
                checkpoints = [e['checkpoint'] for e in final['J']['journal'] if e.get('checkpoint')]
                assert checkpoints
                offset = checkpoints[-1]['cursor']['offset']
                assert offset in [json.loads(r['offset_val']) for r in final['D']]
    print(json.dumps({'archiveIntegrity': True, 'sourceFiles': len(source), 'historicalFilesUnchanged': historical['checked'],
        'snapshot': counts, 'cdcCases': 27, 'currentSourceChecked': check_current_source, 'scope': 'offline evidence validation; not a new live experiment'}, ensure_ascii=False))


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archived-source', action='store_true', help='check archived source hashes, without requiring the live worktree to match this earlier experiment')
    main(not parser.parse_args().archived_source)
