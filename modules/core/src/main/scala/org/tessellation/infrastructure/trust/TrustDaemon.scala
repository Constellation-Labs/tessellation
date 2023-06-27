package org.tessellation.infrastructure.trust

import cats.effect.std.Supervisor
import cats.effect.{Async, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.TrustDaemonConfig
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.trust.storage.{TrustMap, TrustStorage}

trait TrustDaemon[F[_]] extends Daemon[F]

object TrustDaemon {

  def make[F[_]: Async](
    cfg: TrustDaemonConfig,
    trustStorage: TrustStorage[F],
    selfPeerId: PeerId
  )(implicit S: Supervisor[F]): TrustDaemon[F] = new TrustDaemon[F] {

    def start: F[Unit] =
      for {
        _ <- S.supervise(modelUpdate.foreverM).void
      } yield ()

    private def calculatePredictedTrust(trust: TrustMap): Map[PeerId, Double] =
      TrustModel.calculateTrust(trust, selfPeerId)

    private def modelUpdate: F[Unit] =
      for {
        _ <- Temporal[F].sleep(cfg.interval)
        predictedTrust <- trustStorage.getTrust.map(calculatePredictedTrust)
        _ <- trustStorage.updatePredictedTrust(predictedTrust)
      } yield ()

  }
}
