package org.tessellation.sdk.infrastructure.healthcheck.declaration

import cats.effect.Temporal
import cats.effect.kernel.Async
import cats.syntax.flatMap._

import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.healthcheck.consensus.HealthCheckConsensus

object PeerDeclarationHealthCheckDaemon {

  def make[F[_]: Async, K](
    healthCheck: HealthCheckConsensus[F, Key[K], Health, Status[K], Decision],
    config: HealthCheckConfig
  ): Daemon[F] =
    Daemon.spawn((healthCheck.trigger() >> Temporal[F].sleep(config.peerDeclaration.triggerInterval)).foreverM)

}
