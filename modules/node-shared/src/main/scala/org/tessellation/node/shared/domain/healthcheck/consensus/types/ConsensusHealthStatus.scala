package org.tessellation.node.shared.domain.healthcheck.consensus.types

import cats.effect.Concurrent

import org.tessellation.ext.codecs.BinaryCodec
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId

import org.http4s.{EntityDecoder, EntityEncoder}

trait ConsensusHealthStatus[K <: HealthCheckKey, A <: HealthCheckStatus] {
  def key: K
  def roundIds: Set[HealthCheckRoundId]
  def owner: PeerId
  def status: A
  def clusterState: Set[PeerId]
}

object ConsensusHealthStatus {
  implicit def encoder[F[_]: KryoSerializer, K <: HealthCheckKey, A <: HealthCheckStatus]: EntityEncoder[F, ConsensusHealthStatus[K, A]] =
    BinaryCodec.encoder[F, ConsensusHealthStatus[K, A]]

  implicit def decoder[F[_]: Concurrent: KryoSerializer, K <: HealthCheckKey, A <: HealthCheckStatus]
    : EntityDecoder[F, ConsensusHealthStatus[K, A]] = BinaryCodec.decoder[F, ConsensusHealthStatus[K, A]]
}
