package io.constellationnetwork.currency.l1.modules

import cats.effect.Async
import cats.effect.std.Random

import io.constellationnetwork.currency.l1.http.p2p.P2PClient
import io.constellationnetwork.dag.l1.domain.snapshot.programs.SnapshotProcessor
import io.constellationnetwork.dag.l1.modules.{Programs => BasePrograms}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.cluster.programs.L0PeerDiscovery
import io.constellationnetwork.node.shared.modules.SharedPrograms
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}

object Programs {

  def make[
    F[_]: Async: Random,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P],
    R <: CliMethod
  ](
    sharedPrograms: SharedPrograms[F, R],
    p2pClient: P2PClient[F],
    storages: Storages[F, P, S, SI],
    snapshotProcessorProgram: SnapshotProcessor[F, P, S, SI]
  ): Programs[F, P, S, SI, R] = {
    val l0PeerDiscoveryProgram = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)
    val globalL0PeerDiscoveryProgram = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.globalL0Cluster)

    new Programs[F, P, S, SI, R] {
      val peerDiscovery = sharedPrograms.peerDiscovery
      val l0PeerDiscovery = l0PeerDiscoveryProgram
      val globalL0PeerDiscovery = globalL0PeerDiscoveryProgram
      val joining = sharedPrograms.joining
      val snapshotProcessor = snapshotProcessorProgram
    }
  }
}

trait Programs[F[_], P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P], R <: CliMethod] extends BasePrograms[F, P, S, SI] {
  val globalL0PeerDiscovery: L0PeerDiscovery[F]
}
