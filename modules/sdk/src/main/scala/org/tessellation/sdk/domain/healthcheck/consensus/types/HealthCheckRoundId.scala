package org.tessellation.sdk.domain.healthcheck.consensus.types

import cats.effect.Concurrent

import org.tessellation.ext.codecs.BinaryCodec
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.types.RoundId

import derevo.cats.show
import derevo.derive
import org.http4s.{EntityDecoder, EntityEncoder}

@derive(show)
final case class HealthCheckRoundId(roundId: RoundId, owner: PeerId)

object HealthCheckRoundId {
  implicit def encoder[G[_]: Concurrent: KryoSerializer]: EntityEncoder[G, HealthCheckRoundId] =
    BinaryCodec.encoder[G, HealthCheckRoundId]
  implicit def decoder[G[_]: Concurrent: KryoSerializer]: EntityDecoder[G, HealthCheckRoundId] =
    BinaryCodec.decoder[G, HealthCheckRoundId]
}
