"""Behavior tests: catch cross-slot paths, lease theft, artifact drift and evidence mixing."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parent

class PoolTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((HERE / 'pool.py').exists(), 'lab pool implementation is missing')
        spec = importlib.util.spec_from_file_location('pool', HERE / 'pool.py')
        self.pool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.pool)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.config = self.pool.example_config(self.root / 'runtime')
        self.path = self.root / 'config.json'
        self.path.write_text(json.dumps(self.config))
        self.p = self.pool.Pool(self.path)
        # Only the mysql transport is simulated; leases and runner workers are real processes.
        self.identities=self.root/'mysql-identities.json'
        self.identities.write_text(json.dumps({str(cfg['port']):dict(cfg) for slot in self.config['slots'] for cfg in [slot['source'],slot['state']]}))
        self.mysql_trace=self.root/'mysql.trace'
        fake=self.root/'mysql-bin';fake.mkdir();mysql=fake/'mysql'
        mysql.write_text('#!/usr/bin/env python3\nimport json,pathlib,sys\nport=next(a.split("=",1)[1] for a in sys.argv if a.startswith("--port="))\ncfg=json.loads(pathlib.Path('+repr(str(self.identities))+').read_text())[port]\nwith pathlib.Path('+repr(str(self.mysql_trace))+').open("a") as f:f.write(port+"\\n")\nprint(cfg["uuid"]+"\\t"+str(cfg["port"])+"\\t"+cfg["datadir"])\n')
        mysql.chmod(0o755)
        old=os.environ['PATH'];os.environ['PATH']=str(fake)+os.pathsep+old
        self.addCleanup(os.environ.__setitem__,'PATH',old)

    def test_rejects_shared_datadir_uuid_and_protected_port(self):
        for field, value in [('datadir', self.config['slots'][0]['source']['datadir']),
                             ('uuid', self.config['slots'][0]['source']['uuid']), ('port', 13365), ('port', 13366), ('port', 3306), ('port', 14365)]:
            config = json.loads(json.dumps(self.config))
            config['slots'][1]['state'][field] = value
            self.path.write_text(json.dumps(config))
            with self.assertRaises(ValueError): self.pool.Pool(self.path)

    def test_atomic_lease_excludes_second_process_and_wrong_release_token(self):
        token = self.p.acquire('a', 'first')
        code = 'import sys;sys.path.insert(0,sys.argv[1]);import pool;pool.Pool(sys.argv[2]).acquire("a","second")'
        result = subprocess.run([sys.executable, '-c', code, str(HERE), str(self.path)], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        with self.assertRaises(ValueError): self.p.release('a', 'wrong')
        with self.assertRaises(ValueError): self.p.release('a', token, owner='other')
        self.p.release('a', token)
        token2 = self.p.acquire('a', 'second')
        self.assertNotEqual(token, token2)
        self.p.release('a', token2)

    def test_credentials_require_private_file_and_are_not_in_public_environment(self):
        credentials = Path(self.config['slots'][0]['credentials_ref'])
        credentials.parent.mkdir(parents=True)
        credentials.write_text('{"user":"lab","password":"sensitive-test-value"}')
        credentials.chmod(0o644)
        with self.assertRaises(ValueError): self.p.environment('a')
        credentials.chmod(0o600)
        env = self.p.environment('a')
        self.assertEqual(env['CDC_WINDOW_MYSQL_PORT'], '14365')
        self.assertEqual(env['CAPTURE_STATE_PORT'], '14366')
        self.assertNotIn('sensitive-test-value', json.dumps(self.p.public_slot('a')))

    def test_freeze_copies_classpath_and_rejects_tampering(self):
        repo = self.root / 'repo'; (repo / 'server/src').mkdir(parents=True)
        (repo / 'server/src/X.scala').write_text('original source')
        (repo / 'build.sbt').write_text('build')
        classes = repo / 'classes'; classes.mkdir(); (classes / 'X.class').write_bytes(b'original')
        dep = self.root / 'external.jar'; dep.write_bytes(b'original jar')
        candidate = self.pool.freeze(repo, self.root / 'candidate', [classes, dep])
        (classes / 'X.class').write_bytes(b'changed'); dep.write_bytes(b'changed')
        frozen = self.pool.verify_candidate(candidate)
        self.assertEqual(len(frozen['classpath']), 2)
        self.assertEqual(Path(frozen['classpath'][1]).read_bytes(), b'original jar')
        Path(frozen['classpath'][1]).chmod(0o644)
        Path(frozen['classpath'][1]).write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.pool.verify_candidate(candidate)

    def test_parallel_commands_use_distinct_attempts_and_release_failed_lease(self):
        repo = self.root / 'repo'; repo.mkdir(); (repo / 'build.sbt').write_text('build')
        candidate = self.pool.freeze(repo, self.root / 'candidate', [])
        for slot in self.config['slots']:
            cred = Path(slot['credentials_ref']); cred.parent.mkdir(parents=True, exist_ok=True)
            cred.write_text('{"user":"lab","password":"sensitive-test-value"}'); cred.chmod(0o600)
        argv = [sys.executable, '-c', 'import os,pathlib,time;time.sleep(.25);pathlib.Path("owned.txt").write_text(os.environ["CDC_LAB_SLOT"]);print(os.environ["CDC_WINDOW_MYSQL_PASSWORD"])']
        jobs = [{'case_id':'ok','argv':argv}, {'case_id':'failed','argv':[sys.executable,'-c','import time;time.sleep(.25);raise SystemExit(7)']}]
        results = self.pool.run_jobs(self.p, candidate, jobs, ['a','b'], self.root / 'attempts')
        self.assertEqual({r['case_id']:r['exit_code'] for r in results}, {'ok':0,'failed':7})
        self.assertLess(max(r['started_ns'] for r in results),min(r['finished_ns'] for r in results))
        ok = next(r for r in results if r['case_id']=='ok')
        self.assertEqual((Path(ok['attempt'])/'owned.txt').read_text(), ok['slot'])
        self.assertNotIn('sensitive-test-value', (Path(ok['attempt'])/'runner.log').read_text())
        index = json.loads((self.root/'attempts/index.json').read_text())
        self.assertEqual({r['case_id'] for r in index['results']}, {'ok','failed'})
        for slot in ['a','b']:
            token = self.p.acquire(slot, 'after-failure'); self.p.release(slot, token)
        with self.assertRaises(ValueError): self.pool.run_jobs(self.p,candidate,jobs+jobs,['a','b'],self.root/'duplicate')

    def test_wrong_live_identity_prevents_mutating_sql(self):
        # A misrouted mysql socket must be detected before INSERT is sent.
        fake = self.root/'bin';fake.mkdir(); trace=self.root/'sql.trace'
        mysql=fake/'mysql';mysql.write_text('#!/usr/bin/env python3\nimport sys,pathlib\ns=sys.argv[-1]\nwith pathlib.Path('+repr(str(trace))+').open("a") as f:f.write(s+"\\n")\nprint("wrong-uuid\\t14365\\t/tmp/other")\n');mysql.chmod(0o755)
        old=os.environ['PATH'];os.environ['PATH']=str(fake)+os.pathsep+old
        self.addCleanup(os.environ.__setitem__,'PATH',old)
        token=self.p.acquire('a','identity-test')
        with self.assertRaises(ValueError):self.p.sql('a','source','INSERT INTO protected VALUES (1)',token)
        self.assertNotIn('INSERT',trace.read_text())
        self.p.release('a',token)

    def test_failed_token_publication_does_not_orphan_lease(self):
        token_path=self.root/'existing-token.json';token_path.write_text('preserve me')
        result=subprocess.run([sys.executable,str(HERE/'pool.py'),'--config',str(self.path),'acquire','a','--owner','test','--token-file',str(token_path)],capture_output=True)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(token_path.read_text(),'preserve me')
        token=self.p.acquire('a','after-failed-publication');self.p.release('a',token)

    def test_busy_shard_preserves_other_shards_and_explicit_failure_result(self):
        repo=self.root/'repo';repo.mkdir();(repo/'build.sbt').write_text('build')
        candidate=self.pool.freeze(repo,self.root/'candidate',[])
        for slot in self.config['slots']:
            cred=Path(slot['credentials_ref']);cred.parent.mkdir(parents=True,exist_ok=True)
            cred.write_text('{"user":"lab","password":"sensitive-test-value"}');cred.chmod(0o600)
        token=self.p.acquire('a','already-owned')
        jobs=[{'case_id':name,'argv':[sys.executable,'-c','print("owned")']} for name in ['busy','free']]
        results=self.pool.run_jobs(self.p,candidate,jobs,['a','b'],self.root/'attempts')
        self.assertEqual({r['case_id']:r['exit_code'] for r in results},{'busy':125,'free':0})
        self.assertEqual(len(json.loads((self.root/'attempts/index.json').read_text())['results']),2)
        self.p.require('a',token,owner='already-owned');self.p.release('a',token)

    def test_successful_parent_does_not_leave_signal_ignoring_child(self):
        repo=self.root/'repo';repo.mkdir();(repo/'build.sbt').write_text('build')
        candidate=self.pool.freeze(repo,self.root/'candidate',[])
        cred=Path(self.config['slots'][0]['credentials_ref']);cred.parent.mkdir(parents=True)
        cred.write_text('{"user":"lab","password":"sensitive-test-value"}');cred.chmod(0o600)
        child='import os,pathlib,signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);pathlib.Path("child.pid").write_text(str(os.getpid()));time.sleep(30)'
        parent='import pathlib,subprocess,sys,time;subprocess.Popen([sys.executable,"-c",sys.argv[1]]);\nwhile not pathlib.Path("child.pid").exists():time.sleep(.01)'
        results=self.pool.run_jobs(self.p,candidate,[{'case_id':'orphan','argv':[sys.executable,'-c',parent,child]}],['a'],self.root/'attempts')
        pid=int((Path(results[0]['attempt'])/'child.pid').read_text())
        self.addCleanup(self._kill_if_alive,pid)
        for _ in range(50):
            status=subprocess.run(['ps','-p',str(pid),'-o','stat='],capture_output=True,text=True).stdout.strip()
            if not status or status.startswith('Z'):break
            import time;time.sleep(.01)
        self.assertTrue(not status or status.startswith('Z'),'descendant survived runner cleanup: '+status)
        self.assertTrue(results[0]['cleanup']['confirmed_exited'])
        index=json.loads((self.root/'attempts/index.json').read_text())
        self.assertGreaterEqual(index['leases']['a']['released_ns'],results[0]['cleanup']['finished_ns'])

    @staticmethod
    def _kill_if_alive(pid):
        import signal
        try:os.kill(pid,signal.SIGKILL)
        except ProcessLookupError:pass

    def test_unknown_slot_cannot_be_used_as_lease_path(self):
        for name in ['unconfigured','../outside']:
            with self.assertRaises(ValueError):self.p.require(name,'token')

    def test_uncertain_cleanup_quarantines_lease_and_skips_next_case(self):
        from unittest.mock import patch
        repo=self.root/'repo';repo.mkdir();(repo/'build.sbt').write_text('build')
        candidate=self.pool.freeze(repo,self.root/'candidate',[])
        cred=Path(self.config['slots'][0]['credentials_ref']);cred.parent.mkdir(parents=True)
        cred.write_text('{"user":"lab","password":"sensitive-test-value"}');cred.chmod(0o600)
        if not hasattr(self.pool,'terminate_group'):self.fail('cleanup confirmation boundary is missing')
        cleanup=self.pool.terminate_group
        def uncertain(group):
            actual=cleanup(group);actual['confirmed_exited']=False;actual['observation_error']='forced observation unavailable';return actual
        jobs=[{'case_id':'first','argv':[sys.executable,'-c','print("first")']},
              {'case_id':'must-not-start','argv':[sys.executable,'-c','from pathlib import Path;Path("ran").touch()']}]
        with patch.object(self.pool,'terminate_group',uncertain):
            results=self.pool.run_jobs(self.p,candidate,jobs,['a'],self.root/'attempts')
        self.assertEqual({r['case_id']:r['exit_code'] for r in results},{'first':126,'must-not-start':125})
        self.assertFalse((self.root/'attempts/must-not-start-a/ran').exists())
        with self.assertRaises(ValueError):self.p.acquire('a','must-remain-quarantined')
        index=json.loads((self.root/'attempts/index.json').read_text())
        self.assertEqual(index['leases']['a']['status'],'quarantined')
        self.assertNotIn('released_ns',index['leases']['a'])

    def test_each_case_checks_both_live_identities_and_rejects_changed_state(self):
        repo=self.root/'repo';repo.mkdir();(repo/'build.sbt').write_text('build')
        candidate=self.pool.freeze(repo,self.root/'candidate',[])
        cred=Path(self.config['slots'][0]['credentials_ref']);cred.parent.mkdir(parents=True)
        cred.write_text('{"user":"lab","password":"sensitive-test-value"}');cred.chmod(0o600)
        mutate='import json,pathlib,sys;p=pathlib.Path(sys.argv[1]);d=json.loads(p.read_text());d["14366"]["uuid"]="wrong-live-uuid";p.write_text(json.dumps(d));pathlib.Path("first-ran").touch()'
        jobs=[{'case_id':'first','argv':[sys.executable,'-c',mutate,str(self.identities)]},
              {'case_id':'reject','argv':[sys.executable,'-c','from pathlib import Path;Path("must-not-run").touch()']}]
        results=self.pool.run_jobs(self.p,candidate,jobs,['a'],self.root/'attempts')
        self.assertEqual({r['case_id']:r['exit_code'] for r in results},{'first':0,'reject':125})
        self.assertFalse((self.root/'attempts/reject-a/must-not-run').exists())
        self.assertEqual(self.mysql_trace.read_text().splitlines(),['14365','14366','14365','14366'])
        self.assertTrue(results[0]['instance_admission']['admitted'])
        self.assertFalse(results[1]['instance_admission']['admitted'])
        self.assertEqual(results[1]['instance_admission']['instances']['state']['actual']['uuid'],'wrong-live-uuid')

    def test_two_runner_processes_share_one_pool_budget(self):
        self.config['max_parallel']=1;self.path.write_text(json.dumps(self.config))
        repo=self.root/'repo';repo.mkdir();(repo/'build.sbt').write_text('build')
        candidate=self.pool.freeze(repo,self.root/'candidate',[])
        for slot in self.config['slots']:
            cred=Path(slot['credentials_ref']);cred.parent.mkdir(parents=True,exist_ok=True)
            cred.write_text('{"user":"lab","password":"sensitive-test-value"}');cred.chmod(0o600)
        code='import json,pathlib,sys,time;sys.path.insert(0,sys.argv[1]);import pool;pathlib.Path(sys.argv[6]).touch();\nwhile not pathlib.Path(sys.argv[5]).exists():time.sleep(.005)\nr=pool.run_jobs(pool.Pool(sys.argv[2]),sys.argv[3],[{"case_id":"budget","argv":[sys.executable,"-c","import time;time.sleep(.4)"]}],[sys.argv[4]],sys.argv[7]);print(json.dumps(r))'
        children=[]
        for slot in ('a','b'):
            child=subprocess.Popen([sys.executable,'-c',code,str(HERE),str(self.path),str(candidate),slot,str(self.root/'go'),str(self.root/(slot+'.ready')),str(self.root/('runner-'+slot))],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
            children.append(child)
        try:
            deadline=time.monotonic()+5
            while not all((self.root/(slot+'.ready')).exists() for slot in ('a','b')):
                self.assertLess(time.monotonic(),deadline);time.sleep(.005)
            (self.root/'go').touch()
            results=[]
            for child in children:
                stdout,stderr=child.communicate(timeout=8)
                self.assertEqual(child.returncode,0,stderr);results.extend(json.loads(stdout))
            self.assertEqual(sorted(r['exit_code'] for r in results),[0,125])
        finally:
            for child in children:
                if child.poll() is None:child.kill();child.wait()

    def test_manual_and_unknown_lease_reserve_pool_capacity(self):
        self.config['max_parallel']=1;self.path.write_text(json.dumps(self.config));self.p=self.pool.Pool(self.path)
        token=self.p.acquire('a','manual-reservation')
        with self.assertRaises(ValueError):self.p.acquire('b','must-be-denied')
        old=self.p.root/'leases/a';unknown=self.p.root/'leases/unknown-quarantine';old.rename(unknown)
        try:
            with self.assertRaises(ValueError):self.p.acquire('b','must-still-be-denied')
        finally:unknown.rename(old)
        self.p.release('a',token)
        next_token=self.p.acquire('b','after-explicit-release');self.p.release('b',next_token)

    def test_timeout_kills_worker_and_preserves_result(self):
        repo = self.root/'repo'; repo.mkdir(); (repo/'build.sbt').write_text('build')
        candidate = self.pool.freeze(repo,self.root/'candidate',[])
        cred=Path(self.config['slots'][0]['credentials_ref']);cred.parent.mkdir(parents=True)
        cred.write_text('{"user":"lab","password":"sensitive-test-value"}');cred.chmod(0o600)
        results=self.pool.run_jobs(self.p,candidate,[{'case_id':'timeout','argv':[sys.executable,'-c','import time;time.sleep(30)'],'timeout_seconds':.1}],['a'],self.root/'attempts')
        self.assertEqual(results[0]['exit_code'],124)
        self.assertTrue((Path(results[0]['attempt'])/'result.json').exists())
        token=self.p.acquire('a','after-timeout');self.p.release('a',token)

if __name__ == '__main__': unittest.main()
