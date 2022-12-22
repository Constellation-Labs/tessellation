package org.tessellation.dag.l1.infrastructure.block.rumor

import cats.effect.Async
import cats.effect.std.Queue

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.CommonRumor
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.infrastructure.gossip.RumorHandler

object handler {

  def blockRumorHandler[F[_]: Async: KryoSerializer](
    peerBlockQueue: Queue[F, Signed[DAGBlock]]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, Signed[DAGBlock]] {
      case CommonRumor(signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
