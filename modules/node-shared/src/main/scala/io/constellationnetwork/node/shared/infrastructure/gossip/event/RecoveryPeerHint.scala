package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

/** Holds a hint of preferred peers for recovery downloads.
  *
  * When fork divergence is detected, the ForkRecoveryDetector sets the majority peers as preferred download targets. The download daemon
  * can then use these peers instead of picking randomly, improving recovery speed and correctness.
  */
trait RecoveryPeerHint[F[_]] {
  def setPreferredPeers(peers: Set[PeerId]): F[Unit]

  /** Read the current preferred-peers hint WITHOUT clearing it. The recovery download path selects a source peer repeatedly (start,
    * observe, retry ladder), so it peeks the hint on each selection rather than consuming it once; the hint is overwritten by the next fork
    * detection.
    */
  def getPreferredPeers: F[Option[Set[PeerId]]]
  def getAndClearPreferredPeers: F[Option[Set[PeerId]]]
  def clearPreferredPeers: F[Unit]
}

object RecoveryPeerHint {
  def make[F[_]: Async]: F[RecoveryPeerHint[F]] =
    Ref.of[F, Option[Set[PeerId]]](None).map { ref =>
      new RecoveryPeerHint[F] {
        def setPreferredPeers(peers: Set[PeerId]): F[Unit] = ref.set(peers.some)
        def getPreferredPeers: F[Option[Set[PeerId]]] = ref.get
        def getAndClearPreferredPeers: F[Option[Set[PeerId]]] = ref.getAndSet(None)
        def clearPreferredPeers: F[Unit] = ref.set(None)
      }
    }
}
