package org.tessellation.dag.l0.infrastructure.trust.storage

import cats.Order.max
import cats.effect.Ref
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.partialOrder._
import cats.{Monad, Order}

import org.tessellation.dag.l0.infrastructure.trust.TrustModel
import org.tessellation.node.shared.config.types.TrustStorageConfig
import org.tessellation.node.shared.domain.trust.storage._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._

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

  def make[F[_]: Monad: Ref.Make](
    config: TrustStorageConfig,
    seedlist: Option[Set[PeerId]]
  ): F[TrustStorage[F]] = make(TrustMap.empty, config, seedlist)

  def make[F[_]: Monad: Ref.Make](
    trustInfo: TrustMap,
    config: TrustStorageConfig,
    seedlist: Option[Set[PeerId]]
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
      .map(make(_, config, seedlist))

  def make[F[_]: Monad: Ref.Make](
    trustUpdates: Option[PeerObservationAdjustmentUpdateBatch],
    config: TrustStorageConfig,
    seedlist: Option[Set[PeerId]]
  ): F[TrustStorage[F]] =
    trustUpdates.fold(make(config, seedlist))(batch => make(config, seedlist).flatTap(_.updateTrust(batch)))

  def make[F[_]: Monad](
    trustStoreRef: Ref[F, TrustStore],
    config: TrustStorageConfig,
    seedlist: Option[Set[PeerId]]
  ): TrustStorage[F] =
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

      def getTrust: F[TrustMap] = trustStoreRef.get.map(_.trust)

      def getBiasedTrustScores: F[TrustScores] = getTrust
        .map(getBiasedTrust(_).trust)
        .map(_.view.mapValues { trustInfo =>
          trustInfo.predictedTrust.orElse(trustInfo.trustLabel)
        }.toMap)
        .map(_.collect { case (peerId, Some(trustScore)) => peerId -> trustScore })
        .map(TrustScores(_))

      def updateTrustWithBiases(selfPeerId: PeerId): F[Unit] =
        trustStoreRef.update { store =>
          val biasedTrust = getBiasedTrust(store.trust)
          val trustUpdates = TrustModel.calculateTrust(biasedTrust, selfPeerId)

          val updated = store.trust.trust ++ trustUpdates.map {
            case (k, v) =>
              k -> store.trust.trust
                .getOrElse(k, TrustInfo())
                .focus(_.predictedTrust)
                .replace(v.some)
          }

          store.focus(_.trust.trust).replace(updated)
        }

      def getCurrentOrdinalTrust: F[OrdinalTrustMap] = trustStoreRef.get.map(_.currentOrdinalTrust)

      def getNextOrdinalTrust: F[OrdinalTrustMap] = trustStoreRef.get.map(_.nextOrdinalTrust)

      def getPublicTrust: F[PublicTrust] = getTrust
        .map(_.toPublicTrust)
        .map(
          _.focus(_.labels)
            .modify(
              _.view
                .mapValues(max(config.seedlistOutputBias, _))
                .toMap
            )
        )

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

      private def getBiasedTrust(trust: TrustMap): TrustMap =
        seedlist.fold(trust) { peerIds =>
          val updatedTrust = peerIds
            .filterNot(trust.trust.contains)
            .map(_ -> TrustInfo(predictedTrust = config.seedlistInputBias.some))
            .toMap
          val updatedPeerLabels = peerIds
            .filterNot(trust.peerLabels.value.contains)
            .map(_ -> PublicTrust.empty)
            .toMap

          TrustMap(
            trust.trust ++ updatedTrust,
            PublicTrustMap(trust.peerLabels.value ++ updatedPeerLabels)
          )
        }
    }
}
