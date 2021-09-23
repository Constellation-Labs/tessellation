package org.tesselation.modules

import cats.MonadThrow

import org.tesselation.domain.cluster.programs.Joining
import org.tesselation.http.p2p.P2PClient

object Programs {

  def make[F[_]: MonadThrow](
    storages: Storages[F],
    services: Services[F],
    p2pClient: P2PClient[F]
  ): Programs[F] =
    new Programs[F](storages, services, p2pClient) {}
}

sealed abstract class Programs[F[_]: MonadThrow] private (
  storages: Storages[F],
  services: Services[F],
  p2pClient: P2PClient[F]
) {

  val joining: Joining[F] = Joining[F](
    storages.node,
    storages.cluster,
    p2pClient,
    services.cluster,
    services.session
  )
}
