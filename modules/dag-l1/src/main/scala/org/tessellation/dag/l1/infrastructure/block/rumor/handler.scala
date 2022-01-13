package org.tessellation.dag.l1.infrastructure.block.rumor

import cats.effect.Async
import cats.effect.std.Queue

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.ReceivedRumor
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.security.signature.Signed

object handler {

  def blockRumorHandler[F[_]: Async: KryoSerializer](
    peerBlockQueue: Queue[F, Signed[DAGBlock]]
  ): RumorHandler[F] =
    RumorHandler.fromReceivedRumorFn[F, Signed[DAGBlock]]() {
      case ReceivedRumor(_, signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
