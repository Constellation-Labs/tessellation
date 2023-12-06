package org.tessellation.dag.l0.infrastructure.trust

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.tessellation.node.shared.domain.trust.storage.TrustStorage
import org.tessellation.node.shared.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler, rumorLoggerName}
import org.tessellation.schema.gossip.PeerRumor
import org.tessellation.schema.trust.{PublicTrust, SnapshotOrdinalPublicTrust}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def trustHandler[F[_]: Async](
    trustStorage: TrustStorage[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

    RumorHandler.fromPeerRumorConsumer[F, PublicTrust](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, trust) =>
        logger.info(s"Received trust=${trust} from id=${origin.show}") >> {
          trustStorage.updatePeerPublicTrustInfo(origin, trust)
        }
    }
  }

  def ordinalTrustHandler[F[_]: Async](
    trustStorage: TrustStorage[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

    RumorHandler.fromPeerRumorConsumer[F, SnapshotOrdinalPublicTrust](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, trust) =>
        logger.debug(s"Received trust=${trust} from id=${origin.show}") >>
          trustStorage.updateNext(origin, trust)
    }
  }
}
