package org.tessellation.infrastructure.gossip

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.semigroupk._
import cats.syntax.show._

import org.tessellation.domain.cluster.storage.ClusterStorage
import org.tessellation.domain.trust.storage.TrustStorage
import org.tessellation.infrastructure.cluster.rumour.handler.nodeStateHandler
import org.tessellation.infrastructure.trust.handler.trustHandler
import org.tessellation.kryo.KryoSerializer

import org.typelevel.log4cats.slf4j.Slf4jLogger

object RumorHandlers {

  def make[F[_]: Async: KryoSerializer](
    clusterStorage: ClusterStorage[F],
    trustStorage: TrustStorage[F]
  ): RumorHandlers[F] =
    new RumorHandlers[F](clusterStorage, trustStorage) {}
}

sealed abstract class RumorHandlers[F[_]: Async: KryoSerializer] private (
  clusterStorage: ClusterStorage[F],
  trustStorage: TrustStorage[F]
) {
  private val nodeState = nodeStateHandler(clusterStorage)

  private val trust = trustHandler(trustStorage)

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

  val handlers: RumorHandler[F] = nodeState <+> debug <+> trust
}
