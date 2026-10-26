import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import run as runner


class RunnerIsolationTest(unittest.TestCase):
    def test_same_slot_lock_rejects_concurrent_runner(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / 'slot.lock'
            with runner.runner_lock(path):
                with self.assertRaises(BlockingIOError):
                    with runner.runner_lock(path):
                        self.fail('second runner acquired the same slot')
            with runner.runner_lock(path):
                pass

    def test_different_slots_can_hold_locks(self):
        with TemporaryDirectory() as directory:
            with runner.runner_lock(Path(directory) / 'a.lock'):
                with runner.runner_lock(Path(directory) / 'b.lock'):
                    pass

    def test_sessions_and_transactions_each_prevent_idle(self):
        for role in ['source', 'state']:
            for kind in ['PROCESSLIST', 'INNODB_TRX']:
                pool = Mock()
                pool.sql.side_effect = lambda slot, current, sql, token: 'residual' if current == role and kind in sql else ''
                result = runner.observe_resources(pool, 'a', 'private-test-token')
                self.assertFalse(result['idle'])
                self.assertEqual(pool.sql.call_count, 4)

    def test_observation_failure_is_not_empty_resources(self):
        pool = Mock()
        pool.sql.side_effect = RuntimeError('observer unavailable')
        with self.assertRaisesRegex(RuntimeError, 'observer unavailable'):
            runner.observe_resources(pool, 'a', 'private-test-token')

    def test_nonidle_slot_never_starts_jvm_or_releases_lease(self):
        with TemporaryDirectory() as directory:
            pool = Mock()
            pool.slots = {'a': {'source': {'uuid': 's'}, 'state': {'uuid': 't'}}}
            pool.environment.return_value = {'CDC_WINDOW_MYSQL_PASSWORD': 'test-only-secret'}
            pool.verify_slot.return_value = {'admitted': True}
            pool.sql.return_value = 'residual-session'
            args = SimpleNamespace(candidate=Path('unused'), legacy_candidate=None,
                                   output=Path(directory) / 'attempt', java=Path('java'),
                                   suite=['reader'], case=None)
            candidate = dict(classpath=[], source_sha256='source', production_sha256='production')
            with patch.object(runner, 'verify_candidate', return_value=candidate), \
                    patch.object(runner.subprocess, 'Popen') as start:
                with self.assertRaisesRegex(RuntimeError, 'no JVM started'):
                    runner.run_locked(args, pool, dict(slot='a', token='private-test-token', owner='test'))
                start.assert_not_called()
            pool.release.assert_not_called()
            receipt = json.loads((args.output / 'receipt.json').read_text())
            self.assertEqual(receipt['refusal'], 'SLOT_NOT_IDLE')
            self.assertFalse(receipt['after_idle'])


if __name__ == '__main__':
    unittest.main()
