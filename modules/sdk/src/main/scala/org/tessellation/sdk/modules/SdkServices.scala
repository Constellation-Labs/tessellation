package org.tessellation.sdk.modules

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencyTransaction}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.sdk.config.types.{CollateralConfig, SdkConfig}
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.http.p2p.clients.NodeClient
import org.tessellation.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.sdk.infrastructure.cluster.services.Cluster
import org.tessellation.sdk.infrastructure.gossip.Gossip
import org.tessellation.sdk.infrastructure.healthcheck.LocalHealthcheck
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash

import fs2.concurrent.SignallingRef

object SdkServices {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    cfg: SdkConfig,
    nodeId: PeerId,
    generation: Generation,
    keyPair: KeyPair,
    storages: SdkStorages[F],
    queues: SdkQueues[F],
    session: Session[F],
    nodeClient: NodeClient[F],
    validators: SdkValidators[F],
    seedlist: Option[Set[PeerId]],
    restartSignal: SignallingRef[F, Unit],
    versionHash: Hash,
    collateral: CollateralConfig,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]]
  ): F[SdkServices[F]] = {

    val cluster = Cluster
      .make[F](
        cfg.leavingDelay,
        cfg.httpConfig,
        nodeId,
        keyPair,
        storages.cluster,
        storages.session,
        storages.node,
        seedlist,
        restartSignal,
        versionHash
      )

    for {
      localHealthcheck <- LocalHealthcheck.make[F](nodeClient, storages.cluster)
      gossip <- Gossip.make[F](queues.rumor, nodeId, generation, keyPair)
      currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F, CurrencyTransaction, CurrencyBlock](validators.currencyBlockValidator),
        collateral.amount
      )
      currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(currencySnapshotAcceptanceManager)
      globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager.make(None, stateChannelAllowanceLists)
      globalSnapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F, DAGTransaction, DAGBlock](validators.blockValidator),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](validators.stateChannelValidator, globalSnapshotStateChannelManager, currencySnapshotContextFns),
        collateral.amount
      )
      globalSnapshotContextFns = GlobalSnapshotContextFunctions.make(globalSnapshotAcceptanceManager)
    } yield
      new SdkServices[F](
        localHealthcheck = localHealthcheck,
        cluster = cluster,
        session = session,
        gossip = gossip,
        globalSnapshotContextFns = globalSnapshotContextFns,
        currencySnapshotContextFns = currencySnapshotContextFns,
        currencySnapshotAcceptanceManager = currencySnapshotAcceptanceManager
      ) {}
  }
}

sealed abstract class SdkServices[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
  val currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
  val currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F]
)
