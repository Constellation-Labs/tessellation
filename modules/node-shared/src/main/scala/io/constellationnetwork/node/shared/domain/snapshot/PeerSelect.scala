package io.constellationnetwork.node.shared.domain.snapshot

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.{L0Peer, PeerId}

trait PeerSelect[F[_]] {
  def select: F[L0Peer]

  /** Recovery-path peer selection: prefers Ready peers, falls back to Observing peers when no Ready peer is available.
    *
    * During a recovery cascade (e.g., alpha.40: three peers detected fork within 25s and all flipped to `WaitingForDownload`
    * simultaneously), the standard `select` finds no Ready peers and returns `NoPeersToSelect`, burning the inner retry ladder. Observing
    * peers -- those that have completed download and are observing the cluster prior to becoming Ready -- are valid sources for recovery
    * metadata. Including them widens the candidate pool for the recovery path without affecting the standard download path.
    *
    * Mirrors the `Ready -> Observing` filter used by `StateTransitions.fetchOutcomeFromCluster`.
    *
    * `preferredPeers` is a recovery hint (the fork-recovery majority set, see `RecoveryPeerHint`): when non-empty, selection is biased
    * toward those peers by intersecting them with the already-validated majority-ordinal/majority-hash candidate set. It only narrows
    * WITHIN the validated set and falls back to the full set when it does not overlap -- it biases the source choice, never bypasses
    * validation. An empty set is the prior behavior.
    *
    * `minOrdinalExclusive` is the caller's local snapshot ordinal (recovery path). When provided AND a STRICT MAJORITY of responding peers
    * are strictly ahead of it, the candidate pool is restricted to those ahead peers, so a node that is legitimately behind the cluster
    * majority catches up to the live (higher) chain instead of forming a mutual-503 download triangle with equally-stuck peers. When only a
    * sub-quorum minority is ahead (a fork), it FAILS CLOSED to prior global behavior -- it never converges the majority onto an
    * uncorroborated minority higher tip (resolving such a partition is the quorum fix, not source selection). Inert for rollback /
    * already-caught-up. `None` is the prior behavior.
    */
  def selectForRecovery(preferredPeers: Set[PeerId], minOrdinalExclusive: Option[SnapshotOrdinal]): F[L0Peer]

  def selectForRecovery(preferredPeers: Set[PeerId]): F[L0Peer] = selectForRecovery(preferredPeers, None)

  def selectForRecovery: F[L0Peer] = selectForRecovery(Set.empty, None)
}
