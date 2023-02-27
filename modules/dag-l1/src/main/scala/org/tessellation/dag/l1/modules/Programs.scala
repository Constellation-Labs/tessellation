package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.tessellation.sdk.modules.SdkPrograms
import org.tessellation.security.SecurityProvider

object Programs {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Random, T <: Transaction, B <: Block[T], S <: Snapshot[T, B]](
    sdkPrograms: SdkPrograms[F],
    p2pClient: P2PClient[F, T, B],
    storages: Storages[F, T, B, S],
    snapshotProcessorProgram: SnapshotProcessor[F, T, B, S]
  ): Programs[F, T, B, S] = {
    val l0PeerDiscoveryProgram = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)

    new Programs[F, T, B, S] {
      val peerDiscovery = sdkPrograms.peerDiscovery
      val l0PeerDiscovery = l0PeerDiscoveryProgram
      val joining = sdkPrograms.joining
      val snapshotProcessor = snapshotProcessorProgram
    }
  }
}

trait Programs[F[_], T <: Transaction, B <: Block[T], S <: Snapshot[T, B]] {
  val peerDiscovery: PeerDiscovery[F]
  val l0PeerDiscovery: L0PeerDiscovery[F]
  val joining: Joining[F]
  val snapshotProcessor: SnapshotProcessor[F, T, B, S]
}
