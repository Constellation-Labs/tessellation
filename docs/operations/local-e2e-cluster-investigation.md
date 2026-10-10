# Interrogating a live local E2E cluster

Companion to the canonical [local Docker/E2E workflow](../../docker/README.md) and the log-analysis
procedure (memory `reference_e2e_log_analysis` / the `debug-e2e-logs` command). The workflow covers
build inputs, artifact locations, and exact cleanup semantics. The log procedure covers **reading
the persisted per-node logs** after a run, including the peer-id rotation trap. This document covers
**querying the cluster while it is still running**, which is a different and usually faster route
to a root cause.

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

Do **not** run `just down` until you have captured any needed `docker logs`. When done, use:

```bash
just down
```

This removes the scoped containers, named volumes, and network but preserves the host-mounted
`nodes/` data and application logs. If those are no longer needed, run `just clean-data` afterward
to remove only layer data/logs, or `just clean-configs` to remove the entire generated node tree.
Both destructive node-cleanup commands require the containers to be stopped first.

Do not rely on `just down --clean` locally: `--clean` only changes remote teardown behavior. Also
do not start another ordinary `just test` until the evidence is captured; the next run deletes and
recreates `nodes/` by default.

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

## Reconstructing the committee for an ordinal

The signed artifacts carry everything; no debug endpoint needed. Global L0 has one frozen voting
set per round and no tiers or scores ([ADR-0036](../adr/0036-single-frozen-voting-set.md)).

```bash
O=66
curl -s "localhost:9000/global-snapshots/$O" | jq '.value | {ordinal, epochProgress, rewards}'
# round O's certified value (committee, successor, admissions, evictions) rides in snapshot O+1
curl -s "localhost:9000/global-snapshots/$((O+1))" \
  | jq '.value.certifiedLineage.parentOutcome.proposalQc.value
        | {roundStartFacilitators, next: .nextRoundAuthority, admittedPeers, evictedPeers, timeoutVoters}'
```

Two off-by-ones that will mislead you:

- **The certified value for ordinal N lives in snapshot N+1** (`certifiedLineage.parentOutcome`).
  Snapshot N carries round N-1's certified outcome. The child of a recovery-seed root has an empty
  `certifiedLineage`, because the root itself was not certified.
- **Trigger type is not serialized in the artifact.** N was TimeTrigger iff
  `epochProgress[N] > epochProgress[N-1]`. Static validator rewards mint only on TimeTrigger
  rounds, so `rewards: []` is usually an EventTrigger round, not a bug.

Things to know when reading the committee:

- **Round N+1's committee is `nextRoundAuthority` of round N**, computed by the successor rule
  `(B \ (D u E)) u A` (B = round N's `roundStartFacilitators`, E = `evictedPeers`, A =
  `admittedPeers`, D = members that left the seedlist or lost collateral). Nothing else changes
  membership; an offline member keeps its seat until it leaves through one of those sets.
- **The leader is computable.** `LeaderSchedule.leader`: sort the committee by PeerId and take index
  `(N + view) mod n`. `committedView` in the certified value is the view that finalized.
- **`proofs` is not the committee.** `snapshot[N].proofs` holds whoever signed within the grace
  window; it varies across honest nodes and does not decide membership or delegated rewards (payout
  follows `roundStartFacilitators`). If a peer looks like it "isn't signing", compare `proofs`
  against `roundStartFacilitators` and check the node's own logs before concluding anything.

Cross-check against the node's own view:

```bash
docker logs gl0-0 2>&1 | grep 'FacilitatorsFinalized' | tail -5   # votingCommittee=, leader=
curl -s localhost:9000/metrics | grep -E 'dag_consensus_committee_size'
curl -s localhost:9000/cluster/info | jq '.[] | {id, peerCommittee}'  # seated / probation / cooldown
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

- **Some consensus config has no `CL_` override** (`max-facilitator-count`,
  `certified-consensus-activation-ordinal`). Changing it means editing `dag-l0.conf` and
  **rebuilding the jar** -- drop `--skip-assembly`. The tier-era sizing keys
  (`core-committee-size`, `active-facilitator-target`, `active-facilitator-max`, probation slots)
  no longer exist.
- **Proof sets are noisy on a laptop.** Under load, late signers miss the grace window and drop out
  of `proofs` even though they are seated and at tip. Any test criterion built on who appears in
  `proofs` is flaky here by construction; prefer criteria the node itself uses (committee
  membership, certified admissions/evictions).
