package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataUpdate}
import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.node.shared.config.types.SnapshotConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencySnapshotCreator, CurrencySnapshotValidator}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

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
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    snapshotConfig: SnapshotConfig,
    client: Client[F],
    session: Session[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    creator: CurrencySnapshotCreator[F],
    validator: CurrencySnapshotValidator[F],
    hasherSelector: HasherSelector[F],
    restartService: RestartService[F, _],
    leavingDelay: FiniteDuration
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
        .make[F](
          keyPair,
          consensusStorage,
          consensusFunctions,
          stateChannelSnapshotService,
          gossip,
          maybeDataApplication,
          restartService,
          nodeStorage,
          leavingDelay
        )
      consensusStateCreator = CurrencySnapshotConsensusStateCreator
        .make[F](consensusFunctions, consensusStorage, lastGlobalSnapshotStorage, gossip, selfId, seedlist)
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
