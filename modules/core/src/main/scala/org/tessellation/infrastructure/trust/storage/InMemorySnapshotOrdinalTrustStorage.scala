package org.tessellation.infrastructure.trust.storage

import cats.Monad
import cats.data.OptionT
import cats.effect.kernel.Ref
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.domain.trust.storage.SnapshotOrdinalTrustStorage
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{SnapshotOrdinalPublicTrust, SnapshotOrdinalTrustInfo, TrustInfo}

import eu.timepit.refined.auto._

object InMemorySnapshotOrdinalTrustStorage {

  def make[F[_]: Monad: Ref.Make](
    getSnapshotOrdinal: => F[Option[SnapshotOrdinal]]
  ): F[SnapshotOrdinalTrustStorage[F]] =
    Ref[F].of[Map[PeerId, SnapshotOrdinalTrustInfo]](Map.empty).map(make(_, getSnapshotOrdinal))

  def make[F[_]: Monad](
    ordinalTrustStorage: Ref[F, Map[PeerId, SnapshotOrdinalTrustInfo]],
    getSnapshotOrdinal: => F[Option[SnapshotOrdinal]]
  ): SnapshotOrdinalTrustStorage[F] = new SnapshotOrdinalTrustStorage[F] {
    def getTrust: F[Map[PeerId, SnapshotOrdinalTrustInfo]] = ordinalTrustStorage.get

    def getSnapshotOrdinalPublicTrust: F[SnapshotOrdinalPublicTrust] = getTrust
      .map(_.view.mapValues(v => (v.ordinal, v.trustInfo.predictedTrust)))
      .map(_.toMap)
      .map(SnapshotOrdinalPublicTrust(_))

    def update(updates: Map[PeerId, TrustInfo]): F[SnapshotOrdinal] =
      OptionT(getSnapshotOrdinal)
        .foldF(SnapshotOrdinal.MinValue.pure[F]) { ordinal =>
          ordinalTrustStorage.update { currentTrusts =>
            updates.filterNot { case (_, trust) => trust.isEmpty }
              .foldLeft(currentTrusts) {
                case (currentTrust, (peerId, updatedTrustInfo)) =>
                  currentTrust.updatedWith(peerId) {
                    case trust @ Some(t) if t.ordinal.value >= ordinal.value => trust
                    case _                                                   => SnapshotOrdinalTrustInfo(updatedTrustInfo, ordinal).some
                  }
              }
          }.as(ordinal)
        }
  }
}
