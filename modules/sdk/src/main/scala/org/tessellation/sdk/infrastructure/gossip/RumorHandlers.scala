package org.tessellation.sdk.infrastructure.gossip

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.semigroupk._
import cats.syntax.show._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.fork.ForkInfoStorage
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.infrastructure.cluster.rumor.handler.nodeStateHandler
import org.tessellation.sdk.infrastructure.fork.ForkInfoHandler
import org.tessellation.sdk.infrastructure.healthcheck.ping.PingHealthCheckConsensus
import org.tessellation.sdk.infrastructure.healthcheck.ping.handler.pingProposalHandler

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RumorHandlers {

  def make[F[_]: Async: KryoSerializer](
    clusterStorage: ClusterStorage[F],
    pingHealthCheck: PingHealthCheckConsensus[F],
    localHealthcheck: LocalHealthcheck[F],
    forkInfoStorage: ForkInfoStorage[F]
  ): RumorHandlers[F] =
    new RumorHandlers[F](clusterStorage, pingHealthCheck, localHealthcheck, forkInfoStorage) {}
}

sealed abstract class RumorHandlers[F[_]: Async: KryoSerializer] private (
  clusterStorage: ClusterStorage[F],
  pingHealthCheck: PingHealthCheckConsensus[F],
  localHealthcheck: LocalHealthcheck[F],
  forkInfoStorage: ForkInfoStorage[F]
) {
  private val nodeState = nodeStateHandler(clusterStorage, localHealthcheck)
  private val pingProposal = pingProposalHandler(pingHealthCheck)
  private val forkInfo = ForkInfoHandler.make(forkInfoStorage)

  private val debug: RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    val strHandler = RumorHandler.fromCommonRumorConsumer[F, String] { rumor =>
      logger.info(s"String rumor received ${rumor.content}")
    }

    val optIntHandler = RumorHandler.fromPeerRumorConsumer[F, Option[Int]]() { rumor =>
      rumor.content match {
        case Some(i) if i > 0 => logger.info(s"Int rumor received ${i.show}, origin ${rumor.origin.show}")
        case o =>
          MonadThrow[F].raiseError(new RuntimeException(s"Int rumor error ${o.show}, origin ${rumor.origin.show}"))
      }
    }

    strHandler <+> optIntHandler
  }

  val handlers: RumorHandler[F] = nodeState <+> pingProposal <+> debug <+> forkInfo
}
