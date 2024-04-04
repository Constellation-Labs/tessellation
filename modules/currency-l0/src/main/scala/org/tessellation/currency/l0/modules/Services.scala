package org.tessellation.currency.l0.modules

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import org.tessellation.currency.dataApplication.BaseDataApplicationL0Service
import org.tessellation.currency.l0.config.types.AppConfig
import org.tessellation.currency.l0.http.p2p.P2PClient
import org.tessellation.currency.l0.node.L0NodeContext
import org.tessellation.currency.l0.snapshot._
import org.tessellation.currency.l0.snapshot.services.{SentStateChannelBinaryTrackingService, StateChannelSnapshotService}
import org.tessellation.currency.schema.currency._
import org.tessellation.json.{JsonBrotliBinarySerializer, JsonSerializer}
import org.tessellation.node.shared.domain.cluster.services.{Cluster, Session}
import org.tessellation.node.shared.domain.collateral.Collateral
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.healthcheck.LocalHealthcheck
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.snapshot.services.{AddressService, GlobalL0Service}
import org.tessellation.node.shared.infrastructure.collateral.Collateral
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.snapshot._
import org.tessellation.node.shared.infrastructure.snapshot.services.{AddressService, CurrencyMessageService}
import org.tessellation.node.shared.modules.SharedServices
import org.tessellation.node.shared.snapshot.currency._
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.SignedValidator
import org.tessellation.security.{HashSelect, Hasher, SecurityProvider}

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: JsonSerializer: SecurityProvider: Hasher: Metrics: Supervisor](
    p2PClient: P2PClient[F],
    sharedServices: SharedServices[F],
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
    hashSelect: HashSelect
  ): F[Services[F]] =
    for {
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync[F]

      l0NodeContext = L0NodeContext.make[F](storages.snapshot)

      dataApplicationAcceptanceManager = (maybeDataApplication, storages.calculatedStateStorage).mapN {
        case (service, storage) =>
          DataApplicationSnapshotAcceptanceManager.make[F](service, l0NodeContext, storage)
      }

      sentStateChannelBinaryTrackingService <- SentStateChannelBinaryTrackingService.make(storages.identifier)

      stateChannelSnapshotService <- StateChannelSnapshotService
        .make[F](
          keyPair,
          p2PClient.stateChannelSnapshot,
          storages.globalL0Cluster,
          storages.snapshot,
          storages.identifier,
          jsonBrotliBinarySerializer,
          dataApplicationAcceptanceManager,
          sentStateChannelBinaryTrackingService
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
          maybeRewards,
          cfg.snapshot,
          client,
          session,
          stateChannelSnapshotService,
          maybeDataApplication,
          creator,
          validator
        )
      addressService = AddressService.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](storages.snapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.snapshot)
      globalL0Service = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshot, storages.globalL0Cluster, storages.lastGlobalSnapshot, None, maybeMajorityPeerIds, hashSelect)
    } yield
      new Services[F](
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
        sentStateChannelBinaryTrackingService = sentStateChannelBinaryTrackingService,
        currencyMessageService = CurrencyMessageService.make[F](signedValidator, seedlist, storages.currencyMessageStorage)
      ) {}
}

sealed abstract class Services[F[_]] private (
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
  val sentStateChannelBinaryTrackingService: SentStateChannelBinaryTrackingService[F],
  val currencyMessageService: CurrencyMessageService[F]
)
