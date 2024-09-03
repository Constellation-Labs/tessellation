package io.constellationnetwork.node.shared.infrastructure.fork

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import io.constellationnetwork.node.shared.domain.fork.{ForkInfo, ForkInfoStorage}
import io.constellationnetwork.node.shared.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler, rumorLoggerName}
import io.constellationnetwork.schema.gossip.PeerRumor

import org.typelevel.log4cats.slf4j.Slf4jLogger

object ForkInfoHandler {

  def make[F[_]: Async](storage: ForkInfoStorage[F]): RumorHandler[F] = {
    val logger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

    RumorHandler.fromPeerRumorConsumer[F, ForkInfo](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, content) =>
        logger.debug(s"Received fork info=${content} from id=${origin.show}") >>
          storage.add(peerId = origin, entry = content)
    }
  }

}
