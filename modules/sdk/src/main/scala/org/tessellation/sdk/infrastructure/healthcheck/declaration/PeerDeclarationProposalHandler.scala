package org.tessellation.sdk.infrastructure.healthcheck.declaration

import cats.Applicative
import cats.effect.Async
import cats.syntax.eq._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensus
import org.tessellation.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

import io.circe.Decoder

object PeerDeclarationProposalHandler {

  def make[F[_]: Async: KryoSerializer, K: TypeTag: Decoder](
    healthCheck: HealthCheckConsensus[F, Key[K], Health, Status[K], Decision]
  ): RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, Status[K]](IgnoreSelfOrigin) { rumor =>
      Applicative[F].whenA(rumor.content.owner === rumor.origin) {
        healthCheck.handleProposal(rumor.content)
      }
    }

}
