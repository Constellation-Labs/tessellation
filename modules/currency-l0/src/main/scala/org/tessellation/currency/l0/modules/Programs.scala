package org.tessellation.currency.l0.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import org.tessellation.currency.l0.http.p2p.P2PClient
import org.tessellation.currency.l0.snapshot.programs.{Download, Genesis, Rollback}
import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.tessellation.node.shared.domain.snapshot.PeerSelect
import org.tessellation.node.shared.domain.snapshot.programs.Download
import org.tessellation.node.shared.infrastructure.genesis.{GenesisFS => GenesisLoader}
import org.tessellation.node.shared.infrastructure.snapshot.{CurrencySnapshotContextFunctions, PeerSelect}
import org.tessellation.node.shared.modules.SharedPrograms
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.security.SecurityProvider

object Programs {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    nodeId: PeerId,
    globalL0Peer: L0Peer,
    sharedPrograms: SharedPrograms[F],
    storages: Storages[F],
    services: Services[F],
    p2pClient: P2PClient[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    dataApplication: Option[(BaseDataApplicationL0Service[F], CalculatedStateLocalFileSystemStorage[F])]
  )(implicit context: L0NodeContext[F]): Programs[F] = {
    val peerSelect: PeerSelect[F] =
      PeerSelect.make(
        storages.cluster,
        p2pClient.currencySnapshot,
        p2pClient.l0Trust.getCurrentTrust.run(globalL0Peer)
      )
    val download = Download
      .make(
        p2pClient,
        storages.cluster,
        currencySnapshotContextFns,
        storages.node,
        services.consensus,
        peerSelect,
        storages.identifier,
        dataApplication.map { case (da, _) => da }
      )

    val globalL0PeerDiscovery = L0PeerDiscovery.make(
      p2pClient.globalL0Cluster,
      storages.globalL0Cluster
    )

    val genesisLoader = GenesisLoader.make[F, CurrencySnapshot]

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
      storages.identifier
    )

    val rollback = Rollback.make(
      nodeId,
      services.globalL0,
      storages.identifier,
      storages.lastBinaryHash,
      storages.snapshot,
      services.collateral,
      services.consensus.manager,
      dataApplication
    )

    new Programs[F](sharedPrograms.peerDiscovery, globalL0PeerDiscovery, sharedPrograms.joining, download, genesis, rollback) {}
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
