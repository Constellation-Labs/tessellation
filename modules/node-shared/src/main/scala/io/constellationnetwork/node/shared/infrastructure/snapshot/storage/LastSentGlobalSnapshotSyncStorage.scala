package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncReference}
import io.constellationnetwork.node.shared.infrastructure.snapshot.RecoveryGlobalSnapshotSync.RefreshMode
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

/** Process-local publication state. The required recovery refresh is an operational first-successor guard, not signed protocol state; a
  * rollback restart reconstructs and re-arms it before consensus starts.
  */
trait LastSentGlobalSnapshotSyncStorage[F[_]] {
  def set(globalSnapshotSync: Signed[GlobalSnapshotSync]): F[Unit]
  def get: F[Option[GlobalSnapshotSyncReference]]
  def armRecoveryRefresh(required: LastSentGlobalSnapshotSyncStorage.RequiredRecoveryRefresh): F[Unit]
  def getRequiredRecoveryRefresh: F[Option[LastSentGlobalSnapshotSyncStorage.RequiredRecoveryRefresh]]
  def clearRequiredRecoveryRefresh: F[Unit]
}

object LastSentGlobalSnapshotSyncStorage {

  final case class RequiredRecoveryRefresh(
    value: Signed[GlobalSnapshotSync],
    mode: RefreshMode,
    validThroughGlobalParent: SnapshotOrdinal
  )
  private final case class State(
    lastSent: Option[GlobalSnapshotSyncReference],
    requiredRecoveryRefresh: Option[RequiredRecoveryRefresh]
  )

  def make[F[_]: Async: Hasher](): F[LastSentGlobalSnapshotSyncStorage[F]] =
    Ref.of[F, State](State(None, None)).map { stateR =>
      new LastSentGlobalSnapshotSyncStorage[F] {
        def get: F[Option[GlobalSnapshotSyncReference]] = stateR.get.map(_.lastSent)

        def set(globalSnapshotSync: Signed[GlobalSnapshotSync]): F[Unit] =
          GlobalSnapshotSyncReference
            .of(globalSnapshotSync)
            .flatMap(reference => stateR.update(_.copy(lastSent = reference.some)))

        def armRecoveryRefresh(required: RequiredRecoveryRefresh): F[Unit] =
          stateR.update(_.copy(requiredRecoveryRefresh = required.some))

        def getRequiredRecoveryRefresh: F[Option[RequiredRecoveryRefresh]] =
          stateR.get.map(_.requiredRecoveryRefresh)

        def clearRequiredRecoveryRefresh: F[Unit] =
          stateR.update(_.copy(requiredRecoveryRefresh = None))
      }
    }
}
