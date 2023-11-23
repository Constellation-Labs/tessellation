package org.tessellation.node.shared.infrastructure.fork

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.tessellation.node.shared.domain.fork.{ForkInfo, ForkInfoStorage}
import org.tessellation.node.shared.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler, rumorLoggerName}
import org.tessellation.schema.gossip.PeerRumor

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
