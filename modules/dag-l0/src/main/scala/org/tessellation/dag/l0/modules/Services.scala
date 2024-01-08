package org.tessellation.dag.l0.modules

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l0.config.types.AppConfig
import org.tessellation.dag.l0.domain.cell.L0Cell
import org.tessellation.dag.l0.domain.statechannel.StateChannelService
import org.tessellation.dag.l0.infrastructure.rewards._
import org.tessellation.dag.l0.infrastructure.snapshot._
import org.tessellation.dag.l0.infrastructure.trust.TrustStorageUpdater
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.services.{Cluster, Session}
import org.tessellation.node.shared.domain.collateral.Collateral
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.healthcheck.LocalHealthcheck
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.snapshot.services.AddressService
import org.tessellation.node.shared.infrastructure.collateral.Collateral
import org.tessellation.node.shared.infrastructure.consensus.Consensus
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.snapshot.services.AddressService
import org.tessellation.node.shared.modules.{SharedServices, SharedValidators}
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.tessellation.security.{Hasher, SecurityProvider}

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: Hasher: SecurityProvider: Metrics: Supervisor](
    sharedServices: SharedServices[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: SharedValidators[F],
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

      consensus <- GlobalSnapshotConsensus
        .make[F](
          sharedServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.globalSnapshot,
          validators,
          sharedServices,
          cfg.snapshot,
          stateChannelPullDelay = cfg.stateChannelPullDelay,
          stateChannelPurgeDelay = cfg.stateChannelPurgeDelay,
          stateChannelAllowanceLists,
          client,
          session,
          rewards
        )
      addressService = AddressService.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](storages.globalSnapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.globalSnapshot)
      stateChannelService = StateChannelService
        .make[F](L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput), validators.stateChannelValidator)
      getOrdinal = storages.globalSnapshot.headSnapshot.map(_.map(_.ordinal))
      trustUpdaterService = TrustStorageUpdater.make(getOrdinal, sharedServices.gossip, storages.trust)
    } yield
      new Services[F](
        localHealthcheck = sharedServices.localHealthcheck,
        cluster = sharedServices.cluster,
        session = sharedServices.session,
        gossip = sharedServices.gossip,
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
