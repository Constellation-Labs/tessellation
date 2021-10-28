package org.tesselation.infrastructure.gossip

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.semigroupk._
import cats.syntax.show._

import org.tesselation.domain.cluster.storage.ClusterStorage
import org.tesselation.infrastructure.cluster.rumour.handler.nodeStateHandler
import org.tesselation.kryo.KryoSerializer

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RumorHandlers {

  def make[F[_]: Async: KryoSerializer](
    clusterStorage: ClusterStorage[F]
  ): RumorHandlers[F] =
    new RumorHandlers[F](clusterStorage) {}
}

sealed abstract class RumorHandlers[F[_]: Async: KryoSerializer] private (
  clusterStorage: ClusterStorage[F]
) {
  private val nodeState = nodeStateHandler(clusterStorage)

  private val debug: RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    val strHandler = RumorHandler.fromFn[F, String] { s =>
      logger.info(s"String rumor received $s")
    }

    val optIntHandler = RumorHandler.fromBiFn[F, Option[Int]] { (peerId, optInt) =>
      optInt match {
        case Some(i) if i > 0 => logger.info(s"Int rumor received ${i.show}, origin ${peerId.show}")
        case o =>
          MonadThrow[F].raiseError(new RuntimeException(s"Int rumor error ${o.show}, origin ${peerId.show}"))
      }
    }

    strHandler <+> optIntHandler
  }

  val handlers: RumorHandler[F] = nodeState <+> debug
}
