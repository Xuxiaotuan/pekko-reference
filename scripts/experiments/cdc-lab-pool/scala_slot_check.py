#!/usr/bin/env python3
"""Run one frozen Scala suite inside its leased runner slot; retain read-only before/after receipts."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

from pool import Pool, ROLES, digest, write_json


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config',type=Path,required=True);p.add_argument('--classpath',required=True)
    p.add_argument('--java',required=True);p.add_argument('--protected-schema-file',type=Path,required=True)
    a=p.parse_args();pool=Pool(a.config);slot=os.environ['CDC_LAB_SLOT']
    schemas=json.loads(a.protected_schema_file.read_text())['unique_experiment_schemas_preserved']
    receipt={'slot':slot,'wrapper_sha256':digest(__file__),'source_sha256':os.environ['BOOTSTRAP_SOURCE_MANIFEST_SHA256']}
    password=os.environ['CDC_WINDOW_MYSQL_PASSWORD']
    def sql(role,statement):
        cfg=pool.slots[slot][role]
        args=['mysql','--defaults-file='+str(pool.instance_dir(slot,role)/'client.cnf'),
              '--protocol=TCP','--host=127.0.0.1','--port='+str(cfg['port']),'-N','-B','-e',statement]
        result=subprocess.run(args,capture_output=True,text=True,timeout=30)
        if result.returncode:raise RuntimeError(result.stderr.replace(password,'[REDACTED]'))
        return result.stdout
    def snapshot():
        result={}
        schema_literals=','.join("'"+name.replace("'","''")+"'" for name in schemas)
        for role in ROLES:
            row=sql(role,'SELECT @@server_uuid,@@port,@@datadir').strip().split('\t')
            cfg=pool.slots[slot][role]
            if row[:2]!=[cfg['uuid'],str(cfg['port'])] or Path(row[2]).resolve()!=Path(cfg['datadir']).resolve():raise ValueError('wrapper live identity mismatch')
            inventory=sql(role,'SELECT TABLE_SCHEMA,TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('+schema_literals+') ORDER BY TABLE_SCHEMA,TABLE_NAME')
            names=[]
            for line in inventory.strip().splitlines():
                schema,table=line.split('\t');names.append('`'+schema.replace('`','``')+'`.`'+table.replace('`','``')+'`')
            result[role]={'identity':row,'other_connections':sql(role,"SELECT ID,USER,HOST,DB,COMMAND,STATE FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND USER NOT IN ('event_scheduler','system user') ORDER BY ID"),
                          'system_threads':sql(role,"SELECT ID,USER,COMMAND,STATE FROM information_schema.PROCESSLIST WHERE USER IN ('event_scheduler','system user') ORDER BY ID"),
                          'active_transactions':sql(role,'SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.INNODB_TRX ORDER BY trx_mysql_thread_id'),
                          'protected_C_tables':inventory,'protected_C_checksums':sql(role,'CHECKSUM TABLE '+','.join(names)) if names else ''}
        return result
    code=125
    try:
        receipt['before']=snapshot()
        if any(row['other_connections'] or row['active_transactions'] for row in receipt['before'].values()):raise RuntimeError('slot not idle before Scala suite')
        command=[a.java,'-Xmx512m','-Duser.timezone=UTC','-cp',a.classpath,'org.scalatest.tools.Runner','-o','-s','cn.xuyinyin.magic.connectors.mysql.source.SharedCaptureReaderSpec']
        receipt['java_started_ns']=time.time_ns()
        child=subprocess.Popen(command);receipt['java_pid']=child.pid
        code=child.wait();receipt['java_finished_ns']=time.time_ns();receipt['java_exit_code']=code
        deadline=time.monotonic()+5
        while True:
            receipt['after']=snapshot()
            idle=not any(row['other_connections'] or row['active_transactions'] for row in receipt['after'].values())
            if idle or time.monotonic()>=deadline:break
            time.sleep(.1)
        receipt['protected_C_unchanged']=all(receipt['before'][role]['protected_C_tables']==receipt['after'][role]['protected_C_tables']
                                            and receipt['before'][role]['protected_C_checksums']==receipt['after'][role]['protected_C_checksums'] for role in ROLES)
        receipt['after_idle']=idle
        if not idle or not receipt['protected_C_unchanged']:code=125
    except Exception as error:receipt['error']=str(error).replace(password,'[REDACTED]');raise
    finally:
        receipt['exit_code']=code;write_json(Path('slot-receipt.json'),receipt)
    raise SystemExit(code)

if __name__=='__main__':main()
