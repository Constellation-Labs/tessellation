package org.tessellation.infrastructure.trust

import cats.Parallel
import cats.effect.std.Random
import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.TrustDaemonConfig
import org.tessellation.domain.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.TrustInfo
import org.tessellation.sdk.domain.Daemon
import org.tessellation.security.SecurityProvider

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait TrustDaemon[F[_]] extends Daemon[F]

object TrustDaemon {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel](
    cfg: TrustDaemonConfig,
    trustStorage: TrustStorage[F],
    selfPeerId: PeerId
  ): TrustDaemon[F] = new TrustDaemon[F] {

    private val logger = Slf4jLogger.getLogger[F]

    def start: F[Unit] =
      for {
        _ <- Spawn[F].start(modelUpdate.foreverM).void
      } yield ()

    private def calculatePredictedTrust(trust: Map[PeerId, TrustInfo]): Map[PeerId, Double] =
      TrustModel.calculateTrust(trust, selfPeerId)

    private def modelUpdate: F[Unit] =
      for {
        _ <- Temporal[F].sleep(cfg.interval)
        predictedTrust <- trustStorage.getTrust.map(calculatePredictedTrust)
        _ <- trustStorage.updatePredictedTrust(predictedTrust)
      } yield ()

  }
}
