package io.constellationnetwork.dag.l1.infrastructure.block.rumor

import cats.effect.Async
import cats.effect.std.Queue

import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.gossip.CommonRumor
import io.constellationnetwork.security.signature.Signed

object handler {

  def blockRumorHandler[F[_]: Async](
    peerBlockQueue: Queue[F, Signed[Block]]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, Signed[Block]] {
      case CommonRumor(signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
