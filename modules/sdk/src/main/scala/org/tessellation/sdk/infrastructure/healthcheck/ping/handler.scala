package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.Applicative
import cats.effect.Async
import cats.syntax.eq._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

object handler {

  def pingProposalHandler[F[_]: Async: KryoSerializer](
    pingHealthcheck: PingHealthCheckConsensus[F]
  ): RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, PingConsensusHealthStatus](IgnoreSelfOrigin) { rumor =>
      Applicative[F].whenA(rumor.content.owner === rumor.origin) {
        pingHealthcheck.handleProposal(rumor.content)
      }
    }
}
