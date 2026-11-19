#!/usr/bin/env python3
"""Negative checks: change captured evidence AND its checksum, so the semantic verifier must reject it."""
import copy
import hashlib
import json
import tarfile
import unittest
import verify as evidence

class EvidenceNegativeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tarfile.open(evidence.ROOT/'evidence.tar.gz','r:gz') as a:
            cls.files={m.name:a.extractfile(m).read() for m in a.getmembers() if m.isfile()}
    def reject(self,name,change):
        files=self.files.copy()
        value=json.loads(files[name]);change(value)
        files[name]=json.dumps(value,ensure_ascii=False).encode()
        manifest=json.loads(files['manifest.json']);manifest['files'][name]=hashlib.sha256(files[name]).hexdigest()
        files['manifest.json']=json.dumps(manifest).encode()
        with self.assertRaises(AssertionError):evidence.verify(files.__getitem__)
    def test_missing_pk_create_member_is_rejected(self):
        def change(x):
            for row in x['T']:
                proof=json.loads(row['evidence'])
                if len(proof['group']['members'])==2:
                    proof['group']['members'].pop();row['evidence']=json.dumps(proof,ensure_ascii=False);return
            self.fail('PK group absent from fixture')
        self.reject('K1/final.json',change)
    def test_equal_flag_cannot_hide_changed_field(self):
        self.reject('J1/final.json',lambda x:x['comparison']['target'][0].update(note='tampered'))
    def test_same_count_audit_corruption_is_rejected(self):
        self.reject('J2/final.json',lambda x:x['audit'][-1].update(new_label='tampered'))
    def test_c2_cannot_be_relabelled_as_success(self):
        self.reject('C2/outcome.json',lambda x:x.update(automaticRecovery='PASS',fullFieldConvergence=True))
    def test_raw_ack_gap_is_rejected_even_with_new_checksum(self):
        files=self.files.copy();name='K1/original.log';lines=files[name].decode().splitlines()
        for i,line in enumerate(lines):
            if line.startswith('WINDOW '):
                e=json.loads(line[7:])
                if e['kind']=='RAW_ACK_RETURNED':
                    e['callback']=42;lines[i]='WINDOW '+json.dumps(e);break
        files[name]='\n'.join(lines).encode()
        manifest=json.loads(files['manifest.json']);manifest['files'][name]=hashlib.sha256(files[name]).hexdigest()
        files['manifest.json']=json.dumps(manifest).encode()
        with self.assertRaises(AssertionError):evidence.verify(files.__getitem__)

if __name__=='__main__':unittest.main()
