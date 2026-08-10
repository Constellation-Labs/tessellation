# ADR-0031: Align application storage when recovery accepts a newer outcome

Date: 2026-08-10

Status: Accepted

## Context

Global-L0 incremental recovery downloads and observes snapshots before initializing
consensus. The requested consensus outcome can be evicted while this handoff is in
progress. In that case the specific-outcome endpoint returns Conflict and the client
falls back to the peer's latest outcome.

The existing initialization path accepted that newer outcome into consensus but left
application snapshot storage at the ordinal that recovery had just observed. A captured
IntegrationNet failure converged application storage at ordinal N, accepted consensus
outcome N+1, then finalized N+2. `LastNGlobalSnapshotStorage.set` correctly rejected N+2
as non-contiguous because it still required N+1, and subsequent `CheckUpdate` retries
could not repair the torn handoff.

A contemporaneous DownloadDaemon `aborting retry loop` message was not the cause. It
followed an explicit successful recovery-convergence log and came from a queued state
event cancelled after the download semaphore was released.

## Decision

1. Classify download initialization into three cases:
   - exact key, artifact, and context: preserve the caller's recovery mode;
   - different key from the latest-outcome fallback: accept it only through the
     layer-specific storage-alignment hook and treat it as recovery;
   - same key with a different artifact or context: reject it.
2. Before Global L0 installs a different-key outcome into consensus, run one required
   initialization sequence that:
   - reset `LastNGlobalSnapshotStorage` to the accepted artifact/context;
   - reset `LastSnapshotStorage` to the same pair;
   - move the persisted snapshot head to that pair;
   - rebuild the MPT from the accepted context at its ordinal; and
   - remove locally held mempool events already committed by the accepted artifact.
3. Store the accepted outcome key, rather than the originally requested stale key, in
   the recovery marker.
4. Keep Currency L0's layer hook inert because it does not use Global L0's incremental
   recovery storage stack.
5. Include current and incoming ordinal/hash in non-contiguous Last-N storage errors.

## Consequences

- Consensus and application storage begin the next round at the same accepted ordinal,
  preventing the observed N/N+1/N+2 contiguity failure.
- Failure of any alignment step prevents consensus initialization instead of allowing a
  partially aligned node to become Ready; the normal recovery retry resets the stores
  again before another attempt.
- No artifact, snapshot, state-proof, consensus-message, or deterministic-configuration
  schema changes. The behavior is node-local recovery plumbing.
- The latest-outcome fallback and its ability to catch a recovering node up remain
  unchanged.
- Operators must correlate DownloadDaemon cancellation with the preceding convergence
  result; cancellation after convergence is expected cleanup, not proof of an aborted
  in-flight recovery.
