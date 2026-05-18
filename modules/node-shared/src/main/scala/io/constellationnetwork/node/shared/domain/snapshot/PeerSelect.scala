package io.constellationnetwork.node.shared.domain.snapshot

import io.constellationnetwork.schema.peer.L0Peer

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
    */
  def selectForRecovery: F[L0Peer]
}
