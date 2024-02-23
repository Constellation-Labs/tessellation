package org.tessellation.dag.l1.infrastructure.block.rumor

import cats.effect.Async
import cats.effect.std.Queue

import org.tessellation.node.shared.infrastructure.gossip.RumorHandler
import org.tessellation.schema.Block
import org.tessellation.schema.gossip.CommonRumor
import org.tessellation.security.signature.Signed

object handler {

  def blockRumorHandler[F[_]: Async](
    peerBlockQueue: Queue[F, Signed[Block]]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, Signed[Block]] {
      case CommonRumor(signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
