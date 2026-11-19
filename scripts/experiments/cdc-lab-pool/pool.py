#!/usr/bin/env python3
"""Local disposable MySQL slots and frozen, explicitly indexed command attempts. Stdlib only."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import time
import uuid
import zipfile

ROLES = ('source', 'state')
PROTECTED_PORTS = {3306, 13365, 13366}


def assert_loopback_bind_available(port):
    # Permit stale TIME_WAIT sockets while still refusing an active listener.
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        probe.bind(('127.0.0.1',port))


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def write_json(path, value, private=False):
    """Publish a complete JSON file atomically without overwriting older evidence."""
    path = Path(path)
    temporary = path.with_name('.'+path.name+'-'+secrets.token_hex(12))
    try:
        with open(temporary, 'x', opener=lambda p, flags: os.open(p, flags, 0o600 if private else 0o644)) as out:
            json.dump(value, out, indent=2, sort_keys=True)
            out.write('\n'); out.flush(); os.fsync(out.fileno())
        os.link(temporary,path)  # atomic create-if-absent; works on the local pool filesystem
    finally:
        temporary.unlink(missing_ok=True)


def example_config(root):
    root = Path(root).resolve()
    slots = []
    for name, port in [('a', 14365), ('b', 14375)]:
        slot = {'id': name, 'credentials_ref': str(root / name / 'credentials.json')}
        for i, role in enumerate(ROLES):
            slot[role] = {'port': port+i, 'uuid': str(uuid.uuid4()),
                          'datadir': str(root / name / role / 'data')}
        slots.append(slot)
    return {'runtime_root': str(root), 'max_parallel': 2, 'mysql_buffer_pool_mb': 64,
            'mysql_max_connections': 24, 'job_timeout_seconds': 600,
            'slots': slots}


class Pool:
    def __init__(self, path):
        self.path = Path(path).resolve()
        self.config = json.loads(self.path.read_text())
        self.root = Path(self.config['runtime_root']).resolve()
        if not self.root.is_absolute() or self.root == Path('/'):
            raise ValueError('invalid runtime root')
        self.slots = {s['id']: s for s in self.config['slots']}
        if len(self.slots) != len(self.config['slots']) or not 1 <= self.config['max_parallel'] <= 2:
            raise ValueError('duplicate slots or invalid parallel budget')
        if not 32 <= self.config['mysql_buffer_pool_mb'] <= 128 or not 4 <= self.config['mysql_max_connections'] <= 32:
            raise ValueError('MySQL budget outside lab bounds')
        ports, uuids, paths = set(), set(), []
        for name, slot in self.slots.items():
            if not name.isalnum(): raise ValueError('invalid slot id')
            cred = Path(slot['credentials_ref']).resolve()
            if not cred.is_relative_to(self.root / name): raise ValueError('credentials must be private slot-local')
            for role in ROLES:
                cfg = slot[role]; data = Path(cfg['datadir']).resolve()
                if not data.is_relative_to(self.root / name / role) or data == self.root / name / role:
                    raise ValueError('datadir must be exclusive slot-local child')
                if any(data == p or data.is_relative_to(p) or p.is_relative_to(data) for p in paths):
                    raise ValueError('overlapping datadir')
                paths.append(data)
                port = cfg['port']; identity = str(uuid.UUID(cfg['uuid']))
                if not isinstance(port, int) or not 1024 <= port <= 65535 or port in PROTECTED_PORTS or port in ports:
                    raise ValueError('protected or duplicate port')
                if identity in uuids: raise ValueError('duplicate UUID')
                ports.add(port); uuids.add(identity)

    def public_slot(self, name):
        return self.slots[name]

    @contextmanager
    def lease_lock(self):
        """Serialize only pool budget/lease metadata operations across threads and processes."""
        self.root.mkdir(parents=True,exist_ok=True,mode=0o700)
        with open(self.root/'.pool-lock','a',opener=lambda p,f:os.open(p,f,0o600)) as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            try:
                budget=self.root/'.pool-budget.json'
                if not budget.exists():write_json(budget,{'max_parallel':self.config['max_parallel']},True)
                elif json.loads(budget.read_text())['max_parallel']!=self.config['max_parallel']:
                    raise ValueError('runtime pool budget differs from controlled configuration')
                yield
            finally:fcntl.flock(lock,fcntl.LOCK_UN)

    def acquire(self, name, owner):
        if name not in self.slots: raise ValueError('unknown slot')
        with self.lease_lock():
            leases = self.root / 'leases'; leases.mkdir(exist_ok=True, mode=0o700)
            directory = leases / name
            if directory.exists():raise ValueError('slot already leased: '+name)
            # Every reservation counts, including manual, incomplete and unknown/quarantined leases.
            if sum(entry.is_dir() for entry in leases.iterdir())>=self.config['max_parallel']:
                raise ValueError('pool concurrency budget exhausted')
            directory.mkdir(mode=0o700)
            token = secrets.token_hex(24)
            try:
                write_json(directory/'lease.json', {'token':token, 'owner':owner, 'pid':os.getpid(), 'created_ns':time.time_ns()}, True)
            except BaseException:
                directory.rmdir(); raise
            return token

    def require(self, name, token, owner=None):
        if name not in self.slots: raise ValueError('unknown slot')
        lease = json.loads((self.root/'leases'/name/'lease.json').read_text())
        if not secrets.compare_digest(lease['token'], token): raise ValueError('lease token mismatch')
        if owner is not None and lease['owner']!=owner: raise ValueError('lease owner mismatch')

    def release(self, name, token, owner=None):
        with self.lease_lock():
            self.require(name, token, owner)
            directory = self.root/'leases'/name
            (directory/'lease.json').unlink(); directory.rmdir()

    def credentials(self, name):
        path = Path(self.slots[name]['credentials_ref'])
        if path.stat().st_mode & 0o077: raise ValueError('credentials file must be mode 0600')
        return json.loads(path.read_text())

    def environment(self, name):
        slot = self.slots[name]; credentials = self.credentials(name)
        return {'CDC_LAB_SLOT':name, 'CDC_WINDOW_MYSQL_HOST':'127.0.0.1',
                'CDC_WINDOW_MYSQL_PORT':str(slot['source']['port']), 'CAPTURE_STATE_PORT':str(slot['state']['port']),
                'CDC_WINDOW_MYSQL_USER':credentials['user'], 'CDC_WINDOW_MYSQL_PASSWORD':credentials['password'],
                'CAPTURE_TEST_URL':f"jdbc:mysql://127.0.0.1:{slot['source']['port']}/pekko_test_shared_wire?useSSL=false&allowPublicKeyRetrieval=true",
                'CAPTURE_STATE_URL':f"jdbc:mysql://127.0.0.1:{slot['state']['port']}/pekko_test_shared_state?useSSL=false&allowPublicKeyRetrieval=true",
                'RUN_SHARED_CAPTURE_MATRIX':'1', 'RUN_MYSQL_CDC_RECOVERY_WINDOWS':'1', 'RUN_MYSQL_SNAPSHOT_SPLITS':'1'}

    def instance_dir(self, name, role):
        return Path(self.slots[name][role]['datadir']).parent

    def sql(self, name, role, statement, token, verify=True):
        self.require(name, token)
        base = self.instance_dir(name,role)
        if verify:
            identity = self.sql(name,role,'SELECT @@server_uuid,@@port,@@datadir',token,False).strip().split('\t')
            cfg = self.slots[name][role]
            if identity[:2] != [cfg['uuid'],str(cfg['port'])] or Path(identity[2]).resolve()!=Path(cfg['datadir']).resolve():
                raise ValueError('live MySQL identity differs from leased configuration')
        result = subprocess.run(['mysql', '--defaults-file='+str(base/'client.cnf'), '--protocol=TCP', '--host=127.0.0.1', '--port='+str(self.slots[name][role]['port']), '-N', '-B', '-e', statement],
                                capture_output=True, text=True, timeout=20)
        if result.returncode: raise RuntimeError('mysql failed: '+result.stderr.replace(self.credentials(name)['password'],'[REDACTED]'))
        return result.stdout

    def verify_slot(self, name, token):
        """Observe both live TCP endpoints immediately before each case can be dispatched."""
        self.require(name,token)
        admission={'checked_ns':time.time_ns(),'instances':{},'admitted':True}
        for role in ROLES:
            cfg=self.slots[name][role]
            observation={'expected':{key:cfg[key] for key in ('uuid','port','datadir')}}
            try:
                row=self.sql(name,role,'SELECT @@server_uuid,@@port,@@datadir',token,False).strip().split('\t')
                if len(row)!=3:raise ValueError('malformed live identity response')
                actual={'uuid':row[0],'port':int(row[1]),'datadir':str(Path(row[2]).resolve())}
                observation['actual']=actual
                observation['matches']=(actual['uuid']==cfg['uuid'] and actual['port']==cfg['port']
                                        and actual['datadir']==str(Path(cfg['datadir']).resolve()))
            except Exception as error:observation.update(matches=False,error=str(error))
            admission['instances'][role]=observation
            admission['admitted']=admission['admitted'] and observation['matches']
        admission['finished_ns']=time.time_ns()
        return admission

    def initialize(self, name, token):
        self.require(name, token)
        slot = self.slots[name]
        # Inspect every target before creating any instance. Existing data is never initialized again.
        for role in ROLES:
            if self.instance_dir(name,role).exists(): raise ValueError('instance directory already exists; preserve failed initialization')
            with socket.socket() as probe: probe.bind(('127.0.0.1',slot[role]['port']))
        private = self.root/name; private.mkdir(parents=True, exist_ok=True, mode=0o700); private.chmod(0o700)
        if not Path(slot['credentials_ref']).exists():
            write_json(slot['credentials_ref'], {'user':'cdc_lab', 'password':secrets.token_hex(24)}, True)
        credentials = self.credentials(name)
        for role in ROLES:
            cfg = slot[role]; base = self.instance_dir(name,role); base.mkdir(mode=0o700)
            data = Path(cfg['datadir'])
            with (base/'initialize.log').open('x') as log:
                result = subprocess.run(['mysqld','--no-defaults','--initialize-insecure','--datadir='+str(data)], stdout=log, stderr=subprocess.STDOUT, timeout=90)
            if result.returncode: raise RuntimeError('initialize failed; retained '+str(base/'initialize.log'))
            (data/'auto.cnf').write_text('[auto]\nserver-uuid='+cfg['uuid']+'\n')
            server = f'''[mysqld]
datadir={data}
port={cfg['port']}
bind-address=127.0.0.1
socket={base}/mysql.sock
pid-file={base}/mysqld.pid
log-error={base}/mysqld.log
server-id={cfg['port']}
mysqlx=0
log-bin={data}/binlog
binlog-format=ROW
binlog-row-image=FULL
gtid-mode=ON
enforce-gtid-consistency=ON
binlog-expire-logs-seconds=0
innodb-buffer-pool-size={self.config['mysql_buffer_pool_mb']}M
innodb-redo-log-capacity=32M
max-connections={self.config['mysql_max_connections']}
performance-schema=OFF
table-open-cache=128
table-definition-cache=128
max-allowed-packet=16M
skip-name-resolve=ON
'''
            (base/'server.cnf').write_text(server)
            self.start(name,role,token,check_identity=False)
            # Password travels through stdin only, and stays out of process argv and evidence.
            user, password = credentials['user'], credentials['password']
            if not user.replace('_','').isalnum() or not password.isalnum(): raise ValueError('initial credentials must be alphanumeric')
            sql = f"CREATE USER '{user}'@'localhost' IDENTIFIED BY '{password}'; GRANT ALL PRIVILEGES ON *.* TO '{user}'@'localhost' WITH GRANT OPTION; CREATE USER '{user}'@'127.0.0.1' IDENTIFIED BY '{password}'; GRANT ALL PRIVILEGES ON *.* TO '{user}'@'127.0.0.1' WITH GRANT OPTION; ALTER USER 'root'@'localhost' IDENTIFIED BY '{secrets.token_hex(24)}';"
            result = subprocess.run(['mysql','--no-defaults','--socket='+str(base/'mysql.sock'),'-uroot'], input=sql, capture_output=True, text=True, timeout=20)
            if result.returncode: raise RuntimeError('credential initialization failed (output withheld)')
            client = base/'client.cnf'
            with open(client, 'x', opener=lambda p,f:os.open(p,f,0o600)) as out:
                out.write(f'[client]\nuser={user}\npassword={password}\nsocket={base}/mysql.sock\n')
            self.sql(name,role,'CREATE DATABASE pekko_test_shared_wire; CREATE DATABASE pekko_test_shared_state;',token)

    def start(self, name, role, token, check_identity=True):
        self.require(name,token); base = self.instance_dir(name,role)
        cfg = self.slots[name][role]
        auto = (Path(cfg['datadir'])/'auto.cnf').read_text()
        if 'server-uuid='+cfg['uuid'] not in auto: raise ValueError('datadir UUID mismatch')
        assert_loopback_bind_available(cfg['port'])
        result = subprocess.run(['mysqld','--defaults-file='+str(base/'server.cnf'),'--daemonize'],capture_output=True,text=True,timeout=30)
        if result.returncode: raise RuntimeError('mysqld start failed; retained '+str(base/'mysqld.log'))
        if check_identity: self.sql(name,role,'SELECT 1',token)

    def stop(self, name, role, token, crash=False):
        self.sql(name,role,'SELECT 1',token)
        base = self.instance_dir(name,role); pid = int((base/'mysqld.pid').read_text())
        command = subprocess.check_output(['ps','-p',str(pid),'-o','command='],text=True).strip()
        if '--defaults-file='+str(base/'server.cnf') not in command or 'mysqld' not in command:
            raise ValueError('PID ownership mismatch')
        os.kill(pid, signal.SIGKILL if crash else signal.SIGTERM)
        deadline = time.monotonic()+20
        while time.monotonic()<deadline:
            with socket.socket() as probe:
                if probe.connect_ex(('127.0.0.1',self.slots[name][role]['port'])) != 0: return
            time.sleep(.1)
        raise RuntimeError('server still listening after stop')

    def archive_and_purge(self, name, role, token, destination):
        self.sql(name,role,'FLUSH BINARY LOGS',token)
        files = [line.split('\t')[0] for line in self.sql(name,role,'SHOW BINARY LOGS',token).strip().splitlines()]
        dest = Path(destination); dest.mkdir(parents=True,exist_ok=False)
        copied = {}
        for filename in files[:-1]:
            if Path(filename).name != filename: raise ValueError('unsafe binlog name')
            source = Path(self.slots[name][role]['datadir'])/filename
            shutil.copy2(source,dest/filename); copied[filename]=digest(dest/filename)
        write_json(dest/'manifest.json',{'slot':name,'role':role,'uuid':self.slots[name][role]['uuid'],'files':copied})
        self.sql(name,role,"PURGE BINARY LOGS TO '"+files[-1]+"'",token)
        return copied


def source_manifest(repo):
    repo = Path(repo)
    files = [f for folder in ['server/src','project'] if (repo/folder).exists()
             for f in (repo/folder).rglob('*') if f.is_file() and 'target' not in f.relative_to(repo).parts]
    files += [repo/'build.sbt'] if (repo/'build.sbt').exists() else []
    return {str(f.relative_to(repo)):digest(f) for f in sorted(files)}


def freeze(repo, destination, classpath):
    repo = Path(repo).resolve(); dest = Path(destination).resolve(); dest.mkdir(parents=True,exist_ok=False)
    before = source_manifest(repo)
    paths = []
    for i, path in enumerate(classpath):
        path = Path(path)
        target = dest/f'classpath-{i}.jar'
        if path.is_dir():
            with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as archive:
                for f in sorted(path.rglob('*')):
                    if f.is_file(): archive.write(f,str(f.relative_to(path)))
        else: shutil.copy2(path,target)
        paths.append(str(target)); target.chmod(0o444)
    with zipfile.ZipFile(dest/'source.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for f in before: archive.write(repo/f,f)
    if before != source_manifest(repo): raise ValueError('source changed during freeze; failed candidate retained')
    text = json.dumps(before,sort_keys=True).encode()
    test = {p:v for p,v in before.items() if p.startswith('server/src/test/')}
    production = {p:v for p,v in before.items() if not p.startswith('server/src/test/')}
    manifest = {'tool_sha256':digest(__file__), 'source_manifest':before,'source_sha256':hashlib.sha256(text).hexdigest(),
                'production_sha256':hashlib.sha256(json.dumps(production,sort_keys=True).encode()).hexdigest(),
                'test_sha256':hashlib.sha256(json.dumps(test,sort_keys=True).encode()).hexdigest(),
                'classpath':paths,'artifacts':{str(p.relative_to(dest)):digest(p) for p in sorted(dest.iterdir()) if p.is_file()}}
    write_json(dest/'candidate.json',manifest); (dest/'candidate.json').chmod(0o444)
    return dest/'candidate.json'


def verify_candidate(path):
    path = Path(path); manifest = json.loads(path.read_text())
    for name, expected in manifest['artifacts'].items():
        if digest(path.parent/name)!=expected: raise ValueError('frozen artifact changed: '+name)
    return manifest



def terminate_group(group):
    """Bound cleanup to this worker's process group and confirm no live members remain."""
    evidence={'group':group,'term_sent':False,'kill_sent':False,'phases':[]}
    def observe():
        try:os.killpg(group,0)
        except ProcessLookupError:return []
        result=subprocess.run(['ps','-ax','-o','pid=,pgid=,stat='],capture_output=True,text=True,timeout=3)
        if result.returncode:raise RuntimeError('cannot observe process group')
        return [{'pid':int(row[0]),'state':row[2]} for line in result.stdout.splitlines()
                if len(row:=line.split())==3 and int(row[1])==group and not row[2].startswith('Z')]
    remaining=None
    for label,signum,budget in [('TERM',signal.SIGTERM,.5),('KILL',signal.SIGKILL,2.0)]:
        try:os.killpg(group,signum);evidence['term_sent' if label=='TERM' else 'kill_sent']=True
        except ProcessLookupError:remaining=[];break
        deadline=time.monotonic()+budget
        while True:
            try:remaining=observe()
            except Exception as error:remaining=None;evidence['observation_error']=str(error)
            if remaining==[] or time.monotonic()>=deadline:break
            time.sleep(.05)
        evidence['phases'].append({'signal':label,'remaining_live':remaining})
        if remaining==[]:break
    evidence.update(confirmed_exited=remaining==[],remaining_live=remaining,finished_ns=time.time_ns())
    return evidence


def run_jobs(pool, candidate, jobs, slots, destination):
    if len(slots)!=len(set(slots)) or len(slots)>pool.config['max_parallel'] or not slots or any(s not in pool.slots for s in slots):
        raise ValueError('invalid slot concurrency')
    ids=[job['case_id'] for job in jobs]
    if len(ids)!=len(set(ids)) or any(not case or not all(c.isalnum() or c in '-_' for c in case) for case in ids):
        raise ValueError('case ids must be unique safe names')
    manifest=verify_candidate(candidate); candidate_hash=digest(candidate); runner_hash=digest(__file__)
    out=Path(destination).resolve();out.mkdir(parents=True,exist_ok=False)
    # Explicit round-robin ownership, one sequential worker per physical slot.
    assignments={slot:jobs[i::len(slots)] for i,slot in enumerate(slots)}
    write_json(out/'plan.json',{'runner_tool_sha256':runner_hash,'candidate_sha256':candidate_hash,'assignments':{s:[j['case_id'] for j in js] for s,js in assignments.items()}})
    leases={}
    def failed_shard(slot, error, jobs=None):
        results=[]
        for job in assignments[slot] if jobs is None else jobs:
            attempt=out/(job['case_id']+'-'+slot);attempt.mkdir()
            result={'case_id':job['case_id'],'slot':slot,'attempt':str(attempt),'source_sha256':manifest['source_sha256'],
                    'test_sha256':manifest['test_sha256'],'candidate_sha256':candidate_hash,'runner_tool_sha256':runner_hash,'exit_code':125,'error':str(error),
                    'started_ns':time.time_ns(),'finished_ns':time.time_ns(),'evidence_files':{}}
            write_json(attempt/'result.json',result);results.append(result)
        return results
    def worker(slot):
        try: token=pool.acquire(slot,str(out))
        except Exception as error:
            leases[slot]={'status':'not_acquired'};return failed_shard(slot,error)
        leases[slot]={'status':'acquired','acquired_ns':time.time_ns()}
        results=[];quarantined=False
        try:
            try: environment=pool.environment(slot)
            except Exception as error: return failed_shard(slot,error)
            password=environment['CDC_WINDOW_MYSQL_PASSWORD']
            for job_number,job in enumerate(assignments[slot]):
                attempt=out/(job['case_id']+'-'+slot);attempt.mkdir()
                result={'case_id':job['case_id'],'slot':slot,'attempt':str(attempt),'source_sha256':manifest['source_sha256'],
                        'test_sha256':manifest['test_sha256'],'candidate_sha256':candidate_hash,'runner_tool_sha256':runner_hash,'environment':pool.public_slot(slot), 'started_ns':time.time_ns()}
                try:
                    if digest(candidate)!=candidate_hash: raise ValueError('candidate manifest changed')
                    verify_candidate(candidate)
                    argv=[arg.replace('{classpath}',os.pathsep.join(manifest['classpath'])).replace('{evidence}',str(attempt)) for arg in job['argv']]
                    if any(password in arg for arg in argv): raise ValueError('credential in argv')
                    result['argv']=argv
                    env=os.environ.copy();env.update(environment)
                    env.update(BOOTSTRAP_EVIDENCE_RUN=attempt.name, BOOTSTRAP_SOURCE_MANIFEST_SHA256=manifest['source_sha256'],CDC_LAB_EVIDENCE_DIR=str(attempt))
                    raw=pool.root/slot/('raw-'+secrets.token_hex(12)+'.log');raw.parent.mkdir(parents=True,exist_ok=True)
                    result['private_raw_log']=str(raw)
                    with open(raw, 'x',opener=lambda p,f:os.open(p,f,0o600)) as log:
                        result['instance_admission']=pool.verify_slot(slot,token)
                        if not result['instance_admission']['admitted']:raise ValueError('live source/state identity admission rejected')
                        child=subprocess.Popen(argv,cwd=attempt,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
                        try: code=child.wait(timeout=job.get('timeout_seconds',pool.config['job_timeout_seconds']))
                        except subprocess.TimeoutExpired:
                            os.killpg(child.pid,signal.SIGKILL);child.wait();code=124
                        finally:
                            try: result['cleanup']=terminate_group(child.pid)
                            except Exception as error:
                                result['cleanup']={'confirmed_exited':False,'error':str(error),'finished_ns':time.time_ns()}
                            quarantined=not result['cleanup']['confirmed_exited']
                    (attempt/'runner.log').write_bytes(raw.read_bytes().replace(password.encode(),b'[REDACTED]'))
                    result.update(exit_code=126 if quarantined else code,private_raw_log=str(raw))
                    verify_candidate(candidate)
                except Exception as error:
                    result.update(exit_code=125,error=str(error).replace(password,'[REDACTED]'))
                result['finished_ns']=time.time_ns()
                result['evidence_files']={str(f.relative_to(attempt)):digest(f) for f in sorted(attempt.rglob('*')) if f.is_file() and not f.is_symlink()}
                write_json(attempt/'result.json',result);results.append(result)
                if quarantined:
                    results.extend(failed_shard(slot,'slot quarantined: cleanup unconfirmed',assignments[slot][job_number+1:]));break
        finally:
            if quarantined:leases[slot]['status']='quarantined'
            else:
                pool.release(slot,token);leases[slot].update(status='released',released_ns=time.time_ns())
        return results
    results=[]
    try:
        with ThreadPoolExecutor(max_workers=len(slots)) as executor:
            for shard in executor.map(worker,slots): results.extend(shard)
    finally:
        write_json(out/'index.json',{'runner_tool_sha256':runner_hash,'candidate_sha256':candidate_hash,'source_sha256':manifest['source_sha256'],'leases':leases,'results':results})
    return results


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--config',type=Path)
    subs=parser.add_subparsers(dest='action',required=True)
    p=subs.add_parser('example');p.add_argument('--runtime-root',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    for action in ['acquire','release','init','start','stop','status']:
        p=subs.add_parser(action);p.add_argument('slot')
        if action in ['acquire','release']: p.add_argument('--owner',required=True)
        if action!='acquire': p.add_argument('--token-file',type=Path,required=True)
        if action in ['start','stop']:p.add_argument('--role',choices=ROLES,required=True)
        if action=='stop':p.add_argument('--crash',action='store_true')
        if action=='acquire':p.add_argument('--token-file',type=Path,required=True)
    p=subs.add_parser('freeze');p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--java-home',required=True)
    p=subs.add_parser('run');p.add_argument('--candidate',type=Path,required=True);p.add_argument('--jobs',type=Path,required=True);p.add_argument('--slots',nargs='+',required=True);p.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.action=='example':write_json(args.output,example_config(args.runtime_root));return
    if args.action=='freeze':
        before=source_manifest(args.repo);env=os.environ.copy();env['JAVA_HOME']=args.java_home;env['PATH']=args.java_home+'/bin:'+env['PATH']
        build=args.output.with_name(args.output.name+'-build');build.mkdir(parents=True,exist_ok=False)
        with (build/'build.log').open('x') as log:
            result=subprocess.run(['sbt','project pekko-server','Test / compile','Test / fullClasspath'],cwd=args.repo,env=env,stdout=log,stderr=subprocess.STDOUT)
        if result.returncode:raise SystemExit(result.returncode)
        if before!=source_manifest(args.repo):raise ValueError('source changed during build; do not freeze')
        cp=(args.repo/'server/target/streams/test/fullClasspath/_global/streams/export').read_text().strip().split(os.pathsep)
        print(freeze(args.repo,args.output,cp));return
    pool=Pool(args.config)
    if args.action=='acquire':
        token=pool.acquire(args.slot,args.owner)
        try:write_json(args.token_file,{'token':token},True)
        except BaseException:
            pool.release(args.slot,token,args.owner);raise
        return
    if args.action=='run':
        results=run_jobs(pool,args.candidate,json.loads(args.jobs.read_text()),args.slots,args.output)
        raise SystemExit(1 if any(r['exit_code'] for r in results) else 0)
    token=json.loads(args.token_file.read_text())['token']
    if args.action=='release':pool.release(args.slot,token,args.owner)
    elif args.action=='init':pool.initialize(args.slot,token)
    elif args.action=='start':pool.start(args.slot,args.role,token)
    elif args.action=='stop':pool.stop(args.slot,args.role,token,args.crash)
    elif args.action=='status':
        print(json.dumps({role:pool.sql(args.slot,role,'SELECT @@server_uuid,@@port,@@datadir,@@innodb_buffer_pool_size,@@max_connections',token).strip() for role in ROLES},indent=2))

if __name__=='__main__':main()
