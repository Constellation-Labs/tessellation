package io.constellationnetwork.node.shared.infrastructure.gossip

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.semigroupk._
import cats.syntax.show._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.fork.ForkInfoStorage
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.cluster.rumor.handler.nodeStateHandler
import io.constellationnetwork.node.shared.infrastructure.fork.ForkInfoHandler

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RumorHandlers {

  def make[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    localHealthcheck: LocalHealthcheck[F],
    forkInfoStorage: ForkInfoStorage[F]
  ): RumorHandlers[F] =
    new RumorHandlers[F](clusterStorage, localHealthcheck, forkInfoStorage) {}
}

sealed abstract class RumorHandlers[F[_]: Async] private (
  clusterStorage: ClusterStorage[F],
  localHealthcheck: LocalHealthcheck[F],
  forkInfoStorage: ForkInfoStorage[F]
) {
  private val nodeState = nodeStateHandler(clusterStorage, localHealthcheck)
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

  val handlers: RumorHandler[F] = nodeState <+> debug <+> forkInfo
}
