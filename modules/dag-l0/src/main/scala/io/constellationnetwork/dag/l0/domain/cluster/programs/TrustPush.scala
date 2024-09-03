package io.constellationnetwork.dag.l0.domain.cluster.programs

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.trust.storage.TrustStorage

object TrustPush {

  def make[F[_]: Async](
    trustStorage: TrustStorage[F],
    gossip: Gossip[F]
  ): TrustPush[F] = new TrustPush[F](trustStorage, gossip) {}

}

sealed abstract class TrustPush[F[_]: Async] private (
  trustStorage: TrustStorage[F],
  gossip: Gossip[F]
) {

  def publishUpdated(): F[Unit] =
    for {
      trust <- trustStorage.getPublicTrust
      _ <- gossip.spread(trust)
    } yield {}

}
