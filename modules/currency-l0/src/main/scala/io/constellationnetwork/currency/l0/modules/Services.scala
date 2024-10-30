package io.constellationnetwork.currency.l0.modules

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
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
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.collateral.Collateral
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.snapshot.services.{AddressService, GlobalL0Service}
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.collateral.Collateral
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.services.AddressService
import io.constellationnetwork.node.shared.modules.SharedServices
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.SignedValidator
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: JsonSerializer: KryoSerializer: SecurityProvider: HasherSelector: Metrics: Supervisor, R <: CliMethod](
    p2PClient: P2PClient[F],
    sharedServices: SharedServices[F, R],
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
    hasherSelector: HasherSelector[F]
  ): F[Services[F, R]] =
    for {
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync[F]
      implicit0(hasher: Hasher[F]) = hasherSelector.getCurrent

      l0NodeContext = L0NodeContext.make[F](storages.snapshot, hasherSelector, storages.identifier)

      dataApplicationAcceptanceManager = (maybeDataApplication, storages.calculatedStateStorage).mapN {
        case (service, storage) =>
          DataApplicationSnapshotAcceptanceManager.make[F](service, l0NodeContext, storage)
      }

      stateChannelBinarySender <- StateChannelBinarySender.make(
        storages.identifier,
        storages.globalL0Cluster,
        storages.lastNGlobalSnapshot,
        p2PClient.stateChannelSnapshot
      )

      feeCalculator = FeeCalculator.make(cfg.shared.feeConfigs)

      stateChannelSnapshotService <- StateChannelSnapshotService
        .make[F](
          keyPair,
          storages.snapshot,
          storages.lastNGlobalSnapshot,
          jsonBrotliBinarySerializer,
          dataApplicationAcceptanceManager,
          stateChannelBinarySender,
          feeCalculator,
          cfg.snapshotSize
        )
        .pure[F]

      creator = CurrencySnapshotCreator.make[F](
        sharedServices.currencySnapshotAcceptanceManager,
        dataApplicationAcceptanceManager,
        cfg.snapshotSize,
        sharedServices.currencyEventsCutter
      )

      validator = CurrencySnapshotValidator.make[F](
        creator,
        signedValidator,
        maybeRewards,
        maybeDataApplication
      )

      consensus <- CurrencySnapshotConsensus
        .make[F](
          sharedServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.lastNGlobalSnapshot,
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
          cfg.shared.leavingDelay
        )
      addressService = AddressService.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](cfg.shared.addresses, storages.snapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.snapshot)
      globalL0Service = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshot, storages.globalL0Cluster, storages.lastNGlobalSnapshot, None, maybeMajorityPeerIds)
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
