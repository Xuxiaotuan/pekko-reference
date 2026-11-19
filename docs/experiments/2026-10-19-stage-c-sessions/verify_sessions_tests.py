#!/usr/bin/env python3
"""Negative evidence checks, independent of production envelope and Sink implementations."""
import json
import tarfile
import unittest
import verify_sessions as sessions


class RejectBadEvidence(unittest.TestCase):
    def setUp(self):
        with tarfile.open(sessions.ROOT / 'evidence.tar.gz', 'r:gz') as archive:
            self.files = {m.name: archive.extractfile(m).read() for m in archive.getmembers() if m.isfile()}

    def alter(self, path, change):
        obj = json.loads(self.files[path])
        change(obj)
        self.files[path] = json.dumps(obj).encode()

    def test_rejects_premature_release(self):
        self.alter('sessions/cancel-T/cancel-pending.json', lambda x: x.update(status='Released'))
        with self.assertRaises(AssertionError):
            sessions.verify(self.files.__getitem__)

    def test_rejects_live_runner(self):
        self.alter('sessions/none/released.json', lambda x: x.update(liveRunners=1))
        with self.assertRaises(AssertionError):
            sessions.verify(self.files.__getitem__)

    def test_rejects_field_change_with_same_row_count(self):
        self.alter('sessions/cancel-T/final.json', lambda x: x['comparison']['target'][0].update(label='tampered'))
        with self.assertRaises(AssertionError):
            sessions.verify(self.files.__getitem__)


if __name__ == '__main__':
    unittest.main()
