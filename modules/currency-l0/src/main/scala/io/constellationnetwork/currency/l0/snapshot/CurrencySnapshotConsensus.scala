package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.Parallel
import cats.effect.kernel.Async
import cats.effect.std.{Queue, Random, Supervisor}
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction}
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, SnapshotConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{ChainTip, EventGossipClient}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.selfhealth.LocalHealthMonitor
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{
  LastSentGlobalSnapshotSyncStorage,
  OrdinalJsonSidecarStorage,
  SnapshotInfoLocalFileSystemStorage
}
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencySnapshotCreator, CurrencySnapshotValidator}
import io.constellationnetwork.node.shared.resources.ConsensusDispatcher
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.schema.{CurrencyStateProofSelector, GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}

import io.circe.{Decoder, Encoder}
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Factory for creating the Currency L0 consensus engine.
  *
  * Wires together all components and starts the consensus background stream. Returns a Consensus instance with handler (for gossip),
  * manager (external API), storage (state queries), and routes (HTTP endpoints).
  *
  * @see
  *   ConsensusEventLoop for FSM and command processing
  */
object CurrencySnapshotConsensus {

  def make[F[_]: Async: Parallel: Random: SecurityProvider: Metrics: JsonSerializer](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    snapshotInfoStorage: SnapshotInfoLocalFileSystemStorage[F, CurrencySnapshotStateProof, CurrencySnapshotInfo],
    getCurrencyAddress: F[Address],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    snapshotConfig: SnapshotConfig,
    effectiveConsensusConfig: ConsensusConfig,
    networkId: String,
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
    maybeCustomArtifacts: Option[Signed[CurrencyIncrementalSnapshot] => Option[SortedSet[SharedArtifact]]],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    lastGlobalSnapshotSyncStorage: LastSentGlobalSnapshotSyncStorage[F],
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    // B2 witness channel — see GlobalSnapshotConsensus for the full rationale.
    getPeerChainTips: F[Map[PeerId, ChainTip]],
    // v15 self-health throttle. Injected so the metagraph creator can stamp Facility.selfHealthHint
    // from this node's current LocalHealthMonitor sample. Shared with the dag-l0 instance via
    // SharedServices.localHealthMonitor at the caller site.
    localHealthMonitor: LocalHealthMonitor[F],
    // Dedicated dispatcher (EC + EC-scoped supervisor) for the FSM consume fiber. When provided,
    // the whole `loop.run.compile.drain` is shifted onto its EC via `Async[F].evalOn` and the fiber
    // is supervised by the dispatcher's supervisor (finalized before the EC, so the fiber is
    // cancelled while the pool is still alive). When None (default, and what tests use) the loop
    // runs on the ambient runtime under the outer supervisor. See `ConsensusExecutor`.
    consensusDispatcher: Option[ConsensusDispatcher[F]] = None
  )(
    implicit supervisor: Supervisor[F],
    currencyStateProofSelector: CurrencyStateProofSelector
  ): F[CurrencySnapshotConsensus[F]] = {
    implicit val daDecoder: Decoder[DataTransaction] = DataTransactionCodecs.decoder(maybeDataApplication)
    implicit val daEncoder: Encoder[DataTransaction] = DataTransactionCodecs.encoder(maybeDataApplication)
    implicit val hs: HasherSelector[F] = hasherSelector

    // Startup resolves this exact value once for both the join fence and live engine. SnapshotConfig
    // remains present only for sidecar paths; it does not participate in a second consensus projection.
    val resolvedCoreCommitteeSize = effectiveConsensusConfig.coreCommitteeSize.getOrElse(3)
    val seedlistPeerIds = seedlist.fold(Set.empty[PeerId])(_.iterator.map(_.peerId).toSet)

    for {
      certifiedVoteLockPersistence <- CertifiedVoteLockPersistence.forSnapshotOrdinal[F](
        snapshotConfig.incrementalPersistedSnapshotPath / "certifiedVoteLocks"
      )
      consensusStorage <- ConsensusStorage.make[
        F,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](effectiveConsensusConfig, LegacyViewChangePolicy.PreserveLegacy, certifiedVoteLockPersistence)

      certifiedOutcomeSidecar <- OrdinalJsonSidecarStorage.make[F, CurrencyConsensusOutcome](
        snapshotConfig.incrementalPersistedSnapshotPath / "certifiedOutcomes"
      )

      consensusFns =
        CurrencySnapshotConsensusFunctions.make[F](
          collateral,
          maybeRewards,
          creator,
          validator,
          maybeCustomArtifacts
        )

      eventGossipClient = EventGossipClient.make[F, CurrencySnapshotEvent](client, session)

      facilitatorSelector = FacilitatorSelector.make(
        effectiveConsensusConfig.facilitatorSelectionMax
      )

      consensusStateAdvancer =
        CurrencySnapshotConsensusStateAdvancer.make(
          effectiveConsensusConfig,
          networkId,
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
          clusterStorage,
          eventMempool,
          eventGossipClient,
          facilitatorSelector,
          lastGlobalSnapshotSyncStorage
        )

      certifiedDownloadPreflight = CurrencyCertifiedDownloadValidator.make[F](
        effectiveConsensusConfig,
        resolvedCoreCommitteeSize,
        seedlistPeerIds,
        getCurrencyAddress,
        facilitatorSelector,
        consensusFns.facilitatorFilter,
        snapshotStorage.get,
        snapshotInfoStorage.read,
        certifiedOutcomeSidecar,
        consensusStateAdvancer
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
          networkId,
          keyPair,
          seedlist,
          facilitatorSelector,
          effectiveConsensusConfig.deterministicConfigHash,
          effectiveConsensusConfig,
          peerQualityTracker,
          tcaFilter,
          eventMempool,
          localHealthMonitor,
          // v19 per-environment Core floor mirror of dag-l0. v20 routes the env-resolved
          // value through `effectiveConsensusConfig.coreCommitteeSize`, so this binding is
          // what both the state creator and `deterministicConfigHash` see.
          resolvedCoreCommitteeSize
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

      directPushFn <- ConsensusDirectSender.makeDirectPushFn(clusterStorage, consensusClient)
      _ <- gossip.setDirectPushFn(directPushFn)

      viewChangeVoter = new GossipingViewChangeVoter[
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
        keyPair,
        gossip,
        consensusStorage,
        (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
        effectiveConsensusConfig.quorumThresholdFraction,
        Slf4jLogger.getLogger[F]
      )

      timeoutVoter = new GossipingTimeoutVoter[
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
        keyPair,
        gossip,
        consensusStorage,
        (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
        effectiveConsensusConfig.quorumThresholdFraction,
        Slf4jLogger.getLogger[F]
      )

      evictionVoter = new GossipingEvictionVoter[
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
        keyPair,
        gossip,
        consensusStorage,
        (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
        HealthDerivedMembershipPolicy.LegacyAutomaticRemoval,
        Slf4jLogger.getLogger[F]
      )

      admissionVoter = new GossipingAdmissionVoter[
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
        keyPair,
        gossip,
        consensusStorage,
        (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
        HealthDerivedMembershipPolicy.LegacyAutomaticRemoval,
        Slf4jLogger.getLogger[F]
      )

      // HTTP preflight for the rumor-stale abandonment escalation (issue #1533); mirror the
      // corroborated `(ordinal, hash)` GlobalSnapshotConsensus probe against this layer's endpoint.
      fetchLatestCommittedMetadata = {
        import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
        val request = PeerResponse[F, SnapshotMetadata]("snapshots/latest/metadata")(client, session.some)
        (peer: Peer) => request(peer)
      }
      peersCommittedAheadProbe = PeersCommittedAheadProbe.make[F](clusterStorage, fetchLatestCommittedMetadata)

      validateGenesisRoot = (outcome: CurrencyConsensusOutcome) =>
        snapshotStorage.get(outcome.key).flatMap {
          case Some(localArtifact) =>
            CurrencyCertifiedGenesisOutcome
              .validateAgainstLocalArtifact[F](outcome, localArtifact, seedlistPeerIds)
              .flatMap(
                _.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_genesis:$error")).liftTo[F]
              )
          case None =>
            new IllegalStateException(
              s"downloaded_certified_outcome_genesis:trusted_snapshot_missing:${outcome.key.value.value}"
            ).raiseError[F, Unit]
        }

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
          gossip,
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
          effectiveConsensusConfig,
          facilitatorSelector,
          peerQualityTracker,
          HealthDerivedMembershipPolicy.LegacyAutomaticRemoval,
          viewChangeVoter,
          timeoutVoter,
          evictionVoter,
          admissionVoter,
          (o: CurrencyConsensusOutcome) =>
            !o.recentProofSizes.values.exists(_ >= effectiveConsensusConfig.bootstrapCompleteProofsThreshold),
          (o: CurrencyConsensusOutcome) => ReadmissionMaintenance.probationPeers(o.readmissionCountdown),
          (o: CurrencyConsensusOutcome) => {
            val target = ActiveFacilitatorAdmission.activeAdmissionTarget(
              effectiveConsensusConfig.activeFacilitatorTarget,
              effectiveConsensusConfig.coreCommitteeSize,
              o.facilitators.value.size
            )
            if (o.facilitators.value.size < target) o.finished.candidates.value else Set.empty
          },
          (_: CurrencyConsensusOutcome) => Set.empty[PeerId],
          (key: CurrencySnapshotKey) =>
            ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(
              key.value.value,
              effectiveConsensusConfig.activeAdmissionExpansionIntervalRounds
            ),
          (_: CurrencyConsensusOutcome) => None,
          (o: CurrencyConsensusOutcome) =>
            CertifiedConsensusGenesis.hasExpandedBeyondSingleton(
              effectiveConsensusConfig.certifiedConsensusActivationKey,
              o.key,
              o.facilitators.value.size,
              o.expandedBeyondSingleton
            ),
          (o: CurrencyConsensusOutcome) => o.finished.snapshotHash,
          (o: CurrencyConsensusOutcome) => o.peerQuality.toMap,
          (o: CurrencyConsensusOutcome) => o.recentRoundEndTimes.lastOption.map(_._2),
          getPeerChainTips,
          none[AdmissionCandidateTipProbe.Probes[F]],
          peersCommittedAheadProbe,
          onOutcomePreInitialize = Some(certifiedDownloadPreflight),
          onOutcomeFinalized = Some((outcome: CurrencyConsensusOutcome) =>
            outcome.finished.certifiedOutcome.traverse_(_ =>
              certifiedOutcomeSidecar.write(outcome.key, outcome) >>
                certifiedOutcomeSidecar.retain(SnapshotOrdinal.MinValue, outcome.key) >>
                consensusStorage.deleteCertifiedVoteLock(outcome.key)
            )
          ),
          onOutcomeInitialized = Some((outcome: CurrencyConsensusOutcome) =>
            certifiedOutcomeSidecar.deleteAbove(outcome.key) >>
              consensusStorage.deleteCertifiedVoteLocksAtOrBelow(outcome.key)
          ),
          onOutcomeSafetyInitialized = Some((outcome: CurrencyConsensusOutcome) =>
            if (
              CertifiedConsensusGenesis.isRootKey(
                effectiveConsensusConfig.certifiedConsensusActivationKey,
                outcome.key
              )
            )
              validateGenesisRoot(outcome).adaptError {
                case error =>
                  new IllegalStateException(s"certified_genesis_sidecar:${error.getMessage}", error)
              } >>
                certifiedOutcomeSidecar.write(outcome.key, outcome)
            else
              outcome.finished.certifiedOutcome.fold(certifiedOutcomeSidecar.delete(outcome.key))(_ =>
                certifiedOutcomeSidecar.write(outcome.key, outcome)
              )
          ),
          onOutcomeRollbackInitialized = Some((_: CurrencyConsensusOutcome, _: ConsensusCommand.RollbackStartPolicy) => Async[F].unit)
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
      ](consensusStorage, rumorQueue, Some(certifiedOutcomeSidecar.read))

      // Pin the consume fiber onto the dedicated consensus EC when one was provided, supervised by
      // that dispatcher's EC-scoped supervisor so it is cancelled before the pool is shut down.
      // The shift covers the entire stream's compile-drain so queue.take blocks on the consensus
      // pool rather than the global runtime. Without a dispatcher: run on the ambient runtime under
      // the outer supervisor (test/default behaviour).
      _ <- consensusDispatcher.fold(supervisor.supervise(loop.run.compile.drain)) { d =>
        d.supervisor.supervise(Async[F].evalOn(loop.run.compile.drain, d.ec))
      }
      triggerEventConsensus = loop.queue.offer(
        ConsensusCommand.FacilitateByEvent
      )
      consensus = new Consensus(
        handler,
        consensusStorage,
        loop.manager,
        routes,
        consensusFns,
        Some(loop.healthRef),
        Some(triggerEventConsensus)
      )
    } yield consensus
  }
}
