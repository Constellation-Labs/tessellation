package org.tessellation.infrastructure.trust.storage

import cats.effect.Ref
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.partialOrder._
import cats.{Monad, Order}

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._
import org.tessellation.sdk.config.types.TrustStorageConfig
import org.tessellation.sdk.domain.trust.storage._

import derevo.circe.magnolia.encoder
import derevo.derive
import monocle.Monocle.toAppliedFocusOps

object TrustStorage {

  @derive(encoder)
  case class TrustStore(
    trust: TrustMap,
    currentOrdinalTrust: OrdinalTrustMap,
    nextOrdinalTrust: OrdinalTrustMap
  )

  def make[F[_]: Monad: Ref.Make](config: TrustStorageConfig): F[TrustStorage[F]] = make(TrustMap.empty, config)

  def make[F[_]: Monad: Ref.Make](
    trustInfo: TrustMap,
    config: TrustStorageConfig
  ): F[TrustStorage[F]] =
    Ref[F]
      .of[TrustStore](
        TrustStore(
          trustInfo,
          OrdinalTrustMap.empty,
          OrdinalTrustMap.empty
            .focus(_.ordinal)
            .replace(SnapshotOrdinal(config.ordinalTrustUpdateInterval))
        )
      )
      .map(make(_, config))

  def make[F[_]: Monad: Ref.Make](
    trustUpdates: Option[PeerObservationAdjustmentUpdateBatch],
    config: TrustStorageConfig
  ): F[TrustStorage[F]] =
    trustUpdates.fold(make(config))(batch => make(config).flatTap(_.updateTrust(batch)))

  def make[F[_]: Monad](trustStoreRef: Ref[F, TrustStore], config: TrustStorageConfig): TrustStorage[F] =
    new TrustStorage[F] {

      def updateTrust(trustUpdates: PeerObservationAdjustmentUpdateBatch): F[Unit] =
        trustStoreRef.update { store =>
          val updated = store.trust.trust ++ trustUpdates.updates.map { trustUpdate =>
            trustUpdate.id -> store.trust.trust
              .getOrElse(trustUpdate.id, TrustInfo())
              .focus(_.trustLabel)
              .replace(trustUpdate.trust.value.some)
          }.toMap
          store.focus(_.trust.trust).replace(updated)
        }

      def updatePredictedTrust(trustUpdates: Map[PeerId, Double]): F[Unit] =
        trustStoreRef.update { store =>
          val updated = store.trust.trust ++ trustUpdates.map {
            case (k, v) =>
              k -> store.trust.trust
                .getOrElse(k, TrustInfo())
                .focus(_.predictedTrust)
                .replace(v.some)
          }
          store.focus(_.trust.trust).replace(updated)
        }

      def getTrust: F[TrustMap] = trustStoreRef.get.map(_.trust)

      def getCurrentOrdinalTrust: F[OrdinalTrustMap] = trustStoreRef.get.map(_.currentOrdinalTrust)

      def getNextOrdinalTrust: F[OrdinalTrustMap] = trustStoreRef.get.map(_.nextOrdinalTrust)

      def getPublicTrust: F[PublicTrust] = getTrust.map(_.toPublicTrust)

      def updatePeerPublicTrustInfo(peerId: PeerId, publicTrust: PublicTrust): F[Unit] = trustStoreRef.update { store =>
        val updated = store.trust.peerLabels.value + (peerId -> publicTrust)
        store.focus(_.trust.peerLabels).replace(PublicTrustMap(updated))
      }

      def updateNext(ordinal: SnapshotOrdinal): F[Option[SnapshotOrdinalPublicTrust]] =
        trustStoreRef.modify { store =>
          val next = store.nextOrdinalTrust

          if (
            Order.gteqv(ordinal, next.ordinal) &&
            next.trust.isEmpty &&
            store.trust.hasTrustValues
          ) {
            val updated = next.focus(_.trust).replace(store.trust)
            val ordinalPublicTrust = SnapshotOrdinalPublicTrust(
              next.ordinal,
              store.trust.toPublicTrust
            )

            (store.focus(_.nextOrdinalTrust).replace(updated), ordinalPublicTrust.some)
          } else (store, none)
        }

      def updateNext(
        peerId: PeerId,
        publicTrust: SnapshotOrdinalPublicTrust
      ): F[Unit] = trustStoreRef.update { store =>
        val next = store.nextOrdinalTrust

        store.focus(_.nextOrdinalTrust.peerLabels).modify { peerLabels =>
          if (
            next.ordinal === publicTrust.ordinal &&
            !next.peerLabels.value.contains(peerId) &&
            !publicTrust.labels.isEmpty
          ) {
            peerLabels.add(peerId, publicTrust.labels)
          } else peerLabels
        }
      }

      def updateCurrent(ordinal: SnapshotOrdinal): F[Unit] =
        trustStoreRef.update { store =>
          if (
            Order.gteqv(
              ordinal,
              store.nextOrdinalTrust.ordinal.plus(config.ordinalTrustUpdateDelay)
            )
          ) {
            store.copy(
              currentOrdinalTrust = store.nextOrdinalTrust,
              nextOrdinalTrust = OrdinalTrustMap(
                store.nextOrdinalTrust.ordinal.plus(config.ordinalTrustUpdateInterval),
                PublicTrustMap.empty,
                TrustMap.empty
              )
            )
          } else store
        }
    }
}
