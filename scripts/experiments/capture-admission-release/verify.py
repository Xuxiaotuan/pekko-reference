"""Offline evidence summary; candidate signals are not production readiness or HA proof."""
import json
from pathlib import Path


def summarize(events, resource_observations):
    def unwrap(value):
        return value.get('offset', value)

    def complete(offset):
        value = unwrap(offset)
        snapshot = value.get('snapshot', False)
        return 'file' in value and 'pos' in value and (snapshot is False or snapshot == 'false')

    def key(value):
        return json.dumps({k: v for k, v in unwrap(value).items()
                           if k in ('file', 'pos', 'event', 'row', 'gtids', 'snapshot')}, sort_keys=True)

    end = next((i for i, e in enumerate(events) if e.get('kind') == 'QUIET_END'), len(events) - 1)
    quiet = events[:end + 1]
    durable = any(e.get('kind') == 'D_VERIFIED' and complete(e.get('D', {})) for e in quiet)
    fresh = any(e.get('kind') == 'ORIGINAL_RECORD' and complete(e.get('offset', {})) for e in quiet)
    completed = any(e.get('kind') == 'QUIET_OBSERVATION' and any(
        m.get('attributes', {}).get('SnapshotCompleted') is True for m in e.get('metrics', [])) for e in quiet)
    skipped = any(e.get('kind') == 'QUIET_OBSERVATION' and any(
        m.get('attributes', {}).get('SnapshotSkipped') is True for m in e.get('metrics', [])) for e in quiet)
    callbacks, acknowledged, sdk_acknowledged = {}, set(), set()
    linked, sdk_linked, last_sdk_offset = False, False, None
    for e in quiet:
        kind = e.get('kind')
        if kind == 'ORIGINAL_RECORD':
            last_sdk_offset = e.get('offset', {})
        elif kind == 'SDK_COMPONENT_ACK_RETURNED' and last_sdk_offset is not None and complete(last_sdk_offset):
            sdk_acknowledged.add(key(last_sdk_offset))
        elif kind == 'SDK_D_OBSERVED' and complete(e.get('D', {})):
            sdk_linked = sdk_linked or key(e['D']) in sdk_acknowledged
        if kind == 'RAW_CALLBACK':
            callbacks[e['id']] = e.get('offset', {})
        elif kind == 'RAW_ACK_RETURNED' and e.get('id') in callbacks and complete(callbacks[e['id']]):
            acknowledged.add(key(callbacks[e['id']]))
        elif kind == 'D_VERIFIED' and complete(e.get('D', {})):
            linked = linked or key(e['D']) in acknowledged
    attempts = {e['attempt'] for e in quiet if 'attempt' in e}
    capture = next((e.get('captureId') for e in quiet if e.get('kind') == 'BEFORE_START'), None)
    partition_valid = bool(capture and all(
        e.get('partition') == {'server': capture} for e in quiet if e.get('kind') == 'ORIGINAL_RECORD')
        and all(e.get('offset_key') == [capture, {'server': capture}] for e in quiet
                if e.get('kind') in ('D_VERIFIED', 'SDK_D_OBSERVED')))
    admission = durable and fresh and linked and partition_valid and (completed or skipped) and len(attempts) == 1
    ends = [e for e in events if e.get('kind') == 'LOCAL_END']
    local_end_ns = ends[-1].get('epoch_ns') if ends else None
    contexts = []
    for observation in resource_observations:
        try:
            contexts.append({role: (observation['instances'][role]['identity'].splitlines()[0],
                                   int(observation['instances'][role]['identity'].splitlines()[1].split('\t')[1]))
                             for role in ('source', 'state')})
        except (KeyError, IndexError, ValueError):
            contexts.append(None)
    consistent = bool(contexts and all(c is not None for c in contexts))
    if consistent:
        consistent = all(contexts[i][r][0] == contexts[0][r][0]
                         and (i == 0 or contexts[i][r][1] >= contexts[i-1][r][1])
                         for i in range(len(contexts)) for r in ('source', 'state'))
    released = bool(consistent and local_end_ns is not None and resource_observations
                    and resource_observations[-1].get('idle') is True
                    and resource_observations[-1].get('observed_ns', 0) >= local_end_ns)
    local = bool(ends and ends[-1].get('completionCompleted') is True and ends[-1].get('completionOutcome') == 'success' and ends[-1].get('threads') == []
                 and any(e.get('kind') == 'CLOSE_RETURNED' for e in events)
                 and not any(e.get('kind') in ('CLOSE_WAIT_UNKNOWN', 'CLOSE_FAILED') for e in events))
    witness = next((e for e in events if e.get('kind') == 'WITNESS'), None)
    equal, missing, extra, differences = None, [], [], 0
    if witness:
        left = {r['id']: r for r in witness['source']}
        right = {r['id']: r for r in witness['target']}
        missing, extra = sorted(left.keys() - right.keys()), sorted(right.keys() - left.keys())
        differences = sum(left[k].get(f) != right[k].get(f) for k in left.keys() & right.keys()
                          for f in left[k].keys() | right[k].keys())
        equal = not missing and not extra and not differences and len(left) == len(witness['source']) and len(right) == len(witness['target'])
    return dict(durable_non_initial=durable, current_non_initial_callback=fresh,
                current_acknowledged_d=linked, snapshot_completed_observed=completed,
                unique_legal_partition_observed=partition_valid,
                snapshot_skipped_observed=skipped, admission_candidate=admission,
                sdk_current_acknowledged_d_without_L=sdk_linked,
                local_release_confirmed=local, observed_instance_context_consistent=consistent,
                server_release_confirmed=released, reusable_candidate=released and local,
                local_end=ends[-1] if ends else None, witness_equal=equal,
                witness_missing_keys=missing, witness_extra_keys=extra, witness_different_fields=differences)


def check_expected(evidence, events, positive=False, witness=False, sdk_only=False):
    failures = []
    sdk_candidate = (evidence.get('sdk_current_acknowledged_d_without_L') and evidence.get('unique_legal_partition_observed')
                     and (evidence.get('snapshot_completed_observed') or evidence.get('snapshot_skipped_observed')))
    if positive and not (sdk_candidate if sdk_only else evidence.get('admission_candidate')):
        failures.append('missing_current_attempt_admission_evidence')
    if witness and evidence.get('witness_equal') is not True:
        failures.append('missing_or_unequal_witness')
    if not evidence.get('reusable_candidate'):
        failures.append('release_unknown')
    if any(e.get('kind') == 'QUIET_END' and e.get('captureFailure') is not None for e in events):
        failures.append('capture_failed')
    if any((e.get('kind') == 'ENGINE_COMPLETION' and (e.get('success') is not True or e.get('errorKind') is not None))
           or (e.get('kind') in ('QUIET_END', 'LOCAL_END') and e.get('completionOutcome') == 'failed') for e in events):
        failures.append('engine_failed')
    start = next((e for e in events if e.get('kind') == 'QUIET_SOURCE_START'), None)
    end = next((e for e in events if e.get('kind') == 'QUIET_END'), None)
    if start and end and (start.get('rows') != end.get('sourceRows') or start.get('status') != end.get('sourceStatus')):
        failures.append('quiet_source_changed')
    return failures


def read_events(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]


def ownership_summary(events, observations):
    """Exclusive-slot observation only; no PID-only isolation or production fencing claim."""
    start = next((e for e in events if e.get('kind') == 'BEFORE_START'), {})
    pid = start.get('pid')
    app = {str(row['connection_id']) for e in events if e.get('kind') == 'APPLICATION_SOURCE_OPEN'
           for row in e.get('identity', [])}
    mapped = {'source': set(), 'state': set()}
    dumps, jdbc, owners = set(), set(), set()
    for observation in observations:
        sockets = observation.get('worker_sockets', {})
        trusted = pid is not None and sockets.get('pid') == pid and sockets.get('parent_owned_and_alive') is True and sockets.get('exit_code') == 0
        for role, instance in observation.get('instances', {}).items():
            owner = (instance.get('owner_lock_connection') or '').strip()
            if role == 'state' and owner and owner != 'NULL':
                owners.add(owner)
            for item in instance.get('socket_mappings', []):
                connection = item['connection_id']
                if trusted and item.get('worker_socket_match') is True:
                    mapped[role].add(connection)
                if role == 'source':
                    if item['command'].startswith('Binlog Dump'):
                        dumps.add(connection)
                    elif item['command'] == 'Sleep' and connection not in app:
                        jdbc.add(connection)
    return {'scope': 'exclusive slot and trusted live Popen worker; not HA isolation proof',
            'dump_connections_observed': sorted(dumps), 'dump_connections_mapped': sorted(dumps & mapped['source']),
            'source_jdbc_connections_mapped': sorted(jdbc & mapped['source']),
            'owner_connections_observed': sorted(owners), 'owner_connections_mapped': sorted(owners & mapped['state']),
            'dump_mapping_complete': bool(dumps) and dumps <= mapped['source'],
            'owner_mapping_complete': bool(owners) and owners <= mapped['state']}
