package org.tessellation.node.shared.infrastructure.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, MonadThrow}

import org.tessellation.node.shared.domain.collateral.LatestBalances
import org.tessellation.node.shared.domain.snapshot.Validator.isNextSnapshot
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.Height
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.security.Hashed

import fs2.Stream
import fs2.concurrent.SignallingRef

object LastSnapshotStorage {

  def make[F[_]: Async, S <: Snapshot, SI <: SnapshotInfo[_]]: F[LastSnapshotStorage[F, S, SI] with LatestBalances[F]] =
    SignallingRef.of[F, Option[(Hashed[S], SI)]](None).map(make(_))

  def make[F[_]: Async, S <: Snapshot, SI <: SnapshotInfo[_]](
    snapshot: Option[(Hashed[S], SI)]
  ): F[LastSnapshotStorage[F, S, SI]] =
    SignallingRef.of[F, Option[(Hashed[S], SI)]](snapshot).map(make(_))

  def make[F[_]: Async, S <: Snapshot, SI <: SnapshotInfo[_]](
    snapshotR: SignallingRef[F, Option[(Hashed[S], SI)]]
  ): LastSnapshotStorage[F, S, SI] with LatestBalances[F] =
    new LastSnapshotStorage[F, S, SI] with LatestBalances[F] {

      def set(snapshot: Hashed[S], state: SI): F[Unit] =
        snapshotR.modify {
          case Some((current, _)) if isNextSnapshot(current, snapshot.signed.value) => ((snapshot, state).some, Applicative[F].unit)
          case other =>
            (other, MonadThrow[F].raiseError[Unit](new Throwable("Failure during setting new global snapshot!")))
        }.flatten

      def setInitial(snapshot: Hashed[S], state: SI): F[Unit] =
        snapshotR.modify {
          case None => ((snapshot, state).some, Applicative[F].unit)
          case other =>
            (
              other,
              MonadThrow[F].raiseError[Unit](new Throwable(s"Failure setting initial snapshot! Encountered non empty "))
            )
        }.flatten

      def get: F[Option[Hashed[S]]] =
        snapshotR.get.map(_.map(_._1))

      def getCombined: F[Option[(Hashed[S], SI)]] = snapshotR.get

      def getCombinedStream: Stream[F, Option[(Hashed[S], SI)]] =
        Stream
          .eval[F, Option[(Hashed[S], SI)]](snapshotR.get)
          .merge(snapshotR.discrete)

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] =
        get.map(_.map(_.height))

      def getLatestBalances: F[Option[Map[Address, Balance]]] =
        snapshotR.get.map(_.map(_._2.balances))

      def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
        snapshotR.discrete
          .map(_.map(_._2))
          .flatMap(_.fold[Stream[F, SI]](Stream.empty)(Stream(_)))
          .map(_.balances)
    }
}
