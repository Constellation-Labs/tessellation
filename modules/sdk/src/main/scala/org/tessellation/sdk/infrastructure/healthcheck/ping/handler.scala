package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.CommonRumor
import org.tessellation.sdk.infrastructure.gossip.RumorHandler

object handler {

  def pingProposalHandler[F[_]: Async: KryoSerializer](
    pingHealthcheck: PingHealthCheckConsensus[F]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, PingConsensusHealthStatus] {
      case CommonRumor(proposal) =>
        pingHealthcheck.handleProposal(proposal)
    }
}
