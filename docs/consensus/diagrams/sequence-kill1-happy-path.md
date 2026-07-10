# Scenario A: Single Node Isolation (Kill 1 of 8)

This sequence shows the happy path for recovering a single isolated node.

```mermaid
sequenceDiagram
    participant gl0_7 as gl0-7 (Isolated)
    participant Cluster as Healthy Cluster (7 nodes)
    participant Network as Network

    Note over gl0_7,Cluster: PHASE 1: Network Partition

    Network-->>gl0_7: Network isolated
    
    loop Every ~57s (stall cycle)
        gl0_7->>gl0_7: StartRound(TimeTrigger)
        gl0_7->>gl0_7: Cannot reach quorum
        gl0_7->>gl0_7: QuorumInfeasible (retriable=true)
        Note right of gl0_7: Does NOT count toward<br/>recovery threshold
    end

    loop Every ~13s (normal rounds)
        Cluster->>Cluster: Consensus round completes
        Cluster->>Cluster: 7/7 proofs (gl0-7 missing)
    end

    Note over gl0_7,Cluster: PHASE 2: Network Restored

    Network-->>gl0_7: Network restored
    
    gl0_7->>Cluster: Attempt to participate
    Note right of gl0_7: Sees peers at higher ordinal

    loop 5 consecutive abandonments (~5 min)
        gl0_7->>gl0_7: StartRound
        gl0_7->>gl0_7: Lagging detected
        gl0_7->>gl0_7: AbandonReason.Lagging (retriable=false)
        Note right of gl0_7: Counts toward recovery
    end

    Note over gl0_7,Cluster: PHASE 3: Recovery Download

    gl0_7->>gl0_7: AbandonmentTracker triggers recovery
    gl0_7->>gl0_7: Set isRecovery flag
    gl0_7->>gl0_7: Transition → WaitingForDownload

    gl0_7->>Cluster: Fetch latest chain tip
    Cluster-->>gl0_7: ChainTip(ordinal=N, hash=H)
    
    gl0_7->>gl0_7: Walk back from tip
    gl0_7->>gl0_7: Stop at persisted hash
    gl0_7->>gl0_7: setForRecovery (gap download)

    Note over gl0_7,Cluster: PHASE 4: Rejoin & Observe

    gl0_7->>Cluster: twoWayHandshake (all peers)
    Note right of gl0_7: Restore P2P mesh

    gl0_7->>gl0_7: Transition → Observing
    
    loop 1-5 rounds (random offset)
        Cluster->>gl0_7: Gossip round progress
        gl0_7->>gl0_7: Observe (don't participate)
    end

    gl0_7->>gl0_7: Transition → WaitingForReady

    Note over gl0_7,Cluster: PHASE 5: First Round

    gl0_7->>gl0_7: initFromDownload(isRecovery=true)
    Note right of gl0_7: Skip 43s deferral<br/>Grace period = 3

    gl0_7->>Cluster: Facility declaration
    Cluster->>Cluster: Include gl0-7 as facilitator
    gl0_7->>Cluster: Proposal/Signature
    
    Cluster->>Cluster: Round completes
    Note over Cluster: 8/8 proofs restored!

    gl0_7->>gl0_7: Transition → Ready
    gl0_7->>gl0_7: Grace period: 3→2→1→0
```

## Timeline (Production Timings)

| Phase | Duration | Notes |
|-------|----------|-------|
| Partition | Variable | Isolated node loops with QuorumInfeasible |
| Post-restore | ~5 min | 5 × ~57s stall cycles with Lagging abandon |
| Download | ~30s | Gap download (not full history) |
| Observe | ~15-65s | Random 1-5 rounds × ~13s |
| First round | ~13s | Immediate facilitation (isRecovery=true) |
| **Total recovery** | **~6 min** | From network restore to 8/8 proofs |

## Key Observations

1. **QuorumInfeasible is retriable** — isolated node doesn't trigger recovery while partitioned
2. **Lagging is non-retriable** — detection starts after restore when peers are visible at higher ordinals
3. **Gap download** — only fetches missing snapshots, not full history
4. **Immediate facilitation** — `isRecovery=true` skips 43s TimeTick deferral
5. **Grace period** — prevents false fork detection from stale facilitatorsHash
