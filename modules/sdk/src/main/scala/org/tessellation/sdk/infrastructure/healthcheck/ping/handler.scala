package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.PeerRumor
import org.tessellation.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

object handler {

  def pingProposalHandler[F[_]: Async: KryoSerializer](
    pingHealthcheck: PingHealthCheckConsensus[F]
  ): RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, PingConsensusHealthStatus](IgnoreSelfOrigin) {
      case PeerRumor(_, _, proposal) =>
        pingHealthcheck.handleProposal(proposal)
    }
}
