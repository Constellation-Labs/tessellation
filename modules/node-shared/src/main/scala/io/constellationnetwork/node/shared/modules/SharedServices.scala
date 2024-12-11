package io.constellationnetwork.node.shared.modules

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.{CollateralConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.http.p2p.clients.NodeClient
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.cluster.services.Cluster
import io.constellationnetwork.node.shared.infrastructure.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

import fs2.concurrent.SignallingRef

object SharedServices {

  def make[F[_]: Async: HasherSelector: SecurityProvider: Metrics: Supervisor: JsonSerializer: KryoSerializer, A <: CliMethod](
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
    restartSignal: SignallingRef[F, Option[A]],
    versionHash: Hash,
    jarHash: Hash,
    collateral: CollateralConfig,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    environment: AppEnvironment,
    txHasher: Hasher[F]
  ): F[SharedServices[F, A]] =
    for {
      restartService <- RestartService.make(restartSignal, storages.cluster)

      cluster = Cluster
        .make[F](
          cfg.leavingDelay,
          cfg.http,
          nodeId,
          keyPair,
          storages.cluster,
          storages.session,
          storages.node,
          seedlist,
          restartService,
          versionHash,
          jarHash,
          environment
        )

      localHealthcheck <- LocalHealthcheck.make[F](nodeClient, storages.cluster)
      gossip <- HasherSelector[F].withCurrent(implicit hasher => Gossip.make[F](queues.rumor, nodeId, generation, keyPair))
      currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
        cfg.lastGlobalSnapshotsSync,
        BlockAcceptanceManager.make[F](validators.currencyBlockValidator, txHasher),
        TokenLockBlockAcceptanceManager.make[F](validators.tokenLockBlockValidator),
        AllowSpendBlockAcceptanceManager.make[F](validators.allowSpendBlockValidator),
        collateral.amount,
        validators.currencyMessageValidator,
        validators.feeTransactionValidator,
        validators.globalSnapshotSyncValidator
      )

      currencyEventsCutter = CurrencyEventsCutter.make[F](None)

      currencySnapshotValidator = CurrencySnapshotValidator.make[F](
        CurrencySnapshotCreator.make[F](
          currencySnapshotAcceptanceManager,
          None,
          cfg.snapshotSize,
          currencyEventsCutter,
          storages.currencySnapshotEventValidationError
        ),
        validators.signedValidator,
        None,
        None
      )
      currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(
        currencySnapshotValidator
      )
      feeCalculator = FeeCalculator.make(cfg.feeConfigs)
      globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager.make(stateChannelAllowanceLists)
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync
      globalSnapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F](validators.blockValidator, txHasher),
        AllowSpendBlockAcceptanceManager.make[F](validators.allowSpendBlockValidator),
        GlobalSnapshotStateChannelEventsProcessor
          .make[F](
            validators.stateChannelValidator,
            globalSnapshotStateChannelManager,
            currencySnapshotContextFns,
            jsonBrotliBinarySerializer,
            feeCalculator
          ),
        collateral.amount
      )
      globalSnapshotContextFns = GlobalSnapshotContextFunctions.make(globalSnapshotAcceptanceManager)
    } yield
      new SharedServices[F, A](
        localHealthcheck = localHealthcheck,
        cluster = cluster,
        session = session,
        gossip = gossip,
        globalSnapshotContextFns = globalSnapshotContextFns,
        currencySnapshotContextFns = currencySnapshotContextFns,
        currencySnapshotAcceptanceManager = currencySnapshotAcceptanceManager,
        currencyEventsCutter = currencyEventsCutter,
        restart = restartService
      ) {}
}

sealed abstract class SharedServices[F[_], A <: CliMethod] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
  val currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
  val currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
  val currencyEventsCutter: CurrencyEventsCutter[F],
  val restart: RestartService[F, A]
)
