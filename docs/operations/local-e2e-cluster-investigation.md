# Investigating a local E2E cluster run

How to drive and debug the docker E2E rig on your workstation. Written from the
`committee-rewards` diagnosis on 2026-07-30, where the test's own criteria -- not the node -- were
wrong, and the only way to tell was querying the live cluster.

## 1. The cluster survives a failed test

`just test` does **not** tear the cluster down when a test fails. That is the single most useful
property of the local rig: the failing state is still running and queryable.

```bash
docker ps --format '{{.Names}}\t{{.Status}}'     # gl0-0..N, gl1-0..N, ml0/cl1/dl1 if metagraph
curl -s localhost:9000/global-snapshots/latest | jq '.value.ordinal, .value.epochProgress'
```

Do **not** run `just down` until you have finished investigating. When you are done:

```bash
just down --clean     # also wipes nodes/*/data
```

## 2. Port and path map

Node `i` of each layer is reachable on the host at `<prefix><i>0`:

| layer | prefix | node i public API | p2p | CLI (join) | logs |
|---|---|---|---|---|---|
| gl0 (global L0) | 90 | `90<i>0` | `90<i>1` | `90<i>2` | `nodes/<i>/gl0-logs/` |
| gl1 (DAG L1) | 91 | `91<i>0` | `91<i>1` | `91<i>2` | `nodes/<i>/gl1-logs/` |
| ml0 (metagraph L0) | 92 | `92<i>0` | ... | ... | `nodes/<i>/ml0-logs/` |
| cl1 / dl1 | 93 / 94 | `93<i>0` / `94<i>0` | ... | ... | `nodes/<i>/{cl1,dl1}-logs/` |

Container IPs are `172.32.0.1<i>`. Container names are `gl0-<i>` etc. Key material for node `i` is
`nodes/<i>/{peer_id,address,id_ecdsa.hex}` -- this is the authoritative peer-id-to-address mapping
for the running cluster, synced from `docker/config/local-test-keys/` by
`docker/bin/node-key-env-setup.sh`.

## 3. First three commands on any failure

```bash
# 1. is the chain advancing at all?
for i in 0 1 2 3 4; do
  printf "gl0-%s " "$i"
  curl -s --max-time 3 "localhost:90${i}0/global-snapshots/latest" | jq -c '.value.ordinal' 2>/dev/null || echo "no answer"
done

# 2. what does each node think its own state is (trust this over cluster/info labels)?
for i in 0 1 2 3 4; do printf "gl0-%s " "$i"; curl -s --max-time 3 "localhost:90${i}0/node/info" | jq -r '.state'; done

# 3. errors in the last few minutes, per node
for i in 0 1 2 3 4; do echo "== gl0-$i"; docker logs --tail 400 "gl0-$i" 2>&1 | grep -c ERROR; done
```

`cluster/info` peer labels lag a node's self-reported `node/info` by minutes -- the same caveat as
production (see the `node-ops` skill). Trust `node/info`.

## 4. Committee and reward state per ordinal

The signed artifact carries everything needed to reconstruct a round; no debug endpoint required.

```bash
O=66
curl -s "localhost:9000/global-snapshots/$O"      | jq '.value | {ordinal, epochProgress, rewards}'
curl -s "localhost:9000/global-snapshots/$((O+1))" \
  | jq ".value.peerHistory.controllerEvidence[\"$O\"]"     # committee FOR ordinal O
curl -s "localhost:9000/global-snapshots/$O"      | jq '.value.peerHistory.recentSigners | keys'
```

Two off-by-ones that will mislead you if you forget them:

- **Committee for ordinal N lives in snapshot N+1** (`controllerEvidence[N].roundStartFacilitators`).
  Snapshot N's own `peerHistory` is the outcome as of round N's *proposal*, i.e. entries up to N-1.
- **TimeTrigger vs EventTrigger** is not serialized. Snapshot N was time-triggered iff
  `epochProgress[N] > epochProgress[N-1]`. Static validator rewards only mint on TimeTrigger rounds,
  so an ordinal with `rewards: []` is usually an EventTrigger round, not a bug.

Per-peer tier is **not** available over HTTP (`peerHistory.perPeer` is deliberately blanked). Derive
it: a seated peer absent from the last `TierTransitions.DemotionConsecutiveMisses` (3) entries of
`recentSigners` is Tier 1; otherwise Core. For committee sizes as the node saw them, grep the log:

```bash
docker logs gl0-0 2>&1 | grep FACILITATORS_FINALIZED | tail -5
docker logs gl0-0 2>&1 | grep -o 'core=[0-9]* tier1=[0-9]* witness=[0-9]*' | tail -5
curl -s localhost:9000/metrics | grep -E 'dag_consensus_committee_(core_size|tier_size|core_floor)'
```

Controller scores are not exposed either; replicate `ControllerEvidenceDerivation`:
`+20` per entry the peer signed, `-15` per entry it was seated but did not sign, `+10` per
`admittedPeers`/`timeoutVoters` appearance, clamped `[0,150]`, over
`snapshot[N].peerHistory.controllerEvidence`.

## 5. Write a throwaway replication script, do not eyeball JSON

The decisive move in the committee-rewards diagnosis was a ~30-line script that fetched a window of
snapshots once and printed, per ordinal, exactly what the test computes: committee, per-peer score,
recent-signer membership, reward count. That turned "the test says no valid sample exists" into "at
ordinal 20 a genesis peer had dipped to score 60, so my pool proxy rejected a perfectly good sample"
in one pass. Template:

```javascript
const axios = require('.github/action_scripts/node_modules/axios')
const get = async (u) => (await axios.get(u, { headers: { 'Cache-Control': 'no-cache' } })).data
const head = (await get('http://localhost:9000/global-snapshots/latest')).value.ordinal
const snaps = {}
for (let o = head - 70; o <= head; o++) { try { snaps[o] = await get(`http://localhost:9000/global-snapshots/${o}`) } catch {} }
// then compute per ordinal and print one line each
```

Fetch the window **once** into a map and reuse it; fetching per-check hammers a node that is also
trying to run consensus. The local rig disables the per-IP snapshot cap
(`CL_SNAPSHOT_PER_IP_MAX_REQUESTS_PER_WINDOW=0`), so this is safe locally but would 429 against a
real network, which caps at 120 req/min.

## 6. Logs

Two sources, and they are not equivalent:

- `docker logs gl0-<i>` -- stdout of the current container. Lost on `docker rm`, survives a test
  failure. Fastest for "what just happened".
- `nodes/<i>/gl0-logs/` -- the mounted log directory (`app.log`, `http.log`, `gossip.log` and
  `archived/*.gz`). Survives container removal. Note `app.log` rotates **by size**, so a long run
  needs `archived/` too.

Useful greps:

```bash
docker logs gl0-0 2>&1 | grep -E 'FACILITATORS_FINALIZED|ROUND_COMPLETED|ACCEPTANCE.*REWARDS'
docker logs gl0-0 2>&1 | grep -A15 'Unhandled error'          # stack traces
grep -c 'InvalidChain' nodes/*/gl0-logs/app.log
docker logs gl0-3 2>&1 | grep -E 'cluster/join|ReadyToJoin|Observing|state changed'   # late joiners
```

## 7. Re-running a test against the SAME cluster

You rarely need to rebuild the cluster to retry a *test script*. The JS tests are stateless clients:

```bash
cd .github/action_scripts
NUM_GL0_NODES=5 NUM_GL0_EARLY=3 node committee_rewards.js 90 91
```

This is the fastest iteration loop there is -- seconds per attempt against a real cluster, versus
~15 minutes for a full `just test`. Env knobs the scripts honor are documented in each script's
header (e.g. `COMMITTEE_REWARDS_SCAN_DEPTH`, `GL0_URL`, `TEST_HOST`).

## 8. Rig knobs worth knowing

| knob | effect |
|---|---|
| `--num-gl0=N` | gl0 node count (`MAX_NODES` caps at 9) |
| `--num-gl0-early=K` | nodes `>= K` delay their self-join (staggered-join rig) |
| `--gl0-late-delay=S` | that delay in seconds, default 240 |
| `--skip-assembly` | reuse `docker/jars/`, skips the ~10 min sbt assembly |
| `--test=a,b` | run named tests only; also skips metagraph setup unless a metagraph test is named |
| `CL_TIME_TRIGGER_INTERVAL="15 seconds"` | snapshot cadence (local default 43s); forwarded by `docker-compose.test.yaml` |

Only a handful of `CL_*` vars are forwarded into containers (`CL_TIME_TRIGGER_INTERVAL`,
`CL_DECLARATION_TIMEOUT`, `CL_EVENT_TRIGGER_COOLDOWN`, `CL_MAX_ROUND_DURATION`,
`CL_RE_STALL_TIMEOUT`, ...). Anything else needs `--env=<file>` (`EXTRA_ENV_PATH`), and
consensus-critical values must be identical on every node because they are folded into
`deterministicConfigHash`.

## 9. Things that will waste your time

- **Config values without a `CL_` override.** `core-committee-size`, `active-facilitator-target`,
  `active-facilitator-max` and the probation slots are per-environment maps with the env overrides
  deliberately removed. Changing them means editing `dag-l0.conf` and **rebuilding the jar** (drop
  `--skip-assembly`).
- **Scores are noisy on a loaded box.** A `-15` miss penalty per unsigned round means peers dip
  below thresholds routinely on a laptop. Any test criterion that needs several peers above a
  threshold *simultaneously* will be flaky locally. Prefer criteria the node itself uses.
- **`docker logs --since` can error** with a log-driver quirk; use `--tail N`.
- **`nodes/` is gitignored** -- safe to inspect and fabricate, and wiped by `just down --clean`.
