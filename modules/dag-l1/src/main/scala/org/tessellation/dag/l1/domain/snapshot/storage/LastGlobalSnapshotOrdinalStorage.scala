package org.tessellation.dag.l1.domain.snapshot.storage

import cats.Monad
import cats.effect.Ref
import cats.syntax.eq._
import cats.syntax.functor._

import org.tessellation.dag.snapshot.SnapshotOrdinal

trait LastGlobalSnapshotOrdinalStorage[F[_]] {
  def trySet(expected: SnapshotOrdinal, snapshotOrdinal: SnapshotOrdinal): F[Boolean]

  def get: F[SnapshotOrdinal]
}

object LastGlobalSnapshotOrdinalStorage {

  def make[F[_]: Monad: Ref.Make](ordinal: SnapshotOrdinal): F[LastGlobalSnapshotOrdinalStorage[F]] =
    Ref.of[F, SnapshotOrdinal](ordinal).map(make(_))

  def make[F[_]](ordinal: Ref[F, SnapshotOrdinal]): LastGlobalSnapshotOrdinalStorage[F] =
    new LastGlobalSnapshotOrdinalStorage[F] {

      def trySet(expected: SnapshotOrdinal, snapshotOrdinal: SnapshotOrdinal): F[Boolean] =
        ordinal.modify { current =>
          if (current === expected)
            (snapshotOrdinal, true)
          else
            (current, false)
        }

      def get: F[SnapshotOrdinal] =
        ordinal.get
    }

}
