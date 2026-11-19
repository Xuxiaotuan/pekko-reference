import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import verify


class ArtifactVerificationTest(unittest.TestCase):
    def frame(self, sequence=1, kind='SCHEMA', payload='{}'):
        f = dict(seq=str(sequence), kind=kind, table_name='a', subscription_id='', schema_id='', payload=payload, point_json=None)
        f['digest'] = verify.digest(verify.canonical([f[k] for k in ['kind','table_name','subscription_id','schema_id','payload']] + ['']))
        return f

    def test_checks_every_field_and_key_set(self):
        result = verify.reconcile([dict(id='1', nullable=None, text='')], [dict(id='1', nullable='', text='')])
        self.assertFalse(result['equal']); self.assertEqual(result['differences'], [('"1"', 'nullable')])
        self.assertFalse(verify.reconcile([dict(id='1')], [dict(id='2')])['equal'])

    def test_duplicate_keys_cannot_be_hidden_by_dict_conversion(self):
        with self.assertRaisesRegex(ValueError, 'duplicate primary key'):
            verify.reconcile([dict(id='1'), dict(id='1')], [dict(id='1')])

    def test_raw_payload_corruption_is_detected(self):
        f = self.frame(); f['payload'] = '{"changed":true}'
        with self.assertRaisesRegex(ValueError, 'L digest'):
            verify.verify_log(dict(log=[f]))

    def test_a_valid_hash_does_not_authorize_a_future_ack_reference(self):
        f = self.frame(1, 'ACK', verify.canonical(dict(parent=None, references=[dict(sequence=2, digest='future')], offset={})))
        with self.assertRaisesRegex(ValueError, 'ACK references'):
            verify.verify_log(dict(log=[f]))

    def test_ack_return_cannot_skip_an_original_callback(self):
        with TemporaryDirectory() as d:
            p = Path(d)/'reader.log'
            events = [dict(kind='RAW_ACK_BEGIN', id=0), dict(kind='RAW_ACK_BEGIN', id=1), dict(kind='RAW_ACK_RETURNED', id=1)]
            p.write_text('\n'.join('WINDOW '+json.dumps(e) for e in events))
            with self.assertRaisesRegex(ValueError, 'outside original prefix'):
                verify.verify_ack_file(p)

    def test_ack_begin_cannot_skip_a_callback_even_if_returns_match(self):
        with TemporaryDirectory() as d:
            p=Path(d)/'reader.log'
            events=[dict(kind='RAW_ACK_BEGIN',id=0),dict(kind='RAW_ACK_RETURNED',id=0),
                    dict(kind='RAW_ACK_BEGIN',id=2),dict(kind='RAW_ACK_RETURNED',id=2)]
            p.write_text('\n'.join('WINDOW '+json.dumps(e) for e in events))
            with self.assertRaisesRegex(ValueError,'monotone original-callback prefix'):
                verify.verify_ack_file(p)

    def test_optional_none_encoding_is_equivalent_but_required_fields_remain_exact(self):
        self.assertEqual(verify.canonical_progress(dict(revision=1)), verify.canonical_progress(dict(revision=1, window=None)))
        self.assertNotEqual(verify.canonical_progress(dict(revision=1)), verify.canonical_progress(dict(revision=2, window=None)))
        self.assertNotEqual(verify.canonical_progress(dict(revision=1)), verify.canonical_progress(dict(revision=1, unexpected=None)))

    def test_control_only_case_accepts_absent_baseline_receipts(self):
        with TemporaryDirectory() as d:
            p = Path(d)/'final.json'
            value = dict(case='P08', resultKind='CONTROL_PLANE_ONLY', worker=dict(L=dict(log=[]), tasks=[], baselineT=None, spools={}), comparisons={})
            p.write_text(json.dumps(value))
            self.assertEqual(verify.verify_platform(p, [])['matchedJournalTasks'], 0)

    def test_streaming_requires_actual_decoded_journal_not_a_projection_label(self):
        with TemporaryDirectory() as d:
            p = Path(d)/'final.json'
            task = dict(task_id='task', capture_id='capture', journal_revision='1', phase='STREAMING')
            value = dict(case='P01', resultKind='CONVERGED', worker=dict(L=dict(log=[]), tasks=[task], baselineT=[], spools={}), comparisons={})
            p.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, 'no decoded persisted Journal'):
                verify.verify_platform(p, [])


if __name__ == '__main__':
    unittest.main()
