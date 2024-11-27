package io.constellationnetwork.dag.l1.infrastructure.swap.rumor

import cats.effect.Async
import cats.effect.std.Queue

import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.schema.gossip.CommonRumor
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.security.signature.Signed

object handler {

  def allowSpendBlockRumorHandler[F[_]: Async](
    peerBlockQueue: Queue[F, Signed[AllowSpendBlock]]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, Signed[AllowSpendBlock]] {
      case CommonRumor(signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
