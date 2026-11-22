#!/usr/bin/env python3
"""Destructive checks only inside two newly leased disposable pool slots; preserve all attempts."""
import argparse
import json
from pathlib import Path
import subprocess
import time

from pool import Pool, ROLES, write_json


def run(pool, tokens, output):
    output = Path(output).resolve(); output.mkdir(parents=True, exist_ok=False)
    report = {'status':'running','checks':[],'environment':pool.config}
    def sql(slot, role, statement): return pool.sql(slot,role,statement,tokens[slot])
    def snapshot(slot):
        return {role:sql(slot,role,'SELECT @@server_uuid,@@port,@@datadir; SELECT * FROM cdc_lab_isolation.witness ORDER BY id; SHOW CREATE TABLE cdc_lab_isolation.witness; SHOW BINARY LOG STATUS') for role in ROLES}
    def unchanged(slot, expected):
        actual=snapshot(slot)
        if actual!=expected: raise AssertionError('observer changed: '+slot)
        return actual
    def healthy_write(slot):
        for role in ROLES:
            before=int(sql(slot,role,'SELECT seq FROM cdc_lab_isolation.witness WHERE id=1').strip())
            sql(slot,role,'UPDATE cdc_lab_isolation.witness SET seq=seq+1 WHERE id=1')
            after=int(sql(slot,role,'SELECT seq FROM cdc_lab_isolation.witness WHERE id=1').strip())
            if after!=before+1: raise AssertionError('healthy observer write failed')
    try:
        for slot in tokens:
            pool.require(slot,tokens[slot])
            for role in ROLES:
                sql(slot,role,'CREATE DATABASE cdc_lab_isolation; CREATE TABLE cdc_lab_isolation.witness(id INT PRIMARY KEY,seq INT NOT NULL); INSERT INTO cdc_lab_isolation.witness VALUES(1,0)')
        report['baseline']={slot:snapshot(slot) for slot in tokens}
        for victim,observer in [('a','b'),('b','a')]:
            for role in ROLES:
                label=victim+'-'+role
                expected=snapshot(observer)
                base=pool.instance_dir(victim,role)
                command=['mysql','--defaults-file='+str(base/'client.cnf'),'-N','-B','--unbuffered','-e']
                lock_sql='START TRANSACTION; SELECT CONNECTION_ID(); SELECT id FROM cdc_lab_isolation.witness WHERE id=1 FOR UPDATE; SELECT "READY"; DO SLEEP(30); ROLLBACK'
                blocker=subprocess.Popen(command+[lock_sql],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                waiter=None;connection=None
                try:
                    connection=int(blocker.stdout.readline().strip())
                    if blocker.stdout.readline().strip()!='1' or blocker.stdout.readline().strip()!='READY':raise AssertionError('lock not acquired')
                    waiter=subprocess.Popen(command+['SET innodb_lock_wait_timeout=10; UPDATE cdc_lab_isolation.witness SET seq=seq+1 WHERE id=1'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                    deadline=time.monotonic()+5;locked='0'
                    while time.monotonic()<deadline:
                        locked=sql(victim,role,"SELECT count(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'").strip()
                        if int(locked)>0:break
                        time.sleep(.1)
                    if not int(locked)>0 or waiter.poll() is not None:raise AssertionError('victim did not reach real lock wait')
                    unchanged(observer,expected);healthy_write(observer)
                    report['checks'].append({'victim':victim,'role':role,'fault':'blocking','lock_wait_transactions':int(locked),'observer_unchanged':True,'observer_source_state_writes':'passed'})
                finally:
                    if connection is not None: sql(victim,role,'KILL CONNECTION '+str(connection))
                    blocker.communicate(timeout=5)
                    if waiter is not None:
                        _,err=waiter.communicate(timeout=12)
                        if waiter.returncode:raise AssertionError('waiting transaction failed: '+err)
                expected=snapshot(observer)
                sql(victim,role,'ALTER TABLE cdc_lab_isolation.witness ADD COLUMN ddl_marker INT DEFAULT 7')
                unchanged(observer,expected);healthy_write(observer)
                report['checks'].append({'victim':victim,'role':role,'fault':'DDL','observer_unchanged':True,'observer_source_state_writes':'passed'})
                expected=snapshot(observer)
                before=sql(victim,role,'SHOW BINARY LOGS')
                archive=pool.root/'binlog-archives'/(output.name+'-'+label)
                copied=pool.archive_and_purge(victim,role,tokens[victim],archive)
                after=sql(victim,role,'SHOW BINARY LOGS')
                if not copied or any(name in after for name in copied):raise AssertionError('purge did not remove archived old logs')
                unchanged(observer,expected);healthy_write(observer)
                report['checks'].append({'victim':victim,'role':role,'fault':'purge','before':before,'after':after,'private_archive':str(archive),'archive_sha256':copied,'observer_unchanged':True,'observer_source_state_writes':'passed'})
                expected=snapshot(observer);victim_data=sql(victim,role,'SELECT * FROM cdc_lab_isolation.witness ORDER BY id')
                pool.stop(victim,role,tokens[victim],crash=True)
                unchanged(observer,expected);healthy_write(observer)
                pool.start(victim,role,tokens[victim])
                if sql(victim,role,'SELECT * FROM cdc_lab_isolation.witness ORDER BY id')!=victim_data:raise AssertionError('crash recovery changed victim data')
                report['checks'].append({'victim':victim,'role':role,'fault':'SIGKILL','observer_unchanged':True,'observer_source_state_writes':'passed','victim_restart_data':'matched'})
                print(label+': blocking, DDL, archived purge, crash passed',flush=True)
        report['final']={slot:snapshot(slot) for slot in tokens}
        report['status']='passed'
    except BaseException as error:
        report.update(status='failed',error=repr(error));raise
    finally:write_json(output/'result.json',report)
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--config',type=Path,required=True);p.add_argument('--token-root',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    pool=Pool(a.config)
    tokens={slot:json.loads((a.token_root/(slot+'-isolation-token.json')).read_text())['token'] for slot in ('a','b')}
    run(pool,tokens,a.output)
