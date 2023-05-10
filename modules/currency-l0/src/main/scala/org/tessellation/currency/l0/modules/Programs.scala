package org.tessellation.currency.l0.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.currency.l0.http.P2PClient
import org.tessellation.currency.l0.snapshot.programs.{Download, Genesis, Rollback}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.tessellation.sdk.domain.snapshot.PeerSelect
import org.tessellation.sdk.domain.snapshot.programs.Download
import org.tessellation.sdk.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.sdk.infrastructure.snapshot.{CurrencySnapshotContextFunctions, MajorityPeerSelect}
import org.tessellation.sdk.modules.SdkPrograms
import org.tessellation.security.SecurityProvider

object Programs {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    nodeId: PeerId,
    globalL0Peer: L0Peer,
    sdkPrograms: SdkPrograms[F],
    storages: Storages[F],
    services: Services[F],
    p2pClient: P2PClient[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F]
  ): Programs[F] = {
    val peerSelect: PeerSelect[F] = MajorityPeerSelect.make(storages.cluster, p2pClient.currencySnapshot)
    val download = Download
      .make(
        p2pClient,
        storages.cluster,
        currencySnapshotContextFns,
        storages.node,
        services.consensus,
        peerSelect
      )

    val globalL0PeerDiscovery = L0PeerDiscovery.make(
      p2pClient.globalL0Cluster,
      storages.globalL0Cluster
    )

    val genesisLoader = GenesisLoader.make

    val genesis = Genesis.make(
      keyPair,
      services.collateral,
      storages.lastBinaryHash,
      services.stateChannelSnapshot,
      storages.snapshot,
      p2pClient.stateChannelSnapshot,
      globalL0Peer,
      nodeId,
      services.consensus.manager,
      genesisLoader,
      storages.identifierStorage
    )

    val rollback = Rollback.make(
      nodeId,
      services.globalL0,
      storages.identifierStorage,
      storages.lastBinaryHash,
      storages.snapshot,
      services.collateral,
      services.consensus.manager
    )

    new Programs[F](sdkPrograms.peerDiscovery, globalL0PeerDiscovery, sdkPrograms.joining, download, genesis, rollback) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val globalL0PeerDiscovery: L0PeerDiscovery[F],
  val joining: Joining[F],
  val download: Download[F],
  val genesis: Genesis[F],
  val rollback: Rollback[F]
)
