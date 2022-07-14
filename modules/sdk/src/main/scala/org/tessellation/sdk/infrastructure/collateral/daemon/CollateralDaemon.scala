package org.tessellation.sdk.infrastructure.collateral.daemon

import cats.Applicative
import cats.effect.{Async, Spawn}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverseFilter._

import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.collateral.{Collateral, LatestBalances}

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait CollateralDaemon[F[_]] extends Daemon[F] {}

object CollateralDaemon {

  def make[F[_]: Async](
    collateral: Collateral[F],
    latestBalances: LatestBalances[F],
    clusterStorage: ClusterStorage[F]
  ) = new CollateralDaemon[F] {
    private val logger = Slf4jLogger.getLogger[F]

    def start: F[Unit] =
      Spawn[F].start(updatePeersInCluster).void

    private def updatePeersInCluster: F[Unit] =
      latestBalances.getLatestBalancesStream
        .evalMap(_ => updateCluster())
        .compile
        .drain

    private def updateCluster() =
      clusterStorage.getPeers
        .flatMap(_.map(_.id).toList.filterA(id => collateral.hasCollateral(id).map(!_)))
        .flatTap { ids =>
          Applicative[F].whenA(ids.size > 0) {
            logger.debug(s"Removing peers due to not sufficient collateral: ${ids.show}")
          }
        }
        .map(_.toSet)
        .flatMap(clusterStorage.removePeers)
  }
}
