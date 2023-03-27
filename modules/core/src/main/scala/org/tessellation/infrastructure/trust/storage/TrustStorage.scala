package org.tessellation.infrastructure.trust.storage

import cats.Monad
import cats.effect.Ref
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._

import org.tessellation.domain.trust.storage.TrustStorage
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{PeerObservationAdjustmentUpdateBatch, PublicTrust, TrustInfo}

object TrustStorage {

  def make[F[_]: Monad: Ref.Make]: F[TrustStorage[F]] = Ref[F].of[Map[PeerId, TrustInfo]](Map.empty).map(make(_))

  def make[F[_]: Monad: Ref.Make](trustUpdates: Option[PeerObservationAdjustmentUpdateBatch]): F[TrustStorage[F]] =
    trustUpdates.fold(make)(trust => make.flatTap(_.updateTrust(trust)))

  def make[F[_]: Monad](trust: Ref[F, Map[PeerId, TrustInfo]]): TrustStorage[F] =
    new TrustStorage[F] {

      def updateTrust(trustUpdates: PeerObservationAdjustmentUpdateBatch): F[Unit] =
        trust.update { t =>
          t ++ trustUpdates.updates.map { trustUpdate =>
            trustUpdate.id -> t.getOrElse(trustUpdate.id, TrustInfo()).copy(trustLabel = trustUpdate.trust.value.some)
          }.toMap
        }

      def getTrust: F[Map[PeerId, TrustInfo]] = trust.get

      def getPublicTrust: F[PublicTrust] = getTrust.map { trustValue =>
        PublicTrust(trustValue.mapFilter(_.publicTrust))
      }

      def updatePeerPublicTrustInfo(peerId: PeerId, publicTrust: PublicTrust): F[Unit] = trust.update { t =>
        t.updatedWith(peerId) { trustInfo =>
          trustInfo
            .orElse(Some(TrustInfo()))
            .map(_.copy(peerLabels = publicTrust.labels))
        }
      }

      def updatePredictedTrust(trustUpdates: Map[PeerId, Double]): F[Unit] = trust.update { t =>
        t ++ trustUpdates.map {
          case (k, v) =>
            k -> t.getOrElse(k, TrustInfo()).copy(predictedTrust = v.some)
        }
      }
    }
}
