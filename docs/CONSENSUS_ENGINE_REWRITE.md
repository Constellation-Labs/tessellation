# Consensus Engine Rewrite — Technical Handoff Document

**Branch:** `consensus-engine-rewrite-for-develop`
**Base:** `develop` (merge-base `7b30215`)
**Stats:** 101 files changed, +9,362 / -1,240 lines
**Status:** Running on testnet, battle-tested through multiple production incidents

---

## Table of Contents

1. [Why This Rewrite Exists](#1-why-this-rewrite-exists)
2. [Architecture Overview](#2-architecture-overview)
3. [New Components (10 new files)](#3-new-components)
4. [Modified Components (detailed per-file)](#4-modified-components)
5. [Deleted Components](#5-deleted-components)
6. [Consensus Protocol: Old vs New](#6-consensus-protocol-old-vs-new)
7. [Production Incident Fixes (8 collapse modes)](#7-production-incident-fixes)
8. [Configuration Changes](#8-configuration-changes)
9. [Test Coverage](#9-test-coverage)
10. [How to Review](#10-how-to-review)
11. [Known Risks & Edge Cases](#11-known-risks--edge-cases)

---

## 1. Why This Rewrite Exists

The old consensus engine used an all-to-all **lock/unlock model** where every peer sent lock requests to every other peer, waited for responses, then sent unlock requests. This model had fundamental issues:

- **Deadlock-prone:** Concurrent lock/unlock between peers created circular waits
- **Non-deterministic facilitator sets:** Each node's local view of which peers were online differed, causing `facilitatorsHash` mismatches → forks
- **No stall detection:** If a peer went silent mid-round, the entire round would hang indefinitely
- **No recovery escalation:** A node stuck on one ordinal would retry forever with no fallback
- **No structured logging:** Diagnosing consensus failures in production was nearly impossible

---

## 2. Architecture Overview

### Data Flow (New Model)

```
                     ┌─────────────────────────────────────┐
                     │       ConsensusEventLoop             │
                     │  (builds & wires everything)         │
                     └──────────┬──────────────────────────┘
                                │
              ┌─────────────────┴─────────────────────┐
              │          Command Queue                  │
              │    Queue[F, ConsensusCommand]           │
              └─────────────────┬─────────────────────┘
                                │
              ┌─────────────────┴─────────────────────┐
              │           ConsensusFSM                  │
              │   IDLE ←→ BUSY state machine            │
              │   Routes commands based on state         │
              └────┬──────────┬───────────┬───────────┘
                   │          │           │
         ┌────────┴──┐  ┌────┴─────┐  ┌──┴──────────┐
         │RumorHandler│  │StateTransitions│  │RoundRunner│
         │(rumors)   │  │(lifecycle)     │  │(facilitation)│
         └───────────┘  └──────────────┘  └──────┬──────┘
                                                  │
                                    ┌─────────────┴──────────────┐
                                    │       StallDetector          │
                                    │  Phase timeouts, lagging,    │
                                    │  view changes, abandonment   │
                                    └──────┬──────────┬───────────┘
                                           │          │
                                  ┌────────┴──┐  ┌───┴──────────────┐
                                  │ViewChange  │  │AbandonmentTracker│
                                  │Manager     │  │(recovery escal.) │
                                  └───────────┘  └──────────────────┘
```

### Three Parallel Streams

`ConsensusEventLoop` runs three concurrent streams via `parJoinUnbounded`:

1. **commandStream:** `Stream.repeatEval(queue.take).evalMap(fsm.handle)` — main event loop
2. **peerRegistrationStream:** Watches `clusterStorage.peerChanges` for peers entering `Observing` state, collects their registration key via `consensusClient.getRegistration`
3. **leavingStream:** Watches `nodeStorage.nodeStates` for `Leaving` state, triggers `withdrawFromConsensus`

### FSM States

The FSM has exactly two states: **IDLE** (no round running) and **BUSY** (round in progress).

Commands that are state-independent (always processed regardless of IDLE/BUSY):
- `RumorReceived(r)` → `rumorHandler.process(r)`
- `CheckUpdate(key)` → `transitions.checkUpdate(key)`
- `InternalScheduled(inner)` → recursive `handle(inner)`
- `PeerObserved(peer)` → `transitions.registerPeer(peer)`
- `IgnoreUnexpectedRumor(r)` → log warning

When **IDLE**, the FSM starts rounds:
- `StartRound(trigger)` → `startRound(trigger)`
- `TimeTick` → `startRound(Some(TimeTrigger))`
- `FacilitateByEvent` → `startRound(Some(EventTrigger))`
- `InitializeFromDownload(...)` → `transitions.initFromDownload(...)`
- `InitializeFromRollback(...)` → `transitions.initFromRollback(...)`
- `WithdrawFromConsensus` → `transitions.withdraw`

When **BUSY**, the FSM defers triggers:
- `FacilitateByEvent` / `TimeTick` → `pending.setEvent()` / `pending.setTime()`
- `StartRound(Some(TimeTrigger))` → `pending.setTime()`
- `StartRound(_)` → `pending.setEvent()`
- `RoundCompleted` → `completeRound(...)` (set IDLE, pull pending, start next)
- `ConsensusFinished(key, outcome, trigger)` → `completeRound(...)` with `afterConsensusFinish`

### Round Blocked States

Rounds will NOT start when the node is in:
- `WaitingForDownload`
- `DownloadInProgress`
- `Leaving` ← added to break infinite spin loop

---

## 3. New Components

### 3.1 `StallDetector.scala` (~558 lines)

**Purpose:** Monitors consensus round progress with phase-aware adaptive timeouts.

**Key behaviors:**
- Spawned as a background fiber when a round starts (`startRoundMonitor`)
- Polls `storage.getState(key)` and `storage.getResources(key)` periodically
- Phase-aware timeouts: different timeout for facilities phase (phase 0) vs proposals/signatures
- `noProgressTimeout` restricted to facilities phase only (M1 fix) — prevents premature timeout during legitimate processing in later phases
- Near-completion timeout bonus skipped when ALL missing peers are `Unresponsive` (M3 fix)
- `facilitiesTimeoutMultiplier = 0.3` (reduced from 0.5, M4 fix)

**Lagging detection (H3 fix):**
```scala
peerRegs <- storage.getPeerRegistrations
peersAtHigherKey = peerRegs.count { case (_, peerKey) => peerKey > key }  // strict >
totalRegisteredPeers = peerRegs.size
isLagging = totalRegisteredPeers >= 3 && peersAtHigherKey > totalRegisteredPeers / 2
```
If lagging, immediately abandons the round → triggers recovery download.

**Quorum feasibility check (H2 fix):**
After peer eviction, checks if remaining facilitators can still form quorum. If not, abandons immediately instead of waiting for timeout.

**View change:**
When leader stalls (facilities timeout), increments `viewNumber` and selects new leader via `ViewChangeManager`. Short-circuits when `leader == selfId`.

### 3.2 `AbandonmentTracker.scala` (~376 lines)

**Purpose:** 3-tier recovery escalation when rounds fail.

**Escalation tiers:**
1. **Retry** (consecutive count < `maxConsecutiveAbandonments`, default 5): Abandon round, queue `RoundCompleted` + `TimeTick`, try again
2. **Recovery download** (count >= max): Transition to `WaitingForDownload`, clear all consensus state, let `DownloadDaemon` fetch fresh state from peers
3. **Force-leave** (total recovery attempts >= `maxConsecutiveAbandonments * 3`, default 15): Transition to `Leaving` state, node exits cluster

**Key methods:**
- `abandonRound(key, reason)` — clears state, tracks consecutive failures, escalates
- `trackConsecutiveAbandonments(key)` — returns count, resets to 1 when key changes
- `triggerRecoveryDownload(key, count)` — increments total counter, decides between recovery and force-leave
- `forceLeave(key, totalAttempts)` — tries multiple source states (Ready, WaitingForDownload, DownloadInProgress, Observing)
- `attemptRecoveryDownload(key)` — transitions to WaitingForDownload from Ready or Observing
- `resetOnSuccessfulRound` — resets total counter after ConsensusFinished (prevents stale history)
- `trackInitFromDownloadFailure` — tracks init failures separately (CR2 fix for infinite download→init loops)

**Critical guards:**
- `forceLeave` checks if already in `Leaving` before trying transitions → prevents infinite loop
- `attemptRecoveryDownload` fallback does NOT queue `TimeTick` → breaks spin loop
- All cleanup paths call `ctx.pending.clear()` to prevent stale triggers

### 3.3 `ViewChangeManager.scala` (~188 lines)

**Purpose:** Leader eviction and view number management.

When StallDetector detects leader stall:
1. Increments `viewNumber` in consensus state
2. Selects new leader via `FacilitatorSelector.selectLeaderWeighted` with updated view number
3. Re-spreads own facility declaration to new facilitator set
4. Tracks `skippedEvictionCount` — after 3 skipped evictions, escalates to abandonment (P8 fix)

### 3.4 `FacilitatorSelector.scala` (~71 lines)

**Purpose:** Deterministic facilitator selection using rendezvous hashing (SHA-256 HRW).

```scala
def selectLeaderWeighted(
  candidates: NonEmptySet[PeerId],
  roundKey: String,
  viewNumber: Int,
  qualityScores: Map[PeerId, Long]
): PeerId
```

- Uses `SHA256(peerId + roundKey + viewNumber)` as hash
- Weights by quality scores (integer-only arithmetic, C2 fix)
- Deterministic: all nodes with same inputs select same leader
- **Critical:** Uses `.toLong` explicitly to prevent implicit numeric widening (audit fix)

### 3.5 `PeerQualityTracker.scala` (~160 lines)

**Purpose:** Local-only quality scoring based on consensus participation.

**Key behavior:**
- `recordRoundSuccess(signers: Set[PeerId])` — increments score for all signers (from `Signed[Artifact].proofs`)
- `recordRoundAbandoned(facilitators: Set[PeerId])` — decrements score for all facilitators
- Quality scores are **derived from artifact proofs (who signed)**, NOT from gossip state (`withdrawnFacilitators`/`removedFacilitators`) — this is the C1 fix that prevents non-deterministic quality divergence
- Includes decay/pruning to prevent unbounded map growth (phase 4 fix)
- `qualityDecayThreshold` is included in `deterministicConfigHash` for fork detection

### 3.6 `TrailingCommonAncestorFilter.scala` (~94 lines)

**Purpose:** Ouroboros-inspired deterministic facilitator filtering.

**Algorithm:**
- Reads `Signed[Snapshot].proofs` from last `tcaLookbackWindow` snapshots
- Peers must sign >= `tcaMinParticipation` snapshots to remain eligible
- **Early/recent split** (fix for post-rollback onboarding): lookback window divided into early (first 3) and recent (last 2). A peer is degraded only if it signed early snapshots but NOT any recent ones. New peers that only appear in recent snapshots pass through.
- Active in both Global L0 and Currency L0

### 3.7 `ConsensusLog.scala` (~145 lines)

**Purpose:** Structured logging utility.

**Format:** `[CONSENSUS:<CATEGORY>] round=<key> role=<role> event=<EVENT> k1=v1 k2=v2`

**Categories:** `LIFECYCLE`, `PHASE`, `STALL`, `QUORUM`, `FORK`, `FACILITATOR`, `PROPOSAL`, `VALIDATION`, `RECOVERY`, `RUMOR`

**Helper methods:**
- `ConsensusLog.format(category, key, role, pairs*)` — builds formatted string
- `ConsensusLog.info/warn/error/debug(logger, category, key, role, pairs*)` — logs at level
- `ConsensusLog.pid(peerId)` — truncated peer ID (first 8 chars)
- `ConsensusLog.pids(peerIds)` — truncated list with count
- `ConsensusLog.role(selfId, leader)` — returns "LEADER" or "FOLLOWER"

### 3.8 `ConsensusDirectSender.scala` (~46 lines)

**Purpose:** Targeted declaration delivery (replaces broadcast).

Sends declarations directly to specific peers instead of broadcasting to all. Used for facility declarations and re-spread after view changes.

### 3.9 `ConsensusHealthStatus.scala` (~63 lines)

**Purpose:** Tracks consensus health metrics.

```scala
case class ConsensusHealthStatus(
  consecutiveAbandonments: Int,
  totalRecoveryAttempts: Int
)
```

Exposed via HTTP endpoint (`ConsensusInfoRoutes`). Updated by `AbandonmentTracker`.

### 3.10 `EvictionVoteTracker.scala` (~78 lines)

**Purpose:** Scaffolding for future gossip-based deterministic eviction.

Currently tracks eviction votes locally. Intended to be extended with gossip protocol for cluster-wide eviction consensus.

---

## 4. Modified Components (Detailed)

### 4.1 `ConsensusFSM.scala`

**Old:** Simple command dispatcher with whitelist of allowed states.
**New:** Two-state FSM (IDLE/BUSY) with explicit command routing tables.

Key changes:
- `roundBlockedStates: Set[NodeState]` = `{WaitingForDownload, DownloadInProgress, Leaving}` — blacklist instead of whitelist
- `startRound` checks `isRunning` flag AND `roundBlockedStates` before starting
- `completeRound` calls `roundRunner.cleanupRound` first (cancels stall detector fibers), then pulls pending triggers and directly invokes `startRound` (no queue roundtrip)
- `handleWhileBusy` defers `FacilitateByEvent`/`TimeTick`/`StartRound` to `PendingTriggers`
- Metrics: `dag_consensus_fsm_command_processed`, `dag_consensus_fsm_round_started`, `dag_consensus_fsm_round_completed`, `dag_consensus_fsm_round_running`, `dag_consensus_round_blocked_by_state`, `dag_consensus_fsm_pending_deferred`

### 4.2 `ConsensusRoundRunner.scala`

**Old (~400 lines):** Contained both round facilitation AND a 278-line `roundMonitor` method with inline stall detection, unlock logic, and complex polling loop.
**New (~230 lines):** Round facilitation only. Stall detection delegated to `StallDetector`.

Key changes:
- Removed: entire `roundMonitor` method, `MonitorState` case class, `ResourcesInfo`, `getResourcesInfo`, `getCurrentDeclarationTimeout`, all stall-related logic
- Added: `spawnTracked(task)` — spawn fiber tracked by round lifecycle
- Added: `cancelRoundFibers` — cancel all tracked fibers on round completion
- Added: `cleanupRound` — signal cancel + cancel fibers (called by FSM before pulling next trigger)
- `scheduleTimeTrigger` now uses `supervisor.supervise` directly (NOT `spawnTracked`) — timer fiber survives round cleanup, preventing deadlock where next round cancels the timer before it fires
- `startRoundMonitor` now delegates to `stallDetector.startMonitor(key)`

### 4.3 `ConsensusStateUpdater.scala`

**Old:** All-to-all lock/unlock model with `UnlockConsensusUpdate` and `ConsensusStateUpdateFn`.
**New:** Leader-based 3-phase declarations (Facilities → Proposals → Signatures).

Key changes:
- Removed: lock/unlock logic, `UnlockConsensusUpdate` dependency
- Added: `tryUpdateConsensus(key, resources)` processes declarations in order:
  1. Collect facility declarations from all facilitators
  2. Once quorum of facilities received, leader creates proposal
  3. Collect signature declarations
  4. Once quorum of signatures received, finalize outcome
- Quorum is checked against active facilitators (excluding withdrawn/removed)
- Metrics track declaration counts per phase

### 4.4 `ConsensusStateAdvancer.scala`

**Old:** Simple outcome extraction.
**New:** 3-phase advancement with quorum checks and eligible facilitator filtering.

Key changes:
- Added: `EligibleFacilitators` — deterministic filtering using TCA
- Added: quorum feasibility checks at each phase transition
- Added: `viewNumber` tracking for view changes
- Added: removal penalties based on proofs (who signed) not gossip state

### 4.5 `ConsensusStateCreator.scala`

**Old:** Creates initial round state with all known peers.
**New:** Creates round state with TCA-filtered eligible facilitators and leader selection.

Key changes:
- Added: TCA filter integration — `TrailingCommonAncestorFilter.filterDegraded(candidates, recentSnapshots)`
- Added: Leader selection via `FacilitatorSelector.selectLeaderWeighted`
- Added: `EligibleFacilitators` wrapper type
- Quality scores derived from proofs (deterministic), not local gossip

### 4.6 `ConsensusState.scala`

**Old:** Flat state with `lockStatus: LockStatus`.
**New:** State with `viewNumber`, `leader`, `eligibleFacilitators`, no lock status.

Added fields:
- `viewNumber: Int` — incremented on view change (leader eviction)
- `leader: PeerId` — current round leader
- `eligibleFacilitators: EligibleFacilitators` — TCA-filtered peer set

Removed fields:
- `lockStatus: LockStatus` — entire lock/unlock model removed

### 4.7 `StateTransitions.scala`

**Old:** Basic `checkUpdate`, `initFromDownload`, `initFromRollback`.
**New:** Extended with cleanup, metrics, retry logic, and peer registration.

Key changes:
- `finalizeAndNotify`: Now records time metrics, prunes stale resources/events/peer registrations, handles `OUTCOME_CONFLICT`
- `registerPeer`: Registers newly observed peers for current consensus round
- `initFromDownload`: Added 20-retry policy with exponential backoff, post-retry validation, `setJoiningGracePeriod`
- `initFromRollback`: **Added full state cleanup** before init (CR8 fix):
  ```scala
  storage.clearAllConsensusState >>
  storage.clearAllPeerRegistrations >>
  storage.clearTimeTrigger >>
  storage.clearObservationKey >>
  ctx.pending.clear()
  ```

### 4.8 `ConsensusEventLoop.scala`

**Old:** Simple `Stream.repeatEval(queue.take).evalMap(fsm.handle)`.
**New:** Error-resilient event loop with recovery logic.

Key changes:
- `commandStream` now has `.handleErrorWith` that:
  - On `ConsensusFinished`/`RoundCompleted` failure: force `RoundCompleted` + `TimeTick` (unless `Leaving`)
  - On `InitializeFromDownload` failure: track via `abandonmentTracker.trackInitFromDownloadFailure`, transition to `WaitingForDownload`
- After successful `ConsensusFinished`: calls `abandonmentTracker.resetOnSuccessfulRound`
- `peerRegistrationStream`: error handling moved to per-element (`.handleErrorWith` inside `evalMap`) instead of stream-level — prevents one failed registration from killing the entire stream
- `leavingStream`: same per-element error handling
- `build()` now takes additional params: `selfId: PeerId`, `facilitatorSelector: FacilitatorSelector`, `peerQualityTracker: PeerQualityTracker[F]`
- `BuiltConsensusLoop` now includes `healthRef: Ref[F, ConsensusHealthStatus]`
- Wires up: `AbandonmentTracker`, `StallDetector`, `ViewChangeManager`, `EvictionVoteTracker`, `roundFibersRef`, `cancelSignalRef`

### 4.9 `ConsensusStorage.scala`

New methods added to the trait:
```scala
// Peer registration for lagging detection
private[consensus] def registerPeer(peerId: PeerId, key: Key): F[Boolean]
private[consensus] def getPeerRegistrations: F[Map[PeerId, Key]]
private[consensus] def clearAllPeerRegistrations: F[Unit]
private[consensus] def pruneStalePeerRegistrations(activePeers: Set[PeerId]): F[Unit]

// Resource cleanup
private[consensus] def pruneStaleResources(activeKey: Key): F[Unit]
private[consensus] def pruneStaleEvents(activePeers: Set[PeerId]): F[Unit]
private[consensus] def clearAllConsensusState: F[Unit]
private[consensus] def cleanupConflictedRound(key: Key): F[Unit]

// Time trigger management
def clearTimeTrigger: F[Unit]  // was private, now package-private
```

`registerPeer` has **never-downgrade semantics**: `maybeKey.filter(_ > newKey).getOrElse(newKey)` — keeps the higher key. This is by design for normal operation (peers advance monotonically) but requires `clearAllPeerRegistrations` before rollback to avoid stale high keys.

### 4.10 `ConsensusEngineContext.scala`

New fields:
```scala
selfId: PeerId
facilitatorSelector: FacilitatorSelector
peerQualityTracker: PeerQualityTracker[F]
```

### 4.11 `PendingTriggers.scala`

Rewritten for atomic get-and-clear semantics:
```scala
trait PendingTriggers[F[_]] {
  def setTime(): F[Unit]
  def setEvent(): F[Unit]
  def pullNext: F[Option[TriggerPriority]]
  def clear(): F[Unit]
}
```
`pullNext` atomically gets and clears the pending trigger. Priority: `Time` > `Event`.

### 4.12 `RumorHandler.scala`

Minor: replaced raw logger calls with `ConsensusLog` structured format.

### 4.13 `ConsensusStateRemover.scala`

Minor: added `ConsensusLog` structured logging.

### 4.14 Global L0: `GlobalSnapshotConsensusStateAdvancer.scala` (+784 lines)

Major rewrite:
- Quality scores now derived from `Signed[Artifact].proofs` (signers), NOT `withdrawnFacilitators`/`removedFacilitators`
- `removalPenalties` use `nonSigners` from proofs (deterministic, C1 fix)
- `previouslyRemoved` derived from proofs-based participation (not gossip)
- Added quality decay/pruning to prevent unbounded map growth
- Fork detection: `identifyForkedPeers` based on proofs, `checkForkByFacilitatorsHash` skipped when `viewNumber > 0`

### 4.15 Global L0: `GlobalSnapshotConsensusStateCreator.scala` (+172 lines)

Key changes:
- TCA filter integration for eligible facilitator calculation
- Proofs-based `previouslyRemoved` instead of gossip-based

### 4.16 Global L0: `GlobalSnapshotConsensusFunctions.scala` (+172 lines)

Key changes:
- `deterministicConfigHash` now includes TCA parameters (`tcaLookbackWindow`, `tcaMinParticipation`) and `qualityDecayThreshold`
- Fork detection logic updated

### 4.17 Currency L0: State Advancer & Creator

Mirrors Global L0 changes:
- Proofs-based quality scores (C1 fix, identical to Global L0)
- Integer quality scores (no Double conversion, S2-P1 fix)
- `CurrencySnapshotConsensusOps` added

### 4.18 `Gossip.scala` / `GossipRoundRunner.scala`

- Downgraded ERROR to DEBUG for peers in download/leaving states (noise reduction)
- Added error handling for gossip failures during state transitions

### 4.19 `RestartService.scala`

- `signalNodeForkedRestart`: falls back to `signalClusterLeaveRestart()` when peers not found in cluster storage (already removed)
- Wrapped recovery fiber in `handleErrorWith`

### 4.20 `GlobalSnapshotAcceptanceManager.scala` (+219 lines)

Changes related to state channel processing and fee handling.

### 4.21 MptStore / MerklePatriciaProducer

- `MptStore.scala`: Added savepoint key tracking
- `MerklePatriciaProducer.scala`, `InMemoryMerklePatriciaProducer.scala`, `FileSystemMerklePatriciaProducer.scala`: Savepoint API changes
- `MptStoreSavepointSuite.scala`: 188 lines of tests for savepoint key validation

### 4.22 `ConsensusInfoRoutes.scala`

Added health status endpoint exposing `ConsensusHealthStatus` (consecutive abandonments, total recovery attempts).

### 4.23 `Cluster.scala` / `Joining.scala`

Minor changes for responsive peer filtering.

### 4.24 `schema/peer.scala`

Added `isResponsive` method to `Peer`.

---

## 5. Deleted Components

| File | Reason |
|------|--------|
| `update/ConsensusStateUpdateFn.scala` | Lock/unlock model replaced by 3-phase declarations |
| `update/UnlockConsensusUpdate.scala` | Lock/unlock model removed entirely |
| `update/UnlockConsensusUpdateSuite.scala` | Tests for deleted code |

---

## 6. Consensus Protocol: Old vs New

### Old: Lock/Unlock Model

```
Round Start
  → All peers send LockConsensus to all peers
  → Wait for lock responses (timeout → stall, hang forever)
  → Process data, create artifact
  → All peers send UnlockConsensus to all peers
  → Wait for unlock responses
  → Finalize
```

**Problems:** O(n²) messages, deadlock-prone, no leader, no recovery.

### New: Leader-Based 3-Phase Protocol

```
Round Start
  → FacilitatorSelector picks leader (rendezvous hash)
  → TCA filter selects eligible facilitators
  → Phase 1 (Facilities): All facilitators send facility declarations
  → Phase 2 (Proposals): Leader creates proposal from facilities
  → Phase 3 (Signatures): All facilitators sign the proposal
  → Finalize when quorum of signatures received
```

**If leader stalls:** StallDetector triggers view change → new leader selected → re-spread declarations.

**If round stalls:** AbandonmentTracker escalates: retry → recovery download → force-leave.

---

## 7. Production Incident Fixes (8 Collapse Modes)

### CR1: MptStore Savepoint Corruption
**Symptom:** After recovery download, MptStore reverts to pre-download state, corrupting proposals.
**Root cause:** Savepoint from ordinal N restored in round at ordinal M (after recovery downloaded M).
**Fix:** Track ordinal key alongside savepoint; validate key before restore.
**Files:** `MptStore.scala`, `MerklePatriciaProducer.scala`, `MptStoreSavepointSuite.scala`

### CR2: Infinite Download→Init Failure Loop
**Symptom:** Node repeatedly downloads state, fails to init, downloads again — forever.
**Root cause:** Only `abandonRound` incremented `totalRecoveryAttempts`; `initFromDownload` failures didn't.
**Fix:** `AbandonmentTracker.trackInitFromDownloadFailure` increments counter; error handler in `ConsensusEventLoop` transitions to `WaitingForDownload` and tracks failure.
**Files:** `AbandonmentTracker.scala`, `ConsensusEventLoop.scala`

### CR3: Ghost State After Recovery
**Symptom:** First post-recovery round fails because ghost state entries from abandoned rounds interfere.
**Root cause:** Recovery only cleared current key's state, not all keys.
**Fix:** `clearAllConsensusState` + `clearAllPeerRegistrations` + `clearTimeTrigger` + `clearObservationKey` in `attemptRecoveryDownload`.
**Files:** `AbandonmentTracker.scala`, `ConsensusStorage.scala`

### CR4: Non-Deterministic Quality Scores → Forks
**Symptom:** Different nodes select different leaders → facilitatorsHash mismatch → fork.
**Root cause:** Quality scores derived from local gossip state (`withdrawnFacilitators`/`removedFacilitators`) which differs per node.
**Fix:** Derive quality from `Signed[Artifact].proofs` (consensus-agreed, deterministic). Integer-only arithmetic in `selectLeaderWeighted`.
**Files:** `PeerQualityTracker.scala`, `FacilitatorSelector.scala`, `GlobalSnapshotConsensusStateAdvancer.scala`, `CurrencySnapshotConsensusStateAdvancer.scala`

### CR5: Lagging Detection Cascade → Cluster-Wide Deadlock
**Symptom:** ALL nodes simultaneously detect each other as "lagging", all abandon, all try to download from each other.
**Root cause:** `=!=` operator meant peers at the PREVIOUS ordinal (normal during transition) triggered lagging detection.
**Fix:** Changed to strict `>` so only peers at strictly higher keys trigger lagging.
**Files:** `StallDetector.scala`

### CR6: Timer Fiber Deadlock
**Symptom:** After first EventTrigger round, no more TimeTick ever fires — consensus permanently halts.
**Root cause:** `scheduleTimeTrigger` used `spawnTracked`, so the timer fiber was added to `roundFibersRef`. When the next round's `cleanupRound` ran, it cancelled the timer fiber. Since `afterEventTrigger` saw `timeTrigger` ref still set, it assumed a fiber was alive — but it was dead.
**Fix:** `scheduleTimeTrigger` uses `supervisor.supervise` directly so timer fibers survive round cleanup.
**Files:** `ConsensusRoundRunner.scala`

### CR7: Leaving State Infinite Loop (21K+ iter/sec CPU spin)
**Symptom:** Node in `Leaving` state burns CPU at 21,000+ iterations/second.
**Root cause:** `TimeTick → startRound → abandon → forceLeave(fails, already Leaving) → recoveryDownload(fails, not Ready/Observing) → queue TimeTick → repeat`
**Fix:** 5-point fix:
1. `ConsensusFSM`: Add `Leaving` to `roundBlockedStates`
2. `AbandonmentTracker.forceLeave`: Detect already-Leaving, clean up and stop
3. `AbandonmentTracker.forceLeaveFromInitFailures`: Same guard
4. `AbandonmentTracker.attemptRecoveryDownload`: Remove `TimeTick` re-queue from fallback
5. `ConsensusEventLoop`: Suppress `TimeTick` in error handler when node is `Leaving`
**Files:** `ConsensusFSM.scala`, `AbandonmentTracker.scala`, `ConsensusEventLoop.scala`

### CR8: Rollback False Lagging Detection
**Symptom:** After rollback, node immediately abandons rounds → recovery download → "0 selectable peers" → stuck.
**Root cause:** `initFromRollback` did zero state cleanup. Pre-rollback peer registrations survived with keys higher than rollback ordinal. StallDetector saw `peersAtHigherKey=3/3 > 50%` → lagging.
**Fix:** Added full state cleanup to `initFromRollback`:
```scala
storage.clearAllConsensusState >>
storage.clearAllPeerRegistrations >>
storage.clearTimeTrigger >>
storage.clearObservationKey >>
ctx.pending.clear()
```
**Files:** `StateTransitions.scala`

---

## 8. Configuration Changes

### New Parameters in `ConsensusConfig`

| Parameter | Type | Default | Purpose |
|-----------|------|---------|---------|
| `tcaLookbackWindow` | `Int` | 5 | How many recent snapshots to check for TCA filtering |
| `tcaMinParticipation` | `Int` | 2 | Minimum snapshots a peer must sign to remain eligible |
| `qualityDecayThreshold` | `Int` | 100 | Quality map entries beyond this are pruned |
| `facilitiesTimeoutMultiplier` | `Double` | 0.3 | Multiplier for facilities phase stall timeout (was 0.5) |
| `maxConsecutiveAbandonments` | `Int` | 5 | Tier 1→2 escalation threshold |

### Changed in `dag-l0.conf` and `currency-l0.conf`

New sections for TCA and quality parameters. Check `.conf` files for exact values.

### `deterministicConfigHash` now includes

- `tcaLookbackWindow`
- `tcaMinParticipation`
- `qualityDecayThreshold`

All nodes must have identical values for these or they'll fork on `facilitatorsHash`.

---

## 9. Test Coverage

| Suite | Tests | What It Covers |
|-------|-------|---------------|
| `ConsensusCrisisFixesSuite` | 39 | All 8 crisis fixes (CR1-CR8) — savepoint validation, init failure tracking, recovery cleanup, abandonment escalation, fork determinism, resource cleanup, Leaving loop, rollback cleanup |
| `ConsensusAuditFixesSuite` | 71 | Security audit: C1 (proofs-based quality), C2 (integer arithmetic), H2 (quorum feasibility), H3 (lagging detection), M1-M4 (timeout tuning), P7-P11 (init recovery, view change loop, resource pruning, semaphore timeout, eviction tracker) |
| `StallDetectorSuite` | ~30 | Phase timeouts, lagging detection, view changes, adaptive intervals |
| `FacilitatorSelectorSuite` | 16 | Rendezvous hashing determinism, weighted selection, view number rotation |
| `PeerQualityTrackerSuite` | ~15 | Quality scoring, decay, pruning |
| `TrailingCommonAncestorFilterSuite` | ~15 | Early/recent split, degradation, new peer passthrough |
| `QuorumDeclarationsSuite` | ~20 | Quorum feasibility, withdrawn peer handling |
| `EligibleFacilitatorsSuite` | ~15 | Facilitator eligibility filtering |
| `PendingTriggersSuite` | ~10 | Atomic get-and-clear, priority ordering |
| `RemovalPenaltySuite` | ~15 | Proofs-based removal penalties |
| `MptStoreSavepointSuite` | ~10 | Savepoint key validation |

**Total:** ~250+ tests

---

## 10. How to Review

### Suggested order:

1. **Start with the entry point:** `ConsensusEventLoop.scala` — see how everything is wired together
2. **FSM:** `ConsensusFSM.scala` — understand IDLE/BUSY routing
3. **Round lifecycle:** `ConsensusRoundRunner.scala` — facilitation, timer scheduling, fiber management
4. **Stall detection:** `StallDetector.scala` — timeout logic, lagging detection, view changes
5. **Recovery:** `AbandonmentTracker.scala` — 3-tier escalation
6. **Protocol:** `ConsensusStateUpdater.scala` → `ConsensusStateAdvancer.scala` → `ConsensusStateCreator.scala` — 3-phase declarations
7. **Determinism:** `FacilitatorSelector.scala`, `PeerQualityTracker.scala`, `TrailingCommonAncestorFilter.scala` — all must be deterministic
8. **Global L0:** `GlobalSnapshotConsensusStateAdvancer.scala` — largest changed file, proofs-based quality
9. **Tests:** `ConsensusCrisisFixesSuite.scala` and `ConsensusAuditFixesSuite.scala` — each test documents an invariant

### Key invariants to verify:

- All quality scores derived from proofs (never gossip state)
- All arithmetic is integer-only (no Float/Double in leader selection)
- `deterministicConfigHash` includes all parameters that affect facilitator selection
- `clearAllPeerRegistrations` called before rollback and recovery download
- Timer fibers survive round cleanup (use `supervisor.supervise`, not `spawnTracked`)
- No `TimeTick` queued when node is in `Leaving` state
- Error handlers never swallow `ConsensusFinished`/`RoundCompleted` failures silently

---

## 11. Known Risks & Edge Cases

1. **`registerPeer` never-downgrade semantics:** By design, `registerPeer` keeps the higher key. This works during normal operation (peers advance monotonically) but requires explicit `clearAllPeerRegistrations` during rollback and recovery. If any new code path resets peer state without clearing registrations, stale high keys will cause false lagging detection.

2. **TCA filter cold start:** When a node first joins, it has no snapshot history for TCA filtering. The filter gracefully degrades (accepts all peers when history is insufficient), but during the first few rounds after cluster bootstrap, filtering is effectively disabled.

3. **View change loop:** If the new leader also stalls, another view change occurs. `skippedEvictionCount` limits this to 3 before escalating to full abandonment, but in a degraded cluster this could mean 3 × declaration timeout before recovery starts.

4. **Solo node behavior:** A single node runs consensus alone. With no peers, lagging detection is impossible (`totalRegisteredPeers < 3`), view changes don't apply, and TCA filtering has no effect. The timer mechanism (`scheduleTimeTrigger`) drives rounds forward.

5. **FS2 stream resilience:** Error handling was moved from stream-level to per-element in `peerRegistrationStream` and `leavingStream`. If an error is thrown OUTSIDE the `evalMap` (e.g., in the `mapFilter`), the stream will still terminate. This hasn't been observed in production but is theoretically possible.
