# Mainnet successive-phase recovery reproduction

Status: historical stock-code reproduction record. The research revisions described
here changed no production behavior. The subsequent gossip response-deadline fix
is documented separately in [the response-lifetime report](mainnet-gossip-response-deadline.md).
This stock reproduction is not itself a Mainnet incident fix.

## Observed problem

Two operator-supplied Mainnet logs for ordinal 6885978 show an event-triggered round
lasting approximately 204–206 seconds. Facilities, proposals, and signatures each
hit the 50-second recovery timeout. The two observers agree on three distinct
removed peer prefixes (0174825e, 8d34f2bf, 82f85488), with the facilitator population
falling 144→143→142→141. No voluntary withdrawals are recorded in the corrected
exports. The preceding timed round, 6885977, lasted approximately 115 seconds.

The objective is to reproduce the recovery mechanism before proposing a correction.
The original Mainnet cause—why declarations from those peers were unavailable—is
not established by two receiving nodes' state logs alone.

The recovery wait is not new in v3.5.30. Repository history traces stall resolution
to `76e0a5b730` (2022-11-16), with conditional locking/ack logic in `2de02b96d5`
(2022-11-24) and timeout placement in `49274459ab` (2022-12-12). Current GL0
43-second trigger / 50-second declaration timeout / 10-second lock settings are
already present in the 2024 configuration move `ad4d35dfba`; that move is not claimed
as their first introduction. The manager, updater, unlock implementation, and
`dag-l0.conf` have no diff between v3.5.29 and v3.5.30. The distinct 10-second
`peers-declaration-timeout` warning setting is not the 50-second recovery timeout.

## Baseline and isolation

- Production source: v3.5.30, commit `9b1f826db65d56d1736a298fd18c842e0c93f5d6`.
- All modifications in the stock research revisions were tests or documentation,
  not production code/configuration.
- Research branch: `research/mainnet-phase-recovery`.
- No public validators, keys, transactions, or network writes are used by tests.
- No signatures or signed artifacts are manufactured. The manager test's artifact
  access path throws if invoked; artifact validation is explicitly outside its scope.
- A linked worktree failed during project loading because sbt-git's JGit version
  could not resolve its work tree. A standalone local clone is used for the build.
- Upstream read-only head check remained release/mainnet at the baseline and develop
  at `65b3667d414760763fe342e720a9486d2c0beb82` (refreshed September 7, approximately
  19:33 UTC). The nine open PRs' complete changed-file lists were retrieved next:
  #1597, #1596, #1595, #1594, #1593, #1592, #1591, #1538, and #1526. None changes
  the core manager/storage/updater/unlock/barrier files under test or the new
  reproduction files. This is a path-level overlap check, not proof that adjacent
  changes have no behavioral interactions or a full audit of every upstream branch.
  [PR #1597](https://github.com/Constellation-Labs/tessellation/pull/1597) changes
  adjacent ordinal-default configuration and repository guidance; #1596 changes
  download recovery. A selected production correction needs a fresh patch-level
  compatibility review against its actual target branch and these changes.

## Tests and their boundaries

`SuccessivePhaseRecoverySuite` exercises the actual stock generic declaration barrier
and `UnlockConsensusUpdate` with 144 participants and three distinct failure stages.
It checks threshold-minus-one versus sufficient acknowledgments at 144, 143, and
142 facilitators, accumulation of removed sets, stale/wrong-kind/outsider inputs,
healthy delivery, delayed delivery, and a keep decision that does not supply a
missing local declaration.

`StockRecoveryTimerSuite` runs the actual stock manager, storage, updater, and unlock
code under Cats Effect's virtual clock. It records actual lock and local-ack calls,
tests three successive 50-second/10-second timer sequences, and delivers simulated
remote acknowledgments through the real storage API. Additional checks cover
ordinal-specific candidate registration, monotonic registration keys, first-ack
immutability, and allowed declaration ordinal ranges.

Phase readiness and transport are fixtures. The generic barrier uses a proposal
presence slot to stand for the chosen phase declaration; the timer suite uses an
opaque phase integer and advances only after the corresponding real removal
decision. The local gossip sink records emission time, not public transport or
authentication. Thus these tests are not a five-node network reproduction, GL0
artifact-validation test, independent signature audit, or measured Mainnet latency
benchmark. Virtual elapsed time is deterministic; it is not server runtime.

The timer fixture does not accept a final signed snapshot. It stops at the third
phase transition; its sentinel artifact/network paths must never execute. The
driver adds 1ms scheduling checkpoints and delivers each decisive remote ack at a
controlled instant. These offsets are not proposed protocol delays.

## Test command

```bash
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=4 \
  'set ThisBuild / Test / javaOptions ++= Seq("-Xmx2G", "-XX:ActiveProcessorCount=4", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.security=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED")' \
  'nodeShared/testOnly *SuccessivePhaseRecoverySuite *StockRecoveryTimerSuite'
```

Initial test compilation failures are retained in the external experiment evidence:
missing test-key `Next[Int]`, a test context that did not meet the existing AnyRef
bound, and a function-valued test interface implemented with the wrong method shape.
These were fixture errors, not findings against Mainnet production code. The
production sources compiled unchanged.

On 2026-09-07 the focused run passed all 13 new tests. The full `nodeShared/test`
run then passed 282 tests, zero failures/errors, and `nodeShared/Test/scalafmtCheck`
passed. Completed at 19:20:41 UTC with Java 21.0.12 and sbt 1.9.8. Full log SHA256:
`c2b476c937eb9dfc082145f286d6ed1bb6db7e8838494eab073637dc674255e0`.

## Native experiment procedure

`docker/bin/mainnet-staged-recovery.py` runs only the verified stock v3.5.30 JAR
(SHA256 `9a5726027b962f3a8271d77c37e9c11c66d10a5a960da44d06c283b2a523ed5d`).
The cached native Docker image supplies Java and offline throwaway-key utilities;
the stock node JAR is mounted separately, read-only. Its image ID is recorded.
Five GL0 nodes use an internal-only bridge, no published ports, a seedlist containing
only the five generated keys, and fresh local genesis data. Three nodes start early,
two join later. Each node has a 1,800MiB container limit and 1,200MiB JVM heap.

After five-signer agreement and two healthy rounds, the controller injects three
different faults within one timed round:

1. Node 4 is paused just before facilities collection.
2. Node 3 is paused after emitting its facility, before emitting a proposal.
3. Node 2 remains alive after emitting its proposal. Its outbound P2P pulls are
   blocked inside its verified private network namespace; inbound serving remains
   available so healthy nodes can still retrieve its native acknowledgment.

The controller checks both healthy observers for 5→4→3→2 facilitators, the exact
cumulative removed-peer sets, zero withdrawals, and a 50-second lock in each phase.
It fails the experiment if a target escapes its intended phase. All faults are
restored, and three subsequent matching rounds on the healthy pair are required.
This post-fault gate does not assert that all impaired nodes have rejoined.

Six Python unit tests cover parsing the real multiline state format and eight-hex
peer IDs, expected and invalid recovery traces, withdrawals, and namespace guards.
They passed before the first native run. No protocol fields, signed messages,
timeouts, admission rules, version checks, or quorum thresholds are changed.

Native completion and measurements are separate from the component-test results.
The injection is a controlled transport/process failure, not evidence that these
specific failures occurred on Mainnet. A five-node development topology is not a
144-node Mainnet load benchmark. The native runtime is Java 21, not proof of a
byte-identical operator runtime/configuration.

### Native result: stock-staged-01, September 7, 2026

The unchanged stock JAR reproduced three successive phase-recovery delays.
All ten automated evidence gates passed. The run exited zero and its five owned
containers and private bridge were removed; retained evidence was not deleted.
No public network was joined, mutated, restarted, or deployed to.

| Observer | Healthy ordinal 8 | Healthy ordinal 9 | Fault facilities | Fault proposals | Fault signatures | Fault total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Lab node 0 | 1.584 s | 2.545 s | 60.445 s | 60.379 s | 61.616 s | 182.440 s |
| Lab node 1 | 1.745 s | 2.333 s | 60.213 s | 60.580 s | 60.227 s | 181.021 s |

These are active phase/round durations, excluding the ordinary 43-second idle
interval between timed rounds. They are not daily reward-rate estimates.
Fault ordinal 10 began at 19:39:01 UTC; both observers finished by 19:42:04 UTC.
Both recorded 5→4→3→2 facilitators, the intended three distinct removals, and no
withdrawals. The accepted snapshot values matched, with only the two healthy
participants' proofs. Both progressed through ordinal 13 by 19:44:18 UTC. Across
all sampled nodes, no same-ordinal value-digest conflict was observed. This checks
observed value agreement, not independent cryptographic validation or all states
between samples. Nine Python controller/analyzer tests passed, including negative
checks for conflicting values, absent snapshots, and incorrect signer sets.

Important contradictory/limiting evidence: after restoration, nodes 2, 3, and 4
triggered stock facilitator-hash fork recovery at 19:42:53.243, 19:42:10.374, and
19:42:06.277 UTC respectively. Node 2 briefly exposed the same ordinal-10 value
with three proofs rather than two; this is not a different observed snapshot value.
The healthy pair did not trigger the fork guard. The final sample showed the healthy
pair Ready, node 4 Observing, and nodes 2/3 unavailable at the sampled API; all five
containers were still running with OOMKilled=false before teardown. Full reentry
was NOT demonstrated. The observed stock guard/restart path must be preserved and
tested by any future correction; this run is not a five-node recovery qualification.

Resource checks found approximately 11GiB available memory during the run, swap
unchanged at 56KiB used, and sampled container memory roughly 0.5GiB each. GC logs
are retained. This was not a resource-pressure experiment; no build ran concurrently.
After cleanup approximately 14GiB memory was available; no lab containers/network
remained. Other pre-existing stopped Docker containers were left untouched.

Reproduction command (from this checkout, with the verified artifact available):

```bash
python3 docker/bin/mainnet-staged-recovery.py \
  --jar /srv/projects/tw-devnet/evidence/mainnet-cadence/stock.jar \
  --output /srv/projects/tw-devnet/evidence/mainnet-cadence/stock-staged-01 \
  --seconds 720
python3 docker/bin/analyze-mainnet-staged.py \
  /srv/projects/tw-devnet/evidence/mainnet-cadence/stock-staged-01
```

Use a NEW output directory for a repeat; the harness refuses to overwrite evidence.
Events SHA256: `1fdd2155eb94c4368a84bf338485eb732ffde491c04192b3012d87f74f334594`.
Samples SHA256: `89749170e9112be0a6ee2d5c25765f664d508f7fce88be4a441b1045350a3126`.
The external `stock-staged-01-analysis.json` includes every retained node-log hash.

Conclusion: successive unavailable declarations can produce roughly three minutes
of active consensus delay on stock v3.5.30 without a new scheduler setting. This
demonstrates the mechanism seen in the two Mainnet phase traces, not the original
reason their declarations were unavailable or the cause of the entire September
reward-rate change. No behavior-changing correction is included in this branch.

## What a future correction must demonstrate

1. Establish a defect or avoidable delay, not simply that the configured recovery
   mechanism exists. Distinguish missing sends, delivery gaps, local processing,
   and repeated admission using evidence from relevant nodes or faithful fault injection.
2. Reproduce that defect with stock behavior; include a healthy control.
3. Preserve the existing authenticated-message processing and membership decisions
   unless a separate, explicitly governed consensus change is required.
4. Run matched before/after native tests, delayed/out-of-order delivery, insufficient
   acknowledgments, false-removal checks, recovery/reentry, and compatibility tests.
5. Document resource limits, exact source/artifact identities, failures, and limits.

An indiscriminate pause of three out of five processes is not a faithful model of
three missing declarations at different phases: it can deprive remaining nodes of
the acknowledgments needed to recover. A native controller must preserve already
sent declarations and required ack traffic, or use a separately justified topology.

Do not deploy shorter timeouts, blacklist the logged peers, or promote the earlier
cadence candidate on the strength of this component reproduction.
