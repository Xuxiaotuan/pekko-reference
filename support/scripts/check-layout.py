#!/usr/bin/env python3
"""Check the canonical repository layout; no database or filesystem mutation."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[2]
required=['common/src/main/scala','support/deploy/local','support/deploy/kubernetes/cdc-runtime','support/docs/architecture','support/docs/guides','support/docs/articles','support/docs/development','support/docs/archive','support/docs/experiments']
for name in required:
    assert (root/name).is_dir(),f'missing canonical directory: {name}'
assert 'Project(id = "pekko-server", base = file("common"))' in (root/'build.sbt').read_text()
for path in (root/'common/src/main/scala').rglob('*.scala'):
    match=re.search(r'^package\s+([\w.]+)',path.read_text(),re.M)
    if match:
        package_path=match[1].replace('.','/')
        if path.name=='package.scala':
            obj=re.search(r'^package\s+object\s+(\w+)',path.read_text(),re.M)
            if obj:
                package_path += '/' + obj[1]
        # Only named compatibility payloads/entry aliases retain historical namespaces.
        compatibility={
            'LegacyTableSchedulingModels.scala':'cn/xuyinyin/magic/workflow/scheduling',
            'LegacySupervisorEntrypoints.scala':'cn/xuyinyin/magic/workflow/scheduling',
        }
        if path.parent.name=='compat' and path.name in compatibility:
            assert package_path==compatibility[path.name],f'compatibility namespace changed: {path}'
            continue
        assert path.parent.relative_to(root/'common/src/main/scala').as_posix()==package_path,f'package/path mismatch: {path}'
for name in ['support/docs/README.md','support/docs/architecture/README.md','support/docs/guides/README.md','support/docs/articles/README.md','support/docs/development/README.md','support/docs/archive/README.md','support/deploy/local/README.md','support/scripts/README.md']:
    assert (root/name).is_file(),f'missing navigation index: {name}'
print('Canonical roots, package paths, preserved SBT project ID and navigation indexes: PASS')
