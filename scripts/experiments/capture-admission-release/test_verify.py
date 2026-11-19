import unittest
from verify import summarize, check_expected, ownership_summary


class EvidenceTests(unittest.TestCase):
    def test_ack_alone_is_not_durable_ready(self):
        self.assertFalse(summarize([{'kind': 'RAW_ACK_RETURNED'}], [])['durable_non_initial'])

    def test_initial_and_unknown_snapshot_are_rejected(self):
        for snapshot in ['INITIAL', 'true', 'last', 'unrecognized', 0]:
            events = [{'kind': 'D_VERIFIED', 'D': {'file': 'x', 'pos': 4, 'snapshot': snapshot}}]
            self.assertFalse(summarize(events, [])['durable_non_initial'])

    def test_completion_does_not_prove_server_release(self):
        result = summarize([{'kind': 'LOCAL_END', 'completionCompleted': True}],
                           [{'idle': False, 'observed_ns': 10}])
        self.assertFalse(result['server_release_confirmed'])

    def test_unknown_observation_is_not_idle(self):
        self.assertFalse(summarize([], [{'error': 'permission denied'}])['server_release_confirmed'])

    def test_independently_compares_every_field_and_key(self):
        result = summarize([{'kind': 'WITNESS', 'source': [{'id': '1', 'amount': '1.0000'}],
                             'target': [{'id': '1', 'amount': '1.0001'}, {'id': '2', 'amount': '0'}]}], [])
        self.assertFalse(result['witness_equal'])
        self.assertEqual(result['witness_different_fields'], 1)
        self.assertEqual(result['witness_extra_keys'], ['2'])

    def test_release_observation_must_be_after_local_end(self):
        events = [{'kind': 'LOCAL_END', 'epoch_ns': 20, 'completionCompleted': True, 'threads': []}]
        self.assertFalse(summarize(events, [{'idle': True, 'observed_ns': 10}])['server_release_confirmed'])

    def test_old_checkpoint_does_not_make_current_attempt_ready(self):
        result = summarize([{'kind': 'D_VERIFIED', 'attempt': 'new', 'D': {'file': 'x', 'pos': 4}}], [])
        self.assertTrue(result['durable_non_initial'])
        self.assertFalse(result['admission_candidate'])

    def test_close_unknown_is_not_reusable_even_if_server_is_idle(self):
        events = [{'kind': 'CLOSE_WAIT_UNKNOWN'}, {'kind': 'LOCAL_END', 'epoch_ns': 20,
                   'completionCompleted': True, 'threads': []}]
        self.assertFalse(summarize(events, [{'idle': True, 'observed_ns': 21}])['reusable_candidate'])

    def test_server_restart_invalidates_release_identity(self):
        def sample(ns, uptime):
            return {'observed_ns': ns, 'idle': True, 'instances': {
                role: {'identity': 'uuid\t14365\t/data\t9.5.0\nUptime\t' + str(uptime) + '\n'}
                for role in ('source', 'state')}}
        events = [{'kind': 'LOCAL_END', 'epoch_ns': 20, 'completionCompleted': True, 'threads': []}]
        self.assertFalse(summarize(events, [sample(10, 100), sample(21, 1)])['server_release_confirmed'])

    def test_witness_callback_cannot_retroactively_prove_quiet_readiness(self):
        events = [{'kind': 'D_VERIFIED', 'attempt': 'new', 'D': {'file': 'x', 'pos': 4}},
                  {'kind': 'QUIET_END', 'attempt': 'new'},
                  {'kind': 'ORIGINAL_RECORD', 'attempt': 'new', 'offset': {'file': 'x', 'pos': 5}}]
        self.assertFalse(summarize(events, [])['current_non_initial_callback'])

    def test_unrelated_callback_and_checkpoint_do_not_prove_readiness(self):
        events = [{'kind': 'ORIGINAL_RECORD', 'attempt': 'new', 'offset': {'file': 'x', 'pos': 5}},
                  {'kind': 'D_VERIFIED', 'attempt': 'new', 'D': {'file': 'x', 'pos': 4}},
                  {'kind': 'QUIET_OBSERVATION', 'attempt': 'new', 'metrics': [{'attributes': {'SnapshotCompleted': True}}]}]
        self.assertFalse(summarize(events, [])['admission_candidate'])

    def test_matching_current_callback_ack_and_d_form_candidate(self):
        events = [{'kind': 'BEFORE_START', 'attempt': 'new', 'captureId': 'c'},
                  {'kind': 'ORIGINAL_RECORD', 'attempt': 'new', 'partition': {'server': 'c'}, 'offset': {'file': 'x', 'pos': 4}},
                  {'kind': 'RAW_CALLBACK', 'attempt': 'new', 'id': 1, 'offset': {'file': 'x', 'pos': 4}},
                  {'kind': 'RAW_ACK_RETURNED', 'attempt': 'new', 'id': 1},
                  {'kind': 'D_VERIFIED', 'attempt': 'new', 'offset_key': ['c', {'server': 'c'}], 'D': {'file': 'x', 'pos': 4}},
                  {'kind': 'QUIET_OBSERVATION', 'attempt': 'new', 'metrics': [{'attributes': {'SnapshotCompleted': True}}]}]
        self.assertTrue(summarize(events, [])['admission_candidate'])

    def test_failed_completion_is_not_successful_local_release(self):
        events = [{'kind': 'CLOSE_RETURNED'}, {'kind': 'LOCAL_END', 'completionCompleted': True,
                   'completionOutcome': 'failed', 'epoch_ns': 20, 'threads': []}]
        self.assertFalse(summarize(events, [])['local_release_confirmed'])

    def test_matching_offset_in_another_partition_is_not_admission(self):
        events = [{'kind': 'BEFORE_START', 'attempt': 'new', 'captureId': 'c'},
                  {'kind': 'ORIGINAL_RECORD', 'attempt': 'new', 'partition': {'server': 'other'}, 'offset': {'file': 'x', 'pos': 4}},
                  {'kind': 'RAW_CALLBACK', 'attempt': 'new', 'id': 1, 'offset': {'file': 'x', 'pos': 4}},
                  {'kind': 'RAW_ACK_RETURNED', 'attempt': 'new', 'id': 1},
                  {'kind': 'D_VERIFIED', 'attempt': 'new', 'offset_key': ['c', {'server': 'other'}], 'D': {'file': 'x', 'pos': 4}},
                  {'kind': 'QUIET_OBSERVATION', 'attempt': 'new', 'metrics': [{'attributes': {'SnapshotCompleted': True}}]}]
        result = summarize(events, [])
        self.assertTrue(result['current_acknowledged_d'])
        self.assertFalse(result['unique_legal_partition_observed'])
        self.assertFalse(result['admission_candidate'])

    def test_sdk_completion_failure_fails_expectation(self):
        events = [{'kind': 'ENGINE_COMPLETION', 'success': False, 'errorKind': 'IOException'}]
        self.assertIn('engine_failed', check_expected({'reusable_candidate': True}, events))

    def test_positive_missing_witness_must_stop_runner(self):
        evidence = {'admission_candidate': True, 'reusable_candidate': True, 'witness_equal': None}
        self.assertIn('missing_or_unequal_witness', check_expected(evidence, [], positive=True, witness=True))

    def test_failed_capture_cannot_be_counted_as_success(self):
        evidence = {'admission_candidate': True, 'reusable_candidate': True, 'witness_equal': True}
        self.assertIn('capture_failed', check_expected(evidence, [{'kind': 'QUIET_END', 'captureFailure': 'failed'}]))

    def test_socket_match_from_other_process_is_not_ownership(self):
        observations = [{'worker_sockets': {'pid': 99, 'parent_owned_and_alive': True, 'exit_code': 0},
                         'instances': {'source': {'socket_mappings': [
                             {'connection_id': '3', 'command': 'Binlog Dump GTID', 'worker_socket_match': True}]}}}]
        self.assertFalse(ownership_summary([{'kind': 'BEFORE_START', 'pid': 42}], observations)['dump_mapping_complete'])

    def test_owner_mapping_requires_state_socket_not_same_source_id(self):
        observations = [{'worker_sockets': {'pid': 42, 'parent_owned_and_alive': True, 'exit_code': 0},
                         'instances': {'source': {'socket_mappings': [
                             {'connection_id': '3', 'command': 'Binlog Dump GTID', 'worker_socket_match': True}]},
                                       'state': {'owner_lock_connection': '3\n', 'socket_mappings': []}}}]
        result = ownership_summary([{'kind': 'BEFORE_START', 'pid': 42}], observations)
        self.assertTrue(result['dump_mapping_complete'])
        self.assertFalse(result['owner_mapping_complete'])


if __name__ == '__main__':
    unittest.main()
