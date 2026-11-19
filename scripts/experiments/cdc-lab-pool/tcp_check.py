#!/usr/bin/env python3
"""Verify the exact JDBC-facing TCP endpoint without exposing credentials."""
import argparse
import json
from pathlib import Path
import subprocess
from pool import Pool, ROLES, write_json


def check(pool, slot, token):
    pool.require(slot,token)
    results={}
    for role in ROLES:
        cfg=pool.slots[slot][role]
        args=['mysql','--defaults-file='+str(pool.instance_dir(slot,role)/'client.cnf'),
              '--protocol=TCP','--host=127.0.0.1','--port='+str(cfg['port']),'-N','-B','-e',
              'SELECT @@server_uuid,@@port,@@datadir,@@innodb_buffer_pool_size,@@max_connections']
        result=subprocess.run(args,capture_output=True,text=True,timeout=20)
        if result.returncode:raise RuntimeError(result.stderr.strip())
        row=result.stdout.strip().split('\t')
        if row[:2]!=[cfg['uuid'],str(cfg['port'])] or Path(row[2]).resolve()!=Path(cfg['datadir']).resolve():raise ValueError('TCP identity mismatch')
        if row[3:]!=[str(pool.config['mysql_buffer_pool_mb']*1024*1024),str(pool.config['mysql_max_connections'])]:raise ValueError('resource budget mismatch')
        results[role]={'uuid':row[0],'port':int(row[1]),'datadir':row[2],'buffer_pool_bytes':int(row[3]),'max_connections':int(row[4])}
    return results

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--config',type=Path,required=True);p.add_argument('--slot',required=True);p.add_argument('--token-file',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    report={}
    try:report={'status':'passed','slot':a.slot,'instances':check(Pool(a.config),a.slot,json.loads(a.token_file.read_text())['token'])}
    except BaseException as error:report={'status':'failed','error':repr(error)};raise
    finally:write_json(a.output,report)
