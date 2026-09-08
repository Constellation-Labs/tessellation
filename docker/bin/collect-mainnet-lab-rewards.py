#!/usr/bin/env python3
"""Read-only reward/epoch capture on the fixed isolated lab bridge, never public IPs."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--seconds", type=int, default=1800)
    args = parser.parse_args()
    out = Path(args.output).resolve()
    events_file = out / "events.json"
    events = json.loads(events_file.read_text())
    isolation = next(e for e in events if e["kind"] == "isolation")["network"][0]
    if not isolation["Internal"] or isolation["IPAM"]["Config"][0]["Subnet"] != "172.30.194.0/24":
        raise ValueError("requires recorded isolated lab network")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    seen = set()
    failures = Counter()
    rows = 0
    until = time.monotonic() + min(args.seconds, 2400)
    with (out / "reward-observations.jsonl").open("x") as output:
        while time.monotonic() < until:
            for node in range(3):
                try:
                    request = urllib.request.Request(
                        f"http://172.30.194.{10+node}:9000/global-snapshots/latest",
                        headers={"Accept": "application/json"})
                    with opener.open(request, timeout=2) as response:
                        snapshot = json.load(response)
                    value = snapshot["value"]
                    digest = hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
                    key = (node, value["ordinal"], digest)
                    if key in seen:
                        continue
                    row = dict(time=time.time(), node=node, ordinal=value["ordinal"], epoch=value["epochProgress"],
                               value_digest=digest, reward_count=len(value["rewards"]),
                               reward_total_atomic=sum(r["amount"] for r in value["rewards"]),
                               rewards=value["rewards"], signer_count=len(snapshot["proofs"]))
                    output.write(json.dumps(row) + "\n")
                    output.flush()
                    seen.add(key)
                    rows += 1
                except (OSError, ValueError, KeyError, TypeError) as error:
                    failures[type(error).__name__] += 1
            try:
                events = json.loads(events_file.read_text())
                if any(e["kind"] in ("completed", "body_fault_failed") for e in events):
                    break
            except (OSError, ValueError):
                pass
            time.sleep(2)
    summary = dict(rows=rows, failures=dict(failures), capture_available=rows > 0)
    (out / "reward-capture-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary), flush=True)
    if rows == 0:
        raise SystemExit("No rewards captured; this is missing evidence, not zero rewards.")


if __name__ == "__main__":
    main()
