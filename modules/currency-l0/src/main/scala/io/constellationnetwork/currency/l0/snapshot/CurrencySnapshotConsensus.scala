package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.kernel.{Async, Fiber}
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
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSyncGlobalSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusEventLoop, _}
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencySnapshotCreator, CurrencySnapshotValidator}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}

import io.circe.Decoder
import org.http4s.client.Client

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
    maybeCustomArtifacts: Option[Signed[CurrencyIncrementalSnapshot] => Option[SortedSet[SharedArtifact]]]
  )(implicit supervisor: Supervisor[F]): F[CurrencySnapshotConsensus[F]] = {

    // -----------------------------------------------------------------------
    // DataTransaction decoder
    // -----------------------------------------------------------------------
    def noopDecoder: Decoder[DataTransaction] =
      Decoder.failedWithMessage("DataTransaction decoder not provided")

    implicit def daDecoder: Decoder[DataTransaction] =
      maybeDataApplication.map { da =>
        implicit val dataUpdateDecoder: Decoder[DataUpdate] = da.dataDecoder
        DataTransaction.decoder
      }.getOrElse(noopDecoder)

    implicit val hs: HasherSelector[F] = hasherSelector

    for {

      // =====================================================================
      // Storage
      // =====================================================================
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

      // =====================================================================
      // Consensus Functions
      // =====================================================================
      consensusFns =
        CurrencySnapshotConsensusFunctions.make[F](
          collateral,
          maybeRewards,
          creator,
          validator,
          maybeCustomArtifacts
        )

      // =====================================================================
      // Consensus State Machines
      // =====================================================================
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

      consensusStateCreator =
        CurrencySnapshotConsensusStateCreator.make(
          consensusFns,
          consensusStorage,
          lastGlobalSnapshotStorage,
          gossip,
          selfId,
          seedlist,
          clusterStorage
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
          gossip,
          consensusStatusOps
        )

      consensusClient = ConsensusClient.make[F, CurrencySnapshotKey, CurrencyConsensusOutcome](client, session)

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
          snapshotConfig.consensus
        )

      // =====================================================================
      // Gossip Handler (returned for registration upstream)
      // =====================================================================
      handler = CurrencyConsensusHandler.make(loop.queue)

      // =====================================================================
      // REST API Routes
      // =====================================================================
      routes = new ConsensusRoutes[
        F,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](consensusStorage)

      // =====================================================================
      // Start FS2 Loop under Supervisor
      // =====================================================================
      _ <- supervisor.supervise(loop.run.compile.drain)
      consensus = new Consensus(handler, consensusStorage, loop.manager, routes, consensusFns)
    } yield consensus
  }
}
