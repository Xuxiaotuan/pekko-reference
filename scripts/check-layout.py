#!/usr/bin/env python3
"""Check the canonical repository layout; no database or filesystem mutation."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
required=['server/src/main/scala','console/src','deploy/local','deploy/kubernetes','docs/architecture','docs/guides','docs/articles','docs/development','docs/archive','docs/experiments']
for name in required:
    assert (root/name).is_dir(),f'missing canonical directory: {name}'
assert 'Project(id = "pekko-server", base = file("server"))' in (root/'build.sbt').read_text()
for path in (root/'server/src/main/scala').rglob('*.scala'):
    match=re.search(r'^package\s+([\w.]+)',path.read_text(),re.M)
    if match:
        assert path.parent.relative_to(root/'server/src/main/scala').as_posix()==match[1].replace('.','/'),f'package/path mismatch: {path}'
for old,new in {'pekko-server':'server','xxt-ui':'console','deploy/k8s':'kubernetes'}.items():
    assert (root/old).is_symlink() and (root/old).readlink().as_posix()==new,f'broken command alias: {old}'
for name in ['docs/README.md','docs/architecture/README.md','docs/guides/README.md','docs/articles/README.md','docs/development/README.md','docs/archive/README.md','deploy/local/README.md','scripts/README.md']:
    assert (root/name).is_file(),f'missing navigation index: {name}'
print('Canonical roots, package paths, preserved SBT project ID and navigation indexes: PASS')
