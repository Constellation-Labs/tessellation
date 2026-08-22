package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome, Finished}
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipClient
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{LastSentGlobalSnapshotSyncStorage, OrdinalJsonSidecarStorage}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import io.circe.{Decoder, Encoder, Json}
import weaver.MutableIOSuite

/** Currency half of the v35 proof-sufficiency dynamic gate.
  *
  * Public frames carry only a bounded binary proof envelope. Each lifecycle reconstructs the exact StateChannelSnapshotBinary from the
  * public signed artifact through the repository JsonSerializer, verifies its complete frozen-committee proof set, and then invokes the
  * production Currency outcome transition. No run may read an archive or a process-local consensus sidecar.
  */
object CurrencyCertifiedLineageReplaySuite extends MutableIOSuite {

  @derive(encoder, decoder)
  final case class PublicFrame(
    artifact: Signed[CurrencyIncrementalSnapshot],
    context: CurrencySnapshotContext,
    certifiedOutcome: CertifiedOutcome,
    triggerEvidence: List[Signed[TriggerStatement]],
    binaryEvidence: CertifiedLayerEvidenceV1.Currency,
    roundStartFacilitators: NonEmptySet[PeerId],
    roundStartCore: NonEmptySet[PeerId]
  )

  private final case class Observation(
    proposalValueBytes: Vector[Byte],
    proposalValueHash: Hash,
    outcomeBytes: Vector[Byte],
    artifactBytes: Vector[Byte],
    rewardsBytes: Vector[Byte],
    binaryBytes: Vector[Byte],
    binaryHash: Hash,
    nextFacilitators: List[PeerId],
    nextCore: List[PeerId],
    nextTier1: List[PeerId],
    expandedBeyondSingleton: Boolean
  )

  private final case class Script(
    responders: Set[Int],
    selfHealth: SortedMap[Int, SelfHealthHint] = SortedMap.empty,
    admitted: Option[Int] = None,
    evicted: Option[Int] = None,
    timeoutVoters: Set[Int] = Set.empty,
    view: Long = 0L,
    triggerTime: Boolean = false,
    forceRoundStart: Option[List[Int]] = None,
    forceRoundStartCore: Option[List[Int]] = None,
    qcRotation: Int = 0
  )

  final case class Res(
    serializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    selector: HasherSelector[IO],
    provider: SecurityProvider[IO],
    pairs: List[KeyPair]
  )

  override def sharedResource: Resource[IO, Res] =
    for {
      serializer <- Resource.eval(JsonSerializer.forAsync[IO])
      provider <- SecurityProvider.forAsync[IO]
      implicit0(json: JsonSerializer[IO]) = serializer
      hasher = Hasher.forJson[IO]
      pairs <- Resource.eval {
        implicit val securityProvider: SecurityProvider[IO] = provider
        List.fill(5)(KeyPairGenerator.makeKeyPair[IO]).sequence
      }
    } yield Res(serializer, hasher, HasherSelector.forSyncAlwaysCurrent(hasher), provider, pairs)

  private val config = ConsensusConfig(
    timeTriggerInterval = 43.seconds,
    declarationTimeout = 60.seconds,
    declarationRangeLimit = 100L,
    lockDuration = 10.seconds,
    eventCutter = EventCutterConfig(
      maxBinarySizeBytes = PosInt(1024),
      maxUpdateNodeParametersSize = PosInt(1024)
    ),
    quorumThresholdFraction = 2.0 / 3.0,
    tighteningWindow = 3,
    qualityDecayThreshold = 2,
    activeAdmissionMaxExpansionPerRound = 1,
    certifiedConsensusActivationKey = CertifiedConsensusGenesis.FirstIncrementalOrdinal.value.value
  )

  private implicit val metrics: Metrics[IO] = NoOpMetrics.make
  private implicit val stateProofSelector: CurrencyStateProofSelector = CurrencyStateProofSelector.instance
  // The outcome transition under test never serializes mempool events. Explicit inert codecs
  // keep the harness independent of an optional data-application plugin.
  private implicit val eventEncoder: Encoder[CurrencySnapshotEvent] = Encoder.instance(_ => Json.Null)
  private implicit val eventDecoder: Decoder[CurrencySnapshotEvent] = Decoder.failedWithMessage("unused in lineage replay harness")

  private def unused[A]: A = null.asInstanceOf[A]

  private def peer(pair: KeyPair): PeerId = PeerId.fromId(pair.getPublic.toId)

  private def nonEmpty(peers: Iterable[PeerId]): NonEmptySet[PeerId] =
    NonEmptySet.fromSetUnsafe(SortedSet.from(peers))

  private def signWith[A: io.circe.Encoder](value: A, pairs: List[KeyPair])(
    implicit hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[Signed[A]] =
    pairs.traverse(Signed.forAsyncHasher[IO, A](value, _)).map { signed =>
      Signed(value, NonEmptySet.fromSetUnsafe(SortedSet.from(signed.map(_.proofs.head))))
    }

  private def childArtifact(
    ordinal: SnapshotOrdinal,
    parentHash: Hash,
    prior: CurrencyConsensusOutcome
  ): CurrencyIncrementalSnapshot =
    CurrencyIncrementalSnapshot(
      ordinal = ordinal,
      height = Height.MinValue,
      subHeight = SubHeight.MinValue,
      lastSnapshotHash = parentHash,
      blocks = SortedSet.empty,
      rewards = SortedSet.empty[RewardTransaction],
      tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof = prior.finished.signedMajorityArtifact.value.stateProof,
      epochProgress = EpochProgress.MinValue,
      dataApplication = None,
      messages = None,
      globalSnapshotSyncs = None,
      feeTransactions = None,
      artifacts = None,
      allowSpendBlocks = None,
      tokenLockBlocks = None,
      globalSyncView = None,
      peerHistory = Some(prior.signedArtifactPeerHistory),
      certifiedLineage = (prior.finished.certifiedOutcome, prior.finished.certifiedBinary).mapN {
        case (outcome, binary) =>
          CertifiedLineageEvidenceV1(
            outcome,
            CertifiedLayerEvidenceV1
              .Currency(
                binary.value.lastSnapshotHash,
                binary.value.fee,
                binary.proofs
              )
              .some
          )
      }
    )

  private def signedRoot(pairs: List[KeyPair])(
    implicit serializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[CurrencyConsensusOutcome] = {
    val address = Address.fromBytes(Array[Byte](1, 2, 3))
    val genesis = CurrencySnapshot.mkGenesis(Map.empty, None, None)

    for {
      signedGenesis <- Signed.forAsyncHasher[IO, CurrencySnapshot](genesis, pairs.head)
      hashedGenesis <- signedGenesis.toHashed[IO]
      value <- CurrencySnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
      artifact <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](value, pairs.head)
      content <- serializer.serialize(artifact)
      binary <- Signed.forAsyncHasher[IO, StateChannelSnapshotBinary](
        StateChannelSnapshotBinary(Hash.empty, content, SnapshotFee.MinValue),
        pairs.head
      )
      hashedArtifact <- artifact.toHashed[IO]
      hashedBinary <- binary.toHashed[IO]
      context = CurrencySnapshotContext(address, hashedGenesis.info.toCurrencySnapshotInfo)
      seeded = CurrencyCertifiedGenesisOutcome.seed(artifact, hashedBinary, context, hashedArtifact.hash)
    } yield
      seeded.copy(
        readmissionCountdown = SortedMap(peer(pairs(2)) -> 2),
        expandedBeyondSingleton = Some(false)
      )
  }

  private def advancer(keyPair: KeyPair)(
    implicit serializer: JsonSerializer[IO],
    hasherSelector: HasherSelector[IO],
    provider: SecurityProvider[IO]
  ): CurrencySnapshotConsensusStateAdvancer[IO] =
    CurrencySnapshotConsensusStateAdvancer.make[IO](
      consensusConfig = config,
      networkId = "integrationnet-metagraph",
      keyPair = keyPair,
      consensusStorage = unused[CurrencyConsensusStorage[IO]],
      consensusFns = unused[CurrencySnapshotConsensusFunctions[IO]],
      stateChannelSnapshotService = unused[StateChannelSnapshotService[IO]],
      gossip = unused[Gossip[IO]],
      maybeDataApplication = None: Option[BaseDataApplicationL0Service[IO]],
      restartService = unused[RestartService[IO, CliMethod]],
      nodeStorage = unused[NodeStorage[IO]],
      leavingDelay = 1.second,
      getGlobalSnapshotByOrdinal = _ => IO.pure(None),
      clusterStorageInstance = unused[ClusterStorage[IO]],
      eventMempool = unused[EventMempool[IO, CurrencySnapshotEvent, CurrencyStateKey]],
      eventGossipClient = unused[EventGossipClient[IO, CurrencySnapshotEvent]],
      facilitatorSelector = FacilitatorSelector.make(None),
      lastGlobalSnapshotSyncStorage = unused[LastSentGlobalSnapshotSyncStorage[IO]]
    )

  private def selectRotated[A](values: List[A], required: Int, rotation: Int): List[A] = {
    val normalized = if (values.isEmpty) values else values.drop(rotation % values.size) ++ values.take(rotation % values.size)
    normalized.take(required)
  }

  private def buildFrame(
    prior: CurrencyConsensusOutcome,
    script: Script,
    allPairs: List[KeyPair]
  )(
    implicit serializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[PublicFrame] = {
    val ids = allPairs.map(peer)
    val byId = allPairs.map(pair => peer(pair) -> pair).toMap
    val inherited = prior.facilitators.value
    val roundStart = script.forceRoundStart.fold(inherited)(_.map(ids))
    val roundStartSet = roundStart.toSet
    val core = script.forceRoundStartCore.fold(roundStart)(_.map(ids))
    val admitted = script.admitted.map(ids).toSet
    val evicted = script.evicted.map(ids).toSet
    val ordinal = prior.key.next
    val context = prior.finished.context
    val responders = script.responders.toList.map(ids).toSet.intersect(roundStartSet)
    val health = SortedMap.from(script.selfHealth.toList.map { case (index, hint) => ids(index) -> hint }).filter {
      case (id, _) => responders.contains(id)
    }
    val timeoutVoters = script.timeoutVoters.toList.map(ids).toSet.intersect(roundStartSet)
    val trigger = if (script.triggerTime) TimeTrigger else EventTrigger

    for {
      stateProof <- context.snapshotInfo.stateProof[IO](ordinal)
      artifactValue = childArtifact(ordinal, prior.finished.snapshotHash, prior).copy(stateProof = stateProof)
      // Currency v35 intentionally requires complete artifact and binary proof envelopes.
      signedArtifact <- signWith(artifactValue, roundStart.map(byId))
      artifactHash <- Hasher[IO].hash(artifactValue)
      contextHash <- Hasher[IO].hash(context)
      fullHash <- Hasher[IO].hash(nonEmpty(roundStart))
      coreHash <- Hasher[IO].hash(nonEmpty(core))
      value = ProposalValue(
        SchemaVersion,
        ConsensusDomain.CurrencyL0,
        "integrationnet-metagraph",
        ordinal.value.value,
        prior.finished.snapshotHash,
        artifactHash,
        contextHash,
        nonEmpty(roundStart),
        fullHash,
        nonEmpty(core),
        coreHash,
        script.view,
        trigger,
        script.admitted.map(ids),
        SortedSet.from(admitted),
        SortedSet.from(evicted),
        SortedSet.from(responders),
        health,
        SortedSet.from(timeoutVoters),
        Some(2000000L + ordinal.value.value)
      )
      preparePairs = selectRotated(core.map(byId), requiredCoreQuorum(core.size, config.quorumThresholdFraction), script.qcRotation)
      prepareVotes <- preparePairs.traverse(signOutcomeVote[IO](value, _).map(_._2))
      proposalQc <- buildProposalQc[IO](
        value,
        SortedMap.from(preparePairs.map(peer).zip(prepareVotes)),
        roundStartSet,
        core.toSet,
        config.quorumThresholdFraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      commitPairs = selectRotated(core.map(byId), requiredCoreQuorum(core.size, config.quorumThresholdFraction), script.qcRotation + 1)
      commits <- commitPairs.traverse(signCoreCommit[IO](proposalQc, _))
      commitQc <- buildCoreCommitQc[IO](
        proposalQc,
        SortedMap.from(commitPairs.map(peer).zip(commits)),
        core.toSet,
        config.quorumThresholdFraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      triggerStatements <- roundStart
        .map(byId)
        .traverse(pair =>
          signTriggerStatement[IO](
            triggerStatement(
              ConsensusDomain.CurrencyL0,
              "integrationnet-metagraph",
              ordinal.value.value,
              prior.finished.snapshotHash,
              fullHash,
              config.deterministicConfigHash,
              trigger.some
            ),
            pair
          )
        )
      binaryContent <- serializer.serialize(signedArtifact)
      signedBinary <- signWith(
        StateChannelSnapshotBinary(prior.finished.binaryArtifactHash, binaryContent, SnapshotFee.MinValue),
        roundStart.map(byId)
      )
    } yield
      PublicFrame(
        signedArtifact,
        context,
        CertifiedOutcome(proposalQc, commitQc),
        triggerStatements,
        currencyLayerEvidence(signedBinary),
        nonEmpty(roundStart),
        nonEmpty(core)
      )
  }

  private def validateCurrencyTransition(
    roundStart: Set[PeerId],
    admitted: Set[PeerId],
    evicted: Set[PeerId]
  ): Either[String, Unit] =
    for {
      _ <- Either.cond(admitted.intersect(evicted).isEmpty, (), "currency_admit_evict_overlap")
      _ <- Either.cond(admitted.intersect(roundStart).isEmpty, (), "currency_admitted_already_seated")
      _ <- Either.cond(evicted.subsetOf(roundStart), (), "currency_evicted_not_seated")
      _ <- Either.cond(admitted.size <= config.activeAdmissionMaxExpansionPerRound, (), "currency_admission_over_cap")
      _ <- Either.cond(evicted.size <= config.activeAdmissionMaxExpansionPerRound, (), "currency_eviction_over_cap")
    } yield ()

  private def derive(
    prior: CurrencyConsensusOutcome,
    frame: PublicFrame,
    stateAdvancer: CurrencySnapshotConsensusStateAdvancer[IO]
  )(
    implicit serializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[(CurrencyConsensusOutcome, Observation)] = {
    val value = frame.certifiedOutcome.proposalQc.value
    val roundStart = frame.roundStartFacilitators.toSortedSet.toList
    val core = frame.roundStartCore.toSortedSet.toList
    val admitted = value.admittedPeers.toSet
    val evicted = value.evictedPeers.toSet

    for {
      artifactHash <- Hasher[IO].hash(frame.artifact.value)
      bound <- verifyBoundOutcome[IO, CurrencySnapshotContext](
        frame.certifiedOutcome,
        ConsensusDomain.CurrencyL0,
        "integrationnet-metagraph",
        frame.artifact.ordinal.value.value,
        prior.finished.snapshotHash,
        artifactHash,
        frame.context,
        frame.roundStartFacilitators,
        frame.roundStartCore,
        config.quorumThresholdFraction,
        prior.recentRoundEndTimes.lastOption.map(_._2),
        config.viewInterval,
        config.maxRoundDuration
      )
      triggerAuthorization <- validateTriggerEvidence[IO](
        frame.triggerEvidence,
        ConsensusDomain.CurrencyL0,
        "integrationnet-metagraph",
        frame.artifact.ordinal.value.value,
        prior.finished.snapshotHash,
        value.roundStartFacilitatorsHash,
        config.deterministicConfigHash,
        roundStart.toSet,
        requiredArtifactQuorum(roundStart.size, core.size, config.quorumThresholdFraction),
        value.trigger,
        roundStart.head
      )
      artifactProofs <- verifyArtifactProofs[IO, CurrencyIncrementalSnapshot](
        frame.artifact,
        roundStart.toSet,
        roundStart.size
      )
      reconstructed <- reconstructAndVerifyCurrencyBinary[IO, CurrencyIncrementalSnapshot](
        frame.artifact,
        frame.binaryEvidence,
        prior.finished.binaryArtifactHash,
        roundStart.toSet
      )
      parentLineageValidation <- verifyCarriedParentOutcome[IO](
        frame.artifact.value.certifiedLineage,
        prior.finished.certifiedOutcome,
        ConsensusDomain.CurrencyL0,
        config.quorumThresholdFraction
      )
      parentLineage <- IO.fromEither(parentLineageValidation.leftMap(new IllegalStateException(_)))
      parentBinary <- (prior.finished.certifiedBinary, parentLineage).tupled.traverse {
        case (trustedBinary, carried) =>
          carried.parentLayerEvidence match {
            case Some(evidence: CertifiedLayerEvidenceV1.Currency) =>
              val parentFrozenCommittee = carried.parentOutcome.proposalQc.value.roundStartFacilitators.toSortedSet.toSet
              reconstructAndVerifyCurrencyBinary[IO, CurrencyIncrementalSnapshot](
                prior.finished.signedMajorityArtifact,
                evidence,
                trustedBinary.value.lastSnapshotHash,
                parentFrozenCommittee
              ).flatMap(
                _.flatMap(binary =>
                  Either.cond(
                    binary.hash === prior.finished.binaryArtifactHash,
                    (),
                    "parent_binary_hash_mismatch"
                  )
                ).pure[IO]
              )
            case _ => "currency_parent_layer_evidence_missing".asLeft[Unit].pure[IO]
          }
      }.map(_.sequence_)
      _ <- IO.fromEither(bound.leftMap(new IllegalStateException(_)))
      _ <- IO.fromEither(triggerAuthorization.leftMap(new IllegalStateException(_)))
      _ <- IO.fromEither(artifactProofs.leftMap(new IllegalStateException(_)))
      _ <- IO.fromEither(parentBinary.leftMap(new IllegalStateException(_)))
      binary <- IO.fromEither(reconstructed.leftMap(new IllegalStateException(_)))
      _ <- IO.fromEither(validateCurrencyTransition(roundStart.toSet, admitted, evicted).leftMap(new IllegalStateException(_)))
      _ <- IO.raiseUnless(frame.artifact.value.peerHistory.contains(prior.signedArtifactPeerHistory))(
        new IllegalStateException("public Currency artifact peerHistory was not derived from the trusted parent")
      )
      snapshotHash <- Hasher[IO].hash(frame.artifact.value)
      state: CurrencySnapshotConsensusState =
        ConsensusState[CurrencySnapshotKey, CurrencySnapshotStatus, CurrencyConsensusOutcome, CurrencyConsensusKind](
          key = frame.artifact.ordinal,
          lastOutcome = prior,
          facilitators = Facilitators(roundStart),
          roundStartFacilitators = Facilitators(roundStart),
          status = Finished(
            frame.artifact,
            binary.hash,
            frame.context,
            value.trigger,
            Candidates(value.admissionNominee.toSet),
            value.roundStartFacilitatorsHash,
            snapshotHash,
            Some(frame.certifiedOutcome),
            Some(binary.signed)
          ),
          createdAt = Duration.Zero,
          removedFacilitators = RemovedFacilitators(evicted),
          admittedFacilitators = AdmittedFacilitators(admitted),
          observedResponders = ObservedResponders(value.observedResponders.toSet),
          observedSelfHealth = ObservedSelfHealth(value.observedSelfHealth),
          acceptedTimeoutCertificateVoters = value.timeoutVoters,
          certifiedEvictionTargets = value.evictedPeers,
          coreFacilitators = CoreFacilitators(core),
          tier1Facilitators = Tier1Facilitators(roundStart.filterNot(core.toSet)),
          outcomeEndTime = value.consensusEndTime,
          leader = roundStart.head,
          viewNumber = value.committedView.toInt,
          initialViewNumber = 0,
          entropy = prior.finished.snapshotHash,
          certifiedConsensusActive = true
        )
      next <- IO.fromOption(stateAdvancer.getConsensusOutcome(state).map(_._2))(
        new IllegalStateException("production Currency outcome transition rejected frame")
      )
      expectedNext = ConsensusPeerController.applyNextRoundCertifiedMembership(
        roundStart,
        admitted,
        Some(value.evictedPeers)
      )
      _ <- IO.raiseUnless(next.facilitators.value === expectedNext)(
        new IllegalStateException("Currency certified membership projection diverged")
      )
      committees = CommitteeBuilder.build(
        next.facilitators.value,
        next.peerTiers,
        next.peerQuality,
        coreFloor = 2,
        minObservations = config.minParticipationObservations,
        minRatio = config.minParticipationRatio
      )
      proposalBytes <- serializer.serialize(value)
      outcomeBytes <- serializer.serialize(next)
      artifactBytes <- serializer.serialize(frame.artifact)
      rewardsBytes <- serializer.serialize(frame.artifact.value.rewards)
      binaryBytes <- serializer.serialize(binary.signed)
      valueHash <- CertifiedConsensus.valueHash[IO](value)
    } yield
      next -> Observation(
        proposalBytes.toVector,
        valueHash,
        outcomeBytes.toVector,
        artifactBytes.toVector,
        rewardsBytes.toVector,
        binaryBytes.toVector,
        binary.hash,
        next.facilitators.value,
        committees.core,
        committees.tier1,
        next.expandedBeyondSingleton.contains(true)
      )
  }

  test("warm, restart-every-round and fresh-root Currency replay derive byte-identical certified lineage") { res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val hasherSelector: HasherSelector[IO] = res.selector
    implicit val provider: SecurityProvider[IO] = res.provider

    val scripts = List(
      Script(responders = Set(0), admitted = Some(1)),
      Script(responders = Set(0, 1), admitted = Some(2), qcRotation = 1),
      Script(
        responders = Set(0, 1, 2),
        selfHealth = SortedMap(0 -> SelfHealthHint.Healthy, 1 -> SelfHealthHint.Degraded, 2 -> SelfHealthHint.Critical),
        timeoutVoters = Set(0, 2),
        view = 1L,
        triggerTime = true,
        qcRotation = 2
      ),
      // Currency deliberately retains its certified-contraction policy; it does not inherit
      // DAG's denominator-neutral atomic replacement rule.
      Script(responders = Set(0, 2), evicted = Some(1), qcRotation = 1),
      Script(responders = Set(0, 2), admitted = Some(3), qcRotation = 1),
      Script(responders = Set(0, 2, 3), timeoutVoters = Set(0, 3), view = 2L, triggerTime = true, qcRotation = 2),
      // A later public eligibility projection may reduce the round to one member. The certified
      // monotonic fact must remain true and the from-genesis 1 -> 2 bypass must stay disabled.
      Script(responders = Set(0), forceRoundStart = Some(List(0)))
    )

    for {
      root <- signedRoot(res.pairs)
      stateAdvancer = advancer(res.pairs.head)
      built <- scripts.foldM((root, List.empty[PublicFrame])) {
        case ((prior, frames), script) =>
          buildFrame(prior, script, res.pairs).flatMap { frame =>
            derive(prior, frame, stateAdvancer).map { case (next, _) => next -> (frames :+ frame) }
          }
      }
      frames = built._2
      runs <- CertifiedLineageReplayHarness.execute(root, frames)(derive(_, _, stateAdvancer))
      finalOutcome = built._1
      laterSingletonBypass = CertifiedConsensusGenesis.allowsSingletonBootstrapExpansion(
        certifiedConsensusActive = true,
        config.certifiedConsensusActivationKey,
        currentCommitteeSize = 1,
        finalOutcome.expandedBeyondSingleton.getOrElse(false)
      )
    } yield
      expect.all(
        runs.warm == runs.restartEveryRound,
        runs.warm == runs.freshSequentialReplay,
        runs.warm.size === scripts.size,
        runs.warm.last.expandedBeyondSingleton,
        !laterSingletonBypass,
        finalOutcome.recentSigners.size <= config.tighteningWindow,
        finalOutcome.recentRoundEndTimes.size <= config.tighteningWindow,
        finalOutcome.controllerEvidence.exists(_.size <= config.tighteningWindow),
        finalOutcome.readmissionCountdown.get(peer(res.pairs(2))).isEmpty
      )
  }

  test("production download validator reconstructs public root-to-tip lineage and fails closed on a missing interior artifact") { res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val hasherSelector: HasherSelector[IO] = res.selector
    implicit val provider: SecurityProvider[IO] = res.provider

    val scripts = List(
      Script(responders = Set(0), admitted = Some(1)),
      Script(responders = Set(0, 1), admitted = Some(2), forceRoundStartCore = Some(List(0)), qcRotation = 1)
    )
    val emptySidecar = new OrdinalJsonSidecarStorage[IO, CurrencyConsensusOutcome] {
      def write(ordinal: SnapshotOrdinal, value: CurrencyConsensusOutcome): IO[Unit] = IO.unit
      def read(ordinal: SnapshotOrdinal): IO[Option[CurrencyConsensusOutcome]] = IO.pure(None)
      def delete(ordinal: SnapshotOrdinal): IO[Unit] = IO.unit
      def deleteAbove(ordinal: SnapshotOrdinal): IO[Unit] = IO.unit
      def retain(
        cutoffOrdinal: SnapshotOrdinal,
        currentOrdinal: SnapshotOrdinal,
        pinnedOrdinals: Set[SnapshotOrdinal]
      ): IO[Unit] = IO.unit
    }

    for {
      seededRoot <- signedRoot(res.pairs)
      // The activation flush deliberately discards legacy process-local windows. Keep this
      // root to the exact public/proof-derived shape reconstructed by a fresh downloader.
      root = seededRoot.copy(readmissionCountdown = SortedMap.empty)
      stateAdvancer = advancer(res.pairs.head)
      built <- scripts.foldM((root, List.empty[PublicFrame])) {
        case ((prior, frames), script) =>
          buildFrame(prior, script, res.pairs).flatMap { frame =>
            derive(prior, frame, stateAdvancer).map { case (next, _) => next -> (frames :+ frame) }
          }
      }
      candidate = built._1
      frames = built._2
      artifacts = Map.from(
        (root.key -> root.finished.signedMajorityArtifact) :: frames.map(frame => frame.artifact.ordinal -> frame.artifact)
      )
      infos = Map.from(
        (root.key -> root.finished.context.snapshotInfo) :: frames.map(frame => frame.artifact.ordinal -> frame.context.snapshotInfo)
      )
      validator = CurrencyCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 2,
        seedlistPeerIds = Set.empty,
        currencyAddress = root.finished.context.address,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _, _) => IO.pure(true),
        getSnapshot = ordinal => IO.pure(artifacts.get(ordinal)),
        getSnapshotInfo = ordinal => IO.pure(infos.get(ordinal)),
        certifiedOutcomeSidecar = emptySidecar,
        stateAdvancer = stateAdvancer
      )
      valid <- validator(candidate).attempt
      missingInterior = artifacts - frames.head.artifact.ordinal
      missingValidator = CurrencyCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 2,
        seedlistPeerIds = Set.empty,
        currencyAddress = root.finished.context.address,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _, _) => IO.pure(true),
        getSnapshot = ordinal => IO.pure(missingInterior.get(ordinal)),
        getSnapshotInfo = ordinal => IO.pure(infos.get(ordinal)),
        certifiedOutcomeSidecar = emptySidecar,
        stateAdvancer = stateAdvancer
      )
      missing <- missingValidator(candidate).attempt
    } yield {
      val validExpectation = valid match {
        case Right(_)    => success
        case Left(error) => failure(s"public lineage validation unexpectedly failed: ${error.getMessage}")
      }

      validExpectation &&
      expect(
        missing.left.exists(_.getMessage.contains(s"trusted_snapshot_missing:${frames.head.artifact.ordinal.value.value}"))
      )
    }
  }
}
