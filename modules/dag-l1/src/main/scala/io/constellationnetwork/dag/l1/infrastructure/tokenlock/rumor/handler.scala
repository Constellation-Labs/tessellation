package io.constellationnetwork.dag.l1.infrastructure.tokenlock.rumor

import cats.effect.Async
import cats.effect.std.Queue

import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.schema.gossip.CommonRumor
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed

object handler {

  def tokenLockBlockRumorHandler[F[_]: Async](
    peerBlockQueue: Queue[F, Signed[TokenLockBlock]]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, Signed[TokenLockBlock]] {
      case CommonRumor(signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
