package org.tessellation.dag.l1.infrastructure.block.rumor

import cats.effect.Async
import cats.effect.std.Queue

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.gossip.CommonRumor
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.security.signature.Signed

import io.circe.Decoder

object handler {

  def blockRumorHandler[F[_]: Async: KryoSerializer, B <: Block: Decoder: TypeTag](
    peerBlockQueue: Queue[F, Signed[B]]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, Signed[B]] {
      case CommonRumor(signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
