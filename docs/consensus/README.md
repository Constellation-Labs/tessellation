# Tessellation Consensus Process

This document provides an in-depth walkthrough of the Tessellation consensus mechanism. It is the definitive reference for how Tessellation consensus works.

## Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [Concurrent Loops & Command Queue](#2-concurrent-loops--command-queue)
3. [FSM States: IDLE vs BUSY](#3-fsm-states-idle-vs-busy)
4. [Outcome and Key Tracking](#4-outcome-and-key-tracking)
5. [Consensus Round Phases](#5-consensus-round-phases)
6. [Declaration Types](#6-declaration-types)
7. [Detailed Phase Transitions](#7-detailed-phase-transitions)
8. [Trigger System](#8-trigger-system)
9. [Facilitator Selection](#9-facilitator-selection)
10. [Leader Election & View Changes](#10-leader-election--view-changes)
11. [Stall Detection & Eviction](#11-stall-detection--eviction)
12. [Fork Detection](#12-fork-detection)
13. [Recovery Pipeline](#13-recovery-pipeline)
14. [Recovery Scenarios](#14-recovery-scenarios)
15. [Key Files Reference](#15-key-files-reference)

---

## 1. High-Level Architecture

The consensus engine follows an **event-driven architecture** with a central command queue. All state changes happen in response to commands, making the system deterministic and easier to test.

![Consensus State Machine](diagrams/consensus-fsm.png)

### Core Components

| Component | File | Purpose |
|-----------|------|---------|
| `ConsensusFSM` | `state/ConsensusFSM.scala` | Routes commands based on IDLE/BUSY state |
| `ConsensusEventLoop` | `engine/ConsensusEventLoop.scala` | Builds engine, runs command stream |
| `ConsensusManager` | `engine/ConsensusManager.scala` | External API (facade) |
| `ConsensusRoundRunner` | `engine/ConsensusRoundRunner.scala` | Round facilitation, trigger scheduling |
| `StallDetector` | `engine/StallDetector.scala` | Monitors round progress, triggers eviction |
| `ViewChangeManager` | `engine/ViewChangeManager.scala` | Leader re-election, peer eviction |
| `AbandonmentTracker` | `engine/AbandonmentTracker.scala` | Recovery download triggering |
| `ConsensusStateAdvancer` | `state/ConsensusStateAdvancer.scala` | Phase transitions within a round |
| `ConsensusStateCreator` | `state/ConsensusStateCreator.scala` | Creates new round states |

---

## 2. Concurrent Loops & Command Queue

The consensus engine uses an **unbounded command queue** to coordinate **5 concurrent streams**. All event sources feed commands into the queue, and the FSM processes them sequentially.

![Concurrent Loops](diagrams/consensus-concurrent-loops.png)

### Command Types

```scala
sealed trait ConsensusCommand

// Round Control
case class StartRound(trigger: Option[ConsensusTrigger])
case object TimeTick
case object FacilitateByEvent
case class ConsensusFinished(key: Key, outcome: Outcome, trigger: ConsensusTrigger)
case object RoundCompleted

// Rumor Processing
case class RumorReceived(rumor: Either[PeerRumor[_], CommonRumor[_]])
case class CheckUpdate(key: Key)

// Lifecycle
case class InitializeFromDownload(key: Key, artifact: Artifact, context: Context, isRecovery: Boolean)
case class InitializeFromRollback(key: Key, outcome: Outcome)
case object WithdrawFromConsensus
case class PeerObserved(peer: Peer)
```

### Stream 1: Command Stream (Main Event Loop)

The heart of the system — consumes commands and routes to FSM:

```scala
val commandStream: Stream[F, Unit] =
  Stream.repeatEval(queue.take).evalMap { cmd =>
    nodeStorage.getNodeState.flatMap { currentState =>
      // Skip stale commands during recovery
      val isRecovering = currentState === NodeState.WaitingForDownload ||
        currentState === NodeState.DownloadInProgress ||
        currentState === NodeState.WaitingForObserving
      val isStaleCommand = cmd match {
        case _: CheckUpdate | _: ConsensusFinished | RoundCompleted | TimeTick => true
        case _ => false
      }
      if (isRecovering && isStaleCommand) {
        logger.debug(s"Discarding stale ${cmd.getClass.getSimpleName}: node in $currentState")
      } else {
        fsm.handle(cmd)
      }
    }
  }
```

> **Note (v2 change):** The stale command guard prevents gossip/stall declarations from the previous round from crashing the event loop during recovery states.

### Stream 2: Gossip Processing

The `RumorHandlerWithQueue` receives rumors from the gossip layer and enqueues them:

```scala
// For each rumor type (Facility, Proposal, Signature, etc.)
RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration](queue)

// Flow: GossipStream → RumorHandlerWithQueue → queue.offer(RumorReceived(...))
```

**Note**: Rumors are stored immediately, but state updates happen via `CheckUpdate` commands.

### Stream 3: Peer Registration

Watches for new peers entering `Observing` state and collects their registration info:

```scala
clusterStorage.peerChanges.mapFilter {
  case Ior.Both(_, peer) if peer.state === NodeState.Observing => Some(peer)
  case Ior.Right(peer) if peer.state === NodeState.Observing   => Some(peer)
  case _ => None
}.evalMap(peer => collectRegistration(peer))
```

### Stream 4: Leaving Stream

Watches for node entering `Leaving` state to trigger withdrawal:

```scala
nodeStorage.nodeStates
  .filter(_ === NodeState.Leaving)
  .evalMap(_ => manager.withdrawFromConsensus)
```

### Stream 5: Stall Detection (Per Round)

Each active round spawns a supervised stall detection fiber that monitors for missing declarations. This is started by `ConsensusRoundRunner.startRoundMonitor()`:

```scala
def startRoundMonitor(key: Key): F[Unit] =
  for {
    signal <- Deferred[F, Unit]
    _ <- cancelSignalRef.set(Some(signal))
    _ <- spawnTracked {
      stallDetector.monitor(key, signal)
    }
  } yield ()
```

### Coordination

All streams feed commands into the same queue, and the FSM processes them sequentially:

```
Gossip    ──┐
Peers     ──┼──▶ Queue ──▶ FSM (sequential)
Leaving   ──┤
Timer     ──┘
```

This design means:
- **No race conditions** on consensus state
- **Deterministic** command processing order
- **Easy to test** by injecting commands directly

### RumorHandler: Store and Signal

The `RumorHandler` is a **receiver/dispatcher** that does no decision-making. It only:

1. **Stores data** in `ConsensusStorage`
2. **Signals the queue** to trigger processing

```
Gossip Layer
    │
    ▼
RumorHandler.process(rumor)
    │
    ├──► storage.addXxx()     ← Store the data
    │
    └──► queue.offer(...)     ← Signal FSM to process it
             │
             ▼
         FSM handles CheckUpdate → StateTransitions.checkUpdate()
                                       │
                                       ▼
                              ConsensusStateAdvancer (actual logic)
```

**Storage dispatch by rumor type:**

| Rumor Type | Storage Method |
|------------|----------------|
| `Facility` | `storage.addFacility(origin, key, f)` |
| `Proposal` | `storage.addProposal(origin, key, p)` |
| `MajoritySignature` | `storage.addSignature(origin, key, s)` |
| `BinarySignature` | `storage.addBinarySignature(origin, key, b)` |
| `DeclarationAck` | `storage.addPeerDeclarationAck(origin, key, kind, ack)` |
| `WithdrawDeclaration` | `storage.addWithdrawPeerDeclaration(origin, key, kind)` |
| `Event` | `storage.addEvent()` or `addTriggerEvent()` |
| `Artifact` | `storage.addArtifact(key, artifact)` |

---

## 3. FSM States: IDLE vs BUSY

The FSM tracks whether a consensus round is running via `isRunning: Ref[F, Boolean]`.

### IDLE State (isRunning = false)

When idle, the FSM can:
- **Start a new round** on `StartRound`, `TimeTick`, or `FacilitateByEvent`
- **Initialize** from download or rollback
- **Withdraw** from consensus
- **Process rumors** (always)

### BUSY State (isRunning = true)

When busy, the FSM:
- **Queues triggers** for later via `PendingTriggers`
- **Completes rounds** on `ConsensusFinished` or `RoundCompleted`
- **Processes rumors** (always)

```scala
def handle(cmd: ConsensusCommand): F[Unit] =
  isRunning.get.flatMap { running =>
    cmd match {
      case RumorReceived(r)   => rumorHandler.process(r)  // Always
      case CheckUpdate(key)   => transitions.checkUpdate(key)
      case PeerObserved(peer) => transitions.registerPeer(peer)
      case _ if running       => handleWhileBusy(cmd)
      case _                  => handleWhileIdle(cmd)
    }
  }
```

### Round-Blocked States

> **Note (v2 change):** The FSM blocks round starts when the node is in recovery or leaving states to prevent infinite loops.

```scala
private val roundBlockedStates: Set[NodeState] =
  Set(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.Leaving)

private def startRound(trigger: Option[ConsensusTrigger]): F[Unit] =
  nodeStorage.getNodeState.flatMap { state =>
    if (!roundBlockedStates.contains(state))
      isRunning.set(true) >> roundRunner.runRound(trigger)
    else
      logger.warn(s"ROUND_BLOCKED_BY_STATE: nodeState=$state")
  }
```

### Pending Triggers

If a trigger arrives while BUSY, it's stored for the next round:

```scala
// While BUSY:
case TimeTick                      => pending.setTime()
case FacilitateByEvent             => pending.setEvent()

// After round completes:
pending.pullNext match {
  case Some(TriggerPriority.Time)  => startRound(Some(TimeTrigger))
  case Some(TriggerPriority.Event) => startRound(Some(EventTrigger))
  case None                        => // Wait for next trigger
}
```

**Priority**: `TimeTrigger` takes precedence over `EventTrigger`.

---

## 4. Outcome and Key Tracking

Understanding how `Outcome`, `Key`, and the `outcomeKey` lens work together is crucial to understanding the consensus flow.

![Outcome Tracking](diagrams/consensus-outcome-tracking.png)

### What is an Outcome?

An `Outcome` (e.g., `GlobalConsensusOutcome`) represents the **result of a completed consensus round**:

```scala
final case class GlobalConsensusOutcome(
  key: GlobalSnapshotKey,                    // The ordinal (e.g., 42)
  facilitators: Facilitators,                // Who participated
  removedFacilitators: RemovedFacilitators,  // Who was evicted
  withdrawnFacilitators: WithdrawnFacilitators, // Who left voluntarily
  finished: Finished,                        // The actual artifact + context
  eligibleFacilitators: Option[EligibleFacilitators],
  removalPenalties: Map[PeerId, Int],        // Multi-round penalty tracking
  peerQuality: Map[PeerId, (Int, Int)]       // (completed, participated) for quality weighting
)
```

### What is a Key?

A `Key` is typically a `SnapshotOrdinal` (e.g., 41, 42, 43). It uniquely identifies:
- Which round we're deciding (in `ConsensusState.key`)
- Which outcome was produced (in `Outcome.key`)

### The Round Lifecycle with Keys

```
Storage State:
  lastOutcome = Outcome(key=41, artifact41, ctx41)
  statesR = Map.empty

StartRound triggered:
  1. RoundRunner reads: lastOutcome.key = 41
  2. Computes: nextKey = 41.next = 42
  3. Creates: ConsensusState(key=42, lastOutcome=Outcome(41), ...)

Round progresses:
  statesR = Map(42 -> ConsensusState(...))

Round completes:
  4. Advancer returns: (Previous(41), Outcome(key=42, ...))
  5. Storage validates: lastOutcome.key == 41 ✓
  6. Storage updates: lastOutcome = Outcome(key=42, ...)
  7. Storage cleans: remove state for key=42
```

### Why `Previous[Key]`?

The `Previous[Key]` wrapper prevents accidental double-updates:

```scala
def tryUpdateLastConsensusOutcomeWithCleanup(
  prevKey: Previous[Key],  // Must match current lastOutcome.key
  lastOutcome: Outcome
): F[Boolean]
```

If two rounds try to finalize simultaneously, only the first succeeds.

---

## 5. Consensus Round Phases

![Consensus Round Protocol](diagrams/consensus-round.png)

Each consensus round progresses through 4 phases (Global L0):

```
CollectingFacilities
       │
       │ All facilitators sent Facility
       ▼
CollectingProposals
       │
       │ All facilitators sent Proposal (leader creates artifact)
       ▼
CollectingSignatures
       │
       │ All signatures collected
       ▼
    Finished
```

### Phase Requirements

| Phase | Requirement | Who Acts |
|-------|-------------|----------|
| CollectingFacilities | All active facilitators sent `Facility` | Everyone |
| CollectingProposals | Leader creates artifact, all validate & send `Proposal` | Leader + validators |
| CollectingSignatures | All send `MajoritySignature` | Everyone |
| Finished | Outcome persisted | — |

> **Note (v2 change):** The advancer requires **all** facilitator declarations before transitioning — there is no quorum threshold in the advancer itself. Liveness is provided by `StallDetector` evicting unresponsive peers when safe.

---

## 6. Declaration Types

Each phase involves peers exchanging **declarations** via gossip:

### Facility

Sent at the start of a round. Contains:
- `eventHashes: Set[Hash]` — Events from local mempool
- `candidates: Candidates` — Peers that may join
- `trigger: Option[ConsensusTrigger]` — What triggered this round
- `facilitatorsHash: Hash` — Hash of current facilitator set
- `lastGlobalSnapshotOrdinal: SnapshotOrdinal`
- `lastGlobalSnapshotHash: Hash`

### Proposal

Sent after facilities are collected. Contains:
- `hash: Hash` — Hash of the proposed artifact
- `facilitatorsHash: Hash`

### MajoritySignature

Sent after proposals are collected. Contains:
- `signature: Signature` — Signature over majority artifact hash
- `facilitatorsHash: Hash`

### BinarySignature (Currency L0 only)

Sent after signatures are collected. Contains:
- `signature: Signature` — Signature over binary artifact
- `facilitatorsHash: Hash`

---

## 7. Detailed Phase Transitions

### CollectingFacilities → CollectingProposals

**Trigger**: All facilitators have sent `Facility` declarations

**Actions**:
1. Pick **majority trigger** from all facilities
2. Merge event hashes from all facilities
3. **Leader** creates **proposal artifact** using consensus functions
4. Compute artifact **hash**
5. Spread `Proposal(hash, facilitatorsHash)` via gossip
6. Leader spreads artifact via common gossip

```scala
private def toProposalsPhase(
  state: State,
  facilities: SortedMap[PeerId, Facility]
): F[Option[Transition]] = {
  val (bound, candidates, triggers) = facilities.foldMap(...)

  pickMajority(triggers).flatTraverse { majorityTrigger =>
    buildProposalTransition(state, bound, candidates, majorityTrigger)
  }
}
```

### CollectingProposals → CollectingSignatures

**Trigger**: All facilitators have sent `Proposal` declarations

**Actions**:
1. Collect all proposal **hashes**
2. Find **majority artifact** (most common hash)
3. Validate the majority artifact
4. **Sign** the majority hash
5. Spread `MajoritySignature(signature, facilitatorsHash)`

```scala
private def toSignaturesPhase(
  state: State,
  status: CollectingProposals,
  resources: Resources,
  proposals: SortedMap[PeerId, Proposal]
): F[Option[Transition]] = {
  val hashes = proposals.values.toList.map(_.hash)

  findMajorityArtifact(state, status, resources, hashes).flatMap {
    case Some(majorityInfo) => buildSignatureTransition(...)
    case None               => none[Transition].pure[F]
  }
}
```

### CollectingSignatures → Finished

**Trigger**: All valid signatures collected

**Actions**:
1. Collect all `MajoritySignature` declarations
2. **Verify** each signature proof
3. Build `Signed[Artifact]` with valid proofs
4. Persist to storage
5. Build `Outcome` with facilitator metadata

```scala
private def toFinishedPhase(
  state: State,
  status: CollectingSignatures,
  signatures: SortedMap[PeerId, MajoritySignature]
): F[Option[Transition]] = {
  val proofs = signatures.map { case (id, sig) =>
    SignatureProof(PeerId._Id.get(id), sig.signature)
  }

  for {
    valid <- proofs.filterA(verifySignatureProof(hash, _))
    result <- buildFinishedTransition(state, status, valid)
  } yield result
}
```

---

## 8. Trigger System

Consensus rounds can be triggered by two mechanisms. See [ADR-0004](../adr/0004-global-snapshot-trigger.md) for design rationale.

### TimeTrigger

- Scheduled at regular intervals (`timeTriggerInterval`, default 43s for DAG L0)
- Higher priority than EventTrigger
- Cleared when a round starts with TimeTrigger

### EventTrigger

- Fired when a "trigger event" arrives (e.g., from L1)
- Determined by `triggerPredicate` in consensus functions
- Lower priority than TimeTrigger

### Trigger Flow

```
                    ┌──────────────────┐
                    │ scheduleTimeTrigger │
                    └─────────┬────────┘
                              │
              sleep(timeTriggerInterval)
                              │
                              ▼
                    ┌──────────────────┐
                    │    TimeTick      │
                    └─────────┬────────┘
                              │
        ┌─────────────────────┴─────────────────────┐
        │                                           │
        ▼                                           ▼
   isRunning?                                  isRunning?
      false                                       true
        │                                           │
        ▼                                           ▼
  StartRound(TimeTrigger)                   pending.setTime()
```

### Time Trigger Scheduling

After each round completes, the next time trigger is scheduled:

```scala
private def scheduleTimeTrigger: F[Unit] =
  for {
    nextTime <- Async[F].monotonic.map(_ + config.timeTriggerInterval)
    _ <- storage.setTimeTrigger(nextTime)
    _ <- supervisor.supervise {
      Temporal[F].sleep(config.timeTriggerInterval) >>
        checkAndTriggerTime
    }
  } yield ()
```

> **Note:** The timer fiber uses `supervisor.supervise` directly (not `spawnTracked`) so it survives round cleanup. This prevents deadlock where a subsequent round's cleanup would cancel the timer before it fires.

---

## 9. Facilitator Selection

Facilitator selection is a multi-stage process that produces a deterministic facilitator set. See [ADR-0006](../adr/0006-selecting-facilitators.md) for design rationale.

### Selection Pipeline

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Previous       │     │  Seedlist       │     │  TCA Filter     │
│  Eligible +     │────►│  Filter         │────►│  (Proof-based)  │
│  Candidates     │     │                 │     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                                                        │
                                                        ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Rendezvous     │     │  Min Quorum     │     │  Collateral +   │
│  Hashing        │◄────│  Floor          │◄────│  Penalty        │
│  Subset         │     │                 │     │  Exclusion      │
└─────────────────┘     └─────────────────┘     └─────────────────┘
```

### Step-by-Step

1. **Base Set**: Previous eligible facilitators + approved candidates + self
2. **Seedlist Filter**: If seedlist is configured, only include matching peers
3. **TCA Filter**: Exclude degraded peers based on proof participation in last round
4. **Collateral Filter**: Apply `facilitatorFilter` (e.g., minimum stake requirements)
5. **Penalty Exclusion**: Exclude peers with active removal penalties
6. **Min Quorum Floor**: If exclusions would drop below `minViableQuorum`, bypass penalties
7. **Subset Selection**: Apply rendezvous hashing if `maxFacilitatorCount` is set

### TCA (Trailing Common Ancestor) Filter

The TCA filter uses consensus-agreed data for determinism:

```scala
// Compares who was supposed to sign vs who actually signed
val lastFacilitators = lastOutcome.facilitators.value.toSet
val lastSigners = lastOutcome.finished.signedMajorityArtifact.proofs.map(_.id.toPeerId).toSet
val degraded = tcaFilter.degradedPeers(lastFacilitators, lastSigners)
```

### Minimum Viable Quorum

> **Note (v2 change):** Penalties can never reduce the facilitator set below the majority threshold. This prevents `PeerQualityTracker` from shrinking the cluster to an unviable size.

```scala
val minViableQuorum = math.max(3, (allEligible.size / 2) + 1)
val eligibleThisRound = {
  val excluded = previouslyRemoved ++ penalizedPeers
  val filtered = allEligible.filterNot(excluded.contains)
  if (filtered.size >= minViableQuorum) filtered
  else allEligible  // Bypass penalties if would breach quorum
}
```

### Rendezvous Hashing

The `FacilitatorSelector` uses rendezvous hashing (Highest Random Weight) for deterministic subset selection:

```scala
// Per-peer score: SHA-256(entropy ++ peerId)
private def rendezvousScore(peerIdHex: String, entropyHex: String): BigInt = {
  val md = MessageDigest.getInstance("SHA-256")
  md.update(hexToBytes(entropyHex))
  md.update(hexToBytes(peerIdHex))
  BigInt(1, md.digest())
}

def select(candidates: List[PeerId], entropy: Hash): List[PeerId] =
  candidates.sorted(orderByScore(entropy).toOrdering).take(maxCount)
```

**Properties:**
- **Deterministic**: All honest nodes compute the same subset
- **IID uniform distribution**: Each peer's score is independently random
- **Zero autocorrelation**: Selection in round N is independent of round N-1

---

## 10. Leader Election & View Changes

### Leader Selection

The round leader is chosen by rendezvous hashing over the facilitator set:

```scala
def selectLeader(facilitators: List[PeerId], entropy: Hash, viewNumber: Int): PeerId = {
  implicit val scoreOrder: Order[PeerId] = orderByScore(entropy)
  val sorted = facilitators.sorted(scoreOrder.toOrdering)
  val index = viewNumber % sorted.size
  sorted(index)
}
```

On view change (leader failure), `viewNumber` increments, producing a different leader without changing the facilitator set.

### Quality-Weighted Leader Selection

> **Note (v2 change):** Leader selection can use consensus-agreed quality scores for tie-breaking, giving preference to more reliable peers.

```scala
def selectLeaderWeighted(
  facilitators: List[PeerId],
  entropy: Hash,
  qualityScores: Map[PeerId, (Int, Int)]  // (completed, participated)
): PeerId = {
  // Tier = failures (participated - completed). Lower tier = better.
  // Uses integer-only arithmetic to avoid platform-dependent float differences.
  val sorted = facilitators.sortBy { pid =>
    val (completed, participated) = qualityScores.getOrElse(pid, (0, 0))
    val tier = if (participated > 0) participated - completed else 0
    (tier, rendezvousScore(pid.value.value, entropy.value))
  }
  sorted(viewNumber % sorted.size)
}
```

### View Change Protocol

> **Note (v2 change):** The Lock/ACK/Vote mechanism from v1 is **REMOVED**. View changes are now handled by `ViewChangeManager` with deterministic leader re-election.

When a stall is detected, `ViewChangeManager.performViewChange()` is called:

```scala
def performViewChange(key: Key, currentState: ConsensusState): F[Unit] = {
  val newViewNumber = currentState.viewNumber + 1
  val newLeader = facilitatorSelector.selectLeader(
    currentState.facilitators.value,
    currentState.entropy,
    newViewNumber
  )

  peerQualityTracker.recordViewChange(currentState.leader) >>
    storage.condModifyState(key) {
      case Some(state) if state.viewNumber === currentState.viewNumber =>
        state.copy(viewNumber = newViewNumber, leader = newLeader).some
      case _ => none  // State advanced, no-op
    } >>
    queue.offer(CheckUpdate(key))
}
```

### View Change with Eviction

When facilitators fail to declare within the timeout, they can be evicted:

```scala
def performViewChangeWithEviction(
  key: Key,
  currentState: ConsensusState,
  peersToEvict: Set[PeerId]
): F[Unit] = {
  val remainingFacilitators = currentState.facilitators.value.filterNot(peersToEvict.contains)

  if (remainingFacilitators.size < 2) {
    // Can't evict below minimum — track and fall back
    skippedEvictionCountRef.updateAndGet(_ + 1).flatMap { skipped =>
      if (skipped >= maxSkippedEvictions)
        // Signal escalation to abandonment
        logger.error("EVICTION_LOOP_ESCALATION")
      else
        performViewChange(key, currentState)
    }
  } else {
    // Successful eviction
    val newLeader = facilitatorSelector.selectLeader(remainingFacilitators, entropy, newViewNumber)
    storage.condModifyState(key) { state =>
      state.copy(
        facilitators = Facilitators(remainingFacilitators),
        removedFacilitators = RemovedFacilitators(state.removedFacilitators.value ++ peersToEvict),
        viewNumber = newViewNumber,
        leader = newLeader
      ).some
    }
  }
}
```

---

## 11. Stall Detection & Eviction

> **Note (v2 change):** The Lock/ACK/Vote mechanism from v1 is **completely replaced** by `StallDetector` with graduated response.

![Stall Detector](diagrams/stall-detector.png)

### Architecture

`StallDetector` is the orchestrator that polls state periodically and delegates to focused components:
- **ViewChangeManager**: Deterministic leader re-election on proposal stalls
- **AbandonmentTracker**: Consecutive failure tracking, resource cleanup, recovery download

### Stall Detection Flow

```
Poll (100ms-1000ms adaptive)
  → Detect status/resource changes → queue CheckUpdate
  → Calculate phase-adaptive timeout
  → If leader unresponsive → early view change (ViewChangeManager)
  → If timeout exceeded:
      → Proposal phase: view change (ViewChangeManager)
      → Other phases: count toward abandon
  → After maxStallCycles or maxRoundDuration → abandon (AbandonmentTracker)
  → Update health snapshot on each cycle
```

### Graduated Response

> **Note (v2 change):** The first stall timeout produces a **warning** without eviction, effectively doubling the tolerance window. The second stall triggers eviction if quorum allows.

```scala
if (stallCount == 0) {
  // First timeout — warn only, give peers one more cycle
  logger.warn("PEER_STALL_WARNING", ...)
  StallResult(didStall = true, quorumInfeasible = false)
} else {
  // Second+ timeout — evict missing peers
  viewChangeManager.performViewChangeWithEviction(key, state, missingPeers)
}
```

### Quorum Floor

The quorum floor uses the **cluster-wide Ready peer count**, not the current round's facilitator count. This prevents cascade eviction from shrinking quorum to allow degenerate minority consensus:

```scala
clusterStorage.getResponsivePeers.map(_.count(_.state === NodeState.Ready)).flatMap { readyPeerCount =>
  val clusterSize = math.max(readyPeerCount + 1, totalFacilitators)
  val minQuorum = (clusterSize / 2) + 1
  val quorumInfeasible = remaining < minQuorum
  // ...
}
```

| Cluster Size | Min Quorum | Max Tolerable Failures |
|:---:|:---:|:---:|
| 4 | 3 | 1 |
| 5 | 3 | 2 |
| 6 | 4 | 2 |
| 7 | 4 | 3 |
| 8 | 5 | 3 |
| 9 | 5 | 4 |

### Abandon Reasons

> **Note (v2 change):** The `AbandonReason` ADT provides structured tracking of why rounds fail.

```scala
sealed trait AbandonReason { def retriable: Boolean }

case class QuorumInfeasible(active, required, clusterSize) extends AbandonReason
  // retriable = true — node waits for quorum restoration

case class Lagging(peersAhead, totalPeers, totalRegs) extends AbandonReason
  // retriable = false — node is behind majority

case object EvictionLoopStuck extends AbandonReason
  // retriable = false — repeated eviction skips below minimum

case class RoundTimeout(elapsed, max) extends AbandonReason
  // retriable = false — round exceeded maxRoundDuration

case class MaxStalls(count) extends AbandonReason
  // retriable = false — stuck after maxStallCycles
```

- **Retriable** abandonments (`QuorumInfeasible`) do not count toward the recovery threshold
- **Non-retriable** abandonments increment the consecutive count
- After `maxConsecutiveAbandonments` (default: 5), recovery download is triggered

---

## 12. Fork Detection

![Fork Detection](diagrams/fork-detection.png)

### Chain Tip Sampling

The `EventGossipDaemon` samples chain tips during its ~10s heartbeat:

1. Select 3 random mesh peers
2. Call `GET /events/ihave` on each → `ChainTip(ordinal, hash)`
3. Store tips in `MeshState`
4. Call `ForkRecoveryDetector.detectForkDivergence`
5. If fork detected → call `onForkDetected` callback

### Two Detection Modes

| Mode | Condition | Meaning |
|------|-----------|---------|
| **Lagging fork** | `majorityOrdinal - localOrdinal > 2` | Node fell behind majority chain |
| **Running fork** | Same ordinal as peers, but different hash (majority disagrees) | Node on minority fork at same height |

Both modes require the majority group to be >50% of reporters before triggering.

```scala
def detectForkDivergence: F[Option[ForkRecoveryInfo]] = {
  // Group peers by (ordinal, hash) — the full chain tip identity
  val tipGroups = chainTips.groupBy { case (_, tip) => (tip.ordinal, tip.snapshotHash) }
  val ((majorityOrdinal, majorityHash), majorityGroup) = tipGroups.maxBy(_._2.size)
  val isMajority = majorityGroup.size > chainTips.size / 2

  if (!isMajority) none  // No clear majority — can't determine canonical chain
  else {
    val lag = majorityOrdinal.value.value - localOrdinal.value.value
    val isLagging = lag > forkLagThreshold  // Default: 2

    // Check for running fork: same ordinal, different hash
    val peersAtLocalOrdinal = chainTips.filter(_._2.ordinal == localOrdinal)
    val peersWithDifferentHash = peersAtLocalOrdinal.filter(_._2.snapshotHash != localHash)
    val isRunningFork = peersAtLocalOrdinal.size >= 2 &&
      peersWithDifferentHash.size > peersAtLocalOrdinal.size / 2

    if (isLagging || isRunningFork) ForkRecoveryInfo(...).some
    else none
  }
}
```

### Fork Detection Suppression

Fork detection is suppressed when the node is already in recovery states to prevent restart loops:
- `Observing`
- `DownloadInProgress`
- `WaitingForDownload`

---

## 13. Recovery Pipeline

### Node State Machine

![Node State Machine](diagrams/node-state-machine.png)

<details>
<summary>Source: diagrams/node-state-machine.dot</summary>

Render with: `dot -Tsvg diagrams/node-state-machine.dot -o diagrams/node-state-machine.png`
</details>

Every Tessellation node transitions through these states. The recovery path
(red arrows) allows nodes to return to `WaitingForDownload` from either
`Ready` or `WaitingForReady` when a fork is detected or consecutive
abandonments exhaust the recovery threshold.

| From | To | Trigger |
|------|----|---------|
| Ready | WaitingForDownload | Fork detected, or 5 consecutive non-retriable abandonments |
| WaitingForReady | WaitingForDownload | Fork detected, or stuck-at-ordinal recovery |
| Observing | _(suppressed)_ | Fork detection suppressed — waits for observe to complete |
| WaitingForDownload | DownloadInProgress | DownloadDaemon acquires semaphore |
| DownloadInProgress | WaitingForObserving | Download completes |
| WaitingForObserving | Observing | Observe phase begins |
| Observing | WaitingForReady | Observe offset reached |
| WaitingForReady | Ready | First round completes successfully |

![Recovery Decision Tree](diagrams/recovery-decision-tree.png)

### Recovery Triggers

Recovery can be triggered by:

1. **AbandonmentTracker**: After `maxConsecutiveAbandonments` (default: 5) non-retriable abandonments
2. **ForkRecoveryDetector**: Chain tip divergence detected

### Recovery Pipeline Steps

1. **Detection** — Either tracker triggers recovery.

2. **State guard** — If node is already in Observing/DownloadInProgress/WaitingForDownload,
   the trigger is suppressed to prevent restart loops.

3. **Flag + transition** — `isRecovery` flag set on NodeStorage, node transitions
   to `WaitingForDownload`.

4. **recoveryStart** — DownloadDaemon dispatches to `recoveryDownload`:
   - Clear in-memory caches (lastN, lastGlobal) — NOT disk
   - Fetch latest tip from peers
   - Download only the gap (walk back from tip, stop at persisted hash on disk)
   - `setForRecovery` bypasses sequential prepend requirement on `SnapshotStorage`

5. **rejoinAfterRecovery** — Send `twoWayHandshake` to all known peers.
   Restores P2P mesh membership after `LocalHealthcheck` pruned the node
   during isolation.

6. **recoveryObserve** — Reset storage heads, then observe N rounds
   (random offset 1-5 per node, staggering re-entry to prevent thundering herd).
   Sync consensus `SnapshotStorage` head after observe completes.

7. **initFromDownload** — `isRecovery=true` skips the 43s TimeTick deferral.
   Grace period counter set to 3 (suppresses false `FORK_DETECTED` from
   stale `facilitatorsHash` in PeerQualityTracker).

8. **First successful round** — Node transitions `WaitingForReady → Ready`.
   Grace period counts down: 3 → 2 → 1 → 0.

### Failure Handling

- **Download failure during isolation**: `isRecovery` flag preserved across retries.
  DownloadDaemon sleeps 10s backoff, then retries `recoveryDownload` (not the full
  download path). Metadata fetch has 5 retries with exponential backoff (~60s).
- **Stale chain tips**: When all peers are unreachable during isolation, stale chain
  tips are cleared from `MeshState` to prevent false fork detection after restore.
- **Force leave**: After `totalRecoveryAttempts ≥ 15` (3× recovery cycles),
  node transitions to `Leaving → Offline`. Requires manual restart.

```scala
private val maxTotalRecoveryAttempts: Int = config.maxConsecutiveAbandonments * 3

private def triggerRecoveryDownload(key: Key, consecutiveCount: Int): F[Unit] =
  totalRecoveryAttemptsRef.updateAndGet(_ + 1).flatMap { totalAttempts =>
    if (totalAttempts >= maxTotalRecoveryAttempts)
      forceLeave(key, totalAttempts)  // Break pathological loops
    else
      attemptRecoveryDownload(key)
  }
```

---

## 14. Recovery Scenarios

### Scenario A: Single Node Isolation (kill1)

**Tested:** 1 of 8 nodes isolated, ~6 min recovery at production timings.

See [sequence-kill1-happy-path.md](diagrams/sequence-kill1-happy-path.md) for
full Mermaid sequence diagram.

**Summary:**
- Isolated node accumulates retriable `QuorumInfeasible` abandonments (no recovery triggered)
- After network restore: non-retriable abandonments begin (sees lag)
- After 5 non-retriable abandonments: recovery download triggered
- Gap download → rejoin → observe 1-5 rounds → immediate facilitation
- 8/8 proofs restored

### Scenario B: Three Node Isolation (kill3)

**Tested:** 3 of 8 nodes isolated, ~9 min recovery at production timings.

See [sequence-kill3.md](diagrams/sequence-kill3.md) for full Mermaid sequence
diagram.

**Summary:**
- Healthy 5 nodes keep producing (5 ≥ minQuorum=5)
- Isolated 3 form minority fork (different hashes)
- Fork detection fires 22-32s after network restore (natural stagger)
- Random observe offsets (1-5 rounds) prevent thundering herd
- Nodes rejoin gradually: fac grows 5 → 7 → 8
- 8/8 proofs restored

### Scenario C: Symmetric Partition (kill4)

**Tested:** 4 of 8 nodes isolated. 7/8 recover, 1 stuck. **Known limitation.**

See [sequence-kill4-limitation.md](diagrams/sequence-kill4-limitation.md) for
full Mermaid sequence diagram.

**Summary:**
- Both sides below quorum (4 < minQuorum=5)
- Retriable `QuorumInfeasible` abandonments (no recovery triggered)
- 1-2 minority snapshots may be produced before stall detection kicks in
- After restore: 7/8 nodes self-recover via fork detection
- After restore: 7/8 nodes self-recover via fork detection
- gl0-7 stuck: persisted forked ordinal whose successor doesn't exist on canonical chain
- **Mitigation:** restart gl0-7

### Known Limitations

#### Kill 4/8 Observe Deadlock

When both partitions lose quorum (4+4 with minQuorum=5), neither side can
produce snapshots. After restore, one recovering node may have persisted a
forked ordinal whose successor was never produced on the canonical chain.
The download walker gets stuck looking for a non-existent ordinal.

**Mitigation:** External monitoring restarts the stuck node. 7/8 nodes
self-recover.

#### Session Token on Eviction

If a node is evicted and its session token advances (e.g., restart during
partition), `ClusterStorage.addPeer` with the `>` comparison rejects it on
rejoin. The peer's session would be higher than the cluster's record,
but the registration data differs.

**Status:** Identified, not yet addressed.

#### Recovery Timing

Recovery takes ~6-9 minutes at production timings:
- 5 consecutive abandonments × ~57s per cycle ≈ 5 minutes
- Plus download + observe + first round ≈ 1-2 minutes

Fork detection is faster (~10-30s after network restore) but only fires
after the node can reach peers — during isolation, the node can't sample
chain tips.

#### Penalty Cycle Waste

When recovery takes multiple penalty cycles (`removalPenaltyRounds=3`), each
expired penalty re-admits recovering nodes as facilitators. They stall the
round (~35s), get evicted again, and the cycle repeats. ~70s wasted per cycle.

#### Healthy Nodes Triggering Recovery

In symmetric partitions, healthy nodes that can't reach quorum may accumulate
`MaxStalls` after the stall cycle budget exhausts. After 5 total non-retriable
abandonments, they unnecessarily trigger recovery download. `QuorumInfeasible`
abandonments don't count, but `MaxStalls` can still fire after 5 stall cycles
without progress.

---

## 15. Key Files Reference

### Engine Layer (`consensus/engine/`)

| File | Purpose |
|------|---------|
| `ConsensusCommand.scala` | Command ADT definitions |
| `ConsensusEventLoop.scala` | Builds and wires components |
| `ConsensusManager.scala` | External API facade |
| `ConsensusRoundRunner.scala` | Round facilitation, trigger scheduling |
| `StallDetector.scala` | Phase-aware stall monitoring, graduated eviction |
| `ViewChangeManager.scala` | Leader re-election, peer eviction |
| `AbandonmentTracker.scala` | Consecutive failure tracking, recovery trigger |
| `PendingTriggers.scala` | Queues triggers while BUSY |
| `EvictionVoteTracker.scala` | Local eviction vote tracking (scaffolding) |

### State Layer (`consensus/state/`)

| File | Purpose |
|------|---------|
| `ConsensusFSM.scala` | Command routing based on IDLE/BUSY |
| `ConsensusState.scala` | Immutable round state snapshot |
| `ConsensusStateAdvancer.scala` | Phase transition logic |
| `ConsensusStateCreator.scala` | Creates new round states |
| `ConsensusStateRemover.scala` | Handles withdrawal |
| `ConsensusStateUpdater.scala` | Updates state from declarations |
| `ConsensusEngineContext.scala` | Shared dependencies bundle |
| `RumorHandler.scala` | Processes rumors, stores declarations |
| `StateTransitions.scala` | High-level state change logic |

### Supporting Files

| File | Purpose |
|------|---------|
| `declaration.scala` | Declaration types (Facility, Proposal, etc.) |
| `trigger.scala` | Trigger types (TimeTrigger, EventTrigger) |
| `ConsensusStorage.scala` | Storage for state and declarations |
| `ConsensusResources.scala` | Resources gathered for a round |
| `FacilitatorSelector.scala` | Rendezvous hashing for selection/leader |
| `PeerQualityTracker.scala` | Score-based peer assessment |
| `TrailingCommonAncestorFilter.scala` | Proof-based peer quality, removal penalties |

### Global Snapshot Specific (`dag-l0/infrastructure/snapshot/`)

| File | Purpose |
|------|---------|
| `GlobalSnapshotConsensusStateCreator.scala` | Facilitator selection pipeline |
| `GlobalSnapshotConsensusStateAdvancer.scala` | Phase transitions for global snapshots |
| `GlobalSnapshotConsensusFunctions.scala` | Artifact creation, validation |

### Fork Recovery & Download

| File | Purpose |
|------|---------|
| `ForkRecoveryDetector.scala` | Hash-based fork detection from chain tips |
| `EventGossipDaemon.scala` | Mesh gossip, chain tip sampling |
| `MeshState.scala` | Adaptive mesh connectivity, chain tip storage |
| `Download.scala` (dag-l0) | `recoveryDownload`, `recoveryObserve`, observe offset |
| `DownloadDaemon.scala` | Recovery vs normal download dispatch |
| `StateTransitions.scala` | `initFromDownload` with `isRecovery` flag |
| `Joining.scala` | `rejoinAfterRecovery` (P2P mesh restoration) |
| `ClusterStorage.scala` | `addPeer` same-session rejoin |
| `SnapshotStorage.scala` | `setForRecovery` (bypass sequential prepend) |
| `NodeStorage.scala` | `isRecoveryDownload` flag, grace period counter |

### Related ADRs

| ADR | Topic |
|-----|-------|
| [0004-global-snapshot-trigger.md](../adr/0004-global-snapshot-trigger.md) | TimeTick trigger design |
| [0006-selecting-facilitators.md](../adr/0006-selecting-facilitators.md) | Facilitator selection |
| [0013-delayed_download.md](../adr/0013-delayed_download.md) | Download deferral |
| [0014-download-for-incremental-snapshots.md](../adr/0014-download-for-incremental-snapshots.md) | Incremental download |

---

## Sequence Diagram

For a detailed view of the full consensus lifecycle including gossip, queue, and FSM interactions, see:

![Consensus Sequence](diagrams/consensus-sequence.png)
