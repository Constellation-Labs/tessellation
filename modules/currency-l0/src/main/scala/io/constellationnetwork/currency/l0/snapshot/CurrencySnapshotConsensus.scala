package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.config.types.SnapshotConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSyncGlobalSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusEventLoop
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencySnapshotCreator, CurrencySnapshotValidator}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}

import io.circe.Decoder
import org.http4s.client.Client

/** Factory for creating the Currency L0 consensus engine.
  *
  * Wires together all components and starts the consensus background stream. Returns a Consensus instance with handler (for gossip),
  * manager (external API), storage (state queries), and routes (HTTP endpoints).
  *
  * @see
  *   ConsensusEventLoop for FSM and command processing
  */
object CurrencySnapshotConsensus {

  def make[F[_]: Async: Random: SecurityProvider: Metrics](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
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
    leavingDelay: FiniteDuration,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    getSnapshotByOrdinal: SnapshotOrdinal => F[Option[Signed[CurrencySnapshotArtifact]]],
    maybeCustomArtifacts: Option[Signed[CurrencyIncrementalSnapshot] => Option[SortedSet[SharedArtifact]]],
    rumorQueue: cats.effect.std.Queue[F, Hashed[RumorRaw]]
  )(implicit supervisor: Supervisor[F]): F[CurrencySnapshotConsensus[F]] = {
    def noopDecoder: Decoder[DataTransaction] =
      Decoder.failedWithMessage("DataTransaction decoder not provided")

    implicit def daDecoder: Decoder[DataTransaction] =
      maybeDataApplication.map { da =>
        implicit val dataUpdateDecoder: Decoder[DataUpdate] = da.dataDecoder
        DataTransaction.decoder
      }.getOrElse(noopDecoder)

    implicit val hs: HasherSelector[F] = hasherSelector

    for {
      consensusStorage <- ConsensusStorage.make[
        F,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](snapshotConfig.consensus)

      consensusFns =
        CurrencySnapshotConsensusFunctions.make[F](
          collateral,
          maybeRewards,
          creator,
          validator,
          maybeCustomArtifacts
        )

      consensusStateAdvancer =
        CurrencySnapshotConsensusStateAdvancer.make(
          snapshotConfig.consensus,
          keyPair,
          consensusStorage,
          consensusFns,
          stateChannelSnapshotService,
          gossip,
          maybeDataApplication,
          restartService,
          nodeStorage,
          leavingDelay,
          getGlobalSnapshotByOrdinal,
          clusterStorage
        )

      facilitatorSelector = FacilitatorSelector.make(
        snapshotConfig.consensus.maxFacilitatorCount.map(_.value)
      )

      peerQualityTracker <- PeerQualityTracker.make[F]

      tcaFilter = TrailingCommonAncestorFilter.make[F]

      consensusStateCreator =
        CurrencySnapshotConsensusStateCreator.make(
          consensusFns,
          consensusStorage,
          lastGlobalSnapshotStorage,
          gossip,
          selfId,
          seedlist,
          facilitatorSelector,
          snapshotConfig.consensus.deterministicConfigHash,
          peerQualityTracker,
          tcaFilter
        )

      consensusStateRemover =
        CurrencySnapshotConsensusStateRemover.make(
          consensusStorage,
          gossip
        )

      consensusStatusOps = CurrencySnapshotConsensusOps.make

      stateUpdater =
        ConsensusStateUpdater.make(
          consensusStateAdvancer,
          consensusStorage,
          consensusStatusOps
        )

      consensusClient = ConsensusClient.make[F, CurrencySnapshotKey, CurrencyConsensusOutcome](client, session)

      directPushFn = ConsensusDirectSender.makeDirectPushFn(clusterStorage, consensusClient)
      _ <- gossip.setDirectPushFn(directPushFn)

      loop <-
        ConsensusEventLoop.build[
          F,
          CurrencySnapshotEvent,
          CurrencySnapshotKey,
          CurrencySnapshotArtifact,
          CurrencySnapshotContext,
          CurrencySnapshotStatus,
          CurrencyConsensusOutcome,
          CurrencyConsensusKind
        ](
          selfId,
          consensusStorage,
          consensusStateCreator,
          stateUpdater,
          consensusStateAdvancer,
          consensusStateRemover,
          consensusStatusOps,
          nodeStorage,
          clusterStorage,
          consensusFns,
          consensusClient,
          snapshotConfig.consensus,
          facilitatorSelector,
          peerQualityTracker
        )

      handler = CurrencyConsensusHandler.make(loop.queue)

      routes = new ConsensusRoutes[
        F,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](consensusStorage, rumorQueue)

      _ <- supervisor.supervise(loop.run.compile.drain)
      consensus = new Consensus(handler, consensusStorage, loop.manager, routes, consensusFns, Some(loop.healthRef))
    } yield consensus
  }
}
