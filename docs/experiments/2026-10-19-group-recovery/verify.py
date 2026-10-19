#!/usr/bin/env python3
"""Independent artifact verification; no production Scala helpers or running database required."""
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parent

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

def sha(value):
    return hashlib.sha256(value.encode()).hexdigest()

def checkpoint(state):
    return [e["checkpoint"] for e in state["journal"] if e["checkpoint"] is not None][-1]

def compare(evidence):
    comparison = evidence["comparison"]
    left = {row["id"]: row for row in comparison["source"]}
    right = {row["id"]: row for row in comparison["target"]}
    assert len(left) == len(comparison["source"]) == 64
    assert len(right) == len(comparison["target"]) == 64
    assert left == right, "independent full-row comparison failed"
    assert comparison["equal"] and not comparison["fieldDifferences"]
    assert not comparison["missingKeys"] and not comparison["extraKeys"]
    assert set(comparison["comparedFields"]) == {"id", "label", "amount", "note", "updated_at", "revision"}
    assert all(len(row) == 6 for row in left.values())

def verify():
    result = []
    with tarfile.open(ROOT / "evidence.tar.gz", "r:gz") as archive:
        def read(name):
            return archive.extractfile(name).read()
        def load(name):
            return json.loads(read(name))
        manifest = load("manifest.json")
        for name, digest in manifest["files"].items():
            assert hashlib.sha256(read(name)).hexdigest() == digest, name
        for scenario in ("split-gap", "merge-gap", "pk-gap"):
            def data(name):
                return load(f"{scenario}/{name}.json")
            baseline, cut, after, final = (data(n) for n in ("baseline", "T-committed-J-missing", "after-replay", "final"))
            replay, env = data("replay"), data("environment")
            compare(baseline); compare(after); compare(final)
            for state in (baseline, cut, after, final):
                assert checkpoint(state["J"]) in state["J"]["actorCheckpoints"]
            assert checkpoint(baseline["J"]) == checkpoint(cut["J"])
            assert baseline["D"] == cut["D"]
            assert len(cut["T"]) > len(baseline["T"])
            assert replay["cutBatch"]["batchId"] == replay["firstReplayBatch"]["batchId"]
            assert replay["originalPid"] != replay["recoveredPid"]
            assert checkpoint(final["J"])["sequence"] > checkpoint(cut["J"])["sequence"]
            assert any(json.loads(r["offset_val"]) == checkpoint(final["J"])["cursor"]["offset"] for r in final["D"])
            counts = (1, 1) if scenario == "pk-gap" else ((16, 1) if scenario == "split-gap" else (1, 72))
            assert (len(replay["cutBatch"]["rows"]), len(replay["firstReplayBatch"]["rows"])) == counts
            assert len(final["audit"]) == (69 if scenario == "pk-gap" else 140)
            assert len(after["audit"]) == (68 if scenario == "pk-gap" else 139)
            assert after["audit"][:len(cut["audit"])] == cut["audit"]
            if scenario == "pk-gap":
                assert after["audit"] == cut["audit"]
            added = after["audit"][len(cut["audit"]):]
            for group in replay["cutBatch"]["rows"]:
                for member in group["members"]:
                    e = member["envelope"]
                    image = e["before"] if e["op"] == "d" else e["after"]
                    assert not any(a["row_id"] == str(image["id"]) and
                        a["old_label" if e["op"] == "d" else "new_label"] == image["label"] for a in added)
            observed = {}
            processes = {}
            for process in ("original", "recovered"):
                events = [json.loads(line[7:]) for line in read(f"{scenario}/{process}.log").decode().splitlines() if line.startswith("WINDOW ")]
                processes[process] = events
                callbacks = [e for e in events if e["kind"] == "RAW_CALLBACK"]
                acked = [e["callback"] for e in events if e["kind"] == "RAW_ACK_RETURNED"]
                assert acked == list(range(len(acked))), "original callback ACK is not a consecutive prefix"
                if process == "original":
                    assert not any(e["kind"] == "ACK_BEGIN" and e["batchId"] == replay["cutBatch"]["batchId"] for e in events)
                else:
                    assert len(acked) == len(callbacks)
                for callback in callbacks:
                    for record in callback["records"]:
                        e = record["normalizedEnvelope"]
                        assert e is not None and not e["source"]["snapshot"]
                        headers = {h["key"]: h["value"] for h in record["headers"]}
                        role = ("pk." if "__debezium.newkey" in headers or "__debezium.oldkey" in headers else "single.") + e["op"]
                        s = e["source"]
                        protocol = {"version": 1, "replicationId": env["connector"], "generation": "initialized-v1", "sourceLineage": env["lineage"]}
                        gid = sha(canonical([protocol, s["database"], s["table"], s["file"], s["position"], s["row"]]))
                        key = (gid, role)
                        if key in observed:
                            assert observed[key] == e, "same physical member changed normalized content on recovery"
                        observed[key] = e
            ledger_members = {}
            for row in final["T"]:
                proof = json.loads(row["evidence"])
                g = proof["group"]
                assert row["group_id"] == g["groupId"]
                roles = [m["role"] for m in g["members"]]
                assert roles == ["pk.d", "pk.c"] or (len(roles) == 1 and roles[0] in ("single.c", "single.u", "single.d"))
                for m in g["members"]:
                    key = (g["groupId"], m["role"])
                    assert key not in ledger_members
                    assert m["eventId"] == sha(canonical([g["groupId"], m["role"]]))
                    ledger_members[key] = m["envelope"]
            assert ledger_members == observed, "target group evidence differs from unique raw SourceRecord members"
            assert len(final["T"]) == (3 if scenario == "pk-gap" else 74)
            if scenario != "pk-gap":
                repeated = [(k, e) for k, e in observed.items() if e.get("after") and e["after"]["label"] == "repeat-b"]
                assert len(repeated) == 2 and repeated[0][0] != repeated[1][0]
                assert repeated[0][1]["before"] == repeated[1][1]["before"]
                assert repeated[0][1]["after"] == repeated[1][1]["after"]
            else:
                events = processes["recovered"]
                pair_callbacks = [e for e in events if e["kind"] == "RAW_CALLBACK"][:2]
                assert [[r["value"]["op"] for r in c["records"]] for c in pair_callbacks] == [["d"], ["c"]]
                d, c = (cb["records"][0] for cb in pair_callbacks)
                assert d["sourceOffset"] == c["sourceOffset"]
                index = lambda kind, cb: next(i for i,e in enumerate(events) if e["kind"] == kind and e.get("callback") == cb)
                assert index("RAW_CALLBACK_RETURNED", 0) < index("RAW_CALLBACK", 1) < index("RAW_ACK_BEGIN", 0)
                committed = next(i for i,e in enumerate(events) if e["kind"] == "SINK_COMMITTED")
                ack_begin = next(i for i,e in enumerate(events) if e["kind"] == "ACK_BEGIN")
                assert committed < ack_begin < index("RAW_ACK_BEGIN", 0) < index("RAW_ACK_BEGIN", 1)
            result.append({"scenario": scenario, "cutGroups": counts[0], "firstReplayGroups": counts[1],
                "cutJSequence": checkpoint(cut["J"])["sequence"], "finalJSequence": checkpoint(final["J"])["sequence"],
                "finalTargetGroups": len(final["T"]), "finalAuditRows": len(final["audit"]),
                "comparedRows": 64, "comparedFields": 384, "missingKeys": 0, "extraKeys": 0,
                "fieldDifferences": 0, "replayedCutEffects": 0, "status": "PASS"})
    return {"status": "PASS", "scenarios": result,
        "scope": "real MySQL / Actor JDBC Journal / production Source and Sink; fixed lineage and schema, single writer, trusted streaming baseline"}

if __name__ == "__main__":
    print(json.dumps(verify(), ensure_ascii=False, indent=2))
