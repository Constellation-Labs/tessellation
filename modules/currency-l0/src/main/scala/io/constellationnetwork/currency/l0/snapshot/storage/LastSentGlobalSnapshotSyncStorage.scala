package io.constellationnetwork.currency.l0.snapshot.storage

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncReference}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

trait LastSentGlobalSnapshotSyncStorage[F[_]] {
  def set(globalSnapshotSync: Signed[GlobalSnapshotSync]): F[Unit]
  def get: F[Option[GlobalSnapshotSyncReference]]
}

object LastSentGlobalSnapshotSyncStorage {
  def make[F[_]: Async: Hasher](): F[LastSentGlobalSnapshotSyncStorage[F]] =
    Ref
      .of[F, Option[GlobalSnapshotSyncReference]](None)
      .map(make[F](_))

  def make[F[_]: Async: Hasher](
    lastSentR: Ref[F, Option[GlobalSnapshotSyncReference]]
  ): LastSentGlobalSnapshotSyncStorage[F] =
    new LastSentGlobalSnapshotSyncStorage[F] {

      def get: F[Option[GlobalSnapshotSyncReference]] = lastSentR.get

      def set(globalSnapshotSync: Signed[GlobalSnapshotSync]): F[Unit] =
        GlobalSnapshotSyncReference.of(globalSnapshotSync).flatMap(ref => lastSentR.set(ref.some))
    }
}
