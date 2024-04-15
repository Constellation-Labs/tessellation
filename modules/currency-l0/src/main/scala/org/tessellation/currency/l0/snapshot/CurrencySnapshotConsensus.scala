package org.tessellation.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, DataUpdate}
import org.tessellation.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency._
import org.tessellation.node.shared.config.types.SnapshotConfig
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.snapshot.{CurrencySnapshotCreator, CurrencySnapshotValidator}
import org.tessellation.node.shared.snapshot.currency._
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.{HasherSelector, SecurityProvider}

import io.circe.Decoder
import org.http4s.client.Client

object CurrencySnapshotConsensus {

  def make[F[_]: Async: Random: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    snapshotConfig: SnapshotConfig,
    client: Client[F],
    session: Session[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    creator: CurrencySnapshotCreator[F],
    validator: CurrencySnapshotValidator[F],
    hasherSelector: HasherSelector[F]
  ): F[CurrencySnapshotConsensus[F]] = {
    def noopDecoder: Decoder[DataUpdate] = Decoder.failedWithMessage[DataUpdate]("not implemented")

    implicit def daDecoder: Decoder[DataUpdate] = maybeDataApplication.map(_.dataDecoder).getOrElse(noopDecoder)
    implicit val hs: HasherSelector[F] = hasherSelector

    for {
      consensusStorage <- ConsensusStorage
        .make[
          F,
          CurrencySnapshotEvent,
          CurrencySnapshotKey,
          CurrencySnapshotArtifact,
          CurrencySnapshotContext,
          CurrencySnapshotStatus,
          CurrencyConsensusOutcome,
          CurrencyConsensusKind
        ](snapshotConfig.consensus)
      consensusFunctions = CurrencySnapshotConsensusFunctions.make[F](
        collateral,
        maybeRewards,
        creator,
        validator
      )
      consensusStateAdvancer = CurrencySnapshotConsensusStateAdvancer
        .make[F](keyPair, consensusStorage, consensusFunctions, stateChannelSnapshotService, gossip, maybeDataApplication)
      consensusStateCreator = CurrencySnapshotConsensusStateCreator.make[F](consensusFunctions, consensusStorage, gossip, selfId, seedlist)
      consensusStateRemover = CurrencySnapshotConsensusStateRemover.make[F](consensusStorage, gossip)
      consensusStatusOps = CurrencySnapshotConsensusOps.make
      stateUpdater = ConsensusStateUpdater.make(
        consensusStateAdvancer,
        consensusStorage,
        gossip,
        consensusStatusOps
      )
      consensusClient = ConsensusClient.make[F, CurrencySnapshotKey, CurrencyConsensusOutcome](client, session)
      manager <- ConsensusManager.make(
        snapshotConfig.consensus,
        consensusStorage,
        consensusStateCreator,
        stateUpdater,
        consensusStateAdvancer,
        consensusStateRemover,
        consensusStatusOps,
        nodeStorage,
        clusterStorage,
        consensusClient
      )
      routes = new ConsensusRoutes(consensusStorage)
      handler = CurrencyConsensusHandler.make(consensusStorage, manager, consensusFunctions)
      consensus = new Consensus(handler, consensusStorage, manager, routes, consensusFunctions)
    } yield consensus
  }
}
