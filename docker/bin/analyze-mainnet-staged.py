#!/usr/bin/env python3
"""Read retained native evidence; report failed gates rather than hiding them."""
import argparse
import hashlib
import json
import pathlib

from mainnet_staged_fault import PHASES, parse_states, validate_round


def analyze(root):
    events = json.loads((root / "events.json").read_text())
    peers = json.loads((root / "peers.json").read_text())
    ids = [p["id"] for p in peers]
    samples = [json.loads(line) for line in (root / "samples.jsonl").read_text().splitlines()]
    plan = next((e for e in events if e["kind"] == "staged_fault_planned"), None)
    report = dict(status="incomplete", gates={}, conflicts=[], nodes=[], controls=[],
                  limitations=["Controlled injected faults, not the historical Mainnet root cause",
                               "Five development nodes, not Mainnet-scale qualification",
                               "Value-digest agreement is not an independent cryptographic audit"])
    if plan is None:
        report["reason"] = "No staged fault was reached"
        return report
    ordinal = report["fault_ordinal"] = plan["fault_ordinal"]
    all_logs = sorted(root.glob("mr-stock-*.log"))
    report["fork_recovery_observations"] = []
    for node in range(5):
        path = next((p for p in all_logs if p.name.endswith(f"-{node}.log")), None)
        if path:
            lines = path.read_text().splitlines()
            guards = [line.split(" ", 1)[0] for line in lines if "Different hash observations" in line]
            report["fork_recovery_observations"].append(dict(node=node, guard_timestamps=guards))
    report["last_sample_node_states"] = samples[-1].get("node_states") if samples else None
    for node in (0, 1):
        path = next((p for p in all_logs if p.name.endswith(f"-{node}.log")), None)
        if path is None:
            report["nodes"].append(dict(node=node, error="retained log missing"))
            continue
        rows = parse_states(path.read_text())
        selected = [r for r in rows if r["ordinal"] == ordinal]
        try:
            report["nodes"].append(dict(node=node, **validate_round(selected, ids)))
        except ValueError as error:
            report["nodes"].append(dict(node=node, error=str(error), records=selected))
        for control in (ordinal-2, ordinal-1):
            control_rows = [r for r in rows if r["ordinal"] == control]
            starts = [r for r in control_rows if r["phase"] == PHASES[0]]
            finishes = [r for r in control_rows if r["phase"] == PHASES[-1]]
            valid = bool(starts and finishes and all(r["count"] == 5 and not r["removed"]
                         and not r["withdrawn"] and r["lock"] == "Open" for r in control_rows))
            report["controls"].append(dict(node=node, ordinal=control, healthy=valid,
                seconds=finishes[0]["time"]-starts[0]["time"] if starts and finishes else None))
    seen, accepted = {}, {0: [], 1: []}
    for row in samples:
        for node, tip in enumerate(row["tips"]):
            if not tip:
                continue
            key = tip["ordinal"]
            seen.setdefault(key, set()).add(tip["digest"])
            if node in accepted and key == ordinal:
                accepted[node].append(tip)
    report["conflicts"] = [key for key, digests in seen.items() if len(digests) > 1]
    expected_signers = set(ids[:2])
    report["accepted_fault_snapshot"] = {node: rows[0] if rows else None for node, rows in accepted.items()}
    report["gates"] = dict(
        completed=any(e["kind"] == "completed" for e in events),
        no_controller_failure=not any(e["kind"] in ("staged_fault_failed", "restoration_failed") for e in events),
        native_phase_sequence=len(report["nodes"]) == 2 and all("error" not in n for n in report["nodes"]),
        healthy_controls=len(report["controls"]) == 4 and all(c["healthy"] for c in report["controls"]),
        fault_snapshot_accepted_by_both=all(accepted.values()),
        fault_snapshot_signers_match=all(accepted.values()) and all(set(t["signers"]) == expected_signers
                                                        for rows in accepted.values() for t in rows),
        no_observed_same_ordinal_value_conflicts=not report["conflicts"],
        no_healthy_node_fork_guard=not any(n["guard_timestamps"] for n in report["fork_recovery_observations"]
                                         if n["node"] in (0, 1)),
        post_fault_progress=any(e["kind"] == "post_fault_rounds_observed" for e in events),
        faults_restored=any(e["kind"] == "faults_restored" for e in events))
    report["status"] = "pass" if all(report["gates"].values()) else "failed_or_incomplete"
    report["evidence_sha256"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in [root/"events.json", root/"samples.jsonl", *all_logs]}
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=pathlib.Path)
    args = parser.parse_args()
    report = analyze(args.evidence)
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["status"] == "pass" else 1)
