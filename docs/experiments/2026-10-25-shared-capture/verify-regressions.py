#!/usr/bin/env python3
"""Recompute final six-field comparisons of the saved legacy-path regression, without I/O to MySQL."""
import argparse, json
from pathlib import Path

FIELDS = {'id', 'label', 'amount', 'note', 'updated_at', 'revision'}

def main():
    p = argparse.ArgumentParser()
    p.add_argument('runner', type=Path)
    p.add_argument('--repo', type=Path, default=Path.cwd())
    p.add_argument('--output', type=Path)
    args = p.parse_args()
    run = json.loads((args.runner / 'result.json').read_text())
    assert run['exitCode'] == 0 and len(run['caseDirectories']) == 27
    results = []
    for name in run['caseDirectories']:
        directory = args.repo / name
        final = json.loads((directory / 'final.json').read_text())
        comparison = final['comparison']
        def index(rows):
            assert all(set(r) == FIELDS for r in rows)
            result = {r['id']: r for r in rows}
            assert len(result) == len(rows)
            return result
        source, target = index(comparison['source']), index(comparison['target'])
        missing, extra = sorted(source.keys() - target.keys()), sorted(target.keys() - source.keys())
        changed = [{'id': k, 'field': f, 'source': source[k][f], 'target': target[k][f]}
                   for k in sorted(source.keys() & target.keys()) for f in sorted(FIELDS) if source[k][f] != target[k][f]]
        equal = not missing and not extra and not changed
        assert comparison['missingKeys'] == missing and comparison['extraKeys'] == extra and comparison['fieldDifferences'] == changed
        assert comparison['equal'] == equal
        outcome = json.loads((directory / 'outcome.json').read_text()) if (directory / 'outcome.json').exists() else {}
        if 'fullFieldConvergence' in outcome:
            assert outcome['fullFieldConvergence'] == equal
        if 'allFieldsEqual' in outcome:
            assert outcome['allFieldsEqual'] == equal and outcome['physicalRelease']
        outcome = {k: v for k, v in outcome.items() if k in {'automaticRecovery', 'fullFieldConvergence', 'fault', 'allFieldsEqual', 'physicalRelease', 'scenario', 'injectionEstablished'}}
        results.append({'case': directory.name, 'equal': equal, 'sourceRows': len(source), 'targetRows': len(target),
                        'missing': len(missing), 'extra': len(extra), 'fieldDifferences': len(changed), 'outcome': outcome})
    report = {'status': 'passed', 'cases': 27, 'sourceManifestSha256': run['sourceManifestSha256'], 'results': results,
              'boundary': 'Saved legacy mysql.cdc evidence only. A safety pause may retain field differences; this is not additional shared mysql.cdc.log COMMIT-uncertainty coverage.'}
    if args.output:
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Verified 27 saved legacy regression comparisons; convergence and safety pauses remain separate.')
if __name__ == '__main__':
    main()
