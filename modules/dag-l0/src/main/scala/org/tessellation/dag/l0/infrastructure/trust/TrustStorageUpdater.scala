package org.tessellation.dag.l0.infrastructure.trust

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration.DurationInt

import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.trust.storage.TrustStorage
import org.tessellation.schema.SnapshotOrdinal

trait TrustStorageUpdater[F[_]] {
  def update: F[Unit]
}

object TrustStorageUpdater {

  def make[F[_]: Async](
    getOrdinal: F[Option[SnapshotOrdinal]],
    gossip: Gossip[F],
    storage: TrustStorage[F]
  ): TrustStorageUpdater[F] = new TrustStorageUpdater[F] {
    def update: F[Unit] = for {
      maybeOrdinal <- getOrdinal
      _ <- maybeOrdinal.traverse_(storage.updateCurrent) // This must come first.
      maybeOrdinalPublicTrust <- maybeOrdinal.flatTraverse(storage.updateNext)
      _ <- maybeOrdinalPublicTrust.traverse_(gossip.spread(_))
    } yield ()
  }

  def daemon[F[_]: Async: Supervisor](updater: TrustStorageUpdater[F]): Daemon[F] =
    Daemon.periodic(updater.update, 1.minute)

}
