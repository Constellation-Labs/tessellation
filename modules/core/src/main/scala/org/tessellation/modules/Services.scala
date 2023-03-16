package org.tessellation.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.config.types.AppConfig
import org.tessellation.domain.cell.L0Cell
import org.tessellation.domain.rewards.Rewards
import org.tessellation.domain.statechannel.StateChannelService
import org.tessellation.infrastructure.rewards._
import org.tessellation.infrastructure.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.domain.snapshot.services.AddressService
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.sdk.infrastructure.consensus.Consensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.services.AddressService
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    sdkServices: SdkServices[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: Validators[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[PeerId]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig
  ): F[Services[F]] =
    for {
      rewards <- Rewards
        .make[F](
          cfg.rewards.rewardsPerEpoch,
          ProgramsDistributor.make(cfg.rewards.programs),
          FacilitatorDistributor.make
        )
        .pure[F]
      snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F, DAGTransaction, DAGBlock](validators.blockValidator),
        GlobalSnapshotStateChannelEventsProcessor.make[F](validators.stateChannelValidator),
        cfg.collateral.amount
      )
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
          snapshotAcceptanceManager,
          cfg.snapshot,
          cfg.environment,
          client,
          session,
          rewards
        )
      addressService = AddressService.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](storages.globalSnapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.globalSnapshot)
      stateChannelService = StateChannelService
        .make[F](L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput), validators.stateChannelValidator)
      snapshotContextFunctions = GlobalSnapshotContextFunctions.make(snapshotAcceptanceManager)
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
        snapshotContextFunctions = snapshotContextFunctions
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
  val rewards: Rewards[F],
  val stateChannel: StateChannelService[F],
  val snapshotContextFunctions: GlobalSnapshotContextFunctions[F]
)
