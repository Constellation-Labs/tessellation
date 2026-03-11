# Consensus FSM Refactoring — Summary of Changes

## Overview

This refactoring targets seven categories of issues in the consensus engine:

1. **Quorum safety** — Quorum declarations lacked a supermajority gate, risking non-deterministic decisions across different node views
2. **Stall recovery** — Unlock voting used flat thresholds that couldn't adapt when many facilitators were unresponsive
3. **Facilitator re-entry** — Peers removed during stall recovery were permanently excluded from future rounds
4. **Removal penalty** — Removed peers could be re-selected one round later, causing repeated stalls when still unresponsive
5. **Fiber leaks** — Orphaned fibers accumulating over sustained operation
6. **Race condition** — Non-atomic state in `PendingTriggers` causing lost triggers
7. **Error recovery** — FSM getting permanently stuck when critical commands fail

All changes are backward-compatible. No protocol changes, no config format changes, no public API changes.

---

## Safe Quorum with Supermajority Gate

### Problem

The consensus advancer (`ConsensusStateAdvancer`) waited for 100% of facilitators to declare before advancing status. This meant a single unresponsive node would block the entire round indefinitely. To fix this, a `quorumThreshold` config was introduced (e.g., 0.67 = 67%), but a naive quorum check creates a new problem:

**Non-determinism across views:** Different nodes see declarations arrive in different orders. If two competing values (e.g., two different proposal hashes) each have ~50% support, one node's quorum-sized subset might compute a different `pickMajority` result than another node's subset. The round diverges and fails.

### Solution

Added a **supermajority safety gate** to `maybeGetQuorumDeclarations` in `ConsensusStateAdvancer.scala`:

```scala
protected def maybeGetQuorumDeclarations[A, V](
  state: State, resources: Resources
)(getter: PeerDeclarations => Option[A])(
  valueExtractor: A => V
): F[Option[SortedMap[PeerId, A]]]
```

The method now requires two conditions before returning declarations:

1. **Quorum count met:** `receivedCount >= quorumSize` (where `quorumSize = ceil(totalRequired * quorumThreshold)`)
2. **Supermajority support:** The dominant value (most-supported among received declarations) must itself have `>= quorumSize` supporters

**Why this is safe:** By the pigeonhole principle, if one value has supermajority support (>2/3), then any quorum-sized subset of declarations will contain a majority for that same value. All honest nodes compute the same `pickMajority` result regardless of which subset they see.

**If no value has sufficient support** (split vote): returns `None`, deferring to the stall detector which will lock the round and remove unresponsive peers. This breaks the split naturally.

Falls back to 100% if `quorumThreshold` is not configured.

### Config

```hocon
snapshot.consensus {
  quorum-threshold = 0.67  # Optional — omit for 100% unanimity (legacy behavior)
}
```

### Files Changed

| File | Change |
|------|--------|
| `consensus/state/ConsensusStateAdvancer.scala` | New `maybeGetQuorumDeclarations` with supermajority gate |
| `dag-l0/.../GlobalSnapshotConsensusStateAdvancer.scala` | Calls `maybeGetQuorumDeclarations` instead of checking 100% |
| `currency-l0/.../CurrencySnapshotConsensusStateAdvancer.scala` | Same quorum integration |

---

## Deterministic N-Based Unlock Thresholds

### Problem

When a consensus round stalls (not all facilitators declared within the timeout), the round is locked and each node spreads ACK messages containing the set of peers it considers "present". The unlock mechanism tallies these ACK votes to decide which peers to keep and which to remove.

The old unlock logic used flat thresholds based on total facilitator count. When >50% of facilitators were unresponsive (couldn't even send ACKs), the thresholds could never be met. The round stayed permanently locked — a deadlock.

### Solution

Replaced flat thresholds with **deterministic N-based thresholds** in `UnlockConsensusUpdate.scala`, with a minimum voter gate and a safety floor:

| Phase | Condition | Keep Threshold | Remove Threshold | Rationale |
|-------|-----------|----------------|------------------|-----------|
| **DEFER** | `voterCount < ceil(N/3)` | — | — | Too few voters for a safe decision; defer to re-stall cycle |
| **DECIDE** | `voterCount >= ceil(N/3)` | `(N+1)/2` | `N/2 + 1` | N-based thresholds ensure all nodes make identical decisions |

Where `N` is the total facilitator count and `voterCount` is how many facilitators actually sent ACKs for the current collecting kind. The minimum voter requirement is `ceil(N/3).max(2)`.

**Key properties:**
- `keepThreshold + removeThreshold = N + 1 > N`, guaranteeing mutual exclusivity (no peer can be both kept and removed)
- Thresholds depend only on `N` (total facilitator count), which all nodes agree on — making the decision deterministic across the network
- DEFER uses `ceil(N/3)` as its minimum voter requirement — a lower bar than `keepThreshold` (`(N+1)/2`), allowing unlock decisions with fewer voters while still requiring meaningful participation. For N=20 this means 7 voters needed (vs 11 with keepThreshold)
- The stall detector's `maxStallCycles` and `maxRoundDuration` handle liveness: after exhausting stall cycles, the round is abandoned and a new one starts
- After unlock: `Closed → Reopened`, removed peers are dropped from facilitator list, `advanceStatus` runs again with reduced list

**Safety floor (`MinFacilitatorCount = 2`):** After computing keep/remove decisions, if the number of kept facilitators would fall below 2, the unlock is aborted — the state remains `Closed` and the round is deferred to the stall detector. This prevents a catastrophic scenario where stale ACKs (e.g., from a global latency spike where no declarations arrived before lock) cause all facilitators to be voted out, leaving `facilitatorCount=0` and an irrecoverable cluster.

### Files Changed

| File | Change |
|------|--------|
| `consensus/update/UnlockConsensusUpdate.scala` | Rewritten with deterministic N-based thresholds, DEFER gate, and MinFacilitatorCount safety floor |

---

## Eligible Facilitator Re-Entry

### Problem

When a peer was removed during stall recovery (unlock), it was stored in `removedFacilitators`. The next round's state creator computed the eligible facilitator list by starting from the previous round's eligible peers AFTER filtering out removed peers. This meant a removed peer could never re-enter the eligible pool — even if it came back online and was healthy.

Over multiple stall cycles, the active facilitator count would ratchet downward permanently until the network degraded.

### Solution

Changed the facilitator selection logic in both `GlobalSnapshotConsensusStateCreator` and `CurrencySnapshotConsensusStateCreator`:

```
fullBase = (filteredPreviousEligible ++ filteredCandidates :+ selfId).distinct
         // ↑ computed WITHOUT removal filter — includes all healthy peers

allEligible = fullBase.filterA(facilitatorFilter)
         // ↑ only collateral/health checks, NOT removal filter

eligibleThisRound = allEligible.filterNot(previouslyRemoved.contains)
         // ↑ THIS round excludes recently-removed peers

activeFacilitators = facilitatorSelector.select(eligibleThisRound, entropy)
```

**Key distinction:**
- `allEligible` (stored in `EligibleFacilitators`) — includes previously-removed peers so they persist in the eligible pool
- `eligibleThisRound` — excludes recently-removed peers for active selection THIS round only
- Next round: the removed peers are back in `allEligible` and can be selected again if they pass the facilitator filter

This means a peer removed in round N is excluded from round N+1 but eligible again in round N+2 (assuming it passes collateral/health checks).

### Files Changed

| File | Change |
|------|--------|
| `dag-l0/.../GlobalSnapshotConsensusStateCreator.scala` | `fullBase` without removal filter, `allEligible` stored for re-entry |
| `currency-l0/.../CurrencySnapshotConsensusStateCreator.scala` | Same pattern |

---

## Multi-Round Removal Penalty

### Problem

With the eligible facilitator re-entry mechanism (above), a peer removed during stall recovery in round N is excluded from round N+1 but eligible again in round N+2. If the peer is still unresponsive, the stall detector must wait `declarationTimeout` (35s+) before detecting the stall and removing it again. This wastes an entire round's worth of time on a peer already known to be problematic.

**Why not filter by local health checks?** Every `PeerDeclaration` includes a `facilitatorsHash`. All nodes must compute the same facilitator list. Different nodes have different views of which peers are responsive (`PeerResponsiveness` from `LocalHealthcheck`). Filtering by local health would produce different `facilitatorsHash` values across nodes, triggering fork detection and node restarts.

### Solution

Added a **deterministic multi-round removal penalty** that extends the exclusion period for removed peers. All penalty data derives from the agreed-upon consensus outcome (`lastOutcome`), so all nodes compute identical facilitator lists.

**Penalty lifecycle:**

```
Round N:   Peer X fails to declare → stall → lock → unlock removes X
           Outcome: removedFacilitators = {X}, removalPenalties = {X: 3}

Round N+1: Penalties from lastOutcome: {X: 3}, X excluded (3 > 0)
           Advancer decrements → outcome: {X: 2}

Round N+2: {X: 2}, X excluded (2 > 0)
           Advancer decrements → outcome: {X: 1}

Round N+3: {X: 1}, X excluded (1 > 0)
           Advancer decrements → outcome: {} (expired)

Round N+4: No penalty → X eligible again
           If X is healthy → participates normally
           If X still unresponsive → removed again → penalty resets to 3
```

**Implementation has two parts:**

1. **State creators** (facilitator selection): Read `lastOutcome.removalPenalties`, exclude peers with penalty > 0 from `eligibleThisRound`. Penalized peers remain in `allEligible` (re-entry pool preserved).

```
penalizedPeers = lastOutcome.removalPenalties.filter(_._2 > 0).keySet
eligibleThisRound = allEligible.filterNot((previouslyRemoved ++ penalizedPeers).contains)
```

2. **Advancers** (outcome construction): Decrement previous penalties, filter expired (<=0), add new removals at `removalPenaltyRounds`. Stored in the outcome for the next round.

```
decremented = previousPenalties.mapValues(_ - 1).filter(_._2 > 0)
newPenalties = removedFacilitators.foldLeft(decremented)(_.updated(_, removalPenaltyRounds))
```

**Why deterministic:** All inputs are from the agreed-upon `lastOutcome` (same for all nodes) and `config.removalPenaltyRounds` (same config on all nodes). Decrement and merge are pure functions. No local state (`PeerResponsiveness`, `ClusterStorage`) is used.

**Backward compatible:** `removalPenalties: Map[PeerId, Int] = Map.empty` with default value ensures old serialized outcomes (without the field) decode correctly via circe magnolia. Feature disabled when `removalPenaltyRounds = 0`.

### Config

```hocon
snapshot.consensus {
  removal-penalty-rounds = 3  # 0 = disabled (default, backward compatible)
}
```

### Files Changed

| File | Change |
|------|--------|
| `node-shared/.../config/types.scala` | Added `removalPenaltyRounds: Int = 0` to `ConsensusConfig` |
| `dag-l0/.../snapshot/schema.scala` | Added `removalPenalties: Map[PeerId, Int]` to `GlobalConsensusOutcome` |
| `currency-l0/.../snapshot/schema.scala` | Added `removalPenalties: Map[PeerId, Int]` to `CurrencyConsensusOutcome` |
| `dag-l0/.../GlobalSnapshotConsensusStateCreator.scala` | Filter `eligibleThisRound` by penalties |
| `currency-l0/.../CurrencySnapshotConsensusStateCreator.scala` | Filter `eligibleThisRound` by penalties |
| `dag-l0/.../GlobalSnapshotConsensusStateAdvancer.scala` | Compute `removalPenalties` in `getConsensusOutcome` |
| `currency-l0/.../CurrencySnapshotConsensusStateAdvancer.scala` | Compute `removalPenalties` in `getConsensusOutcome` |
| `dag-l0/src/main/resources/dag-l0.conf` | `removal-penalty-rounds = 3` |
| `currency-l0/src/main/resources/currency-l0.conf` | `removal-penalty-rounds = 3` |

---

## StallDetector Improvements

### Problem

The original stall detector had a single timeout mechanism: if declarations didn't arrive within `declarationTimeout`, the round was abandoned. This was too aggressive — network delays could cause legitimate rounds to be killed.

### Solution

Rewrote `StallDetector.scala` with a multi-stage stall recovery flow:

```
Wait declarationTimeout
  → Lock (Closed) + spread ACKs
  → Wait for unlock (Reopened) via ACK voting
  → If unlock fails after reStallTimeout: re-spread ACKs (failed stall cycle)
  → After maxStallCycles: abandon round
  → After maxRoundDuration: abandon round (wall-clock safety net)
```

**New config parameters:**

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `re-stall-timeout` | 10s | Timeout for subsequent stall cycles after the first lock |
| `max-stall-cycles` | 5 | Maximum lock/unlock attempts before abandoning |
| `max-round-duration` | 5 min | Wall-clock safety net — abandon regardless of stall cycle count |
| `no-progress-timeout` | (optional) | Separate timeout when zero declarations received |

**Adaptive timeout features:**
- **Near-completion grace:** When >=75% of declarations received and on first stall cycle, timeout extended by 50% to allow stragglers
- **Re-stall timeout:** After first lock, subsequent stall cycles use `reStallTimeout` (shorter) instead of `declarationTimeout`
- **Round abandonment:** `shouldAbandon = finalStallCycleCount >= maxStallCycles || roundTimedOut`. When triggered, state is removed unconditionally (`case Some(_) =>`) and `RoundCompleted` is enqueued so the FSM transitions back to IDLE. Note: the stall-cycle-exhaustion path does not require `isLocked` — once the budget is spent, the round is abandoned regardless of lock status. This is a deliberate trade-off: after N failed lock/unlock cycles, cutting losses is faster than hoping N+1 works

**Monitoring improvements:**
- Periodic summary logging (every 10s) showing status, declaration counts, missing peers
- Per-status tracking of which peers haven't declared (logged by truncated peer ID)
- Metrics: `dag_consensus_stall_detected`, `dag_consensus_unlock_success`, `dag_consensus_round_abandoned`

### Config

```hocon
snapshot.consensus {
  declaration-timeout = 35 seconds
  re-stall-timeout = 10 seconds
  max-stall-cycles = 5
  max-round-duration = 5 minutes
}
```

### Files Changed

| File | Change |
|------|--------|
| `consensus/engine/StallDetector.scala` | Rewritten with multi-stage stall recovery |
| `config/types.scala` | Added `reStallTimeout`, `noProgressTimeout`, `maxStallCycles`, `maxRoundDuration`, `quorumThreshold` to `ConsensusConfig` |
| `dag-l0/src/main/resources/dag-l0.conf` | New config values |
| `currency-l0/src/main/resources/currency-l0.conf` | New config values |

---

## Phase 1: Fiber Lifecycle Management

### Problem

The consensus engine spawns background fibers for stall detection monitors and timer triggers using `supervisor.supervise(task).void`. The `.void` discards the fiber handle, making it impossible to cancel the fiber when the round completes. Over sustained operation, orphaned fibers from completed rounds accumulate, consuming threads and CPU.

**Example of the leak pattern (before):**
```scala
// Old code — fiber handle discarded, can never be cancelled
supervisor.supervise(stallDetector.monitor(key)).void
```

The StallDetector's `monitor` method also used polling (`storage.getState(key)`) to detect round completion, introducing a race window of up to 1 second between actual completion and monitor exit.

### Solution

#### 1a. Per-Round Fiber Tracking (`ConsensusRoundRunner.scala`)

Added two `Ref`s to track round lifecycle:

- `roundFibersRef: Ref[F, List[Fiber[F, Throwable, Unit]]]` — Tracks all fibers spawned during the current round
- `cancelSignalRef: Ref[F, Option[Deferred[F, Unit]]]` — Holds a signal to notify the stall monitor

New methods:

| Method | Purpose |
|--------|---------|
| `spawnTracked(task)` | Spawns via Supervisor AND records the fiber handle |
| `cancelRoundFibers` | Cancels all tracked fibers atomically |
| `cleanupRound` | Signals the monitor to stop + cancels all tracked fibers |

Both `startRoundMonitor` and `scheduleTimeTrigger` now use `spawnTracked` instead of bare `supervisor.supervise(...).void`.

#### 1b. Signal-Based Monitor Termination (`StallDetector.scala`)

Changed the `monitor` method signature to accept a cancellation signal:

```scala
// Before: polling-based, no way to stop externally
def monitor(key: Key): F[Unit]

// After: signal-based, stops immediately when round completes
def monitor(key: Key, cancelSignal: Deferred[F, Unit]): F[Unit]
```

Internally uses `Async[F].race(cancelSignal.get, monitorLoop)` — the monitor exits the instant the signal fires, with zero race window.

#### 1c. Round Cleanup Integration (`ConsensusFSM.scala`)

The `completeRound` method now calls `roundRunner.cleanupRound` before resetting the FSM to IDLE:

```scala
private def completeRound(preAction: F[Unit]): F[Unit] =
  for {
    _ <- preAction
    _ <- roundRunner.cleanupRound  // Cancel monitor + timer fibers
    _ <- isRunning.set(false)
    next <- pending.pullNext
    _ <- next.traverse_(...)
  } yield ()
```

#### 1d. Wiring (`ConsensusEventLoop.scala`)

Creates the `Ref`s and passes them to `ConsensusRoundRunner`:

```scala
roundFibersRef <- Ref.of[F, List[Fiber[F, Throwable, Unit]]](Nil)
cancelSignalRef <- Ref.of[F, Option[Deferred[F, Unit]]](None)
roundRunner = new ConsensusRoundRunner(ctx, stallDetector, roundFibersRef, cancelSignalRef)
```

#### Fork Recovery Fiber (documented, not changed)

`ConsensusStateUpdater.scala` has a fire-and-forget fiber for fork recovery via `Temporal[F].start(forkRecovery).void`. This is acceptable because fork recovery is a one-shot operation (node transitions to Leaving -> Offline -> restart) that should outlive any single round. Added a comment documenting the rationale.

### Files Changed

| File | Change |
|------|--------|
| `consensus/engine/ConsensusRoundRunner.scala` | Added fiber tracking, `spawnTracked`, `cleanupRound` |
| `consensus/engine/StallDetector.scala` | Added `Deferred` cancel signal to `monitor` |
| `consensus/state/ConsensusFSM.scala` | Calls `cleanupRound` in `completeRound` |
| `consensus/engine/ConsensusEventLoop.scala` | Creates Refs, wires to RoundRunner |
| `consensus/state/ConsensusStateUpdater.scala` | Added comment on fork recovery fiber |

---

## Phase 2a: PendingTriggers Race Condition Fix

### Problem

`PendingTriggers` tracked pending consensus triggers using two separate `Ref[Boolean]`:

```scala
// Old design — two independent Refs
class PendingTriggersF[F[_]](eventRef: Ref[F, Boolean], timeRef: Ref[F, Boolean]) {
  def pullNext: F[Option[TriggerPriority]] =
    for {
      time  <- timeRef.get      // Step 1: read time
      event <- eventRef.get     // Step 2: read event
      _     <- timeRef.set(false)   // Step 3: clear time
      _     <- eventRef.set(false)  // Step 4: clear event
    } yield ...
}
```

**Race condition:** Between steps 2 and 3, a concurrent `setTime()` call could set `timeRef = true`. Step 3 then clears it, and the trigger is permanently lost. The node misses a consensus round.

### Solution

Replaced with a single atomic `Ref[PendingState]`:

```scala
// New design — single atomic Ref
sealed trait PendingState
case object NoPending extends PendingState
case object EventPending extends PendingState
case object TimePending extends PendingState

class PendingTriggersF[F[_]: Functor](stateRef: Ref[F, PendingState]) {
  def setEvent(): F[Unit] = stateRef.update {
    case TimePending => TimePending  // Don't downgrade time to event
    case _           => EventPending
  }

  def setTime(): F[Unit] = stateRef.set(TimePending)

  def pullNext: F[Option[TriggerPriority]] =
    stateRef.getAndSet(NoPending).map {  // Single atomic operation
      case TimePending  => Some(TriggerPriority.Time)
      case EventPending => Some(TriggerPriority.Event)
      case NoPending    => None
    }
}
```

Key properties:
- `pullNext` is now a single `getAndSet` — atomic, no race window
- `setEvent` won't downgrade an existing `TimePending` to `EventPending`
- `setTime` always wins (highest priority)

### Tests Added

`PendingTriggersSuite.scala` — 7 tests:

| Test | Verifies |
|------|----------|
| pullNext returns None when nothing pending | Empty state |
| setEvent then pullNext returns Event | Basic event trigger |
| setTime then pullNext returns Time | Basic time trigger |
| time takes priority over event | Priority ordering |
| setEvent does not downgrade existing Time | Priority preservation |
| pullNext clears state atomically | State cleanup |
| concurrent set and pull does not lose triggers | Concurrency safety |

### Files Changed

| File | Change |
|------|--------|
| `consensus/engine/PendingTriggers.scala` | Rewritten with single atomic Ref |
| `consensus/engine/PendingTriggersSuite.scala` | **New** — 7 tests |

---

## Phase 2c: Command Stream Error Recovery

### Problem

The command stream in `ConsensusEventLoop` processes FSM commands sequentially. If `fsm.handle(cmd)` throws for a `ConsensusFinished` or `RoundCompleted` command, the error was logged but the round was never completed. The FSM stays stuck in BUSY state permanently — no new rounds can start.

### Solution

Added command-specific recovery after the existing error handler:

```scala
fsm.handle(cmd).handleErrorWith { err =>
  logger.error(err)(s"Unhandled error processing ${cmd.getClass.getSimpleName}, recovering") >>
    Metrics[F].incrementCounter("dag_consensus_command_error") >>
    (cmd match {
      case _: ConsensusCommand.ConsensusFinished | ConsensusCommand.RoundCompleted =>
        // Critical: force round completion so FSM doesn't stay stuck in BUSY.
        // Also offer TimeTick: the forced RoundCompleted calls completeRound without
        // afterConsensusFinish, so no timer is scheduled for the next round. On solo nodes
        // with no external events, this would deadlock consensus.
        logger.warn("Forcing round completion after failed ConsensusFinished/RoundCompleted") >>
          queue.offer(ConsensusCommand.RoundCompleted) >>
          queue.offer(ConsensusCommand.TimeTick)
      case _ => Async[F].unit
    })
}
```

For non-critical commands (rumor processing, peer registration, etc.), the error is logged and the stream continues — same as before.

**Solo-node stall prevention:** The forced `RoundCompleted` calls `completeRound` without `afterConsensusFinish`, so no timer is scheduled for the next round. On solo nodes with no external events (no gossip, no peer messages), consensus would permanently stall. The follow-up `TimeTick` fires once `RoundCompleted` sets `isRunning=false`, starting a new round from IDLE.

### Files Changed

| File | Change |
|------|--------|
| `consensus/engine/ConsensusEventLoop.scala` | Added recovery for critical round-completion commands |

---

## Pre-existing Warning Fixes

These files had unused imports, parameters, or methods that were never caught because incremental compilation only checks changed files. The `-Werror` flag treats warnings as errors on full recompilation. These are not related to the refactoring but were required to unblock compilation.

| File | Fix |
|------|-----|
| `modules/SharedStorages.scala` | Removed unused imports |
| `logger/sink/Slf4jSink.scala` | Removed unused import |
| `logger/sink/clickhouse/ClickHouseConsensusLogger.scala` | Removed unused `logger` parameter from `startFlusher` |
| `logger/sink/clickhouse/ClickHouseSink.scala` | Removed unused `logger` parameter from `startFlusher` |
| `snapshot/managers/global/TokenLockStateManager.scala` | Removed unused imports (`CurrencySnapshotInfoV1`, `Amount`) |
| `snapshot/managers/global/TipUsageManager.scala` | Removed unused `SortedSet` import |
| `snapshot/managers/global/TransactionReferenceManager.scala` | Removed unused wildcard import |
| `snapshot/storage/CombinedSnapshotCheckpointFileSystemStorage.scala` | Removed unused `Concurrent` context bound |
| `snapshot/storage/SnapshotLocalFileSystemStorage.scala` | Removed unused `kryoSerializer` parameter |
| `snapshot/storage/SnapshotStorage.scala` | Removed unused private `getLastN` method |
| `consensus/state/ConsensusStateAdvancer.scala` | Removed unused imports (`Clock`, `FiniteDuration`, `Responsive`, `Unresponsive`) |
| `consensus/engine/StallDetector.scala` | Removed unused imports and `Eq[Status]` bound |
| `consensus/engine/ConsensusRoundRunner.scala` | Removed unused `Metrics` and `Eq[Status]` bounds |
| `consensus/engine/ConsensusEventLoop.scala` | Removed unused `Eq[Status]` bound |

---

## Test Results

```
Total: 83 tests, 0 failures (430 total project-wide)
  - StallDetectorSuite:            27 tests  (stall detection, lock/unlock, re-stall cycles, abandonment, abandon guard)
  - QuorumDeclarationsSuite:       21 tests  (quorum threshold, supermajority gate, split vote deferral, config validation, small cluster)
  - RemovalPenaltySuite:           12 tests  (penalty lifecycle, decrement, expiry, filtering, determinism)
  - UnlockConsensusUpdateSuite:    10 tests  (N-based thresholds, DEFER gate, mutual exclusivity, determinism, MinFacilitatorCount safety floor)
  - PendingTriggersSuite:           7 tests  (atomic state, priority ordering, concurrency safety)
  - EligibleFacilitatorsSuite:      5 tests  (re-entry after removal, collateral filtering)
```

All modules compile: `nodeShared`, `dagL0`, `currencyL0`.

---

## What Was NOT Changed (and Why)

| Item | Reason |
|------|--------|
| **Advancer DRY extraction** | Both advancers share ~60-80 lines of utility methods, but threading 7+ type parameters and 10+ constructor dependencies through an abstract base class adds more complexity than it removes. The phase flow also diverges (Global: 3 phases, Currency: 4 phases with BinarySignatures). |
| **Metrics extraction** | `recordMetrics` methods are completely different between advancers — no shared code to extract. |
| **ConsensusConfig splitting** | The flat HOCON structure maps directly to the flat case class. Splitting into sub-configs would require either HOCON restructuring or custom config readers. 11 fields is manageable. |
| **ConsensusEngineContext rename** | Large rename diff across many files for a cosmetic improvement. |
| **Per-round Supervisor** | Would require changing the `implicit Supervisor[F]` threading throughout the codebase. Explicit fiber tracking via `Ref[List[Fiber]]` achieves the same goal with less disruption. |
| **Bounded command queue** | Risk of dropping commands under load. Unbounded queue is safer. |
| **Fork recovery fiber** | Fire-and-forget is acceptable for a one-shot operation that should outlive any round. Documented with comment. |
