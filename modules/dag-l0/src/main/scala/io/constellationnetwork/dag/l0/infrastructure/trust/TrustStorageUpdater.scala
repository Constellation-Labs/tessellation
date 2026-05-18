package io.constellationnetwork.dag.l0.infrastructure.trust

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration.DurationInt

import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.trust.storage.TrustStorage
import io.constellationnetwork.schema.SnapshotOrdinal

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

  /** Trust-update cadence. Was previously 1.minute; lowered to 1.hour because the only remaining live consumer of trust scores is
    * `PeerSelect.getPeerSublist`, which weights download-peer selection during recovery downloads -- a rare event (cold start or
    * `updateShouldRedownload` triggers) that does not need minute-fresh trust data.
    *
    * Pre-cleanup audit: `SnapshotOrdinalPublicTrust` rumor accounted for ~20% of cluster gossip traffic at the 1-minute cadence to feed a
    * consumer that runs maybe a few times per day. `ForkDetect` was a second consumer on paper but never wired into any service. The
    * `/trust/current` HTTP endpoint receives ~0 external traffic. Hourly cadence captures the bandwidth win (~60x reduction) while
    * preserving recovery-download-selection behavior.
    */
  def daemon[F[_]: Async: Supervisor](updater: TrustStorageUpdater[F]): Daemon[F] =
    Daemon.periodic(updater.update, 1.hour)

}
