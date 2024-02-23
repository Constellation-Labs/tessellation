package org.tessellation.node.shared.infrastructure.healthcheck.ping

import cats.Applicative
import cats.effect.Async
import cats.syntax.eq._

import org.tessellation.node.shared.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

object handler {

  def pingProposalHandler[F[_]: Async](
    pingHealthcheck: PingHealthCheckConsensus[F]
  ): RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, PingConsensusHealthStatus](IgnoreSelfOrigin) { rumor =>
      Applicative[F].whenA(rumor.content.owner === rumor.origin) {
        pingHealthcheck.handleProposal(rumor.content)
      }
    }
}
