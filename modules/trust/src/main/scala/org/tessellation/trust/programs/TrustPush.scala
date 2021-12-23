package org.tessellation.trust.programs

import cats.effect.Async
import cats.implicits._

import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.trust.domain.storage.TrustStorage

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
