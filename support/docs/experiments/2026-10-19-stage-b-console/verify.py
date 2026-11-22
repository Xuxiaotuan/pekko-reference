#!/usr/bin/env python3
"""Verify captured B-stage HTTP/UI evidence, not a new database recovery run."""
from pathlib import Path
import hashlib,json,tarfile
root=Path(__file__).resolve().parent
with tarfile.open(root/'evidence.tar.gz','r:gz') as archive:
    def read(name):return archive.extractfile(name).read()
    manifest=json.loads(read('manifest.json'))
    for name,sha in manifest['files'].items():assert hashlib.sha256(read(name)).hexdigest()==sha,name
    for name in ('result.json','source-manifest.json','preservation.json','historical-checksums.json'):
        assert read(name)==(root/name).read_bytes(),name
    proxy=[json.loads(line) for line in read('checks/fault-proxy.jsonl').decode().splitlines()]
    executions=[r for r in proxy if '/execute?' in r['path']]
    assert executions[-2]['path']==executions[-1]['path']
    assert executions[-2]['upstreamStatus']==202 and executions[-2]['replyReplaced']
    assert executions[-1]['upstreamStatus']==409
    assert json.loads(executions[-2]['response'])['executionId']==json.loads(executions[-1]['response'])['executionId']
    api=json.loads(read('checks/live-api-records.json'))
    assert {201,202,400,404,409,503} <= {r['status'] for r in api}
    final=json.loads(read('checks/final-api-state.json'))
    assert len(final['/api/v1/workflows?page=0&pageSize=100']['body']['items'])==6
    assert final['/api/v1/schedules']['body']['schedules'][0]['enabled'] is True
    assert final['/api/v1/cluster/stats']['body']['sharding']['activeShards'] is None
    checks={r['check']:r['snapshot'] for r in json.loads(read('checks/browser-checks.json'))}
    expected={
        'full-page-followed-by-empty-page':'当前页没有工作流',
        'invalid-definition-400':'exactly one source',
        'definition-created-201':'定义已保存',
        'recent-history-real-terminal-and-unrecorded-fields':'未记录',
        'schedule-pause':'计划已暂停',
        'schedule-resume':'计划已恢复',
        'after-fix-status-refresh-retains-pending-id':'沿用 requestId 重试',
        'after-fix-history-navigation-retains-pending-id':'沿用 requestId 重试',
        'after-fix-navigation-retry-deduplicated':'本次请求已被接受',
        'legal-workflow-id-new-summary-not-import-page':'工作流摘要',
        'initial-list-503-is-unavailable-not-empty':'列表不可用',
        'refresh-list-503-retains-labelled-stale-results':'上次成功结果',
        'late-page-one-response-does-not-replace-latest-size-20-page-zero':'第 1 页',
        'health-503-is-unknown-not-online':'未知 / 不可用',
        'sql-deep-link-unavailable':'此入口尚不提供可验证的能力',
    }
    for name,text in expected.items():assert text in checks[name],name
    assert all(x['exitCode']==0 for x in json.loads(read('historical-checksums.json')))
print('PASS: captured HTTP/UI evidence is consistent; no new MySQL CDC recovery claim')
