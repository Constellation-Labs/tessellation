package org.tessellation.dag.l1.domain.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, MonadThrow}

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.Height
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.Hashed

import fs2.Stream
import fs2.concurrent.SignallingRef

object LastSnapshotStorage {

  def make[F[_]: Async, S <: Snapshot[_, _]]: F[LastSnapshotStorage[F, S] with LatestBalances[F]] =
    SignallingRef.of[F, Option[Hashed[S]]](None).map(make(_))

  def make[F[_]: Async, S <: Snapshot[_, _]](snapshot: Option[Hashed[S]]): F[LastSnapshotStorage[F, S]] =
    SignallingRef.of[F, Option[Hashed[S]]](snapshot).map(make(_))

  def make[F[_]: MonadThrow, S <: Snapshot[_, _]](
    snapshotR: SignallingRef[F, Option[Hashed[S]]]
  ): LastSnapshotStorage[F, S] with LatestBalances[F] =
    new LastSnapshotStorage[F, S] with LatestBalances[F] {

      def set(snapshot: Hashed[S]): F[Unit] =
        snapshotR.modify {
          case Some(current) if current.hash === snapshot.lastSnapshotHash && current.ordinal.next === snapshot.ordinal =>
            (snapshot.some, Applicative[F].unit)
          case other =>
            (other, MonadThrow[F].raiseError[Unit](new Throwable("Failure during setting new global snapshot!")))
        }.flatten

      def setInitial(snapshot: Hashed[S]): F[Unit] =
        snapshotR.modify {
          case None => (snapshot.some, Applicative[F].unit)
          case other =>
            (
              other,
              MonadThrow[F].raiseError[Unit](new Throwable(s"Failure setting initial snapshot! Encountered non empty "))
            )
        }.flatten

      def get: F[Option[Hashed[S]]] =
        snapshotR.get

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        snapshotR.get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] = snapshotR.get.map(_.map(_.height))

      def getLatestBalances: F[Option[Map[Address, Balance]]] =
        get.map(_.map(_.info.balances))

      def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
        snapshotR.discrete
          .flatMap(_.fold[Stream[F, Hashed[S]]](Stream.empty)(Stream(_)))
          .map(_.info.balances)
    }
}
