# Consensus Refactoring: Leader-Based Protocol with Push Gossip

## Overview

This document describes the consensus protocol refactoring implemented across 6 incremental steps. Each step is independently deployable and backward-compatible with the previous protocol state.

The refactoring addresses several interrelated problems in the original consensus:

1. **Stall/Lock/Unlock complexity** causing deadlocks (5 deadlock fixes on this branch)
2. **5-phase all-to-all protocol** with too many round-trip opportunities for stall
3. **Pull-based gossip** adding up to 1s latency per phase
4. **Quorum ambiguity** requiring complex "safe majority gate" logic

---

## Architecture Before vs After

### Before: 5-Phase All-to-All with Lock/Unlock

```
                    ALL PEERS PROPOSE
                    ┌─────────────────┐
                    │                 │
  ┌──────────────┐  │  ┌───────────┐  │  ┌──────────────┐  ┌───────────────────┐  ┌──────────┐
  │  Collecting   │──┼─>│ Collecting │──┼─>│  Collecting   │─>│    Collecting      │─>│ Finished │
  │  Facilities   │  │  │ Proposals  │  │  │  Signatures   │  │ BinarySignatures   │  │          │
  └──────────────┘  │  └───────────┘  │  └──────────────┘  └───────────────────┘  └──────────┘
                    │                 │
                    └─────────────────┘
                    On stall: Lock → ACK vote → Unlock → Retry
```

### After: 3-Phase Leader-Based with View Change

```
                    LEADER PROPOSES
                    ┌────────────────┐
                    │                │
  ┌──────────────┐  │  ┌───────────┐ │  ┌──────────────┐  ┌──────────┐
  │  Collecting   │──┼─>│ Collecting │─┼─>│  Collecting   │─>│ Finished │
  │  Facilities   │  │  │ Proposals  │ │  │  Signatures   │  │          │
  └──────────────┘  │  └───────────┘ │  └──────────────┘  └──────────┘
                    │                │
                    └────────────────┘
                    On proposal stall: View Change (rotate leader)
                    On other stall: Abandon after N cycles
```

---

## Consensus Round Lifecycle

```
                         ┌─────────────────────────────────────────────────┐
                         │                CONSENSUS ENGINE                  │
                         │                                                  │
  TimeTick ─────────┐    │  ┌────────┐    ┌────────────┐    ┌───────────┐  │
  EventTrigger ─────┼───>│  │  FSM   │───>│ RoundRunner │───>│  Creator  │  │
  StartRound ───────┘    │  │ (IDLE/ │    │             │    │           │  │
                         │  │  BUSY) │    │  runRound() │    │ facilitate│  │
                         │  └────────┘    └──────┬──────┘    └─────┬─────┘  │
                         │       ^               │                 │        │
                         │       │               │                 v        │
                         │  ┌────┴────┐    ┌─────┴──────┐   ┌──────────┐   │
                         │  │ Command │    │   Stall    │   │  State   │   │
                         │  │  Queue  │<───│  Detector  │   │ Advancer │   │
                         │  └─────────┘    └────────────┘   └──────────┘   │
                         │                       │                │        │
                         │               View Change /     State Update    │
                         │                 Abandon          + Gossip       │
                         └─────────────────────────────────────────────────┘
```

### Phase Flow (3-Phase Protocol)

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                         CONSENSUS ROUND                                 │
  │                                                                         │
  │  Phase 1: CollectingFacilities                                          │
  │  ┌────────────────────────────────────────────────────────────────┐     │
  │  │ All peers exchange Facility declarations:                      │     │
  │  │   - Event bounds (what events to include)                      │     │
  │  │   - Candidates (peers for next round)                          │     │
  │  │   - Trigger type (time/event)                                  │     │
  │  │   - Config hash (detect config divergence)                     │     │
  │  │                                                                │     │
  │  │ Quorum: 2/3+1 facilities received -> advance                  │     │
  │  │ Leader: creates artifact from collected facilities             │     │
  │  └──────────────────────────┬─────────────────────────────────────┘     │
  │                             │                                           │
  │  Phase 2: CollectingProposals                                           │
  │  ┌──────────────────────────┴─────────────────────────────────────┐     │
  │  │ LEADER creates artifact + spreads LeaderProposal               │     │
  │  │ NON-LEADERS wait for leader's proposal                         │     │
  │  │                                                                │     │
  │  │ Direct push ensures fast delivery                              │     │
  │  │ If leader fails: View Change -> new leader                     │     │
  │  └──────────────────────────┬─────────────────────────────────────┘     │
  │                             │                                           │
  │  Phase 3: CollectingSignatures                                          │
  │  ┌──────────────────────────┴─────────────────────────────────────┐     │
  │  │ All peers validate leader's proposal                           │     │
  │  │ If valid: sign artifact hash, spread Vote                      │     │
  │  │ 2/3+1 valid votes -> Finished                                  │     │
  │  └──────────────────────────┬─────────────────────────────────────┘     │
  │                             │                                           │
  │  Finished: Outcome stored, next round triggered                         │
  └─────────────────────────────────────────────────────────────────────────┘
```

---

## Step-by-Step Changes

### Step 1: Push Gossip for Consensus Declarations

**Problem:** Declarations travel at gossip round speed (~1s polling intervals).

**Solution:** After each `gossip.spread(declaration)`, also push directly to all known facilitators via HTTP.

```
  Before:                          After:
  ┌──────┐    gossip    ┌──────┐   ┌──────┐  direct push  ┌──────┐
  │Node A│───(~1s)─────>│Node B│   │Node A│───(immediate)─>│Node B│
  └──────┘              └──────┘   └──────┘       +        └──────┘
                                          gossip (backup)
```

**Files:**
- `ConsensusDirectSender.scala` (new) - Direct HTTP push to facilitators
- `Gossip.scala` - Added `spreadDirect` method
- Both advancers - Call `spreadDirect` after creating declarations

### Step 2: Leader Selection Infrastructure

**Problem:** Need deterministic leader selection for leader-based protocol.

**Solution:** Use rendezvous hashing (SHA-256) for deterministic leader selection with view-change rotation.

```
  Rendezvous Hashing:
  ┌──────────────────────────────────────────────────┐
  │  score(peer) = SHA-256(entropy || peerId)         │
  │                                                    │
  │  Leader = facilitator with rank[viewNumber]        │
  │                                                    │
  │  View 0: rank 0 (initial leader)                   │
  │  View 1: rank 1 (after first view change)          │
  │  View 2: rank 2 (after second view change)         │
  │  ...wraps around at facilitator count              │
  └──────────────────────────────────────────────────┘
```

**Files:**
- `FacilitatorSelector.scala` - Added `selectLeader(facilitators, entropy, viewNumber)`
- `ConsensusState.scala` - Added `leader: PeerId`, `viewNumber: Int`, `entropy: Hash`
- Both state creators - Compute and set leader on state creation

### Step 3: Leader-Based Proposal

**Problem:** All facilitators create artifacts independently, requiring complex agreement.

**Solution:** Only the leader creates the artifact and proposes it.

```
  Before (all-to-all):           After (leader-based):
  ┌──┐  ┌──┐  ┌──┐  ┌──┐       ┌──┐  ┌──┐  ┌──┐  ┌──┐
  │A │  │B │  │C │  │D │       │A │  │B*│  │C │  │D │
  └┬─┘  └┬─┘  └┬─┘  └┬─┘       └──┘  └┬─┘  └──┘  └──┘
   │      │      │      │               │
   ├──────┼──────┼──────┤          ┌────┴────┐
   │ all create artifacts│          │ B creates│
   │ then negotiate      │          │ artifact │
   └─────────────────────┘          │ + pushes │
                                    └─────────┘
                                    (* = leader)
```

**Files:**
- Both advancers - `advanceFromProposals`: leader creates + spreads proposal; non-leaders wait

### Step 4: Simplify to 3 Phases

**Problem:** CollectingBinarySignatures phase was redundant with leader-based proposal.

**Solution:** Removed BinarySignature phase. CollectingSignatures directly leads to Finished.

```
  Before: Facilities -> Proposals -> Signatures -> BinarySignatures -> Finished
  After:  Facilities -> Proposals -> Signatures -> Finished
```

**Files:**
- Both advancers - Merged signature collection phases
- `declaration.scala` - Simplified declaration types

### Step 5: View Change (Replace Lock/Unlock)

**Problem:** Stall -> Lock -> ACK vote -> Unlock cycle was the primary source of deadlocks.

**Solution:** Simple view-change protocol. If leader fails, rotate to next facilitator.

```
  Before (Lock/Unlock):
  ┌───────┐    ┌──────┐    ┌──────────┐    ┌────────┐    ┌───────┐
  │ Stall │───>│ Lock │───>│ ACK Vote │───>│ Unlock │───>│ Retry │
  └───────┘    └──────┘    └──────────┘    └────────┘    └───────┘
    Complex, 5 deadlock fixes needed

  After (View Change):
  ┌──────────────┐    ┌──────────────┐    ┌────────────────┐
  │ Proposal     │───>│ View Change  │───>│ New leader     │
  │ Phase Stall  │    │ (rotate      │    │ spreads        │
  │              │    │  leader)     │    │ proposal       │
  └──────────────┘    └──────────────┘    └────────────────┘
    Simple, no deadlocks

  ┌──────────────┐    ┌──────────────┐
  │ Other Phase  │───>│ Abandon      │
  │ Stall        │    │ after N      │
  │              │    │ cycles       │
  └──────────────┘    └──────────────┘
```

**Files:**
- `StallDetector.scala` - Complete rewrite: view change for proposal stalls, abandon for others
- `ConsensusState.scala` - Removed `lockStatus`, `spreadAckKinds`
- `ConsensusStateUpdater.scala` - Removed gossip/ACK pipeline
- `UnlockConsensusUpdate.scala` - Deleted entirely
- Both advancers - Removed lock status gate, added leader re-spread on view change

### Step 6: Peer Quality Scoring

**Problem:** Slow/unreliable peers degrade consensus performance.

**Solution:** Track peer quality metrics locally and use them for adaptive timeout behavior.

```
  Quality Tracking:
  ┌──────────────────────────────────────────────────────────┐
  │                  PeerQualityTracker                       │
  │                                                           │
  │  Per-peer metrics:                                        │
  │  ┌────────────────────┬──────────────────────────────┐   │
  │  │ roundsParticipated │ Total rounds as facilitator   │   │
  │  │ roundsCompleted    │ Successful round completions  │   │
  │  │ viewChangesCaused  │ Times failed as leader        │   │
  │  └────────────────────┴──────────────────────────────┘   │
  │                                                           │
  │  Quality Score:                                           │
  │  score = completionRate * (1 - viewChangeRate)            │
  │  range: [0.0 (worst), 1.0 (best)]                        │
  │                                                           │
  │  Events recorded at:                                      │
  │  - Round success  (StateTransitions.finalizeAndNotify)    │
  │  - View change    (StallDetector.performViewChange)       │
  │  - Round abandon  (StallDetector.abandonRound)            │
  └──────────────────────────────────────────────────────────┘

  Quality-Adjusted Timeouts:
  ┌───────────────────────────────────────────────────────────┐
  │ Leader quality < threshold (0.5)?                          │
  │                                                            │
  │   YES -> proposal timeout *= 0.5 (less patience)           │
  │   NO  -> normal timeout                                    │
  │                                                            │
  │ Safe because view changes require majority agreement.      │
  │ Even with different local quality scores, all nodes        │
  │ still agree on the deterministic leader.                   │
  └───────────────────────────────────────────────────────────┘

  Future: selectLeaderWeighted (infrastructure ready)
  ┌───────────────────────────────────────────────────────────┐
  │ Tiered leader selection:                                   │
  │   Tier 0 (quality >= 0.7): preferred for leadership        │
  │   Tier 1 (quality >= 0.3): normal                          │
  │   Tier 2 (quality < 0.3):  deprioritized                  │
  │                                                            │
  │ Requires consensus-agreed quality scores (future work).    │
  │ Method available but not wired into production path.       │
  └───────────────────────────────────────────────────────────┘
```

**Files:**
- `PeerQualityTracker.scala` (new) - Ref-based per-peer quality metrics
- `FacilitatorSelector.scala` - Added `selectLeaderWeighted` (infrastructure for future)
- `StallDetector.scala` - Quality-adjusted proposal timeouts, records events
- `StateTransitions.scala` - Records successful round completions
- `ConsensusEngineContext.scala` - Added `peerQualityTracker` field
- `ConsensusConfig` - Added `leaderQualityThreshold`, `leaderQualityTimeoutMultiplier`

---

## Key Data Structures

### ConsensusState

```scala
ConsensusState(
  key: Key,                              // Round identifier (ordinal)
  lastOutcome: Outcome,                  // Previous round's outcome
  facilitators: Facilitators,            // Selected facilitators for this round
  status: Status,                        // Current phase (CollectingFacilities, etc.)
  createdAt: FiniteDuration,             // Monotonic timestamp
  withdrawnFacilitators: WithdrawnFacilitators, // Peers that left mid-round
  leader: PeerId,                        // Deterministic leader (NEW)
  viewNumber: Int = 0,                   // View change counter (NEW)
  entropy: Hash                          // Entropy for leader selection (NEW)
)
// Removed: lockStatus, spreadAckKinds
```

### ConsensusConfig

```scala
ConsensusConfig(
  // Existing fields...
  declarationTimeout: FiniteDuration,    // Base stall timeout
  maxStallCycles: Int = 3,               // Stalls before abandon
  maxRoundDuration: Option[FiniteDuration], // Wall-clock safety
  quorumThreshold: Option[Double],       // 2/3+1 quorum
  // New fields...
  leaderQualityThreshold: Double = 0.5,           // Quality below this = bad leader
  leaderQualityTimeoutMultiplier: Double = 0.5    // Timeout multiplier for bad leaders
)
```

---

## Stall Recovery Decision Tree

```
                    ┌────────────────────┐
                    │  Stall Detected    │
                    │  (timeout expired) │
                    └────────┬───────────┘
                             │
                    ┌────────┴───────────┐
                    │ Is proposal phase? │
                    └────────┬───────────┘
                       YES   │   NO
                    ┌────────┘   └────────┐
                    │                      │
           ┌────────┴──────┐     ┌────────┴──────┐
           │  View Change  │     │ Count towards  │
           │  - Increment  │     │ maxStallCycles │
           │    viewNumber │     └────────┬───────┘
           │  - New leader │              │
           │  - Record     │     ┌────────┴───────┐
           │    quality    │     │ stallCount >=   │
           └───────────────┘     │ maxStallCycles? │
                                 └────────┬───────┘
                                    YES   │   NO
                                 ┌────────┘   └────┐
                                 │                  │
                        ┌────────┴──────┐    ┌─────┴─────┐
                        │ Abandon Round │    │ Continue   │
                        │ - Remove state│    │ monitoring │
                        │ - Record      │    └───────────┘
                        │   quality    │
                        │ - Next round │
                        └──────────────┘

  Wall-clock safety: maxRoundDuration exceeded -> always abandon
```

---

## Quality Score Lifecycle

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                    PeerQualityTracker Lifecycle                  │
  │                                                                  │
  │  Round Start                                                     │
  │  ┌──────────────────────────────────────────────────────────┐   │
  │  │ Creator selects facilitators + leader                     │   │
  │  │ StallDetector begins monitoring                           │   │
  │  └──────────────────────────────────────────────────────────┘   │
  │                    │                                             │
  │         ┌──────────┴──────────┐                                  │
  │         │                     │                                  │
  │  Round Succeeds        Round Stalls                              │
  │  ┌──────────────┐    ┌──────────────┐                           │
  │  │ finalizeAnd  │    │ Proposal     │                           │
  │  │ Notify()     │    │ phase?       │                           │
  │  │              │    └──────┬───────┘                           │
  │  │ Record:      │      YES │  NO                                │
  │  │ +1 success   │    ┌─────┘  └─────┐                          │
  │  │ for ALL      │    │              │                           │
  │  │ facilitators │    │ View Change  │ Abandon (if               │
  │  └──────────────┘    │ Record:      │ maxStallCycles)           │
  │                      │ +1 viewChange│ Record:                   │
  │                      │ for LEADER   │ +1 participated           │
  │                      └──────────────┘ for ALL facilitators      │
  │                                                                  │
  │  Score = completionRate * (1 - viewChangeRate)                   │
  │  completionRate = completed / participated                       │
  │  viewChangeRate = viewChanges / participated                     │
  │                                                                  │
  │  Decay: counters halved when any peer > 10,000 rounds           │
  └─────────────────────────────────────────────────────────────────┘
```

---

## Module Dependency Map

```
  ┌─────────────┐     ┌──────────────┐     ┌──────────────┐
  │  dag-l0     │     │ currency-l0  │     │ node-shared  │
  │             │     │              │     │              │
  │ Global      │     │ Currency     │     │ Consensus    │
  │ Snapshot    │     │ Snapshot     │     │ Engine       │
  │ Consensus   │────>│ Consensus    │────>│              │
  │             │     │              │     │ - FSM        │
  │ - Advancer  │     │ - Advancer   │     │ - RoundRunner│
  │ - Creator   │     │ - Creator    │     │ - Stall      │
  │ - Schema    │     │ - Schema     │     │   Detector   │
  └─────────────┘     └──────────────┘     │ - Quality    │
                                            │   Tracker   │
                                            │ - Facilitator│
                                            │   Selector  │
                                            │ - Storage   │
                                            └──────────────┘
```

---

## Configuration Reference

| Config Field | Default | Description |
|---|---|---|
| `declarationTimeout` | 45s | Base timeout before stall action |
| `maxStallCycles` | 3 | Stalls before round abandonment |
| `maxRoundDuration` | None | Wall-clock safety net for round |
| `reStallTimeout` | None | Timeout after first stall |
| `noProgressTimeout` | None | Timeout with zero declarations |
| `quorumThreshold` | None | Quorum fraction (must be > 2/3) |
| `maxFacilitatorCount` | None | Max facilitators per round |
| `removalPenaltyRounds` | 0 | Rounds excluded after removal |
| `leaderQualityThreshold` | 0.5 | Quality below this = bad leader |
| `leaderQualityTimeoutMultiplier` | 0.5 | Timeout factor for bad leaders |

---

## Test Coverage

| Suite | Tests | What It Covers |
|---|---|---|
| `PeerQualityTrackerSuite` | 12 | Score computation, multi-peer tracking, decay, formula |
| `FacilitatorSelectorSuite` | 11 | Deterministic selection, view rotation, weighted selection |
| `StallDetectorSuite` | 16 | Timeout logic, view change transitions, abandon conditions |
| `QuorumDeclarationsSuite` | 17 | Quorum thresholds, safe majority, backward compatibility |

---

## Files Modified (Complete List)

### New Files
| File | Purpose |
|---|---|
| `node-shared/.../consensus/ConsensusDirectSender.scala` | Direct HTTP push for declarations |
| `node-shared/.../consensus/PeerQualityTracker.scala` | Per-peer quality metric tracking |
| `node-shared/test/.../PeerQualityTrackerSuite.scala` | Quality tracker tests |
| `node-shared/test/.../FacilitatorSelectorSuite.scala` | Facilitator selector tests |

### Modified Files
| File | Changes |
|---|---|
| `node-shared/.../consensus/FacilitatorSelector.scala` | Added `selectLeader`, `selectLeaderWeighted` |
| `node-shared/.../consensus/state/ConsensusState.scala` | Added `leader`, `viewNumber`, `entropy`; removed `lockStatus`, `spreadAckKinds` |
| `node-shared/.../consensus/state/ConsensusEngineContext.scala` | Added `facilitatorSelector`, `peerQualityTracker` |
| `node-shared/.../consensus/state/ConsensusStateAdvancer.scala` | Added `maybeGetQuorumDeclarations` with threshold |
| `node-shared/.../consensus/state/ConsensusStateUpdater.scala` | Removed gossip/ACK pipeline, simplified |
| `node-shared/.../consensus/engine/StallDetector.scala` | Complete rewrite: view change + quality-adjusted timeouts |
| `node-shared/.../consensus/engine/ConsensusEventLoop.scala` | Wires `facilitatorSelector`, `peerQualityTracker` |
| `node-shared/.../consensus/state/StateTransitions.scala` | Records quality on round success |
| `node-shared/.../config/types.scala` | Added quality config fields |
| `dag-l0/.../GlobalSnapshotConsensusStateAdvancer.scala` | 3-phase leader-based flow |
| `dag-l0/.../GlobalSnapshotConsensusStateCreator.scala` | Computes leader + entropy |
| `dag-l0/.../GlobalSnapshotConsensus.scala` | Creates `PeerQualityTracker` |
| `currency-l0/.../CurrencySnapshotConsensusStateAdvancer.scala` | 3-phase leader-based flow |
| `currency-l0/.../CurrencySnapshotConsensusStateCreator.scala` | Computes leader + entropy |
| `currency-l0/.../CurrencySnapshotConsensus.scala` | Creates `PeerQualityTracker` |

### Deleted Files
| File | Reason |
|---|---|
| `node-shared/.../consensus/update/UnlockConsensusUpdate.scala` | Lock/unlock mechanism removed |
| `node-shared/.../consensus/update/ConsensusStateUpdateFn.scala` | Only used by UnlockConsensusUpdate |
| `node-shared/test/.../update/UnlockConsensusUpdateSuite.scala` | Tests for removed mechanism |

---

## Developer Guide: Following a Consensus Round

This section maps the consensus round lifecycle to specific classes and methods. Use it to trace execution through the codebase.

### Phase 0: Round Triggering

```
ConsensusEventLoop.processCommand()
  └── ConsensusCommand.TimeTick / StartRound
        └── ConsensusFSM transitions IDLE → BUSY
              └── ConsensusRoundRunner.runRound(trigger)
```

**Files:**
- `node-shared/.../consensus/engine/ConsensusEventLoop.scala` — FSM + command dispatch
- `node-shared/.../consensus/engine/ConsensusRoundRunner.scala` — `runRound()`, `facilitateRound()`

### Phase 1: Round Creation (CollectingFacilities)

```
ConsensusRoundRunner.facilitateRound()
  └── ConsensusStateCreator.tryFacilitateConsensus(key, lastOutcome, trigger)
        ├── Select facilitators from eligible peers
        ├── Compute leader via FacilitatorSelector.selectLeader()
        ├── Create ConsensusState with status=CollectingFacilities
        ├── Spread Facility declaration via gossip + direct push
        └── Log: [CONSENSUS:LEADER/FOLLOWER] Round STARTED
```

**Files:**
- `dag-l0/.../GlobalSnapshotConsensusStateCreator.scala` — Global L0 round creation
- `currency-l0/.../CurrencySnapshotConsensusStateCreator.scala` — Currency L0 round creation
- `node-shared/.../consensus/state/ConsensusStateCreator.scala` — Base trait
- `node-shared/.../consensus/FacilitatorSelector.scala` — `selectLeader(facilitators, entropy, viewNumber)`

### Phase 2: Facilities → Proposals

```
ConsensusStateAdvancer.advanceStatus()
  └── advanceFromFacilities(state, status, resources)
        ├── maybeGetQuorumDeclarations() — wait for quorum of Facility declarations
        ├── Check fork by facilitatorsHash, lastSnapshotHash, consensusConfigHash
        ├── toProposalsPhase() → buildProposalTransition()
        │     ├── Pick majority trigger
        │     ├── Create artifact (createProposalArtifact)
        │     ├── Hash artifact
        │     └── Log: [CONSENSUS:LEADER/FOLLOWER] FACILITIES→PROPOSALS
        └── Side effect (LEADER only): spreadProposal() via gossip.spreadDirect
```

**Files:**
- `dag-l0/.../GlobalSnapshotConsensusStateAdvancer.scala` — `advanceFromFacilities()`, `buildProposalTransition()`
- `currency-l0/.../CurrencySnapshotConsensusStateAdvancer.scala` — same methods
- `node-shared/.../consensus/state/ConsensusStateAdvancer.scala` — `maybeGetQuorumDeclarations()`

### Phase 3: Proposals → Signatures

```
advanceFromProposals(state, status, resources)
  ├── Check: has leader's Proposal declaration arrived?
  │     ├── NO (and I'm leader): Re-spread proposal
  │     ├── NO (and I'm follower): Wait
  │     └── YES: resolveLeaderProposal()
  │
  └── resolveLeaderProposal(leaderProposal)
        ├── leaderHash == ownHash? Use local ArtifactInfo (skip validation)
        ├── leaderHash != ownHash? validateLeaderArtifact() then use leader's
        ├── Validation failed? Log warning, wait for view change
        ├── Sign artifact hash → Signature.fromHash()
        ├── Log: [CONSENSUS:LEADER/FOLLOWER] PROPOSALS→SIGNATURES
        └── Side effect: spreadSignature() via gossip.spreadDirect
```

**Files:**
- `dag-l0/.../GlobalSnapshotConsensusStateAdvancer.scala` — `advanceFromProposals()`, `resolveLeaderProposal()`
- `currency-l0/.../CurrencySnapshotConsensusStateAdvancer.scala` — same methods

### Phase 4: Signatures → Finished (Global L0)

```
advanceFromSignatures(state, status, resources)
  ├── maybeGetQuorumDeclarations() — wait for quorum of MajoritySignature
  ├── Check fork by facilitatorsHash, lastSnapshotHash
  └── toFinishedPhase()
        ├── Verify each SignatureProof
        ├── Build Signed[Artifact] with valid signatures
        ├── Log: [CONSENSUS:LEADER/FOLLOWER] SIGNATURES→FINISHED
        └── Side effect: persistAndGossip()
              ├── Store in SnapshotStorage, LastNGlobalSnapshotStorage
              └── Gossip fork info
```

### Phase 4a: Signatures → BinarySignatures → Finished (Currency L0 only)

Currency L0 has an extra phase for binary signing:

```
advanceFromSignatures → toBinarySignaturesPhase()
  ├── Verify signature proofs
  ├── Create StateChannelSnapshotBinary
  ├── Log: [CONSENSUS:LEADER/FOLLOWER] SIGNATURES→BINARY_SIGNATURES
  └── Side effect: spreadBinarySignature()

advanceFromBinarySignatures → toFinishedPhase()
  ├── Verify binary signature proofs
  ├── Build final Signed binary
  ├── Log: [CONSENSUS:LEADER/FOLLOWER] BINARY_SIGNATURES→FINISHED
  └── Side effect: persistAndGossip()
        ├── stateChannelSnapshotService.consume()
        └── Notify data application
```

### Phase 5: Outcome Extraction + Next Round

```
StateTransitions.checkUpdate(key)
  └── ConsensusStateUpdater.tryUpdateConsensus()
        └── ConsensusStateAdvancer.advanceStatus() (state machine)

If state reaches Finished:
  └── ConsensusStateAdvancer.getConsensusOutcome(state)
        └── Returns Some((Previous[Key], Outcome))

  StateTransitions.finalizeAndNotify()
    ├── Record duration metrics
    ├── peerQualityTracker.recordRoundSuccess()
    ├── storage.tryUpdateLastConsensusOutcomeWithCleanup()
    ├── Log: [CONSENSUS] Round COMPLETED
    └── queue.offer(ConsensusFinished)

  ConsensusEventLoop.processCommand(ConsensusFinished)
    ├── ConsensusRoundRunner.cleanupRound()
    └── ConsensusRoundRunner.afterConsensusFinish(trigger)
          ├── EventTrigger: check for pending time/event triggers
          └── TimeTrigger: schedule next time trigger
```

**Files:**
- `node-shared/.../consensus/state/StateTransitions.scala` — `checkUpdate()`, `finalizeAndNotify()`
- `node-shared/.../consensus/state/ConsensusStateUpdater.scala` — `tryUpdateConsensus()`
- `node-shared/.../consensus/engine/ConsensusEventLoop.scala` — `processCommand()`

### Failure Handling

#### View Change (Proposal Phase Stall)

```
StallDetector.monitorStep()
  └── handleStall() when statusDuration >= declarationTimeout
        └── isProposalPhase? YES
              ├── Log: [CONSENSUS] Leader stall — performing view change
              ├── peerQualityTracker.recordViewChange(oldLeader)
              └── performViewChange(key, state)
                    ├── newViewNumber = viewNumber + 1
                    ├── newLeader = FacilitatorSelector.selectLeader(facilitators, entropy, newViewNumber)
                    ├── Log: [CONSENSUS] View change
                    ├── Update state in storage (atomic condModifyState)
                    └── queue.offer(CheckUpdate) — triggers new leader to spread proposal
```

**Files:**
- `node-shared/.../consensus/engine/StallDetector.scala` — `handleStall()`, `performViewChange()`

#### Round Abandon (Non-Proposal Stall or Timeout)

```
StallDetector.monitorStep()
  └── shouldAbandon = (stallCount >= maxStallCycles) || roundTimedOut
        ├── peerQualityTracker.recordRoundAbandoned()
        ├── Remove state from storage
        ├── Log: [CONSENSUS] ABANDONING round
        └── queue.offer(RoundCompleted) + queue.offer(TimeTick)
```

#### Fork Detection

```
ConsensusStateAdvancer (during each phase transition)
  └── checkForkByLastSnapshotHash() / checkForkByFacilitatorsHash()
        └── ConsensusStateUpdater.recoverIfForking()
              ├── pickMajority(observations)
              ├── If own hash != majority hash:
              │     ├── Set node state → Leaving → Offline
              │     └── Restart or exit (configurable)
              └── If own hash == majority hash: no-op
```

#### Artifact Validation Failure

```
resolveLeaderProposal()
  └── validateLeaderArtifact() returns None
        └── Log: [CONSENSUS:FOLLOWER] Leader proposal FAILED validation
              └── Wait for StallDetector to trigger view change
```

### Logging Conventions

All consensus logs use the `[CONSENSUS]` prefix for easy filtering:

```bash
grep "\[CONSENSUS" application.log
```

Role-specific logs include `[CONSENSUS:LEADER]` or `[CONSENSUS:FOLLOWER]`:

```bash
grep "\[CONSENSUS:LEADER\]" application.log   # Only leader actions
grep "\[CONSENSUS:FOLLOWER\]" application.log  # Only follower actions
```

Phase transitions are logged as multi-line blocks for readability:

```
[CONSENSUS:LEADER] FACILITIES→PROPOSALS
  key=SnapshotOrdinal{value=42} ordinal=SnapshotOrdinal{value=42} trigger=TimeTrigger
  hash=32049eed... facilitators=5 candidates=0
  leader=ba43050d... self=ba43050d... view=0
  facilitatorsHash=68d72455... lastSnapshotHash=0205039c... entropy=0205039c...
```

The StallDetector logs show only **missing** peers (who haven't declared yet):

```
[CONSENSUS] Round monitor
  key=SnapshotOrdinal(42) status=CollectingFacilities declared=3/5
  elapsed=12s roundElapsed=12s stallCount=0
  leader=ba43050d... facilitators=5
  missing=[cd12ef34,9a8b7c6d]
```
