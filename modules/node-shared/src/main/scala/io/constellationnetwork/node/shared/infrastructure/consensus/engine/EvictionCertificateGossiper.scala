package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Applicative

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.EvictionCertificate

/** Gossips an assembled [[EvictionCertificate]] to the current facilitator set so peers can apply the eviction during the committee
  * selection of subsequent retry-rounds at the SAME ordinal — without waiting for a Proposal to be accepted at the next ordinal.
  *
  * Background: the cert is normally embedded in the next leader's Proposal (advancer-side) and applied at proposal acceptance. When a
  * cluster is wedged and no Proposal is accepted, the cert keeps re-assembling on every retry but never takes effect. Gossiping it on
  * assembly closes that gap.
  *
  * Parallels `EvictionVoter` / `GossipingEvictionVoter`. Generic engine-level decision logic (when to assemble, what counts as quorum)
  * lives in the caller (`StateTransitions.checkEvictionAssembly`); this trait's responsibility is solely the gossip-broadcast step once the
  * cert has been assembled and stored.
  */
trait EvictionCertificateGossiper[F[_], Key] {
  def gossipCert(
    key: Key,
    cert: EvictionCertificate
  ): F[Unit]
}

object EvictionCertificateGossiper {

  /** No-op gossiper: used when layer-specific gossip wiring is not yet available (tests, bootstrap paths). Safe to call; does nothing. */
  def noop[F[_]: Applicative, Key]: EvictionCertificateGossiper[F, Key] = new EvictionCertificateGossiper[F, Key] {
    def gossipCert(
      key: Key,
      cert: EvictionCertificate
    ): F[Unit] = Applicative[F].unit
  }
}
