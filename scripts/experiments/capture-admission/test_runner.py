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

    def test_purge_requires_exact_identity_and_idle_before_archive(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            request = root/'source-purge-request.json'
            request.write_text(json.dumps(dict(uuid='s', port=14365, captureId='capture_test')))
            pool=Mock(); pool.slots={'a':{'source':{'uuid':'s','port':14365}}}
            pool.verify_slot.return_value={'admitted':True}
            pool.sql.return_value='residual'
            with self.assertRaisesRegex(RuntimeError,'not idle'):
                runner.fulfill_source_purge(pool,'a','token',root,root/'private')
            pool.archive_and_purge.assert_not_called()
            pool.sql.return_value=''
            request.write_text(json.dumps(dict(uuid='wrong', port=14365, captureId='capture_test')))
            with self.assertRaisesRegex(RuntimeError,'identity'):
                runner.fulfill_source_purge(pool,'a','token',root,root/'private')
            pool.archive_and_purge.assert_not_called()

    def test_purge_success_receipt_contains_hashes_not_binlog_contents(self):
        with TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'source-purge-request.json').write_text(json.dumps(dict(uuid='s',port=14365,captureId='capture_test')))
            pool=Mock();pool.slots={'a':{'source':{'uuid':'s','port':14365}}}
            pool.verify_slot.return_value={'admitted':True};pool.sql.return_value=''
            def archive(slot, role, token, destination):
                destination.mkdir()
                return {'binlog.000001':'sha'}
            pool.archive_and_purge.side_effect=archive
            runner.fulfill_source_purge(pool,'a','token',root,root/'private')
            receipt=json.loads((root/'source-purge-response.json').read_text())
            self.assertEqual(receipt['archived'],{'binlog.000001':'sha'})
            self.assertTrue(receipt['idleBefore']['idle'])
            self.assertTrue(receipt['purged'])

    def test_purge_barrier_defers_without_mutation_until_physical_idle(self):
        samples=[]
        with patch.object(runner,'observe_resources',side_effect=[dict(idle=False),dict(idle=True)]), \
                patch.object(runner,'fulfill_source_purge') as fulfill:
            self.assertFalse(runner.try_source_purge(None,'b','token',Path('/output'),Path('/private'),samples))
            fulfill.assert_not_called()
            self.assertTrue(runner.try_source_purge(None,'b','token',Path('/output'),Path('/private'),samples))
            fulfill.assert_called_once()
        self.assertEqual(samples,[dict(idle=False),dict(idle=True)])

    def test_refused_cleanup_signal_preserves_observed_local_uncertainty(self):
        for output, expected in [('', True), ('123 123 S\n', False)]:
            with patch.object(runner,'terminate_group',side_effect=PermissionError('signal refused')), \
                    patch.object(runner.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=output)):
                result=runner.cleanup_group(123)
                self.assertEqual(result['confirmed_exited'],expected)
                self.assertIn('PermissionError',result['signal_error'])

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
