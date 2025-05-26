package io.constellationnetwork.dag.l0.modules

import java.security.KeyPair

import cats.Parallel
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.domain.cluster.programs.TrustPush
import io.constellationnetwork.dag.l0.domain.snapshot.programs.Download
import io.constellationnetwork.dag.l0.http.p2p.P2PClient
import io.constellationnetwork.dag.l0.infrastructure.snapshot.programs.RollbackLoader
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.cluster.programs.{Joining, PeerDiscovery}
import io.constellationnetwork.node.shared.domain.snapshot.PeerSelect
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.{GlobalSnapshotContextFunctions, PeerSelect}
import io.constellationnetwork.node.shared.modules.SharedPrograms
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.{HashSelect, HasherSelector, SecurityProvider}

object Programs {

  def make[F[_]: Async: Parallel: KryoSerializer: JsonSerializer: HasherSelector: SecurityProvider: Random, R <: CliMethod](
    sharedPrograms: SharedPrograms[F, R],
    storages: Storages[F],
    services: Services[F, R],
    keyPair: KeyPair,
    config: AppConfig,
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    p2pClient: P2PClient[F],
    globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
    hashSelect: HashSelect,
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F]
  ): Programs[F] =
    HasherSelector[F].withCurrent { implicit hasher =>
      val trustPush = TrustPush.make(storages.trust, services.gossip)
      val peerSelect: PeerSelect[F] = PeerSelect.make(
        storages.cluster,
        p2pClient.globalSnapshot,
        storages.trust.getBiasedTrustScores
      )
      val download: Download[F] = Download
        .make[F](
          storages.snapshotDownload,
          p2pClient,
          storages.cluster,
          lastFullGlobalSnapshotOrdinal,
          globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
          storages.node,
          services.consensus,
          peerSelect,
          lastNGlobalSnapshotStorage
        )
      val rollbackLoader = RollbackLoader.make(
        keyPair,
        config.snapshot,
        storages.incrementalGlobalSnapshotLocalFileSystemStorage,
        storages.globalSnapshotInfoLocalFileSystemStorage,
        storages.snapshotDownload,
        globalSnapshotContextFns,
        hashSelect,
        lastNGlobalSnapshotStorage.getLastN,
        storages.globalSnapshot.getHashed
      )

      new Programs[F](sharedPrograms.peerDiscovery, sharedPrograms.joining, trustPush, download, rollbackLoader) {}
    }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F],
  val trustPush: TrustPush[F],
  val download: Download[F],
  val rollbackLoader: RollbackLoader[F]
)
