package org.tessellation.node.shared.modules

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.SharedConfig
import org.tessellation.node.shared.domain.cluster.programs.{Joining, PeerDiscovery}
import org.tessellation.node.shared.domain.healthcheck.LocalHealthcheck
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.http.p2p.clients.{ClusterClient, SignClient}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.{Hasher, SecurityProvider}

object SharedPrograms {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Hasher](
    cfg: SharedConfig,
    storages: SharedStorages[F],
    services: SharedServices[F],
    clusterClient: ClusterClient[F],
    signClient: SignClient[F],
    localHealthcheck: LocalHealthcheck[F],
    seedlist: Option[Set[SeedlistEntry]],
    nodeId: PeerId,
    versionHash: Hash
  ): F[SharedPrograms[F]] =
    for {
      pd <- PeerDiscovery.make(clusterClient, storages.cluster, nodeId)
      joining <- Joining.make(
        cfg.environment,
        storages.node,
        storages.cluster,
        signClient,
        services.cluster,
        services.session,
        storages.session,
        localHealthcheck,
        seedlist,
        nodeId,
        cfg.stateAfterJoining,
        pd,
        versionHash
      )
    } yield new SharedPrograms[F](pd, joining) {}
}

sealed abstract class SharedPrograms[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F]
)
