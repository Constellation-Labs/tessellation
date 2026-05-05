# Scenario C: Symmetric Partition (Kill 4 of 8) — Known Limitation

This sequence shows a symmetric partition where both sides lose quorum. **7/8 nodes recover, 1 gets stuck.**

```mermaid
sequenceDiagram
    participant SideA as Side A (4 nodes)
    participant SideB as Side B (4 nodes)
    participant Network as Network

    Note over SideA,SideB: PHASE 1: Network Partition

    Network-->>SideA: Network partitioned
    Network-->>SideB: Network partitioned
    
    Note over SideA: 4 nodes < minQuorum (5)
    Note over SideB: 4 nodes < minQuorum (5)

    loop Both sides stall
        SideA->>SideA: QuorumInfeasible (retriable)
        SideB->>SideB: QuorumInfeasible (retriable)
        Note over SideA,SideB: Neither side triggers recovery<br/>(QuorumInfeasible is retriable)
    end

    Note over SideA,SideB: Both sides may produce 1-2 minority snapshots<br/>before stall detection kicks in

    SideA->>SideA: Produce snapshot at ordinal N (minority)
    SideB->>SideB: Produce snapshot at ordinal N (minority)
    
    Note over SideA,SideB: Different hashes at same ordinal!

    Note over SideA,SideB: PHASE 2: Network Restored

    Network-->>SideA: Network restored
    Network-->>SideB: Network restored

    loop Fork detection fires (gossip + consensus channels)
        SideA->>SideB: Sample chain tips
        SideB-->>SideA: ChainTip(N, hashB)
        SideA->>SideA: ForkRecoveryDetector:<br/>Tier 1 running fork detected
        
        SideB->>SideA: Sample chain tips
        SideA-->>SideB: ChainTip(N, hashA)
        SideB->>SideB: ForkRecoveryDetector:<br/>Tier 1 running fork detected
    end

    Note over SideA,SideB: Both sides set RecoveryPeerHint to<br/>their respective majority and transition<br/>to WaitingForDownload

    Note over SideA,SideB: PHASE 3: Recovery Attempts

    par 7 nodes recover successfully
        SideA->>SideA: 3 nodes: Download, observe, rejoin
        SideB->>SideB: 4 nodes: Download, observe, rejoin
    and 1 node gets stuck
        SideA->>SideA: gl0-7: Download starts
        SideA->>SideA: gl0-7: Persisted forked ordinal N
        SideA->>SideA: gl0-7: Looking for ordinal N+1
        Note right of SideA: N+1 doesn't exist on<br/>canonical chain!
        SideA->>SideA: gl0-7: Download walker stuck
    end

    Note over SideA,SideB: PHASE 4: Partial Recovery

    SideB->>SideB: Cluster reforms: 7 nodes
    
    loop Normal consensus resumes
        SideB->>SideB: 7/8 proofs (gl0-7 stuck)
    end

    Note over SideA: gl0-7 STUCK<br/>Manual restart required

    rect rgb(255, 200, 200)
        Note over SideA: MITIGATION:<br/>Restart gl0-7 to clear<br/>forked state from disk
    end
```

## Timeline

| Phase | Duration | Notes |
|-------|----------|-------|
| Partition | Variable | Neither side has quorum |
| Fork detection | ~10-30s | After network restore |
| 7/8 recovery | ~6-9 min | Standard recovery path |
| 1/8 stuck | ∞ | Download walker deadlock |
| **Manual intervention** | Required | Restart stuck node |

## Root Cause: Observe Deadlock

When both partitions lose quorum (4+4 with minQuorum=5), neither side can produce snapshots during the partition. However, **before stall detection kicks in**, each side may produce 1-2 minority snapshots.

The stuck node's state:
1. Persisted a forked snapshot at ordinal N with hashA
2. Recovery download fetches majority chain (hashB at ordinal N)
3. Download walker looks for N+1 as successor to N (hashA)
4. **N+1 (hashA) was never produced** — the canonical chain has N+1 (hashB)
5. Walker is stuck looking for a non-existent ordinal

```
Local state:           ... → N(hashA) → ?
Canonical chain:       ... → N(hashB) → N+1(hashB) → ...

Download walker expects: N(hashA).next = N+1(hashA)
But N+1(hashA) doesn't exist!
```

## Why Other Nodes Recover

Most nodes either:
1. Didn't persist the forked snapshot before stall kicked in
2. Were on the side that became the majority (their hashes match canonical)
3. Had their forked snapshot at an ordinal that the canonical chain also has

The stuck node is unlucky: it persisted a forked snapshot at exactly the ordinal where the chains diverged.

## Mitigation Strategies

### Immediate
- **Restart the stuck node** — clears disk state, allows fresh download

### Future Improvements
- **Download validation** — detect when walker can't find expected successor
- **Fallback to full download** — if gap download fails, try from genesis
- **Forked ordinal detection** — compare local hash vs majority hash at each ordinal during download
