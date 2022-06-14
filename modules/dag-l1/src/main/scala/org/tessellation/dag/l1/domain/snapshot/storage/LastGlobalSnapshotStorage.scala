package org.tessellation.dag.l1.domain.snapshot.storage

import cats.effect.Ref
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, MonadThrow}

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.Height
import org.tessellation.sdk.domain.collateral.LatestBalances
import org.tessellation.security.Hashed

trait LastGlobalSnapshotStorage[F[_]] {
  def set(snapshot: Hashed[GlobalSnapshot]): F[Unit]
  def setInitial(snapshot: Hashed[GlobalSnapshot]): F[Unit]
  def get: F[Option[GlobalSnapshot]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}

object LastGlobalSnapshotStorage {

  def make[F[_]: MonadThrow: Ref.Make]: F[LastGlobalSnapshotStorage[F] with LatestBalances[F]] =
    Ref.of[F, Option[Hashed[GlobalSnapshot]]](None).map(make(_))

  def make[F[_]: MonadThrow](
    snapshotR: Ref[F, Option[Hashed[GlobalSnapshot]]]
  ): LastGlobalSnapshotStorage[F] with LatestBalances[F] =
    new LastGlobalSnapshotStorage[F] with LatestBalances[F] {

      def set(snapshot: Hashed[GlobalSnapshot]): F[Unit] =
        snapshotR.modify {
          case Some(current)
              if current.hash === snapshot.lastSnapshotHash && current.ordinal.next === snapshot.ordinal =>
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

      def get: F[Option[GlobalSnapshot]] =
        snapshotR.get.map(_.map(_.signed))

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        snapshotR.get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] = snapshotR.get.map(_.map(_.height))

      def getLatestBalances: F[Option[Map[Address, Balance]]] =
        get.map(_.map(_.info.balances))
    }
}
