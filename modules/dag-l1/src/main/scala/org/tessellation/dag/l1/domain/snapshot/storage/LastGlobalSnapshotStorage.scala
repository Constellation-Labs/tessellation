package org.tessellation.dag.l1.domain.snapshot.storage

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, MonadThrow}

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.Height
import org.tessellation.schema.security.Hashed
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage

import fs2.Stream
import fs2.concurrent.SignallingRef

object LastGlobalSnapshotStorage {

  def make[F[_]: Async: Ref.Make]: F[LastGlobalSnapshotStorage[F] with LatestBalances[F]] =
    SignallingRef.of[F, Option[Hashed[GlobalSnapshot]]](None).map(make(_))

  def make[F[_]: MonadThrow](
    snapshotR: SignallingRef[F, Option[Hashed[GlobalSnapshot]]]
  ): LastGlobalSnapshotStorage[F] with LatestBalances[F] =
    new LastGlobalSnapshotStorage[F] with LatestBalances[F] {

      def set(snapshot: Hashed[GlobalSnapshot]): F[Unit] =
        snapshotR.modify {
          case Some(current) if current.hash === snapshot.lastSnapshotHash && current.ordinal.next === snapshot.ordinal =>
            (snapshot.some, Applicative[F].unit)
          case other =>
            (other, MonadThrow[F].raiseError[Unit](new Throwable("Failure during setting new global snapshot!")))
        }.flatten

      def setInitial(snapshot: Hashed[GlobalSnapshot]): F[Unit] =
        snapshotR.modify {
          case None => (snapshot.some, Applicative[F].unit)
          case other =>
            (
              other,
              MonadThrow[F].raiseError[Unit](new Throwable(s"Failure setting initial snapshot! Encountered non empty "))
            )
        }.flatten

      def get: F[Option[Hashed[GlobalSnapshot]]] =
        snapshotR.get

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        snapshotR.get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] = snapshotR.get.map(_.map(_.height))

      def getLatestBalances: F[Option[Map[Address, Balance]]] =
        get.map(_.map(_.info.balances))

      def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
        snapshotR.discrete
          .flatMap(_.fold[Stream[F, Hashed[GlobalSnapshot]]](Stream.empty)(Stream(_)))
          .map(_.info.balances)
    }
}
