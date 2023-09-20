package org.tessellation.modules

import java.security.KeyPair

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.cli.AppEnvironment.Dev
import org.tessellation.config.types.AppConfig
import org.tessellation.domain.cell.L0Cell
import org.tessellation.domain.statechannel.StateChannelService
import org.tessellation.infrastructure.rewards._
import org.tessellation.infrastructure.snapshot._
import org.tessellation.infrastructure.trust.TrustStorageUpdater
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.snapshot.services.AddressService
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.services.AddressService
import org.tessellation.sdk.infrastructure.snapshot.{ProposalOccurrenceSelect, ProposalSelectWithFallback, ProposalTrustSelect}
import org.tessellation.sdk.modules.{SdkServices, SdkValidators}
import org.tessellation.security.SecurityProvider

import eu.timepit.refined.auto._
import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    sdkServices: SdkServices[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: SdkValidators[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig
  ): F[Services[F]] =
    for {
      rewards <- Rewards
        .make[F](
          cfg.rewards,
          ProgramsDistributor.make,
          FacilitatorDistributor.make
        )
        .pure[F]

      trustStorage = storages.trust
      getTrusts = Applicative[F].product(trustStorage.getBiasedSeedlistOrdinalPeerLabels, trustStorage.getTrust)
      proposalSelect =
        if (cfg.environment === Dev)
          ProposalOccurrenceSelect.make()
        else {
          val primary = ProposalTrustSelect.make(getTrusts, cfg.proposalSelect)
          val secondary = ProposalOccurrenceSelect.make()

          ProposalSelectWithFallback.make(primary, secondary)
        }
      consensus <- GlobalSnapshotConsensus
        .make[F](
          sdkServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.globalSnapshot,
          validators,
          sdkServices,
          cfg.snapshot,
          cfg.environment,
          stateChannelPullDelay = cfg.stateChannelPullDelay,
          stateChannelPurgeDelay = cfg.stateChannelPurgeDelay,
          stateChannelAllowanceLists,
          client,
          session,
          rewards,
          proposalSelect
        )
      addressService = AddressService.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](storages.globalSnapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.globalSnapshot)
      stateChannelService = StateChannelService
        .make[F](L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput), validators.stateChannelValidator)
      getOrdinal = storages.globalSnapshot.headSnapshot.map(_.map(_.ordinal))
      trustUpdaterService = TrustStorageUpdater.make(getOrdinal, sdkServices.gossip, storages.trust)
    } yield
      new Services[F](
        localHealthcheck = sdkServices.localHealthcheck,
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        gossip = sdkServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService,
        rewards = rewards,
        stateChannel = stateChannelService,
        trustStorageUpdater = trustUpdaterService
      ) {}
}

sealed abstract class Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext],
  val address: AddressService[F, GlobalIncrementalSnapshot],
  val collateral: Collateral[F],
  val rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent],
  val stateChannel: StateChannelService[F],
  val trustStorageUpdater: TrustStorageUpdater[F]
)
