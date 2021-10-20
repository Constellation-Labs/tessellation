package org.tesselation.infrastructure.trust.storage

import cats.Monad
import cats.effect.Ref
import cats.syntax.functor._

import org.tesselation.domain.trust.storage.TrustStorage
import org.tesselation.schema.cluster
import org.tesselation.schema.cluster.TrustInfo
import org.tesselation.schema.peer.PeerId

object TrustStorage {

  def make[F[_]: Monad: Ref.Make]: F[TrustStorage[F]] =
    for {
      trust <- Ref[F].of[Map[PeerId, TrustInfo]](Map.empty)
    } yield make(trust)

  def make[F[_]: Monad](trust: Ref[F, Map[PeerId, TrustInfo]]): TrustStorage[F] =
    new TrustStorage[F] {

      def updateTrust(trustUpdates: cluster.InternalTrustUpdateBatch): F[Unit] =
        trust.update { t =>
          t ++ trustUpdates.updates.map { trustUpdate =>
            val capped = Math.max(Math.min(trustUpdate.trust, 1), -1)
            val updated = t.getOrElse(trustUpdate.id, TrustInfo()).copy(trustLabel = Some(capped))
            trustUpdate.id -> updated
          }.toMap
        }

      def getTrust(): F[Map[PeerId, TrustInfo]] = trust.get

    }

}
