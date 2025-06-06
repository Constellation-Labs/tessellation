package io.constellationnetwork.currency.l0.modules

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.std.{Random, Supervisor}
import cats.effect.{Async, IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.config.types.AppConfig
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.node.L0NodeContext
import io.constellationnetwork.currency.l0.snapshot._
import io.constellationnetwork.currency.l0.snapshot.services.{StateChannelBinarySender, StateChannelSnapshotService}
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.collateral.Collateral
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.snapshot.services.{AddressService, GlobalL0Service}
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.collateral.Collateral
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.services.AddressService
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.node.shared.modules.{SharedServices, SharedStorages}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.SignedValidator

import org.http4s.client.Client

object Services {

  def make[F[
    _
  ]: Async: Parallel: Random: JsonSerializer: KryoSerializer: SecurityProvider: HasherSelector: Metrics: Supervisor, R <: CliMethod](
    sharedCfg: SharedConfig,
    p2PClient: P2PClient[F],
    sharedServices: SharedServices[F, R],
    sharedStorages: SharedStorages[F],
    storages: Storages[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[SeedlistEntry]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig,
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    signedValidator: SignedValidator[F],
    globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
    maybeMajorityPeerIds: Option[NonEmptySet[PeerId]],
    hasherSelector: HasherSelector[F],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]]
  ): F[Services[F, R]] =
    for {
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync[F]
      implicit0(hasher: Hasher[F]) = hasherSelector.getCurrent

      stateChannelBinarySender <- StateChannelBinarySender.make(
        storages.identifier,
        storages.globalL0Cluster,
        storages.lastSyncGlobalSnapshot,
        p2PClient.stateChannelSnapshot,
        stateChannelAllowanceLists,
        selfId,
        cfg.environment
      )

      l0NodeContext = L0NodeContext
        .make[F](storages.snapshot, hasherSelector, storages.lastSyncGlobalSnapshot, storages.identifier)

      dataApplicationAcceptanceManager = (maybeDataApplication, storages.calculatedStateStorage).mapN {
        case (service, storage) =>
          DataApplicationSnapshotAcceptanceManager.make[F](service, l0NodeContext, storage)
      }

      feeCalculator = FeeCalculator.make(cfg.shared.feeConfigs)

      stateChannelSnapshotService <- StateChannelSnapshotService
        .make[F](
          keyPair,
          storages.snapshot,
          storages.lastSyncGlobalSnapshot,
          jsonBrotliBinarySerializer,
          dataApplicationAcceptanceManager,
          stateChannelBinarySender,
          feeCalculator,
          cfg.snapshotSize
        )
        .pure[F]

      creator = CurrencySnapshotCreator.make[F](
        sharedCfg.fieldsAddedOrdinals.tessellation3Migration.getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
        sharedServices.currencySnapshotAcceptanceManager,
        dataApplicationAcceptanceManager,
        cfg.snapshotSize,
        sharedServices.currencyEventsCutter,
        storages.currencySnapshotEventValidationError
      )

      validator = CurrencySnapshotValidator.make[F](
        SnapshotOrdinal.MinValue,
        creator,
        signedValidator,
        maybeRewards,
        maybeDataApplication
      )

      addressService = AddressService.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](cfg.shared.addresses, storages.snapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.snapshot)
      globalL0Service = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshot, storages.globalL0Cluster, storages.lastSyncGlobalSnapshot, None, maybeMajorityPeerIds)

      consensus <- CurrencySnapshotConsensus
        .make[F](
          sharedServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.lastSyncGlobalSnapshot,
          maybeRewards,
          cfg.snapshot,
          client,
          session,
          stateChannelSnapshotService,
          maybeDataApplication,
          creator,
          validator,
          hasherSelector,
          sharedServices.restart,
          cfg.shared.leavingDelay,
          globalL0Service.pullGlobalSnapshot
        )
    } yield
      new Services[F, R](
        localHealthcheck = sharedServices.localHealthcheck,
        cluster = sharedServices.cluster,
        session = sharedServices.session,
        gossip = sharedServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService,
        stateChannelSnapshot = stateChannelSnapshotService,
        globalL0 = globalL0Service,
        snapshotContextFunctions = sharedServices.currencySnapshotContextFns,
        dataApplication = maybeDataApplication,
        globalSnapshotContextFunctions = globalSnapshotContextFns,
        stateChannelBinarySender = stateChannelBinarySender,
        restart = sharedServices.restart
      ) {}
}

sealed abstract class Services[F[_], R <: CliMethod] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: CurrencySnapshotConsensus[F],
  val address: AddressService[F, CurrencyIncrementalSnapshot],
  val collateral: Collateral[F],
  val stateChannelSnapshot: StateChannelSnapshotService[F],
  val globalL0: GlobalL0Service[F],
  val snapshotContextFunctions: CurrencySnapshotContextFunctions[F],
  val dataApplication: Option[BaseDataApplicationL0Service[F]],
  val globalSnapshotContextFunctions: GlobalSnapshotContextFunctions[F],
  val stateChannelBinarySender: StateChannelBinarySender[F],
  val restart: RestartService[F, R]
)
