#!/usr/bin/env python3
"""Check the observed consensus/epoch effect, not just HTTP error counts."""
import argparse
import hashlib
import json
from pathlib import Path


def analyze(output):
    root = Path(output)
    events = json.loads((root / "events.json").read_text())
    samples = [json.loads(line) for line in (root / "samples.jsonl").read_text().splitlines()]

    def event(kind):
        found = [row for row in events if row["kind"] == kind]
        if len(found) != 1:
            raise ValueError(f"expected exactly one {kind}, found {len(found)}")
        return found[0]

    identity = event("identity")
    start = event("body_fault_installed")["time"]
    observation = event("body_fault_observed")
    end = observation["time"]
    recovery = event("post_fault_rounds_observed")
    event("completed")
    restored = event("body_fault_restored")
    target_ordinal = restored["ordinal"]
    event("five_ready")
    if end - start < 209:
        raise ValueError("fault observation shorter than required interval")
    if any(row["kind"] == "body_fault_failed" for row in events):
        raise ValueError("controller failed")
    mode = identity["mode"]
    if observation["finished"] != [mode == "fixed"] * 3:
        raise ValueError("snapshot completion does not match the control/candidate gate")
    recovery_qualified = False
    if "restoration_baseline" in recovery:
        baseline = event("post_restoration_baseline")
        tips = recovery.get("tips", [])[:3]
        if (baseline["time"] < restored["time"] or baseline["ordinal"] != recovery["restoration_baseline"]
                or recovery["count"] < 3 or len(tips) != 3
                or not all(t and t["ordinal"] >= baseline["ordinal"] + recovery["count"] for t in tips)
                or len({(t["ordinal"], t["digest"]) for t in tips}) != 1):
            raise ValueError("invalid post-restoration qualification")
        recovery_qualified = True

    values = {}
    for row in samples:
        for tip in row["tips"]:
            if tip:
                values.setdefault(tip["ordinal"], set()).add(tip["digest"])
    conflicts = sorted(ordinal for ordinal, hashes in values.items() if len(hashes) > 1)
    if conflicts:
        raise ValueError(f"observed snapshot-value conflict at {conflicts}")

    progress = []
    for node in range(3):
        # A periodic sample may first observe the already-finished warm-up round
        # just after injection. Anchor by that exact ordinal, not sample timing.
        before = [s["tips"][node] for s in samples if s["time"] <= end and s["tips"][node]
                  and s["tips"][node]["ordinal"] == target_ordinal - 1]
        during = [s["tips"][node] for s in samples if start < s["time"] <= end and s["tips"][node]]
        if not before or not during:
            raise ValueError("missing progress samples")
        first, last = before[-1], during[-1]
        progress.append(dict(node=node, start_ordinal=first["ordinal"], end_ordinal=last["ordinal"],
                             timed_epoch_increase=last["epoch"]-first["epoch"]))
    if mode == "fixed" and not all(p["timed_epoch_increase"] > 0 for p in progress):
        raise ValueError("candidate did not demonstrate timed epoch progress during fault")
    if mode == "stock" and any(p["timed_epoch_increase"] != 0 for p in progress):
        raise ValueError("stock progressed during fault; starvation claim is not supported")

    reward_file = root / "reward-observations.jsonl"
    reward_rows = [json.loads(line) for line in reward_file.read_text().splitlines()] if reward_file.exists() else []
    captured_rewards = {}
    for row in reward_rows:
        if row["node"] == 0 and start < row["time"] <= end and row["ordinal"] > progress[0]["start_ordinal"]:
            captured_rewards[row["ordinal"]] = row["reward_total_atomic"]
    # Capture may miss intermediate snapshots. Report observed amounts, never an
    # extrapolated daily return or compensation due to any public operator.
    round_seconds = []
    for records in observation.get("records", []):
        starts = [r["time"] for r in records if r["phase"] == "CollectingFacilities" and r["lock"] == "Open"]
        finishes = [r["time"] for r in records if r["phase"] == "Finished"]
        round_seconds.append(min(finishes) - min(starts) if starts and finishes else None)
    report = dict(mode=mode, jar_sha256=identity["jar_sha256"], peer_gossip_workers=identity["peer_gossip_workers"],
                  fault_seconds=end-start, progress=progress, snapshot_value_conflicts=conflicts,
                  reward_capture_available=bool(reward_rows),
                  captured_reward_snapshots=len(captured_rewards) if reward_rows else None,
                  captured_reward_total_atomic=sum(captured_rewards.values()) if reward_rows else None,
                  captured_rewards_by_ordinal=captured_rewards,
                  affected_round_seconds_by_observer=round_seconds,
                  recovery_gate_required_advances=recovery["count"],
                  recovery_gate_anchor="after_restoration" if recovery_qualified else "affected_ordinal",
                  post_restoration_rounds_qualified=recovery_qualified,
                  checks_passed=True)
    report["evidence_sha256"] = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() if (root / name).exists() else None
                                for name in ("events.json", "samples.jsonl", "reward-observations.jsonl")}
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output")
    args = parser.parse_args()
    print(json.dumps(analyze(args.output), indent=2))
