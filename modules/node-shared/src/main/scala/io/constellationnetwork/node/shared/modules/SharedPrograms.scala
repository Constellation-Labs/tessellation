package io.constellationnetwork.node.shared.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.cluster.programs.{Joining, PeerDiscovery}
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.http.p2p.clients.{ClusterClient, SignClient}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

object SharedPrograms {

  def make[F[_]: Async: SecurityProvider: HasherSelector: Supervisor: Parallel: Random, A <: CliMethod](
    cfg: SharedConfig,
    storages: SharedStorages[F],
    services: SharedServices[F, A],
    clusterClient: ClusterClient[F],
    signClient: SignClient[F],
    localHealthcheck: LocalHealthcheck[F],
    seedlist: Option[Set[SeedlistEntry]],
    nodeId: PeerId,
    versionHash: Hash
  ): F[SharedPrograms[F, A]] =
    for {
      pd <- PeerDiscovery.make(clusterClient, storages.cluster, nodeId)
      joining = HasherSelector[F].withCurrent { implicit hasher =>
        new Joining[F](
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
          versionHash,
          pd
        )
      }
    } yield new SharedPrograms[F, A](pd, joining) {}
}

sealed abstract class SharedPrograms[F[_], A <: CliMethod] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F]
)
