package org.tessellation.node.shared.modules

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.env.AppEnvironment
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.{CollateralConfig, SharedConfig}
import org.tessellation.node.shared.domain.cluster.services.{Cluster, Session}
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.healthcheck.LocalHealthcheck
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.snapshot.DoubleSignDetect
import org.tessellation.node.shared.http.p2p.clients.NodeClient
import org.tessellation.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.node.shared.infrastructure.cluster.services.Cluster
import org.tessellation.node.shared.infrastructure.gossip.Gossip
import org.tessellation.node.shared.infrastructure.healthcheck.LocalHealthcheck
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.snapshot._
import org.tessellation.schema.address.Address
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.{Hasher, SecurityProvider}

import fs2.concurrent.SignallingRef

object SharedServices {

  def make[F[_]: Async: KryoSerializer: Hasher: SecurityProvider: Metrics: Supervisor](
    cfg: SharedConfig,
    nodeId: PeerId,
    generation: Generation,
    keyPair: KeyPair,
    storages: SharedStorages[F],
    queues: SharedQueues[F],
    session: Session[F],
    nodeClient: NodeClient[F],
    validators: SharedValidators[F],
    seedlist: Option[Set[SeedlistEntry]],
    restartSignal: SignallingRef[F, Unit],
    versionHash: Hash,
    collateral: CollateralConfig,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    environment: AppEnvironment
  ): F[SharedServices[F]] = {

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
        versionHash,
        environment
      )

    for {
      localHealthcheck <- LocalHealthcheck.make[F](nodeClient, storages.cluster)
      gossip <- Gossip.make[F](queues.rumor, nodeId, generation, keyPair)
      currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F](validators.currencyBlockValidator),
        collateral.amount
      )

      currencyEventsCutter = CurrencyEventsCutter.make[F]

      currencySnapshotValidator = CurrencySnapshotValidator.make[F](
        CurrencySnapshotCreator.make[F](
          currencySnapshotAcceptanceManager,
          None,
          cfg.snapshotSizeConfig,
          currencyEventsCutter
        ),
        validators.signedValidator,
        None,
        None
      )
      currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(
        currencySnapshotValidator
      )
      globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager.make(stateChannelAllowanceLists)
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync
      globalSnapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F](validators.blockValidator),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](
            validators.stateChannelValidator,
            globalSnapshotStateChannelManager,
            currencySnapshotContextFns,
            jsonBrotliBinarySerializer
          ),
        collateral.amount
      )
      globalSnapshotContextFns = GlobalSnapshotContextFunctions.make(globalSnapshotAcceptanceManager)
      doubleSignDetect = new DoubleSignDetect(storages.forkInfo, cfg.doubleSignDetect)
    } yield
      new SharedServices[F](
        localHealthcheck = localHealthcheck,
        cluster = cluster,
        session = session,
        gossip = gossip,
        globalSnapshotContextFns = globalSnapshotContextFns,
        currencySnapshotContextFns = currencySnapshotContextFns,
        currencySnapshotAcceptanceManager = currencySnapshotAcceptanceManager,
        currencyEventsCutter = currencyEventsCutter,
        doubleSignDetect = doubleSignDetect
      ) {}
  }
}

sealed abstract class SharedServices[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
  val currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
  val currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
  val currencyEventsCutter: CurrencyEventsCutter[F],
  val doubleSignDetect: DoubleSignDetect[F]
)
