package io.constellationnetwork.dag.l0

import cats.Parallel
import cats.effect._
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.dag.l0.StoragesInitializer.initializeStorages
import io.constellationnetwork.dag.l0.cli.method._
import io.constellationnetwork.dag.l0.config.types._
import io.constellationnetwork.dag.l0.domain.snapshot.ForkRecoveryService
import io.constellationnetwork.dag.l0.domain.snapshot.recovery._
import io.constellationnetwork.dag.l0.http.p2p.P2PClient
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.programs.RollbackLoader
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.dag.l0.infrastructure.snapshot.{GlobalRecoverySeedOutcome, GlobalSnapshotArtifactHasher}
import io.constellationnetwork.dag.l0.infrastructure.trust.handler.{ordinalTrustHandler, trustHandler}
import io.constellationnetwork.dag.l0.modules._
import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app.{DagL0, NodeShared, TessellationIOApp}
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, SharedConfig, SnapshotConfig}
import io.constellationnetwork.node.shared.domain.collateral.OwnCollateralNotSatisfied
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand.RollbackStartPolicy
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.consensus.{CertifiedConsensusGenesis, ConsensusLog, TierTransitions}
import io.constellationnetwork.node.shared.infrastructure.genesis.{GenesisFS => GenesisLoader}
import io.constellationnetwork.node.shared.infrastructure.gossip.event._
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{GlobalSnapshotLocalFileSystemStorage, PeerHistorySidecarStorage}
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.node.shared.resources.{ConsensusExecutor, MkHttpServer}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.generic.auto._
import pureconfig.module.enumeratum._

object Main
    extends TessellationIOApp[Run](
      name = "dag-l0",
      header = "Tessellation Node",
      version = TessellationVersion.unsafeFrom(BuildInfo.version),
      clusterId = ClusterId("6d7f1d6a-213a-4148-9d45-d7200f555ecf"),
      layer = DagL0
    ) {

  val opts: Opts[Run] = cli.method.opts

  protected val configFiles: List[String] = List("dag-l0.conf")

  override protected def loadEffectiveConsensusConfig(method: Run, sharedConfig: SharedConfig): IO[Option[ConsensusConfig]] =
    loadConfigAs[AppConfigReader].flatMap { reader =>
      val appConfig = method.appConfig(reader, sharedConfig)
      SnapshotConfig
        .resolveEffectiveConsensusConfig(appConfig.snapshot, appConfig.environment)
        .map(
          _.copy(
            lastGlobalSnapshotSyncOffset = sharedConfig.lastGlobalSnapshotsSync.syncOffset.value,
            lastGlobalSnapshotsInMemory = sharedConfig.lastGlobalSnapshotsSync.maxLastGlobalSnapshotsInMemory.value,
            currencySnapshotProtocolV1ActivationOrdinal = sharedConfig.fieldsAddedOrdinals
              .currencySnapshotProtocolV1For(appConfig.environment)
              .value
              .value
          )
        )
        .liftTo[IO]
        .map(_.some)
    }

  private[dag] def rollbackBootstrapFacilitators(nodeId: PeerId, proofSigners: List[PeerId]): List[PeerId] =
    if (proofSigners.nonEmpty) proofSigners else List(nodeId)

  private[dag] def rollbackBootstrapFacilitators(
    nodeId: PeerId,
    proofSigners: List[PeerId],
    recoveryCommitteeOverride: Option[SortedSet[PeerId]]
  ): List[PeerId] =
    recoveryCommitteeOverride.fold(rollbackBootstrapFacilitators(nodeId, proofSigners))(_.toList)

  private[dag] def rollbackOperationalSeed(
    restored: ConsensusOperationalState,
    recoveryOverrideActive: Boolean
  ): ConsensusOperationalState =
    if (recoveryOverrideActive) ConsensusOperationalState.empty else restored

  private[dag] def rollbackProofSize(snapshotProofSize: Int, recoveryCommitteeSize: Option[Int]): Int =
    recoveryCommitteeSize.getOrElse(snapshotProofSize)

  /** Match GlobalSnapshotConsensusFunctions.getBalance exactly: an address absent from the anchor's consensus-agreed balance map has
    * Balance.empty, not implicit collateral.
    */
  private[dag] def rollbackAnchorHasCollateral(balance: Option[Balance], required: Amount): Boolean =
    balance.getOrElse(Balance.empty).satisfiesCollateral(required)

  private[dag] final case class ConflictingRecoverySeedAnchors(
    selected: RecoveryCheckpoint,
    configured: RecoveryCheckpoint
  ) extends NoStackTrace {
    override def getMessage: String =
      s"GL0 recovery seed anchor conflicts with configured recovery checkpoint: " +
        s"selected=(${selected.network},${selected.ordinal.value.value},${selected.snapshotHash.value}) " +
        s"checkpoint=(${configured.network},${configured.ordinal.value.value},${configured.snapshotHash.value})"
  }

  private[dag] case object RecoverySeedConfiguredWithoutSeedlist extends NoStackTrace {
    override def getMessage: String =
      s"${Gl0RecoverySeedCommittee.EnvironmentVariable} is configured but no GL0 seedlist is present"
  }

  private[dag] final case class RecoverySeedRollbackHashMismatch(expected: Hash, got: Hash) extends NoStackTrace {
    override def getMessage: String =
      s"${Gl0RecoverySeedCommittee.EnvironmentVariable} loaded rollback hash mismatch: expected=${expected.value} got=${got.value}"
  }

  private[dag] final case class RecoverySeedUnsupportedAnchorSource(got: String) extends NoStackTrace {
    override def getMessage: String =
      s"${Gl0RecoverySeedCommittee.EnvironmentVariable} requires an incremental snapshot rollback hash; loaded anchor source=$got"
  }

  private[dag] final case class NormalRollbackLeadNotInAnchorCommittee(lead: PeerId, committee: SortedSet[PeerId]) extends NoStackTrace {
    override def getMessage: String =
      s"Normal post-bootstrap rollback lead=${lead.value.value} is not in anchor committee(size=${committee.size}); " +
        s"select a signer as lead or use ${Gl0RecoverySeedCommittee.EnvironmentVariable}"
  }

  /** Select the ordinary GL0 rollback start policy without changing recovery override precedence.
    *
    * True bootstrap retains the legacy delayed start. An established chain requires the rollback lead to belong to the anchor's proof
    * signer committee, then waits for a structurally aligned quorum of that exact set. Explicit recovery-seed callers bypass this helper
    * and retain their stronger all-member barrier.
    */
  private[dag] def normalRollbackStartPolicy(
    lead: PeerId,
    committee: SortedSet[PeerId],
    postBootstrap: Boolean
  ): Either[NormalRollbackLeadNotInAnchorCommittee, RollbackStartPolicy] =
    if (!postBootstrap) Right(RollbackStartPolicy.LegacyDeferred)
    else
      Either.cond(
        committee.contains(lead),
        RollbackStartPolicy.RequireOutcomeAlignedQuorum(committee),
        NormalRollbackLeadNotInAnchorCommittee(lead, committee)
      )

  private[dag] final case class RecoverySeedHeadroom(
    observed: Int,
    required: Int,
    absent: SortedSet[PeerId]
  ) {
    def isReady: Boolean = observed >= required
    def deficit: Int = math.max(required - observed, 0)
  }

  private[dag] def recoverySeedHeadroom(
    seed: Gl0RecoverySeedCommittee,
    completedSigners: Set[PeerId],
    quorumThresholdFraction: Double
  ): RecoverySeedHeadroom = {
    val observed = seed.committee.intersect(completedSigners)
    RecoverySeedHeadroom(
      observed.size,
      QuorumPolicy.fromFraction(seed.committee.size + 1, quorumThresholdFraction),
      seed.committee.diff(completedSigners)
    )
  }

  private[dag] final case class CertifiedRollbackOutcomeUnavailable(
    anchor: SnapshotOrdinal,
    reason: String
  ) extends NoStackTrace {
    override def getMessage: String =
      s"GL0 certified rollback anchor=${anchor.value.value} has no usable locally validated certified outcome: $reason. " +
        s"Use an intact source-node sidecar for an ordinary restart or explicitly set ${Gl0RecoverySeedCommittee.EnvironmentVariable} " +
        "for a coordinated recovery."
  }

  /** Bind a persisted certified outcome to the exact public rollback anchor before it can become local consensus authority. A matching
    * ordinal alone is insufficient: the signed artifact, derived context, and live artifact hash must all match the independently loaded
    * anchor.
    */
  private[dag] def validateCertifiedRollbackOutcomeBindings(
    candidate: GlobalConsensusOutcome,
    snapshot: Signed[GlobalIncrementalSnapshot],
    snapshotInfo: GlobalSnapshotInfo,
    snapshotHash: Hash
  ): Either[CertifiedRollbackOutcomeUnavailable, Unit] = {
    val anchor = snapshot.ordinal

    for {
      _ <- Either.cond(
        candidate.key === anchor,
        (),
        CertifiedRollbackOutcomeUnavailable(anchor, s"key mismatch got=${candidate.key.value.value}")
      )
      _ <- Either.cond(
        Signed.sameValueAndProofs(candidate.finished.signedMajorityArtifact, snapshot),
        (),
        CertifiedRollbackOutcomeUnavailable(anchor, "signed artifact mismatch")
      )
      _ <- Either.cond(
        candidate.finished.context === snapshotInfo,
        (),
        CertifiedRollbackOutcomeUnavailable(anchor, "snapshot context mismatch")
      )
      _ <- Either.cond(
        candidate.finished.snapshotHash === snapshotHash,
        (),
        CertifiedRollbackOutcomeUnavailable(anchor, "artifact hash mismatch")
      )
      _ <- Either.cond(
        candidate.finished.certifiedOutcome.nonEmpty,
        (),
        CertifiedRollbackOutcomeUnavailable(anchor, "outcome has no certified result")
      )
    } yield ()
  }

  private[dag] final case class RecoverySeedTooCloseToCertifiedActivation(
    anchor: SnapshotOrdinal,
    activation: SnapshotOrdinal
  ) extends NoStackTrace {
    override def getMessage: String =
      s"${Gl0RecoverySeedCommittee.EnvironmentVariable} anchor=${anchor.value.value} is too close to " +
        s"certified-consensus activation=${activation.value.value}; a pre-activation recovery must precede activation by at least three " +
        "ordinals so the controller-evidence window is rebuilt. At or after activation the env seed starts an explicit certified epoch."
  }

  private[dag] final case class RecoverySeedAtCanonicalGenesisRootUnsupported(
    anchor: SnapshotOrdinal,
    activation: SnapshotOrdinal
  ) extends NoStackTrace {
    override def getMessage: String =
      s"${Gl0RecoverySeedCommittee.EnvironmentVariable} cannot replace the canonical certified-from-genesis root " +
        s"at anchor=${anchor.value.value} activation=${activation.value.value}. The ordinary genesis child and a recovery-reset child " +
        "have the same public lineage shape at that boundary, so community validators could not distinguish their authority. " +
        "If no successor exists, restart genesis normally; otherwise recover from an incremental anchor at ordinal 2 or later."
  }

  /** The environment seed is the sole explicit operator recovery authority. It flushes controller-evidence windows. A legacy anchor must
    * precede activation by three ordinals to rebuild those inputs before v35; an anchor at/after activation starts a new canonical
    * certified epoch whose first QC makes the reset boundary publicly reconstructible.
    */
  private[dag] def validateRecoverySeedActivationSpacing(
    anchor: SnapshotOrdinal,
    activation: SnapshotOrdinal
  ): Either[RecoverySeedTooCloseToCertifiedActivation, Unit] = {
    val distance = BigInt(activation.value.value) - BigInt(anchor.value.value)
    Either.cond(
      anchor >= activation || distance >= 3,
      (),
      RecoverySeedTooCloseToCertifiedActivation(anchor, activation)
    )
  }

  /** A certified-from-genesis root and an env-reset root at ordinal 1 produce indistinguishable public key-2 lineage shapes. Refuse that
    * one ambiguous boundary before rollback storage can be mutated. Later certified anchors are publicly discoverable through their
    * first-successor QC; a future ordinal-gated activation is not a genesis root and retains the ordinary spacing rule.
    */
  private[dag] def validateRecoverySeedPublicDiscoverability(
    anchor: SnapshotOrdinal,
    activation: SnapshotOrdinal
  ): Either[RecoverySeedAtCanonicalGenesisRootUnsupported, Unit] =
    Either.cond(
      !CertifiedConsensusGenesis.isActiveFromGenesis(activation.value.value) ||
        anchor =!= CertifiedConsensusGenesis.FirstIncrementalOrdinal,
      (),
      RecoverySeedAtCanonicalGenesisRootUnsupported(anchor, activation)
    )

  private[dag] def validateRecoverySeedAnchorCompatibility(
    selected: RecoveryCheckpoint,
    configured: Option[RecoveryCheckpoint]
  ): Either[ConflictingRecoverySeedAnchors, Unit] =
    configured.fold[Either[ConflictingRecoverySeedAnchors, Unit]](Right(())) { checkpoint =>
      Either.cond(selected === checkpoint, (), ConflictingRecoverySeedAnchors(selected, checkpoint))
    }
  private[dag] def validateRecoverySeedAnchorSource(
    source: RollbackLoader.Source
  ): Either[RecoverySeedUnsupportedAnchorSource, Unit] =
    source match {
      case RollbackLoader.Source.Incremental  => Right(())
      case RollbackLoader.Source.FullSnapshot => Left(RecoverySeedUnsupportedAnchorSource("full_snapshot"))
    }

  private[dag] def validateRecoverySeedRollbackHash(expected: Hash, got: Hash): Either[RecoverySeedRollbackHashMismatch, Unit] =
    Either.cond(expected === got, (), RecoverySeedRollbackHashMismatch(expected, got))
  private[dag] final case class RecentCoreReconstructionDiagnostic(
    source: String,
    entries: SortedMap[SnapshotOrdinal, SortedSet[PeerId]]
  )

  private[dag] def reconstructRecentCoreFacilitatorsDiagnostic(
    peerHistorySidecar: PeerHistorySidecarStorage[IO],
    rollbackOrdinal: SnapshotOrdinal,
    rollbackPeerHistory: Option[ConsensusOperationalState],
    windowSize: Int
  ): IO[RecentCoreReconstructionDiagnostic] = {
    def coreFrom(state: ConsensusOperationalState): SortedSet[PeerId] =
      SortedSet.from(state.perPeer.iterator.collect {
        case (pid, record) if record.tier.contains(TierTransitions.Core) => pid
      })

    val start = math.max(0L, rollbackOrdinal.value.value - math.max(0, windowSize - 1).toLong)
    val ordinals = (start to rollbackOrdinal.value.value).toList.flatMap(SnapshotOrdinal(_))

    ordinals.traverse { ordinal =>
      peerHistorySidecar.read(ordinal).map(_.map(state => ordinal -> coreFrom(state)))
    }
      .map(entries => SortedMap.from(entries.flatten))
      .map { sidecarEntries =>
        if (sidecarEntries.nonEmpty) RecentCoreReconstructionDiagnostic("sidecar", sidecarEntries)
        else
          rollbackPeerHistory
            .map(state => RecentCoreReconstructionDiagnostic("replayed", SortedMap(rollbackOrdinal -> coreFrom(state))))
            .getOrElse(RecentCoreReconstructionDiagnostic("unavailable", SortedMap.empty))
      }
  }

  type KryoRegistrationIdRange = DagL0KryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL0KryoRegistrar

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- loadConfigAs[AppConfigReader].asResource
      implicit0(logger: SelfAwareStructuredLogger[IO]) = Slf4jLogger.getLoggerFromName[IO](this.getClass.getName)
      cfg = method.appConfig(cfgR, sharedConfig)
      loadedConsensusConfig <- IO
        .fromOption(effectiveConsensusConfig)(new IllegalStateException("DAG L0 effective consensus config was not loaded"))
        .asResource
      certifiedConsensusActivationOrdinal = SnapshotOrdinal.unsafeApply(loadedConsensusConfig.certifiedConsensusActivationKey)
      recoveryMaxFacilitatorCount = loadedConsensusConfig.facilitatorSelectionMax
      recoverySeedCommittee = method match {
        case m: RunRollback                 => m.recoverySeedCommittee
        case m: RunValidator                => m.recoverySeedCommittee
        case _: RunValidatorWithJoinAttempt => none[Gl0RecoverySeedCommittee]
        case _: RunGenesis                  => none[Gl0RecoverySeedCommittee]
      }
      validatedRecoverySeed <- recoverySeedCommittee.traverse { seed =>
        nodeShared.seedlist
          .fold[Either[Throwable, Set[PeerId]]](Left(RecoverySeedConfiguredWithoutSeedlist)) { entries =>
            Right(entries.iterator.map(_.peerId).toSet)
          }
          .flatMap { seedlist =>
            Gl0RecoverySeedCommittee
              .validate(
                seed,
                nodeId,
                seedlist,
                nodeShared.customAllowanceList.fold(Option.empty[Set[PeerId]])(entries => Some(entries.iterator.map(_.peerId).toSet)),
                recoveryMaxFacilitatorCount,
                loadedConsensusConfig.quorumThresholdFraction
              )
              .leftWiden[Throwable]
          }
          .liftTo[IO]
      }.asResource
      recoveryRole = method match {
        case _: RunRollback                                   => "rollback_lead"
        case _: RunValidator | _: RunValidatorWithJoinAttempt => "selected_validator"
        case _: RunGenesis                                    => "none"
      }
      resetRecoverySeedGauges =
        Metrics[IO].updateGauge("dag_consensus_recovery_seed_armed", 0L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_committee_size", 0L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_headroom_deficit", 0L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_headroom_ready", 0L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_alignment_missing_session", 0L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_alignment_invalid_state", 0L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_alignment_missing_outcome", 0L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_alignment_mismatched_outcome", 0L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_alignment_fetch_failed", 0L)
      initializeRecoverySeedMetrics = (seed: Gl0RecoverySeedCommittee) =>
        ConsensusLog.warn(
          logger,
          ConsensusLog.Category.Recovery,
          "startup",
          "n/a",
          ConsensusLog.Event.DownloadInitStart,
          "reason" -> "unsigned_recovery_seed_configured",
          "role" -> recoveryRole,
          "committeeSize" -> seed.committee.size.toString,
          "committee" -> seed.committee.toList.map(ConsensusLog.pid).mkString(","),
          "warning" -> "trusted_operator_override_rearms_on_each_fresh_external_jvm_while_env_is_set"
        ) >>
          Metrics[IO]
            .incrementCounter(
              "dag_consensus_recovery_seed_configured_total",
              Seq(Metrics.unsafeLabelName("role") -> recoveryRole)
            ) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_armed", 1L) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_committee_size", seed.committee.size.toLong) >>
          Metrics[IO].updateGauge(
            "dag_consensus_recovery_seed_headroom_deficit",
            recoverySeedHeadroom(seed, Set.empty, loadedConsensusConfig.quorumThresholdFraction).deficit.toLong
          ) >>
          Metrics[IO].updateGauge("dag_consensus_recovery_seed_headroom_ready", 0L)
      // The metrics registry outlives an in-process application restart. An
      // armed invocation therefore resets its gauges when its resource is
      // released; counters remain historical. Keep env-absent startup exactly
      // inert so ordinary nodes do not gain recovery gauges.
      _ <- validatedRecoverySeed
        .traverse(seed => Resource.make(initializeRecoverySeedMetrics(seed).attempt.void)(_ => resetRecoverySeedGauges.attempt.void))
        .void
      initiallyHoldConsensusFirstRound = validatedRecoverySeed.isDefined
      configuredRecoverySeedRef <- Ref.of[IO, Option[Gl0RecoverySeedCommittee]](validatedRecoverySeed).asResource
      recoverySeedHeadroomReachedRef <- Ref.of[IO, Boolean](false).asResource
      clearConfiguredRecoverySeed = (outcome: GlobalConsensusOutcome) => {
        val completedSigners = outcome.finished.signedMajorityArtifact.proofs.toList.map(_.id.toPeerId).toSet

        // The unsigned override is per-invocation startup authority, not
        // durable consensus state. Disarm on the first accepted successor
        // independently of proof headroom. In-process restart methods never
        // carry this authority; each fresh external JVM launch deliberately
        // re-arms while the environment remains configured.
        val disarmAuthority = configuredRecoverySeedRef.getAndSet(None).flatMap {
          case Some(_) =>
            (ConsensusLog.info(
              logger,
              ConsensusLog.Category.Recovery,
              outcome.key.toString,
              "n/a",
              ConsensusLog.Event.RollbackQuorumFeasible,
              "reason" -> "unsigned_recovery_seed_disarmed_after_first_accepted_successor",
              "action" -> "external_env_controls_rearm_on_next_fresh_jvm"
            ) >>
              Metrics[IO].updateGauge("dag_consensus_recovery_seed_armed", 0L) >>
              Metrics[IO].incrementCounter("dag_consensus_recovery_seed_disarmed_total")).attempt.void
          case None => IO.unit
        }

        val recordHeadroom = validatedRecoverySeed.traverse_ { seed =>
          recoverySeedHeadroomReachedRef.get.flatMap {
            case true => IO.unit
            case false =>
              val headroom = recoverySeedHeadroom(seed, completedSigners, loadedConsensusConfig.quorumThresholdFraction)
              val fields = List(
                "observed" -> headroom.observed.toString,
                "required" -> headroom.required.toString,
                "deficit" -> headroom.deficit.toString,
                "absentCount" -> headroom.absent.size.toString,
                "absent" -> headroom.absent.toList.map(ConsensusLog.pid).mkString(",")
              )

              if (headroom.isReady)
                recoverySeedHeadroomReachedRef.set(true) >>
                  (ConsensusLog.info(
                    logger,
                    ConsensusLog.Category.Recovery,
                    outcome.key.toString,
                    "n/a",
                    ConsensusLog.Event.RollbackQuorumFeasible,
                    ("reason" -> "unsigned_recovery_seed_next_seat_headroom_reached") :: fields: _*
                  ) >>
                    Metrics[IO].updateGauge("dag_consensus_recovery_seed_headroom_deficit", 0L) >>
                    Metrics[IO].updateGauge("dag_consensus_recovery_seed_headroom_ready", 1L) >>
                    Metrics[IO].incrementCounter("dag_consensus_recovery_seed_headroom_reached_total")).attempt.void
              else
                (ConsensusLog.warn(
                  logger,
                  ConsensusLog.Category.Recovery,
                  outcome.key.toString,
                  "n/a",
                  ConsensusLog.Event.RollbackFirstRoundDeferred,
                  ("reason" -> "unsigned_recovery_seed_awaiting_next_seat_headroom") :: fields: _*
                ) >>
                  Metrics[IO].updateGauge("dag_consensus_recovery_seed_headroom_deficit", headroom.deficit.toLong) >>
                  Metrics[IO].updateGauge("dag_consensus_recovery_seed_headroom_ready", 0L) >>
                  Metrics[IO].incrementCounter("dag_consensus_recovery_seed_headroom_pending_total")).attempt.void
          }
        }

        disarmAuthority >> recordHeadroom
      }
      queues <- Queues.make[IO](sharedQueues).asResource

      // B2 witness channel: the mesh-gossip peer-chain-tip Ref is created here so it can be
      // closed over by Services (which builds consensus) before EventGossipDaemon exists.
      // Default getter returns Map.empty → no admission votes fire pre-daemon (safe). Once
      // eventGossipDaemon is up below, we `.set(eventGossipDaemon.getPeerChainTips)` so
      // subsequent reads through the thunk return fresh mesh tips.
      peerChainTipsGetterRef <-
        Ref
          .of[IO, IO[Map[PeerId, ChainTip]]](
            Map.empty[PeerId, ChainTip].pure[IO]
          )
          .asResource
      getPeerChainTips = peerChainTipsGetterRef.get.flatten

      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session, sharedConfig.snapshotTimeoutsConfig)
      storages <- Storages
        .make[IO](
          sharedStorages,
          sharedConfig,
          nodeShared.seedlist,
          cfg.snapshot,
          cfg.incremental,
          trustRatings,
          sharedConfig.environment,
          hashSelect,
          loadedConsensusConfig.certifiedConsensusActivationKey
        )
        .asResource
      // Dedicated work-stealing pool for the ConsensusEventLoop consume fiber. Isolates
      // round-timing from HTTP serving load on the default global compute pool. Zero or
      // negative `consensusDispatcherThreads` falls back to the global runtime (legacy
      // behaviour). See ConsensusExecutor for the rationale and lifecycle notes.
      consensusEc <- ConsensusExecutor.optional[IO](loadedConsensusConfig.consensusDispatcherThreads)
      services <- Services
        .make[IO, Run](
          sharedConfig,
          sharedServices,
          sharedStorages,
          queues,
          storages,
          nodeShared.sharedValidators,
          sharedResources.client,
          sharedServices.session,
          nodeShared.seedlist,
          method.stateChannelAllowanceLists,
          nodeShared.nodeId,
          keyPair,
          cfg,
          loadedConsensusConfig,
          Hasher.forKryo[IO],
          nodeShared.loggerBundle,
          getPeerChainTips,
          nodeShared.customAllowanceList.fold(Option.empty[Set[PeerId]])(entries => Some(entries.iterator.map(_.peerId).toSet)),
          configuredRecoverySeedRef.get,
          validatedRecoverySeed.as(clearConfiguredRecoverySeed),
          initiallyHoldConsensusFirstRound,
          consensusEc
        )
        .asResource

      recoveryCheckpoint <- HasherSelector[IO].withCurrent { implicit hasher =>
        RecoveryCheckpointLoader.load[IO](
          cfg.recovery.checkpointPath,
          nodeShared.seedlist.map(_.map(_.peerId)),
          cfg.environment.toString,
          SignedValidator.make[IO]
        )
      }.asResource
      programs = Programs.make[IO, Run](
        sharedPrograms,
        storages,
        services,
        keyPair,
        cfg,
        cfg.incremental.lastFullGlobalSnapshotOrdinal.getOrElse(cfg.environment, SnapshotOrdinal.MinValue),
        p2pClient,
        sharedServices.globalSnapshotContextFns,
        storages.globalSnapshot,
        sharedStorages.lastNGlobalSnapshot,
        sharedStorages.lastGlobalSnapshot,
        sharedStorages.mptStore,
        nodeShared.seedlist.map(_.map(_.peerId)),
        recoveryCheckpoint
      )

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck)
        .handlers <+>
        trustHandler(storages.trust) <+> ordinalTrustHandler(storages.trust) <+> services.consensus.handler

      forkRecoveryService = ForkRecoveryService.make[IO](
        storages.node,
        sharedStorages.lastGlobalSnapshot,
        services.recoveryPeerHint
      )

      // Tier 2 fork verification: resolves PeerId → Peer, then asks the peer for its snapshot
      // hash at the given ordinal via /global-snapshots/{ordinal}/hash. Returns None on any
      // resolution or RPC failure so the detector treats it as inconclusive (safer than false
      // positives). Reuses the existing p2pClient.globalSnapshot.getHash primitive.
      hashAtOrdinalProbe =
        new HashAtOrdinalProbe[IO] {
          def probe(peerId: PeerId, ordinal: SnapshotOrdinal): IO[Option[Hash]] =
            storages.cluster.getPeer(peerId).flatMap {
              case None => none[Hash].pure[IO]
              case Some(peer) =>
                p2pClient.globalSnapshot.getHash(ordinal).run(peer).attempt.map(_.toOption.flatten)
            }
        }

      eventGossipDaemon <- EventGossipDaemon
        .make[IO, GlobalSnapshotEvent, GlobalStateKey](
          services.eventMempool,
          storages.cluster,
          storages.node,
          sharedResources.gossipClient,
          sharedServices.session,
          config = EventGossipConfig(
            heartbeatInterval = loadedConsensusConfig.eventGossipHeartbeatInterval,
            pullInterval = loadedConsensusConfig.eventGossipPullInterval
          ),
          getLocalChainTip = Some(forkRecoveryService.getLocalChainTip),
          onForkDetected = Some(forkRecoveryService.onForkDetected),
          forkLagThreshold = loadedConsensusConfig.forkLagThreshold,
          verifyHashAt = Some(hashAtOrdinalProbe)
        )
        .asResource
      // B2 witness channel: publish eventGossipDaemon's chain-tip getter into the Ref we
      // created pre-services. Before this runs, the consensus StallDetector sees an empty
      // map (no admission votes fire). After this, mesh chain tips flow into admission
      // emission decisions.
      _ <- Resource.eval(peerChainTipsGetterRef.set(eventGossipDaemon.getPeerChainTips))

      _ <- Daemons
        .start(
          storages,
          services,
          programs,
          queues,
          nodeId,
          keyPair,
          cfg,
          loadedConsensusConfig,
          hasherSelector,
          eventGossipDaemon,
          sharedServices.stateEntryAtRef
        )
        .asResource

      api <- Resource.eval(
        HttpApi.make[IO, Run](
          storages,
          queues,
          services,
          programs,
          keyPair.getPrivate,
          sharedConfig.environment,
          nodeShared.nodeId,
          TessellationVersion.unsafeFrom(BuildInfo.version),
          cfg.http,
          sharedValidators,
          cfg.shared.delegatedStaking.withdrawalTimeLimit
            .getOrElse(sharedConfig.environment, EpochProgress.MinValue),
          cfg.shared,
          storages.combinedGlobalSnapshotCheckpointStorage,
          sharedStorages.lastNGlobalSnapshot,
          getLocalChainTip = Some(forkRecoveryService.getLocalChainTip),
          maybeMarkSeen = Some(eventGossipDaemon.markSeen)
        )
      )

      // Alpha.95: env-resolved listener caps. Lifts the alpha.76 blanket maxConnections=100
      // ceiling that caused the May 17 testnet regression by saturating p2p sockets under
      // 13+ peer load. See `HttpMaxConnectionsDefaults` for per-env values.
      httpResolved = cfg.http.envResolved(cfg.environment)
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), httpResolved.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), httpResolved.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), httpResolved.cliHttp, api.cliApp)

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        nodeShared.sharedValidators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        sharedConfig.gossip.daemon,
        services.collateral
      )

      _ <- (method match {
        case m: RunValidator =>
          storages.node.setValidatorMode >>
            gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunValidatorWithJoinAttempt =>
          storages.node.setValidatorMode >>
            gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
            programs.joining.joinOneOf(m.peerToJoinPool) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath,
                recoverySeedCommittee = None
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {
            val activation = certifiedConsensusActivationOrdinal
            val recoverySeed = validatedRecoverySeed
            def loadCertifiedRollbackOutcome(
              snapshotInfo: GlobalSnapshotInfo,
              snapshot: Signed[GlobalIncrementalSnapshot]
            ): IO[Option[GlobalConsensusOutcome]] =
              if (recoverySeed.nonEmpty || snapshot.ordinal < activation) none[GlobalConsensusOutcome].pure[IO]
              else
                for {
                  read <- services.consensus.historicalOutcome.liftTo[IO](
                    CertifiedRollbackOutcomeUnavailable(snapshot.ordinal, "certified-outcome storage is unavailable")
                  )
                  candidate <- read(snapshot.ordinal).flatMap(
                    _.liftTo[IO](CertifiedRollbackOutcomeUnavailable(snapshot.ordinal, "exact sidecar is missing"))
                  )
                  snapshotHash <- hasherSelector.withCurrent { implicit hasher =>
                    GlobalSnapshotArtifactHasher.currentHash[IO](snapshot.value)
                  }
                  _ <- validateCertifiedRollbackOutcomeBindings(candidate, snapshot, snapshotInfo, snapshotHash).liftTo[IO]
                  validate <- services.consensus.validateOutcomeForInitialization.liftTo[IO](
                    CertifiedRollbackOutcomeUnavailable(snapshot.ordinal, "certified-outcome validator is unavailable")
                  )
                  _ <- validate(candidate)
                } yield candidate.some

            val loadRollback = {
              val validateRecoverySeedSource: RollbackLoader.Source => IO[Unit] =
                source => recoverySeed.traverse_(_ => validateRecoverySeedAnchorSource(source).liftTo[IO])
              val validateRecoverySeedBeforeLoad: (
                RollbackLoader.Source,
                GlobalSnapshotInfo,
                Signed[GlobalIncrementalSnapshot]
              ) => IO[Unit] =
                (_, snapshotInfo, snapshot) =>
                  recoverySeed.traverse_ { seed =>
                    for {
                      hashedSnapshot <- hasherSelector.forOrdinal(snapshot.ordinal)(implicit hasher => snapshot.toHashed[IO])
                      _ <- validateRecoverySeedActivationSpacing(snapshot.ordinal, activation).liftTo[IO]
                      _ <- validateRecoverySeedPublicDiscoverability(snapshot.ordinal, activation).liftTo[IO]
                      _ <- validateRecoverySeedRollbackHash(m.rollbackHash, hashedSnapshot.hash).liftTo[IO]
                      _ <- validateRecoverySeedAnchorCompatibility(
                        RecoveryCheckpoint(cfg.environment.toString, snapshot.ordinal, hashedSnapshot.hash),
                        recoveryCheckpoint
                      ).liftTo[IO]
                      ineligibleSeedPeers <- seed.committee.toList.filterA { peerId =>
                        peerId.toPublic[IO].map(_.toAddress).map { address =>
                          !rollbackAnchorHasCollateral(snapshotInfo.balances.get(address), cfg.collateral.amount)
                        }
                      }
                      _ <- Gl0RecoverySeedCommittee
                        .Invalid(s"collateral check failed=${ineligibleSeedPeers.map(_.value.value).mkString(",")}")
                        .raiseError[IO, Unit]
                        .whenA(ineligibleSeedPeers.nonEmpty)
                    } yield ()
                  }

              val validateRollbackBeforeLoad: (
                RollbackLoader.Source,
                GlobalSnapshotInfo,
                Signed[GlobalIncrementalSnapshot]
              ) => IO[Unit] =
                (source, snapshotInfo, snapshot) =>
                  recoverySeed.fold(loadCertifiedRollbackOutcome(snapshotInfo, snapshot).void)(_ =>
                    validateRecoverySeedBeforeLoad(source, snapshotInfo, snapshot)
                  )

              programs.rollbackLoader
                .load(
                  m.rollbackHash,
                  programs.download,
                  recoverySeed.as(validateRecoverySeedSource),
                  validateRollbackBeforeLoad.some,
                  preLoadValidateFrom = recoverySeed.fold(activation.some)(_ => none)
                )
                .map {
                  case (snapshotInfo, snapshot) => (recoverySeed, snapshotInfo, snapshot)
                }
            }

            loadRollback.flatMap {
              case (recoverySeed, snapshotInfo, snapshot) =>
                for {
                  // Preserve the legacy rollback hasher selection when no recovery override is present. A
                  // recovery override, however, is bound operationally to the historical snapshot hash and must
                  // seed the outcome with the same ordinal-selected hasher used by preflight.
                  // Otherwise a hasher migration between the anchor and current tip could pass
                  // authorization and then install a different parent hash.
                  hashedSnapshot <- recoverySeed.fold(
                    hasherSelector.withCurrent(implicit hasher => snapshot.toHashed[IO])
                  )(_ => hasherSelector.forOrdinal(snapshot.ordinal)(implicit hasher => snapshot.toHashed[IO]))
                  certifiedRollbackOutcome <- loadCertifiedRollbackOutcome(snapshotInfo, snapshot)
                  recoveryCommittee = recoverySeed.map(_.committee)
                  // Rollback bootstrap: preserve the rolled-back snapshot's proof signers as
                  // the checkpoint's live seed committee. That keeps lastSigners/Core anchored
                  // to signed evidence instead of turning a non-signer rollback server into a
                  // self-only chain tip. Only fall back to self-only when the checkpoint has no
                  // proofs at all (genesis / malformed legacy edge). An explicitly verified
                  // recovery override replaces this seed with its canonical committee.
                  proofSigners = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                  bootstrapFacilitators = recoveryCommittee
                    .orElse(certifiedRollbackOutcome.map(_.facilitators.value))
                    .fold(rollbackBootstrapFacilitators(nodeId, proofSigners))(_.toList)
                  bootstrapMode = recoverySeed.fold {
                    certifiedRollbackOutcome.fold {
                      if (bootstrapFacilitators === proofSigners) "proof_signers" else "self_only_fallback"
                    }(_ => "certified_outcome")
                  }(_ => "operator_recovery_seed")
                  _ <- ConsensusLog.info(
                    logger,
                    ConsensusLog.Category.Recovery,
                    snapshot.ordinal.toString,
                    "n/a",
                    ConsensusLog.Event.DownloadInitStart,
                    "reason" -> "rollback_bootstrap_facilitators",
                    "mode" -> bootstrapMode,
                    "proofSignerCount" -> proofSigners.size.toString,
                    "bootstrapFacilitatorCount" -> bootstrapFacilitators.size.toString,
                    "selfSignedCheckpoint" -> proofSigners.contains(nodeId).toString,
                    "proofSigners" -> proofSigners.map(ConsensusLog.pid).sorted.mkString(","),
                    "bootstrapFacilitators" -> bootstrapFacilitators.map(ConsensusLog.pid).sorted.mkString(",")
                  )
                  _ <- recoverySeed.traverse_(_ =>
                    ConsensusLog.info(
                      logger,
                      ConsensusLog.Category.Recovery,
                      snapshot.ordinal.toString,
                      "n/a",
                      ConsensusLog.Event.DownloadInitStart,
                      "reason" -> "unsigned_recovery_seed_anchor_selected",
                      "anchorHash" -> hashedSnapshot.hash.value
                    )
                  )
                  _ <- Metrics[IO].incrementCounter(
                    "dag_consensus_rollback_bootstrap_total",
                    Seq(Metrics.unsafeLabelName("mode") -> bootstrapMode)
                  )
                  _ <- Metrics[IO].updateGauge("dag_consensus_rollback_proof_signer_count", proofSigners.size.toLong)
                  _ <- Metrics[IO].updateGauge("dag_consensus_rollback_bootstrap_facilitator_count", bootstrapFacilitators.size.toLong)
                  // Seed the bootstrap-warmup window with the rollback snapshot's proof count.
                  // If we're rolling back to a healthy multi-node snapshot (proofs.size >= threshold),
                  // the window classifies as post-bootstrap and penalties apply immediately. If we're
                  // rolling back to a solo/bootstrap-era snapshot, the window starts in bootstrap mode
                  // and the cluster re-stabilizes naturally.
                  recoveryCommitteeSize = recoveryCommittee.fold(Option.empty[Int])(committee => Some(committee.size))
                  rollbackProofSize = Main.rollbackProofSize(snapshot.proofs.size.toInt, recoveryCommitteeSize)
                  // Persisted operational history if the rollback snapshot carries it.
                  // Older snapshots have `peerHistory = None`, so seedOperational stays empty
                  // and the cluster bootstraps from zero. Newer snapshots restore
                  // chronic-classifier history, B2 readmission, and removal-penalty
                  // escalation across the cold-restart boundary.
                  //
                  // Alpha.94: prefer the post-finalization peerHistory sidecar written by
                  // `GlobalSnapshotConsensusStateAdvancer.persistAndGossip` on the previous run.
                  // The signed `snapshot[N].peerHistory` field is packed at round-N proposal time
                  // (before Outcome[N] existed) so it actually carries `pack(Outcome[N-1])`. The
                  // sidecar at `<snapshotPath>/peerHistory/<ordinal>.meta` contains
                  // `pack(Outcome[N])`, eliminating the previously-documented one-round-stale
                  // seed. Pre-v19 this stale drift was harmless (below the chronic-classifier
                  // floor of 10-30 observations); post-v19 the same fields drive eligible-
                  // facilitator computation, so the stale seed produced the
                  // `facilitator_set_mismatch_revalidate` wedge observed at ord 3127130 (see
                  // `project_alpha92_wedge_may21.md`). On missing/malformed sidecar the read
                  // returns None and we fall back to `snapshot.peerHistory` -- pre-alpha.94
                  // behavior, which is the right thing for snapshots written by older nodes.
                  peerHistorySidecar <- PeerHistorySidecarStorage.make[IO](cfg.snapshot.snapshotPath / "peerHistory")
                  sidecarPeerHistory <- peerHistorySidecar.read(snapshot.value.ordinal)
                  // A recovery override is an explicit replacement of the signing committee. Do not
                  // let locally persisted penalty/tier/probation sidecars silently filter that
                  // committee on the first derived round. Ordinary rollback retains the exact
                  // legacy restoration behavior.
                  seedOperational = rollbackOperationalSeed(
                    sidecarPeerHistory
                      .orElse(snapshot.value.peerHistory)
                      .getOrElse(ConsensusOperationalState.empty),
                    recoveryCommittee.isDefined
                  )
                  // Project the consolidated per-peer record back out to the five PeerId-keyed
                  // dimensions on the outcome. A peer absent from `perPeer` is treated as
                  // `PerPeerOperationalRecord.empty` (= no penalty, no probation, etc.) on the
                  // consumer side; here we only emit non-default entries to keep the maps small.
                  seedPeerQuality = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.quality != ((0, 0)) => pid -> r.quality
                  })
                  seedRemovalPenalties = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.removalPenalty > 0 => pid -> r.removalPenalty
                  })
                  seedCumulativeMissCounts = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.cumulativeMissCount > 0L => pid -> r.cumulativeMissCount
                  })
                  seedReadmissionCountdown = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.readmissionCountdown > 0 => pid -> r.readmissionCountdown
                  })
                  seedDeferralCountdown = SortedMap.from(seedOperational.perPeer.iterator.collect {
                    case (pid, r) if r.deferralCountdown > 0 => pid -> r.deferralCountdown
                  })
                  // v16: per-peer cumulative view-change-caused. Seeded only when non-zero so
                  // the persisted map stays small. Absent peers default to 0 at the v16 filter
                  // call-site, matching the pre-v16 "no penalty" semantic.
                  // PerPeerOperationalRecord.viewChangesCaused is Option[Long] (back-compat with
                  // pre-v16 JSON), so unwrap with the same > 0 filter.
                  seedPeerViewChanges = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                    case (pid, r) => r.viewChangesCaused.filter(_ > 0L).map(v => pid -> v)
                  })
                  // Recent-proof window: prefer the persisted history (so the bootstrap-vs-post
                  // classification matches the running cluster), otherwise seed with the rollback
                  // snapshot's proof count -- preserves pre-v20 behavior.
                  seedRecentProofSizes =
                    if (seedOperational.recentProofSizes.nonEmpty) seedOperational.recentProofSizes
                    else SortedMap(snapshot.ordinal -> rollbackProofSize)
                  // Recent-signers window unwrapped from the Option. Snapshots written before
                  // the field existed decode None -> empty map; FacilitatorSelector treats an
                  // empty window as bootstrap (use full eligibility until the window fills).
                  seedRecentSigners = seedOperational.recentSigners.getOrElse(SortedMap.empty[SnapshotOrdinal, SortedSet[PeerId]])
                  // v19: per-peer tier classification seeded from `PerPeerOperationalRecord.tier`.
                  // Tier is Option[Int], so peers with `None` are absent from this map and
                  // CommitteeBuilder defaults them to bootstrap-Tier-2 at consume time.
                  seedPeerTiers = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                    case (pid, r) => r.tier.map(t => pid -> t)
                  })
                  seedActiveAdmissionScores = SortedMap.from(seedOperational.perPeer.iterator.flatMap {
                    case (pid, r) => r.activeAdmissionScore.filter(_ > 0).map(score => pid -> score)
                  })
                  // v19 phase 2: view-from-time window unwrapped from the Option. Snapshots
                  // written before the field existed decode None -> empty map; the next
                  // round's view derivation falls back to phase 1 `viewChangeVotes.maxToView`
                  // until the window populates.
                  seedRecentRoundEndTimes =
                    seedOperational.recentRoundEndTimes.getOrElse(SortedMap.empty[SnapshotOrdinal, Long])
                  // Stage 4: controller-evidence window + cert-anchored penalty horizons,
                  // kept Option-shaped end to end (the outcome fields are Option too).
                  // Pre-deploy snapshots decode None -> the evidence read side stays in its
                  // carried-map fallback regime until the window refills; `filter(_.nonEmpty)`
                  // normalizes a defensive Some(empty) back to None.
                  seedControllerEvidence = seedOperational.controllerEvidence.filter(_.nonEmpty)
                  seedPenaltyUntil = seedOperational.penaltyUntil.filter(_.nonEmpty)
                  recentCoreDiagnostic <- recoverySeed.fold(
                    reconstructRecentCoreFacilitatorsDiagnostic(
                      peerHistorySidecar,
                      snapshot.value.ordinal,
                      snapshot.value.peerHistory,
                      loadedConsensusConfig.tighteningWindow
                    )
                  )(_ =>
                    IO.pure(
                      RecentCoreReconstructionDiagnostic(
                        "operator_recovery_seed_flush",
                        SortedMap.empty[SnapshotOrdinal, SortedSet[PeerId]]
                      )
                    )
                  )
                  recentCoreSummary = recentCoreDiagnostic.entries.toList.map {
                    case (ordinal, core) =>
                      s"${ordinal.value.value}:${core.size}:${core.toList.map(ConsensusLog.pid).mkString(",")}"
                  }
                    .mkString(";")
                  _ <- ConsensusLog.info(
                    logger,
                    ConsensusLog.Category.Recovery,
                    snapshot.ordinal.toString,
                    "n/a",
                    ConsensusLog.Event.RollbackBootstrapActive,
                    "reason" -> "recent_core_facilitators_reconstruction",
                    "source" -> recentCoreDiagnostic.source,
                    "entries" -> recentCoreDiagnostic.entries.size.toString,
                    "windowSize" -> loadedConsensusConfig.tighteningWindow.toString,
                    "method" -> "best_effort_effective_tier_core",
                    "summary" -> recentCoreSummary
                  )
                  _ <- Metrics[IO].incrementCounter(
                    "dag_consensus_recent_core_reconstruction_total",
                    Seq(Metrics.unsafeLabelName("source") -> recentCoreDiagnostic.source)
                  )
                  _ <- Metrics[IO].updateGauge(
                    "dag_consensus_recent_core_reconstruction_entries",
                    recentCoreDiagnostic.entries.size.toLong
                  )
                  _ <- Metrics[IO].updateGauge(
                    "dag_consensus_recent_core_reconstruction_latest_core_size",
                    recentCoreDiagnostic.entries.lastOption.map(_._2.size.toLong).getOrElse(0L)
                  )
                  legacyRollbackOutcome = GlobalConsensusOutcome(
                    snapshot.ordinal,
                    Facilitators(bootstrapFacilitators),
                    RemovedFacilitators.empty,
                    WithdrawnFacilitators.empty,
                    EligibleFacilitators(bootstrapFacilitators),
                    Finished(snapshot, snapshotInfo, EventTrigger, Candidates.empty, Hash.empty, hashedSnapshot.hash),
                    removalPenalties = seedRemovalPenalties,
                    deferralCountdown = seedDeferralCountdown,
                    peerQuality = seedPeerQuality,
                    cumulativeMissCounts = seedCumulativeMissCounts,
                    recentProofSizes = seedRecentProofSizes,
                    readmissionCountdown = seedReadmissionCountdown,
                    peerViewChanges = seedPeerViewChanges,
                    recentSigners = seedRecentSigners,
                    peerTiers = seedPeerTiers,
                    activeAdmissionScores = seedActiveAdmissionScores,
                    recentRoundEndTimes = seedRecentRoundEndTimes,
                    controllerEvidence = seedControllerEvidence,
                    penaltyUntil = seedPenaltyUntil
                  )
                  rollbackOutcome = recoverySeed.fold(certifiedRollbackOutcome.getOrElse(legacyRollbackOutcome))(seed =>
                    GlobalRecoverySeedOutcome.seed(snapshot, snapshotInfo, hashedSnapshot.hash, seed.committee)
                  )
                  normalRollbackStartPolicy <- {
                    val committee = SortedSet.from(rollbackOutcome.facilitators.value)
                    val postBootstrap =
                      rollbackOutcome.recentProofSizes.values.exists(_ >= loadedConsensusConfig.bootstrapCompleteProofsThreshold)

                    if (recoveryCommittee.nonEmpty || !postBootstrap)
                      (RollbackStartPolicy.LegacyDeferred: RollbackStartPolicy).pure[IO]
                    else
                      Main.normalRollbackStartPolicy(nodeId, committee, postBootstrap).liftTo[IO]
                  }
                  result <- services.consensus.manager.startFacilitatingAfterRollback(
                    snapshot.ordinal,
                    rollbackOutcome,
                    startPolicy = recoverySeed.fold[RollbackStartPolicy](normalRollbackStartPolicy)(seed =>
                      RollbackStartPolicy.RequireAlignedCommittee(seed.committee)
                    )
                  )
                } yield result
            }
          } >>
            services.collateral
              .hasCollateral(nodeShared.nodeId)
              .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath,
                recoverySeedCommittee = None
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
        case m: RunGenesis =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.LoadingGenesis,
            NodeState.GenesisReady
          ) {
            GenesisLoader.make[IO, GlobalSnapshot].loadBalances(m.genesisPath).flatMap { accounts =>
              val genesis = GlobalSnapshot.mkGenesis(
                accounts.map(a => (a.address, a.balance)).toMap,
                m.startingEpochProgress
              )

              hasherSelector.withCurrent { implicit hasher =>
                Signed
                  .forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
                  .flatMap(_.toHashed[IO])
              }.flatMap { hashedGenesis =>
                GlobalSnapshotLocalFileSystemStorage.make[IO](cfg.snapshot.snapshotPath).flatMap {
                  fullGlobalSnapshotLocalFileSystemStorage =>
                    hasherSelector.withCurrent { implicit hasher =>
                      fullGlobalSnapshotLocalFileSystemStorage.write(hashedGenesis.signed) >>
                        GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis).flatMap { firstIncrementalSnapshot =>
                          Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).flatMap {
                            signedFirstIncrementalSnapshot =>
                              for {
                                _ <- services.collateral
                                  .hasCollateral(nodeShared.nodeId)
                                  .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA)
                                hashedSnapshot <- signedFirstIncrementalSnapshot.toHashed[IO]
                                globalSnapshotInfo = hashedGenesis.info.toGlobalSnapshotInfo
                                _ <- initializeStorages[IO](
                                  storages.globalSnapshot,
                                  sharedStorages.lastNGlobalSnapshot,
                                  sharedStorages.lastGlobalSnapshot,
                                  programs.download,
                                  hashedSnapshot,
                                  globalSnapshotInfo
                                )
                                kvPairs <- globalSnapshotInfo.allStateEntries[IO](
                                  Async[IO],
                                  Parallel[IO],
                                  hasher,
                                  jsonSerializer,
                                  globalStateProofSelector
                                )
                                _ <- sharedStorages.mptStore.syncFull(kvPairs, hashedSnapshot.ordinal)

                                genesisSigners = signedFirstIncrementalSnapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                                // Genesis path — a fresh chain start, always classify as bootstrap.
                                // Seed window with the genesis snapshot's proof count (typically 1 for
                                // genesis, which correctly triggers warmup for the initial cluster bring-up).
                                genesisRecentProofSizes = SortedMap(
                                  signedFirstIncrementalSnapshot.ordinal -> signedFirstIncrementalSnapshot.proofs.size.toInt
                                )
                                _ <- services.consensus.manager
                                  .startFacilitatingAfterRollback(
                                    signedFirstIncrementalSnapshot.ordinal,
                                    GlobalConsensusOutcome(
                                      signedFirstIncrementalSnapshot.ordinal,
                                      Facilitators(genesisSigners),
                                      RemovedFacilitators.empty,
                                      WithdrawnFacilitators.empty,
                                      EligibleFacilitators(genesisSigners),
                                      Finished(
                                        signedFirstIncrementalSnapshot,
                                        hashedGenesis.info.toGlobalSnapshotInfo,
                                        EventTrigger,
                                        Candidates.empty,
                                        Hash.empty,
                                        hashedSnapshot.hash
                                      ),
                                      recentProofSizes = genesisRecentProofSizes
                                    ),
                                    // Genesis is also a checkpoint-serving bootstrap. If the
                                    // genesis node starts round 2 immediately, it can finalize a
                                    // self-only snapshot before joining validators register, which
                                    // makes `lastSigners` and Core collapse to one peer forever.
                                    // Deferring gives validators time to download the genesis
                                    // outcome, promote Ready, and register as candidates.
                                    startPolicy = RollbackStartPolicy.LegacyDeferred
                                  )
                              } yield ()
                          }
                        }
                    }
                }
              }
            }
          } >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready) >>
            services.restart.setClusterLeaveRestartMethod(
              RunValidator(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                m.allowanceListPath
              )
            ) >>
            services.restart.setNodeForkedRestartMethod(
              RunValidatorWithJoinAttempt(
                m.keyStore,
                m.alias,
                m.password,
                m.dbConfig,
                m.httpConfig,
                m.environment,
                m.seedlistPath,
                m.collateralAmount,
                m.trustRatingsPath,
                m.prioritySeedlistPath,
                _,
                m.allowanceListPath
              )
            )
      }).asResource
    } yield ()
  }
}
