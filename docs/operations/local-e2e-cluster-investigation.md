# Interrogating a live local E2E cluster

Companion to the log-analysis procedure (memory `reference_e2e_log_analysis` / the `debug-e2e-logs`
skill). That one covers **reading the persisted per-node logs** after a run -- where they live, the
peer-id rotation trap, and the parallel-subagent method. This one covers **querying the cluster
while it is still running**, which is a different and usually faster route to a root cause. Neither
restates the other.

Written from the `committee-rewards` diagnosis on 2026-07-30, where the test's own criteria -- not
the node -- were wrong, and only live queries could show it.

## The cluster survives a failed test

`just test` does **not** tear the cluster down when a test fails; verified with 8 containers still
`Up` after a failing run. The failing state is still running and queryable, so interrogate it before
touching anything.

```bash
docker ps --format '{{.Names}}\t{{.Status}}'
curl -s localhost:9000/global-snapshots/latest | jq '.value.ordinal, .value.epochProgress'
```

Do **not** run `just down` until you are finished. When done: `just down --clean` (also wipes
`nodes/*/data`).

## Port map

Node `i` of each layer is on the host at `<prefix><i>0`; the CLI/join port is `+2`.

| layer | prefix | public API | CLI (join) | container | IP |
|---|---|---|---|---|---|
| gl0 (global L0) | 90 | `90<i>0` | `90<i>2` | `gl0-<i>` | `172.32.0.1<i>` |
| gl1 (DAG L1) | 91 | `91<i>0` | `91<i>2` | `gl1-<i>` | same scheme |
| ml0 / cl1 / dl1 | 92 / 93 / 94 | `9x<i>0` | `9x<i>2` | `ml0-<i>` etc | same scheme |

`nodes/<i>/{peer_id,address}` is the authoritative peer-id-to-reward-address map for the running
cluster (synced by `docker/bin/node-key-env-setup.sh`). `nodes/` is gitignored.

## First three commands on any failure

```bash
# 1. is the chain advancing, and does every node agree?
for i in 0 1 2 3 4; do printf "gl0-%s " "$i"
  curl -s --max-time 3 "localhost:90${i}0/global-snapshots/latest" | jq -c '.value.ordinal' 2>/dev/null || echo "no answer"; done

# 2. each node's SELF-reported state (trust this over cluster/info labels, which lag by minutes)
for i in 0 1 2 3 4; do printf "gl0-%s " "$i"; curl -s --max-time 3 "localhost:90${i}0/node/info" | jq -r '.state'; done

# 3. error volume per node
for i in 0 1 2 3 4; do echo "gl0-$i $(docker logs --tail 400 "gl0-$i" 2>&1 | grep -c ERROR)"; done
```

## Reconstructing committee, tier and score for an ordinal

The signed artifact carries everything; no debug endpoint needed.

```bash
O=66
curl -s "localhost:9000/global-snapshots/$O"       | jq '.value | {ordinal, epochProgress, rewards}'
curl -s "localhost:9000/global-snapshots/$((O+1))" | jq ".value.peerHistory.controllerEvidence[\"$O\"]"
```

Two off-by-ones that will mislead you:

- **The committee for ordinal N lives in snapshot N+1** (`controllerEvidence[N].roundStartFacilitators`).
  Snapshot N's own `peerHistory` is the outcome as of round N's *proposal* -- entries up to N-1.
- **Trigger type is not serialized.** N was TimeTrigger iff `epochProgress[N] > epochProgress[N-1]`.
  Static validator rewards mint only on TimeTrigger rounds, so `rewards: []` is usually an
  EventTrigger round, not a bug.

Neither per-peer tier nor controller score is exposed over HTTP (`peerHistory.perPeer` is
deliberately blanked). Derive them the way the node does:

- **Tier**: a seated peer absent from the last `TierTransitions.DemotionConsecutiveMisses` (3)
  entries of `recentSigners` is Tier 1; otherwise Core. Note the admission pool uses a *wider*
  window (`effectiveRecentSignerWindow`) than this tier rule -- do not conflate them.
- **Score**: `+20` per entry the peer signed, `-15` per entry it was seated but did not sign, `+10`
  per `admittedPeers`/`timeoutVoters` appearance, clamped `[0,150]`, over
  `snapshot[N].peerHistory.controllerEvidence`.

Cross-check against the node's own view:

```bash
docker logs gl0-0 2>&1 | grep -o 'core=[0-9]* tier1=[0-9]* witness=[0-9]*' | tail -5
curl -s localhost:9000/metrics | grep -E 'dag_consensus_committee_(core_size|tier_size|core_floor)'
```

## Write a replication script; do not eyeball JSON

The decisive move in the committee-rewards diagnosis was ~30 lines that fetched a window of
snapshots **once** and printed, per ordinal, exactly what the failing test computes. That converted
"no valid sample exists" into "at ordinal 20 a genesis peer had dipped to score 60, so my pool proxy
rejected a good sample" in a single pass.

```javascript
const axios = require('.github/action_scripts/node_modules/axios')
const get = async (u) => (await axios.get(u, { headers: { 'Cache-Control': 'no-cache' } })).data
const head = (await get('http://localhost:9000/global-snapshots/latest')).value.ordinal
const snaps = {}
for (let o = head - 70; o <= head; o++) { try { snaps[o] = await get(`http://localhost:9000/global-snapshots/${o}`) } catch {} }
// then compute and print one line per ordinal
```

Fetch the window into a map once and reuse it. The local rig disables the per-IP snapshot cap
(`CL_SNAPSHOT_PER_IP_MAX_REQUESTS_PER_WINDOW=0`), so bulk fetching is safe here but would 429
against a real network (dag-l0 caps at 120 req/min).

## Re-run a test script against the SAME cluster

The JS tests are stateless clients, so you almost never need to rebuild to retry one. Seconds per
attempt instead of ~15 minutes for a full `just test`:

```bash
cd .github/action_scripts
NUM_GL0_NODES=5 NUM_GL0_EARLY=3 node committee_rewards.js 90 91
```

Env knobs are documented in each script's header (`COMMITTEE_REWARDS_SCAN_DEPTH`, `GL0_URL`,
`TEST_HOST`, ...).

## Rig knobs

| knob | effect |
|---|---|
| `--num-gl0=N` | gl0 node count (`MAX_NODES` caps at 9) |
| `--num-gl0-early=K` | nodes `>= K` delay their self-join (staggered-join rig) |
| `--gl0-late-delay=S` | that delay in seconds, default 240 |
| `--skip-assembly` | reuse `docker/jars/`, skips the ~10 min sbt assembly |
| `--test=a,b` | named tests only; also skips metagraph setup unless a metagraph test is named |
| `CL_TIME_TRIGGER_INTERVAL="15 seconds"` | snapshot cadence (local default 43s) |

Only a few `CL_*` vars are forwarded into containers by `docker-compose.test.yaml`
(`CL_TIME_TRIGGER_INTERVAL`, `CL_DECLARATION_TIMEOUT`, `CL_EVENT_TRIGGER_COOLDOWN`,
`CL_MAX_ROUND_DURATION`, `CL_RE_STALL_TIMEOUT`, ...). Anything else needs `--env=<file>`
(`EXTRA_ENV_PATH`), and consensus-critical values must be identical on every node because they fold
into `deterministicConfigHash`.

## Two traps specific to the local rig

- **Committee-sizing config has no `CL_` override by design** (`core-committee-size`,
  `active-facilitator-target`, `active-facilitator-max`, probation slots). Changing them means
  editing `dag-l0.conf` and **rebuilding the jar** -- drop `--skip-assembly`.
- **Scores are noisy on a laptop.** A `-15` penalty per unsigned round means peers cross thresholds
  constantly under load. Any test criterion requiring several peers above a threshold
  *simultaneously* is flaky here by construction; prefer criteria the node itself uses.
