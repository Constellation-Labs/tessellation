package org.tessellation.domain.cluster.programs

import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.trust.storage.{SnapshotOrdinalTrustStorage, TrustStorage}
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.sdk.domain.gossip.Gossip

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

object TrustPush {

  def make[F[_]: Async](
    trustStorage: TrustStorage[F],
    ordinalTrustStorage: SnapshotOrdinalTrustStorage[F],
    gossip: Gossip[F]
  ): F[TrustPush[F]] = Ref[F]
    .of[SnapshotOrdinal](SnapshotOrdinal.MinValue)
    .map(new TrustPush[F](_, trustStorage, ordinalTrustStorage, gossip) {})

  def make[F[_]: Async](
    targetSnapshotOrdinal: Ref[F, SnapshotOrdinal],
    trustStorage: TrustStorage[F],
    ordinalTrustStorage: SnapshotOrdinalTrustStorage[F],
    gossip: Gossip[F]
  ): F[TrustPush[F]] = new TrustPush[F](targetSnapshotOrdinal, trustStorage, ordinalTrustStorage, gossip) {}.pure[F]
}

sealed abstract class TrustPush[F[_]: Async] private (
  targetSnapshotOrdinalRef: Ref[F, SnapshotOrdinal],
  trustStorage: TrustStorage[F],
  ordinalTrustStorage: SnapshotOrdinalTrustStorage[F],
  gossip: Gossip[F]
) {
  val snapshotOrdinalTrustGossipInterval = NonNegLong(1000L)
  val targetSnapshotOrdinal = targetSnapshotOrdinalRef

  def publishUpdated(): F[Unit] =
    for {
      trust <- trustStorage.getPublicTrust
      _ <- gossip.spread(trust)
    } yield {}

  def publishOrdinalUpdates: F[Unit] =
    for {
      trust <- trustStorage.getTrust
      ordinal <- ordinalTrustStorage.update(trust)
      gossipValues <- targetSnapshotOrdinal.get.map(_.value <= ordinal.value)
      _ <- ordinalTrustStorage.getSnapshotOrdinalPublicTrust
        .flatMap(gossip.spread(_))
        .whenA(gossipValues)
      _ <- targetSnapshotOrdinal.set(ordinal.plus(snapshotOrdinalTrustGossipInterval)).whenA(gossipValues)
    } yield ()

}
