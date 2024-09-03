package io.constellationnetwork.dag.l0.infrastructure.trust

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import io.constellationnetwork.node.shared.domain.trust.storage.TrustStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler, rumorLoggerName}
import io.constellationnetwork.schema.gossip.PeerRumor
import io.constellationnetwork.schema.trust.{PublicTrust, SnapshotOrdinalPublicTrust}

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
