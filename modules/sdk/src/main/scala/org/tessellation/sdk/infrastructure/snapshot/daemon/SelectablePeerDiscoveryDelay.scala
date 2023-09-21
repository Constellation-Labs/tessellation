package org.tessellation.sdk.infrastructure.snapshot.daemon

import cats.effect.{Async, Temporal}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.cli.AppEnvironment
import org.tessellation.cli.AppEnvironment.Dev
import org.tessellation.schema.node.NodeState.Ready
import org.tessellation.schema.peer.Peer
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.snapshot.PeerDiscoveryDelay
import org.tessellation.sdk.infrastructure.snapshot.PeerSelect.NoPeersToSelect

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies.{constantDelay, limitRetriesByCumulativeDelay}
import retry.retryingOnFailures

object SelectablePeerDiscoveryDelay {

  def make[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    appEnvironment: AppEnvironment,
    checkPeersAttemptDelay: FiniteDuration,
    checkPeersMaxDelay: FiniteDuration,
    additionalDiscoveryDelay: FiniteDuration,
    minPeers: PosInt
  ): PeerDiscoveryDelay[F] = new PeerDiscoveryDelay[F] {

    private val logger = Slf4jLogger.getLogger[F]

    private val fallbackPeersCount: PosInt = 1

    private def waitUntil(minPeers: PosInt): F[Set[Peer]] = retryingOnFailures[Set[Peer]](
      limitRetriesByCumulativeDelay(
        threshold = checkPeersMaxDelay,
        policy = constantDelay(checkPeersAttemptDelay)
      ),
      a => (a.size >= minPeers).pure[F],
      (a, details) =>
        logger.info(
          s"Discovered ${a.size}/$minPeers selectable peers, waiting $checkPeersAttemptDelay: $details"
        )
    )(clusterStorage.getResponsivePeers.map(_.filter(_.state === Ready)))

    def waitForPeers: F[Unit] = {
      val peers =
        if (appEnvironment === Dev)
          waitUntil(fallbackPeersCount)
        else
          waitUntil(minPeers).flatMap {
            case s if s.size >= minPeers => Temporal[F].sleep(additionalDiscoveryDelay).as(s)
            case _ =>
              Temporal[F].sleep(additionalDiscoveryDelay) >> waitUntil(fallbackPeersCount)
          }

      peers.flatMap(s => NoPeersToSelect.raiseError[F, Unit].whenA(s.isEmpty))
    }
  }

}
