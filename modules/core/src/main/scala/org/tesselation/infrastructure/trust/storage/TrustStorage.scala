package org.tesselation.infrastructure.trust.storage

import cats.Monad
import cats.effect.Ref
import cats.syntax.functor._
import cats.syntax.functorFilter._

import org.tesselation.domain.trust.storage.TrustStorage
import org.tesselation.schema.peer.PeerId
import org.tesselation.schema.trust.{InternalTrustUpdateBatch, PublicTrust, TrustInfo}

object TrustStorage {

  def make[F[_]: Monad: Ref.Make]: F[TrustStorage[F]] =
    for {
      trust <- Ref[F].of[Map[PeerId, TrustInfo]](Map.empty)
    } yield make(trust)

  def make[F[_]: Monad](trust: Ref[F, Map[PeerId, TrustInfo]]): TrustStorage[F] =
    new TrustStorage[F] {

      def updateTrust(trustUpdates: InternalTrustUpdateBatch): F[Unit] =
        trust.update { t =>
          t ++ trustUpdates.updates.map { trustUpdate =>
            val capped = Math.max(Math.min(trustUpdate.trust, 1), -1)
            val updated = t.getOrElse(trustUpdate.id, TrustInfo()).copy(trustLabel = Some(capped))
            trustUpdate.id -> updated
          }.toMap
        }

      def getTrust: F[Map[PeerId, TrustInfo]] = trust.get

      def getPublicTrust: F[PublicTrust] = getTrust.map { trust =>
        PublicTrust(trust.mapFilter { _.publicTrust })
      }

      def updatePeerPublicTrustInfo(peerId: PeerId, publicTrust: PublicTrust): F[Unit] = trust.update { t =>
        t.updatedWith(peerId) { trustInfo =>
          trustInfo
            .orElse(Some(TrustInfo()))
            .map(_.copy(peerLabels = publicTrust.labels))
        }
      }

      override def updatePredictedTrust(trustUpdates: Map[PeerId, Double]): F[Unit] = trust.update { t =>
        t ++ trustUpdates.map {
          case (k, v) =>
            k -> t.getOrElse(k, TrustInfo()).copy(predictedTrust = Some(v))
        }
      }
    }

}
