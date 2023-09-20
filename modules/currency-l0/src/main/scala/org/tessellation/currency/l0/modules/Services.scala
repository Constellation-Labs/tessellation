package org.tessellation.currency.l0.modules

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import org.tessellation.currency.l0.config.types.AppConfig
import org.tessellation.currency.l0.http.p2p.P2PClient
import org.tessellation.currency.l0.node.L0NodeContext
import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.l0.snapshot.{CurrencySnapshotConsensus, CurrencySnapshotEvent}
import org.tessellation.currency.schema.currency._
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.snapshot.services.{AddressService, GlobalL0Service}
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot._
import org.tessellation.sdk.infrastructure.snapshot.services.AddressService
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor: L0NodeContext](
    p2PClient: P2PClient[F],
    sdkServices: SdkServices[F],
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
    maybeMajorityPeerIds: Option[NonEmptySet[PeerId]]
  ): F[Services[F]] =
    for {
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.make[F]()
      stateChannelSnapshotService <- StateChannelSnapshotService
        .make[F](
          keyPair,
          storages.lastBinaryHash,
          p2PClient.stateChannelSnapshot,
          storages.globalL0Cluster,
          storages.snapshot,
          storages.identifier,
          jsonBrotliBinarySerializer
        )
        .pure[F]

      creator = CurrencySnapshotCreator.make[F](
        sdkServices.currencySnapshotAcceptanceManager,
        maybeDataApplication
          .map((L0NodeContext.make[F](storages.snapshot), _))
      )

      validator = CurrencySnapshotValidator.make[F](
        creator,
        signedValidator,
        maybeRewards,
        maybeDataApplication
      )

      proposalSelect = ProposalOccurrenceSelect.make()
      consensus <- CurrencySnapshotConsensus
        .make[F](
          sdkServices.gossip,
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
          validator,
          proposalSelect
        )
      addressService = AddressService.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](storages.snapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.snapshot)
      globalL0Service = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshot, storages.globalL0Cluster, storages.lastGlobalSnapshot, None, maybeMajorityPeerIds)
    } yield
      new Services[F](
        localHealthcheck = sdkServices.localHealthcheck,
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        gossip = sdkServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService,
        stateChannelSnapshot = stateChannelSnapshotService,
        globalL0 = globalL0Service,
        snapshotContextFunctions = sdkServices.currencySnapshotContextFns,
        dataApplication = maybeDataApplication,
        globalSnapshotContextFunctions = globalSnapshotContextFns
      ) {}
}

sealed abstract class Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: SnapshotConsensus[
    F,
    CurrencyIncrementalSnapshot,
    CurrencySnapshotContext,
    CurrencySnapshotEvent
  ],
  val address: AddressService[F, CurrencyIncrementalSnapshot],
  val collateral: Collateral[F],
  val stateChannelSnapshot: StateChannelSnapshotService[F],
  val globalL0: GlobalL0Service[F],
  val snapshotContextFunctions: CurrencySnapshotContextFunctions[F],
  val dataApplication: Option[BaseDataApplicationL0Service[F]],
  val globalSnapshotContextFunctions: GlobalSnapshotContextFunctions[F]
)
