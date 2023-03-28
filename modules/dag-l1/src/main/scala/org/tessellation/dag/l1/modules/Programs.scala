package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.tessellation.sdk.modules.SdkPrograms
import org.tessellation.security.SecurityProvider

object Programs {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Random, T <: Transaction, B <: Block[T], P <: StateProof, S <: Snapshot[
    T,
    B
  ], SI <: SnapshotInfo[P]](
    sdkPrograms: SdkPrograms[F],
    p2pClient: P2PClient[F, T, B],
    storages: Storages[F, T, B, P, S, SI],
    snapshotProcessorProgram: SnapshotProcessor[F, T, B, P, S, SI]
  ): Programs[F, T, B, P, S, SI] = {
    val l0PeerDiscoveryProgram = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)

    new Programs[F, T, B, P, S, SI] {
      val peerDiscovery = sdkPrograms.peerDiscovery
      val l0PeerDiscovery = l0PeerDiscoveryProgram
      val joining = sdkPrograms.joining
      val snapshotProcessor = snapshotProcessorProgram
    }
  }
}

trait Programs[F[_], T <: Transaction, B <: Block[T], P <: StateProof, S <: Snapshot[T, B], SI <: SnapshotInfo[P]] {
  val peerDiscovery: PeerDiscovery[F]
  val l0PeerDiscovery: L0PeerDiscovery[F]
  val joining: Joining[F]
  val snapshotProcessor: SnapshotProcessor[F, T, B, P, S, SI]
}
