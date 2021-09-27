package org.tesselation.modules

import cats.effect.Async

import org.tesselation.domain.cluster.programs.Joining
import org.tesselation.effects.GenUUID
import org.tesselation.http.p2p.P2PClient
import org.tesselation.keytool.security.SecurityProvider
import org.tesselation.kryo.KryoSerializer

object Programs {

  def make[F[_]: Async: GenUUID: SecurityProvider: KryoSerializer](
    storages: Storages[F],
    services: Services[F],
    p2pClient: P2PClient[F]
  ): Programs[F] =
    new Programs[F](storages, services, p2pClient) {}
}

sealed abstract class Programs[F[_]: Async: GenUUID: SecurityProvider: KryoSerializer] private (
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
