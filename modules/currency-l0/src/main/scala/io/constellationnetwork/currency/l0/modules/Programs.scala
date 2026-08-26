package io.constellationnetwork.currency.l0.modules

import java.security.KeyPair

import cats.Parallel
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.snapshot.programs.{Download, Genesis, Rollback}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotContext}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import io.constellationnetwork.node.shared.domain.snapshot.PeerSelect
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.infrastructure.genesis.{GenesisFS => GenesisLoader}
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencySnapshotContextFunctions, PeerSelect}
import io.constellationnetwork.node.shared.modules.SharedPrograms
import io.constellationnetwork.schema.peer.{L0Peer, PeerId}
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

object Programs {

  def make[F[_]: Async: Parallel: Random: KryoSerializer: JsonSerializer: SecurityProvider, R <: CliMethod](
    keyPair: KeyPair,
    nodeId: PeerId,
    globalL0Peer: L0Peer,
    sharedPrograms: SharedPrograms[F, R],
    storages: Storages[F],
    services: Services[F, R],
    p2pClient: P2PClient[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    dataApplication: Option[(BaseDataApplicationL0Service[F], CalculatedStateLocalFileSystemStorage[F])],
    seedlist: Option[Set[SeedlistEntry]],
    hasherSelector: HasherSelector[F]
  )(implicit context: L0NodeContext[F]): Programs[F] = {
    implicit val currencyHasherSelector: HasherSelector[F] = hasherSelector

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
        dataApplication.map { case (da, _) => da },
        services.globalL0.pullGlobalSnapshot,
        storages.snapshot,
        storages.currencySnapshotCleanup,
        storages.combinedCurrencySnapshotCheckpointStorage,
        storages.eventMempool,
        services.stateChannelBinarySender,
        storages.recoverySyncPublication,
        storages.stateChannelBinaryOutbox
      )

    val globalL0PeerDiscovery = L0PeerDiscovery.make(
      p2pClient.globalL0Cluster,
      storages.globalL0Cluster
    )

    val genesisLoader = GenesisLoader.make[F, CurrencySnapshot]

    val genesis = Genesis.make(
      keyPair,
      services.collateral,
      services.stateChannelSnapshot,
      p2pClient.stateChannelSnapshot,
      globalL0Peer,
      nodeId,
      genesisLoader,
      storages.identifier,
      services.globalL0
    )

    val rollback = Rollback.make(
      services.globalL0,
      storages.identifier,
      dataApplication,
      storages.currencySnapshotCleanup,
      storages.globalSnapshotsWithStateFileStorage,
      storages.globalSnapshotsWithStateDeltasFileStorage,
      services.globalSnapshotContextFunctions,
      storages.combinedCurrencySnapshotCheckpointStorage,
      storages.snapshot,
      storages.recoverySyncPublication,
      storages.stateChannelBinaryOutbox,
      (snapshot, info) =>
        for {
          identifier <- storages.identifier.get
          collateralEligible <- services.consensus.consensusFns.facilitatorFilter(
            snapshot,
            CurrencySnapshotContext(identifier, info),
            nodeId
          )
          seedlistEligible = seedlist.forall(_.exists(_.peerId === nodeId))
          _ <- Async[F].raiseUnless(collateralEligible && seedlistEligible)(
            new IllegalStateException(s"Controlled Currency rollback lead ${nodeId.show} is not seedlist/collateral eligible")
          )
        } yield ()
    )

    new Programs[F](sharedPrograms.peerDiscovery, globalL0PeerDiscovery, sharedPrograms.joining, download, genesis, rollback) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val globalL0PeerDiscovery: L0PeerDiscovery[F],
  val joining: Joining[F],
  val download: Download[F, CurrencyIncrementalSnapshot],
  val genesis: Genesis[F],
  val rollback: Rollback[F]
)
