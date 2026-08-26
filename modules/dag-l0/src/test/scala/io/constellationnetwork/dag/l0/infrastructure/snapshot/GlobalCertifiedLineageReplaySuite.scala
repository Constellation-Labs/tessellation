package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.dag.l0.Main
import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipClient
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.consensus.CertifiedLineageEvidenceV1
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.Signature

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import weaver.MutableIOSuite

/** Dynamic half of the v35 proof-sufficiency gate for Global L0.
  *
  * The production `getConsensusOutcome` transition is driven from public artifacts, contexts and certificates only, then compared across a
  * warm process, a process whose private outcome is serialized/reloaded after every round, and a fresh sequential replay from the trusted
  * root. The scripted sequence exercises every bounded operational window plus DAG's atomic N-to-N replacement.
  */
object GlobalCertifiedLineageReplaySuite extends MutableIOSuite {

  @derive(encoder, decoder)
  final case class PublicFrame(
    artifact: Signed[GlobalIncrementalSnapshot],
    context: GlobalSnapshotInfo,
    certifiedOutcome: CertifiedOutcome,
    triggerEvidence: List[Signed[TriggerStatement]],
    roundStartFacilitators: NonEmptySet[PeerId],
    roundStartCore: NonEmptySet[PeerId]
  )

  private final case class Observation(
    proposalValueBytes: Vector[Byte],
    proposalValueHash: Hash,
    outcomeBytes: Vector[Byte],
    artifactBytes: Vector[Byte],
    rewardsBytes: Vector[Byte],
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
    forceCore: Option[List[Int]] = None,
    proofRotation: Int = 0
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
  private implicit val stateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(Long.MaxValue))

  private def unused[A]: A = null.asInstanceOf[A]

  private def unexpected[A](operation: String): IO[A] =
    IO.raiseError(new IllegalStateException(s"unexpected snapshot-download mutation: $operation"))

  private def replayConsensusFunctions(implicit provider: SecurityProvider[IO]): GlobalSnapshotConsensusFunctions[IO] =
    new GlobalSnapshotConsensusFunctions[IO] {
      def getRequiredCollateral: Amount = Amount.empty

      def getBalance(context: GlobalSnapshotContext, address: Address): IO[Balance] =
        unexpected("getBalance")

      override def facilitatorEligible(context: GlobalSnapshotContext, peerId: PeerId): IO[Boolean] =
        IO.pure(true)

      def validateArtifact(
        lastSignedArtifact: Signed[GlobalSnapshotArtifact],
        lastContext: GlobalSnapshotContext,
        trigger: ConsensusTrigger,
        artifact: GlobalSnapshotArtifact,
        facilitators: Set[PeerId],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => IO[Option[Hashed[GlobalIncrementalSnapshot]]],
        peerHistory: Option[ConsensusOperationalState],
        certifiedLineage: Option[CertifiedLineageEvidenceV1]
      )(implicit hasher: Hasher[IO]): IO[Either[InvalidArtifact, (GlobalSnapshotArtifact, GlobalSnapshotContext)]] =
        unexpected("validateArtifact")

      def createProposalArtifact(
        lastKey: GlobalSnapshotKey,
        lastArtifact: Signed[GlobalSnapshotArtifact],
        lastContext: GlobalSnapshotContext,
        lastArtifactHasher: Hasher[IO],
        trigger: ConsensusTrigger,
        events: Set[GlobalSnapshotEvent],
        facilitators: Set[PeerId],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => IO[Option[Hashed[GlobalIncrementalSnapshot]]],
        peerHistory: Option[ConsensusOperationalState],
        certifiedLineage: Option[CertifiedLineageEvidenceV1]
      )(implicit hasher: Hasher[IO]): IO[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])] =
        unexpected("createProposalArtifact")
    }

  private def publicDownloadStorage(
    artifacts: Map[SnapshotOrdinal, Signed[GlobalIncrementalSnapshot]],
    contexts: Map[SnapshotOrdinal, GlobalSnapshotInfo]
  ): SnapshotDownloadStorage[IO] =
    new SnapshotDownloadStorage[IO] {
      def readPersisted(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] =
        IO.pure(artifacts.get(ordinal))
      def readTmp(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] = IO.pure(None)
      def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): IO[Unit] = unexpected("writeTmp")
      def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): IO[Unit] = unexpected("writePersisted")
      def deletePersisted(ordinal: SnapshotOrdinal): IO[Unit] = unexpected("deletePersisted")
      def ensurePersistedAnchor(hash: Hash, ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Boolean] =
        unexpected("ensurePersistedAnchor")
      def hasCorrectSnapshotInfo(ordinal: SnapshotOrdinal, proof: GlobalSnapshotStateProof)(
        implicit hasher: Hasher[IO]
      ): IO[Boolean] = unexpected("hasCorrectSnapshotInfo")
      def getHighestSnapshotInfoOrdinal(lte: SnapshotOrdinal): IO[Option[SnapshotOrdinal]] =
        unexpected("getHighestSnapshotInfoOrdinal")
      def readCombined(ordinal: SnapshotOrdinal)(
        implicit hasher: Hasher[IO],
        stateProofSelector: StateProofSelector
      ): IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = unexpected("readCombined")
      def readCombinedValidated(ordinal: SnapshotOrdinal)(
        implicit hasher: Hasher[IO],
        stateProofSelector: StateProofSelector
      ): IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        IO.pure((artifacts.get(ordinal), contexts.get(ordinal)).tupled)
      def readCombinedValidatedAtProofOrdinal(ordinal: SnapshotOrdinal, proofOrdinal: SnapshotOrdinal)(
        implicit hasher: Hasher[IO],
        stateProofSelector: StateProofSelector
      ): IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        readCombinedValidated(ordinal)
      def persistSnapshotInfoWithCutoff(ordinal: SnapshotOrdinal, info: GlobalSnapshotInfo): IO[Unit] =
        unexpected("persistSnapshotInfoWithCutoff")
      def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): IO[Unit] = unexpected("movePersistedToTmp")
      def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): IO[Unit] = unexpected("moveTmpToPersisted")
      def readGenesis(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalSnapshot]]] = unexpected("readGenesis")
      def writeGenesis(genesis: Signed[GlobalSnapshot]): IO[Unit] = unexpected("writeGenesis")
      def cleanupAbove(ordinal: SnapshotOrdinal): IO[Unit] = unexpected("cleanupAbove")
    }

  private def peer(pair: KeyPair): PeerId = PeerId.fromId(pair.getPublic.toId)

  private val emptyStateProof = GlobalSnapshotStateProof(
    lastStateChannelSnapshotHashesProof = Hash.empty,
    lastTxRefsProof = Hash.empty,
    balancesProof = Hash.empty,
    lastCurrencySnapshotsProof = None,
    activeAllowSpends = None,
    activeTokenLocks = None,
    tokenLockBalances = None,
    lastAllowSpendRefs = None,
    lastTokenLockRefs = None,
    updateNodeParameters = None,
    activeDelegatedStakes = None,
    delegatedStakesWithdrawals = None,
    activeNodeCollaterals = None,
    nodeCollateralWithdrawals = None,
    priceState = None,
    lastGlobalSnapshotsWithCurrency = None,
    mptRoot = None
  )

  private def nonEmpty(peers: Iterable[PeerId]): NonEmptySet[PeerId] =
    NonEmptySet.fromSetUnsafe(SortedSet.from(peers))

  private def signWith[A: io.circe.Encoder](value: A, pairs: List[KeyPair])(
    implicit hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[Signed[A]] =
    pairs.traverse(Signed.forAsyncHasher[IO, A](value, _)).map { signed =>
      Signed(value, NonEmptySet.fromSetUnsafe(SortedSet.from(signed.map(_.proofs.head))))
    }

  private def artifact(
    ordinal: SnapshotOrdinal,
    parentHash: Hash,
    prior: GlobalConsensusOutcome,
    rewardCommittee: List[PeerId]
  ): GlobalIncrementalSnapshot =
    GlobalIncrementalSnapshot(
      ordinal = ordinal,
      height = Height.MinValue,
      subHeight = SubHeight.MinValue,
      lastSnapshotHash = parentHash,
      blocks = SortedSet.empty,
      stateChannelSnapshots = SortedMap.empty,
      rewards = SortedSet.empty[RewardTransaction],
      delegateRewards = Some(SortedMap.from(rewardCommittee.map(_ -> SortedMap.empty[Address, Amount]))),
      epochProgress = EpochProgress.MinValue,
      // Historical compatibility field: production proposal construction deliberately writes
      // this fixed singleton. Certified membership lives in ProposalValue/outcome, not here.
      nextFacilitators = GlobalSnapshot.nextFacilitators,
      tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof = emptyStateProof,
      allowSpendBlocks = Some(SortedSet.empty),
      tokenLockBlocks = Some(SortedSet.empty),
      spendActions = Some(SortedMap.empty),
      updateNodeParameters = Some(SortedMap.empty),
      artifacts = Some(SortedSet.empty),
      activeDelegatedStakes = Some(SortedMap.empty),
      delegatedStakesWithdrawals = Some(SortedMap.empty),
      activeNodeCollaterals = Some(SortedMap.empty),
      nodeCollateralWithdrawals = Some(SortedMap.empty),
      peerHistory = Some(prior.signedArtifactPeerHistory),
      certifiedLineage = prior.finished.certifiedOutcome.map(CertifiedLineageEvidenceV1(_))
    )

  private def signedRoot(pairs: List[KeyPair])(
    implicit serializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[GlobalConsensusOutcome] = {
    val rootCommittee = List(peer(pairs.head))

    for {
      rootStateProof <- GlobalSnapshotInfo.empty.stateProof[IO](CertifiedConsensusGenesis.FirstIncrementalOrdinal)
      value = GlobalIncrementalSnapshot(
        ordinal = CertifiedConsensusGenesis.FirstIncrementalOrdinal,
        height = Height.MinValue,
        subHeight = SubHeight.MinValue,
        lastSnapshotHash = Hash.empty,
        blocks = SortedSet.empty,
        stateChannelSnapshots = SortedMap.empty,
        rewards = SortedSet.empty,
        delegateRewards = Some(SortedMap(peer(pairs.head) -> SortedMap.empty)),
        epochProgress = EpochProgress.MinValue,
        nextFacilitators = NonEmptyList.one(peer(pairs.head)),
        tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
        stateProof = rootStateProof,
        allowSpendBlocks = Some(SortedSet.empty),
        tokenLockBlocks = Some(SortedSet.empty),
        spendActions = Some(SortedMap.empty),
        updateNodeParameters = Some(SortedMap.empty),
        artifacts = Some(SortedSet.empty),
        activeDelegatedStakes = Some(SortedMap.empty),
        delegatedStakesWithdrawals = Some(SortedMap.empty),
        activeNodeCollaterals = Some(SortedMap.empty),
        nodeCollateralWithdrawals = Some(SortedMap.empty)
      )
      signed <- signWith(value, List(pairs.head))
      snapshotHash <- Hasher[IO].hash(value)
    } yield
      GlobalConsensusOutcome(
        key = value.ordinal,
        facilitators = Facilitators(rootCommittee),
        removedFacilitators = RemovedFacilitators.empty,
        withdrawnFacilitators = WithdrawnFacilitators.empty,
        eligibleFacilitators = EligibleFacilitators.empty,
        finished = Finished(
          signed,
          GlobalSnapshotInfo.empty,
          EventTrigger,
          Candidates.empty,
          Hash.empty,
          snapshotHash
        ),
        readmissionCountdown = SortedMap(peer(pairs(2)) -> 2),
        peerTiers = SortedMap(peer(pairs.head) -> TierTransitions.Core),
        expandedBeyondSingleton = None
      )
  }

  private def advancer(keyPair: KeyPair)(
    implicit serializer: JsonSerializer[IO],
    hasherSelector: HasherSelector[IO],
    provider: SecurityProvider[IO]
  ): GlobalSnapshotConsensusStateAdvancer[IO] =
    GlobalSnapshotConsensusStateAdvancer.make[IO](
      consensusConfig = config,
      networkId = "integrationnet",
      keyPair = keyPair,
      consensusStorage = unused[GlobalConsensusStorage[IO]],
      globalSnapshotStorage = unused[SnapshotStorage[IO, GlobalSnapshotArtifact, GlobalSnapshotContext]],
      consensusFns = replayConsensusFunctions,
      gossip = unused[Gossip[IO]],
      restartService = unused[RestartService[IO, CliMethod]],
      nodeStorage = unused[NodeStorage[IO]],
      leavingDelay = 1.second,
      lastNGlobalSnapshotStorage = unused[LastNGlobalSnapshotStorage[IO]],
      lastGlobalSnapshotStorage = unused[LastSnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo]],
      getGlobalSnapshotByOrdinal = _ => IO.pure(None),
      clusterStorageInstance = unused[ClusterStorage[IO]],
      eventMempool = unused[EventMempool[IO, GlobalSnapshotEvent, GlobalStateKey]],
      eventGossipClient = unused[EventGossipClient[IO, GlobalSnapshotEvent]],
      loggerBundle = unused[LoggerBundle[IO]],
      mptStore = unused[MptStore[IO, GlobalStateKey]],
      facilitatorSelector = FacilitatorSelector.make(None),
      seedlistPeerIds = Set.empty,
      membershipPolicy = HealthDerivedMembershipPolicy.RetainSigningLeases,
      onRecoverySeedOutcomeCommitted = None,
      scheduleSoftResetRestart = _ => IO.unit
    )

  private def selectRotated[A](values: List[A], required: Int, rotation: Int): List[A] = {
    val normalized = if (values.isEmpty) values else values.drop(rotation % values.size) ++ values.take(rotation % values.size)
    normalized.take(required)
  }

  private def buildFrame(
    prior: GlobalConsensusOutcome,
    script: Script,
    allPairs: List[KeyPair]
  )(
    implicit hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[PublicFrame] = {
    val ids = allPairs.map(peer)
    val byId = allPairs.map(pair => peer(pair) -> pair).toMap
    val inherited = prior.facilitators.value
    val roundStart = script.forceRoundStart.fold(inherited)(_.map(ids))
    val roundStartSet = roundStart.toSet
    val core = script.forceCore.fold(roundStart)(_.map(ids))
    val admitted = script.admitted.map(ids).toSet
    val evicted = script.evicted.map(ids).toSet
    val ordinal = prior.key.next
    val context = GlobalSnapshotInfo.empty
    val responders = script.responders.toList.map(ids).toSet.intersect(roundStartSet)
    val health = SortedMap.from(script.selfHealth.toList.map { case (index, hint) => ids(index) -> hint }).filter {
      case (id, _) => responders.contains(id)
    }
    val timeoutVoters = script.timeoutVoters.toList.map(ids).toSet.intersect(roundStartSet)
    val trigger = if (script.triggerTime) TimeTrigger else EventTrigger
    val artifactValue = artifact(ordinal, prior.finished.snapshotHash, prior, roundStart)
    val artifactQuorum = requiredArtifactQuorum(roundStart.size, core.size, config.quorumThresholdFraction)
    val artifactPairs = selectRotated(roundStart.map(byId), artifactQuorum, script.proofRotation)

    for {
      signedArtifact <- signWith(artifactValue, artifactPairs)
      artifactHash <- Hasher[IO].hash(artifactValue)
      contextHash <- Hasher[IO].hash(context)
      fullHash <- Hasher[IO].hash(nonEmpty(roundStart))
      coreHash <- Hasher[IO].hash(nonEmpty(core))
      value = ProposalValue(
        SchemaVersion,
        ConsensusDomain.DagL0,
        "integrationnet",
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
        Some(1000000L + ordinal.value.value)
      )
      preparePairs = selectRotated(core.map(byId), requiredCoreQuorum(core.size, config.quorumThresholdFraction), script.proofRotation)
      prepareVotes <- preparePairs.traverse(signOutcomeVote[IO](value, _).map(_._2))
      proposalQc <- buildProposalQc[IO](
        value,
        SortedMap.from(preparePairs.map(peer).zip(prepareVotes)),
        roundStartSet,
        core.toSet,
        config.quorumThresholdFraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      commitPairs = selectRotated(core.map(byId), requiredCoreQuorum(core.size, config.quorumThresholdFraction), script.proofRotation + 1)
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
              ConsensusDomain.DagL0,
              "integrationnet",
              ordinal.value.value,
              prior.finished.snapshotHash,
              fullHash,
              config.deterministicConfigHash,
              trigger.some
            ),
            pair
          )
        )
    } yield
      PublicFrame(signedArtifact, context, CertifiedOutcome(proposalQc, commitQc), triggerStatements, nonEmpty(roundStart), nonEmpty(core))
  }

  /** Replace only the two QC proof envelopes while retaining the exact artifact, ProposalValue, trigger evidence and round authority. This
    * is the direct same-round proof-subset falsification that the lifecycle harness cannot provide by rotating subsets between rounds.
    */
  private def withCertificateRotation(
    frame: PublicFrame,
    allPairs: List[KeyPair],
    rotation: Int
  )(
    implicit hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[PublicFrame] = {
    val byId = allPairs.map(pair => peer(pair) -> pair).toMap
    val full = frame.roundStartFacilitators.toSortedSet.toList
    val core = frame.roundStartCore.toSortedSet.toList
    val value = frame.certifiedOutcome.proposalQc.value
    val required = requiredCoreQuorum(core.size, config.quorumThresholdFraction)
    val preparePairs = selectRotated(core.map(byId), required, rotation)
    val commitPairs = selectRotated(core.map(byId), required, rotation + 1)

    for {
      prepareVotes <- preparePairs.traverse(signOutcomeVote[IO](value, _).map(_._2))
      proposalQc <- buildProposalQc[IO](
        value,
        SortedMap.from(preparePairs.map(peer).zip(prepareVotes)),
        full.toSet,
        core.toSet,
        config.quorumThresholdFraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      commits <- commitPairs.traverse(signCoreCommit[IO](proposalQc, _))
      commitQc <- buildCoreCommitQc[IO](
        proposalQc,
        SortedMap.from(commitPairs.map(peer).zip(commits)),
        core.toSet,
        config.quorumThresholdFraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
    } yield frame.copy(certifiedOutcome = CertifiedOutcome(proposalQc, commitQc))
  }

  private def derive(
    prior: GlobalConsensusOutcome,
    frame: PublicFrame,
    stateAdvancer: GlobalSnapshotConsensusStateAdvancer[IO]
  )(
    implicit serializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[(GlobalConsensusOutcome, Observation)] = {
    val value = frame.certifiedOutcome.proposalQc.value
    val roundStart = frame.roundStartFacilitators.toSortedSet.toList
    val core = frame.roundStartCore.toSortedSet.toList
    val admitted = value.admittedPeers.toSet
    val evicted = value.evictedPeers.toSet
    val artifactSigners = prior.finished.signedMajorityArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId).toSet
    val singletonException = CertifiedConsensusGenesis.allowsSingletonBootstrapExpansion(
      certifiedConsensusActive = true,
      config.certifiedConsensusActivationKey,
      roundStart.size,
      CertifiedConsensusGenesis.hasExpandedBeyondSingleton(
        config.certifiedConsensusActivationKey,
        prior.key,
        prior.facilitators.value.size,
        prior.expandedBeyondSingleton
      )
    )

    for {
      artifactHash <- Hasher[IO].hash(frame.artifact.value)
      bound <- verifyBoundOutcome[IO, GlobalSnapshotInfo](
        frame.certifiedOutcome,
        ConsensusDomain.DagL0,
        "integrationnet",
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
        ConsensusDomain.DagL0,
        "integrationnet",
        frame.artifact.ordinal.value.value,
        prior.finished.snapshotHash,
        value.roundStartFacilitatorsHash,
        config.deterministicConfigHash,
        roundStart.toSet,
        requiredArtifactQuorum(roundStart.size, core.size, config.quorumThresholdFraction),
        value.trigger,
        roundStart.head
      )
      artifactProofs <- verifyArtifactProofs[IO, GlobalIncrementalSnapshot](
        frame.artifact,
        roundStart.toSet,
        requiredArtifactQuorum(roundStart.size, core.size, config.quorumThresholdFraction)
      )
      parentLineageValidation <- verifyCarriedParentOutcome[IO](
        frame.artifact.value.certifiedLineage,
        prior.finished.certifiedOutcome,
        ConsensusDomain.DagL0,
        config.quorumThresholdFraction
      )
      _ <- IO.fromEither(bound.leftMap(new IllegalStateException(_)))
      _ <- IO.fromEither(triggerAuthorization.leftMap(new IllegalStateException(_)))
      _ <- IO.fromEither(artifactProofs.leftMap(new IllegalStateException(_)))
      _ <- IO.fromEither(parentLineageValidation.leftMap(new IllegalStateException(_)))
      _ <- IO.raiseUnless(frame.artifact.value.peerHistory.contains(prior.signedArtifactPeerHistory))(
        new IllegalStateException("public artifact peerHistory was not derived from the trusted parent")
      )
      _ <- IO.raiseUnless(
        CertifiedMembershipTransition.allowsPrepareVote(
          roundStart.toSet,
          artifactSigners,
          admitted,
          evicted,
          config.quorumThresholdFraction,
          config.activeAdmissionMaxExpansionPerRound,
          singletonException
        )
      )(new IllegalStateException("scripted membership transition was not prepare-vote admissible"))
      snapshotHash <- Hasher[IO].hash(frame.artifact.value)
      state: GlobalSnapshotConsensusState = ConsensusState[
        GlobalSnapshotKey,
        GlobalSnapshotStatus,
        GlobalConsensusOutcome,
        schema.GlobalConsensusKind
      ](
        key = frame.artifact.ordinal,
        lastOutcome = prior,
        facilitators = Facilitators(roundStart),
        roundStartFacilitators = Facilitators(roundStart),
        status = Finished(
          frame.artifact,
          frame.context,
          value.trigger,
          Candidates(value.admissionNominee.toSet),
          value.roundStartFacilitatorsHash,
          snapshotHash,
          Some(frame.certifiedOutcome)
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
        new IllegalStateException("production DAG outcome transition rejected frame")
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
      rewardsBytes <- serializer.serialize(frame.artifact.value.delegateRewards)
      valueHash <- CertifiedConsensus.valueHash[IO](value)
    } yield
      next -> Observation(
        proposalBytes.toVector,
        valueHash,
        outcomeBytes.toVector,
        artifactBytes.toVector,
        rewardsBytes.toVector,
        next.facilitators.value,
        committees.core,
        committees.tier1,
        next.expandedBeyondSingleton.contains(true)
      )
  }

  test("warm, restart-every-round and fresh-root DAG replay derive byte-identical certified lineage") { res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val hasherSelector: HasherSelector[IO] = res.selector
    implicit val provider: SecurityProvider[IO] = res.provider

    val scripts = List(
      Script(responders = Set(0), admitted = Some(1)),
      Script(
        responders = Set(0, 1),
        selfHealth = SortedMap(0 -> SelfHealthHint.Healthy, 1 -> SelfHealthHint.Degraded),
        timeoutVoters = Set(0),
        view = 1L,
        triggerTime = true,
        proofRotation = 1
      ),
      Script(responders = Set(0, 1), admitted = Some(2), proofRotation = 1),
      Script(
        responders = Set(0, 2),
        selfHealth = SortedMap(2 -> SelfHealthHint.Critical),
        admitted = Some(3),
        evicted = Some(1),
        timeoutVoters = Set(0, 2),
        view = 2L,
        triggerTime = true,
        proofRotation = 2
      ),
      Script(responders = Set(0, 2, 3), proofRotation = 1),
      // Public eligibility/selection may later reduce the next round to a singleton. The
      // monotonic fact must stay true and therefore cannot re-arm the 1 -> 2 bootstrap bypass.
      Script(responders = Set(0), forceRoundStart = Some(List(0)), proofRotation = 0)
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
      publicArtifactRoundTrips <- frames.traverse { frame =>
        for {
          bytes <- serializer.serialize(frame.artifact)
          decoded <- serializer.deserialize[Signed[GlobalIncrementalSnapshot]](bytes).flatMap(_.liftTo[IO])
          verified <- decoded.toHashedWithSignatureCheck[IO]
        } yield verified
      }
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
        publicArtifactRoundTrips.forall(_.isRight),
        runs.warm.size === scripts.size,
        runs.warm.last.expandedBeyondSingleton,
        !laterSingletonBypass,
        finalOutcome.recentSigners.size <= config.tighteningWindow,
        finalOutcome.recentRoundEndTimes.size <= config.tighteningWindow,
        finalOutcome.controllerEvidence.exists(_.size <= config.tighteningWindow),
        finalOutcome.readmissionCountdown.get(peer(res.pairs(2))).isEmpty
      )
  }

  test("same-round valid QC proof subsets change only certificate bytes, never derived state") { res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val hasherSelector: HasherSelector[IO] = res.selector
    implicit val provider: SecurityProvider[IO] = res.provider

    // Prime a three-member committee through the real admission transition. Each floor-raising
    // admission is preceded by a completed round proving the larger signer headroom. Three seats
    // are sufficient to produce two different valid 2-of-3 QC subsets.
    val growth = List(
      Script(responders = Set(0), admitted = Some(1)),
      Script(responders = Set(0, 1)),
      Script(responders = Set(0, 1), admitted = Some(2))
    )

    for {
      root <- signedRoot(res.pairs)
      stateAdvancer = advancer(res.pairs.head)
      prior <- growth.foldM(root) { (state, script) =>
        buildFrame(state, script, res.pairs).flatMap(derive(state, _, stateAdvancer).map(_._1))
      }
      base <- buildFrame(prior, Script(responders = Set(0, 1, 2)), res.pairs)
      first <- withCertificateRotation(base, res.pairs, rotation = 0)
      second <- withCertificateRotation(base, res.pairs, rotation = 1)
      firstDerived <- derive(prior, first, stateAdvancer).map(_._1)
      secondDerived <- derive(prior, second, stateAdvancer).map(_._1)
      // The certificate is intentionally retained in Finished for recovery. Remove only that
      // explicitly allowed envelope before comparing the entire derived outcome byte-for-byte.
      firstSemantic = firstDerived.copy(finished = firstDerived.finished.copy(certifiedOutcome = None))
      secondSemantic = secondDerived.copy(finished = secondDerived.finished.copy(certifiedOutcome = None))
      firstSemanticBytes <- serializer.serialize(firstSemantic)
      secondSemanticBytes <- serializer.serialize(secondSemantic)
      firstCertificateBytes <- serializer.serialize(first.certifiedOutcome)
      secondCertificateBytes <- serializer.serialize(second.certifiedOutcome)
      firstPrepareSigners = first.certifiedOutcome.proposalQc.signatures.toSortedSet.toList.map(_.id.toPeerId).toSet
      secondPrepareSigners = second.certifiedOutcome.proposalQc.signatures.toSortedSet.toList.map(_.id.toPeerId).toSet
    } yield
      expect.all(
        first.certifiedOutcome.proposalQc.value === second.certifiedOutcome.proposalQc.value,
        first.certifiedOutcome.proposalQc.valueHash === second.certifiedOutcome.proposalQc.valueHash,
        firstPrepareSigners =!= secondPrepareSigners,
        firstCertificateBytes.toVector =!= secondCertificateBytes.toVector,
        firstSemantic === secondSemantic,
        firstSemanticBytes.toVector === secondSemanticBytes.toVector
      )
  }

  test("production download validator reconstructs public root-to-tip DAG lineage and fails closed on a missing interior artifact") { res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val hasherSelector: HasherSelector[IO] = res.selector
    implicit val provider: SecurityProvider[IO] = res.provider

    val scripts = List(
      Script(responders = Set(0), admitted = Some(1)),
      // A newly admitted signer is forced into Tier 1 for its first round. The public
      // replay projector must reconstruct this exact Core/Tier-1 split from the certified
      // parent instead of trusting a locally inferred all-Core committee.
      Script(responders = Set(0, 1), forceCore = Some(List(0)), proofRotation = 1)
    )

    for {
      seededRoot <- signedRoot(res.pairs)
      rootCommittee = SortedSet.from(seededRoot.finished.signedMajorityArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId))
      root = GlobalRecoverySeedOutcome.seed(
        seededRoot.finished.signedMajorityArtifact,
        seededRoot.finished.context,
        seededRoot.finished.snapshotHash,
        rootCommittee
      )
      stateAdvancer = advancer(res.pairs.head)
      built <- scripts.foldM((root, List.empty[PublicFrame])) {
        case ((prior, frames), script) =>
          buildFrame(prior, script, res.pairs).flatMap { frame =>
            derive(prior, frame, stateAdvancer).map { case (next, _) => next -> (frames :+ frame) }
          }
      }
      candidate = built._1
      frames = built._2
      publicSnapshotHash <- GlobalSnapshotArtifactHasher.currentHash[IO](candidate.finished.signedMajorityArtifact.value)
      substitutedArtifact <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
        candidate.finished.signedMajorityArtifact.value,
        res.pairs.last
      )
      validRollbackBinding = Main.validateCertifiedRollbackOutcomeBindings(
        candidate,
        candidate.finished.signedMajorityArtifact,
        candidate.finished.context,
        publicSnapshotHash
      )
      wrongKeyRollbackBinding = Main.validateCertifiedRollbackOutcomeBindings(
        candidate.copy(key = SnapshotOrdinal.unsafeApply(candidate.key.value.value + 1L)),
        candidate.finished.signedMajorityArtifact,
        candidate.finished.context,
        publicSnapshotHash
      )
      wrongArtifactRollbackBinding = Main.validateCertifiedRollbackOutcomeBindings(
        candidate.copy(finished = candidate.finished.copy(signedMajorityArtifact = substitutedArtifact)),
        candidate.finished.signedMajorityArtifact,
        candidate.finished.context,
        publicSnapshotHash
      )
      wrongHashRollbackBinding = Main.validateCertifiedRollbackOutcomeBindings(
        candidate.copy(finished = candidate.finished.copy(snapshotHash = Hash.fromBytes(Array[Byte](9)))),
        candidate.finished.signedMajorityArtifact,
        candidate.finished.context,
        publicSnapshotHash
      )
      artifacts = Map.from(
        (root.key -> root.finished.signedMajorityArtifact) :: frames.map(frame => frame.artifact.ordinal -> frame.artifact)
      )
      contexts = Map.from((root.key -> root.finished.context) :: frames.map(frame => frame.artifact.ordinal -> frame.context))
      validator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 2,
        seedlistPeerIds = Set.empty,
        allowancePeerIds = None,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(artifacts, contexts),
        stateAdvancer = stateAdvancer
      )
      valid <- validator(candidate).attempt
      missingInterior = artifacts - frames.head.artifact.ordinal
      missingValidator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 2,
        seedlistPeerIds = Set.empty,
        allowancePeerIds = None,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(missingInterior, contexts),
        stateAdvancer = stateAdvancer
      )
      missing <- missingValidator(candidate).attempt
    } yield {
      val validExpectation = valid match {
        case Right(_)    => success
        case Left(error) => failure(s"public lineage validation unexpectedly failed: ${error.getMessage}")
      }
      validExpectation &&
      expect(validRollbackBinding.isRight) &&
      expect(wrongKeyRollbackBinding.isLeft) &&
      expect(wrongArtifactRollbackBinding.isLeft) &&
      expect(wrongHashRollbackBinding.isLeft) &&
      expect(
        missing.left.exists(
          _.getMessage.contains(s"trusted_snapshot_missing:${frames.head.artifact.ordinal.value.value}")
        )
      )
    }
  }

  test("an unconfigured community validator verifies the latest post-v35 env recovery epoch without older history") { res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val hasherSelector: HasherSelector[IO] = res.selector
    implicit val provider: SecurityProvider[IO] = res.provider

    val growToThree = List(
      Script(responders = Set(0), admitted = Some(1)),
      Script(responders = Set(0, 1)),
      Script(responders = Set(0, 1), admitted = Some(2))
    )
    val afterRecovery = List(
      Script(responders = Set(0, 1, 2)),
      Script(responders = Set(0, 1, 2), proofRotation = 1)
    )

    for {
      seededRoot <- signedRoot(res.pairs)
      genesisCommittee = SortedSet.from(seededRoot.finished.signedMajorityArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId))
      root = GlobalRecoverySeedOutcome.seed(
        seededRoot.finished.signedMajorityArtifact,
        seededRoot.finished.context,
        seededRoot.finished.snapshotHash,
        genesisCommittee
      )
      stateAdvancer = advancer(res.pairs.head)
      preRecovery <- growToThree.foldM((root, List.empty[PublicFrame])) {
        case ((prior, frames), script) =>
          buildFrame(prior, script, res.pairs).flatMap { frame =>
            derive(prior, frame, stateAdvancer).map { case (next, _) => next -> (frames :+ frame) }
          }
      }
      publicParent = preRecovery._1
      recoveryCommittee = SortedSet.from(res.pairs.take(3).map(peer))
      recoveryRoot = GlobalRecoverySeedOutcome.seed(
        publicParent.finished.signedMajorityArtifact,
        publicParent.finished.context,
        publicParent.finished.snapshotHash,
        recoveryCommittee
      )
      postRecovery <- afterRecovery.foldM((recoveryRoot, List.empty[PublicFrame])) {
        case ((prior, frames), script) =>
          buildFrame(prior, script, res.pairs).flatMap { frame =>
            derive(prior, frame, stateAdvancer).map { case (next, _) => next -> (frames :+ frame) }
          }
      }
      firstRecoveryTip = postRecovery._1
      secondRecoveryRoot = GlobalRecoverySeedOutcome.seed(
        firstRecoveryTip.finished.signedMajorityArtifact,
        firstRecoveryTip.finished.context,
        firstRecoveryTip.finished.snapshotHash,
        recoveryCommittee
      )
      latestRecovery <- afterRecovery.foldM((secondRecoveryRoot, List.empty[PublicFrame])) {
        case ((prior, frames), script) =>
          buildFrame(prior, script, res.pairs).flatMap { frame =>
            derive(prior, frame, stateAdvancer).map { case (next, _) => next -> (frames :+ frame) }
          }
      }
      candidate = latestRecovery._1
      firstSuccessor <- derive(secondRecoveryRoot, latestRecovery._2.head, stateAdvancer).map(_._1)
      alternateTerminalArtifact <- signWith(
        candidate.finished.signedMajorityArtifact.value,
        res.pairs.take(2)
      )
      alternateTerminalCandidate = candidate.copy(
        finished = candidate.finished.copy(signedMajorityArtifact = alternateTerminalArtifact)
      )
      underQuorumTerminalArtifact <- signWith(
        candidate.finished.signedMajorityArtifact.value,
        res.pairs.take(1)
      )
      underQuorumTerminalCandidate = candidate.copy(
        finished = candidate.finished.copy(signedMajorityArtifact = underQuorumTerminalArtifact)
      )
      invalidTerminalArtifact = Signed(
        candidate.finished.signedMajorityArtifact.value,
        NonEmptySet.fromSetUnsafe(
          SortedSet.from(
            alternateTerminalArtifact.proofs.toList.map(_.copy(signature = Signature(Hex("00"))))
          )
        )
      )
      invalidTerminalCandidate = candidate.copy(
        finished = candidate.finished.copy(signedMajorityArtifact = invalidTerminalArtifact)
      )
      // A community validator is not required to retain the activation-to-recovery prefix.
      // Nor is it required to retain a prior recovery epoch. The latest public reset certificate
      // makes the validated recovery parent the new trust root, so retain only that parent and
      // the contiguous latest segment.
      frames = latestRecovery._2
      artifacts = Map.from(
        (firstRecoveryTip.key -> firstRecoveryTip.finished.signedMajorityArtifact) :: frames.map(frame =>
          frame.artifact.ordinal -> frame.artifact
        )
      )
      contexts = Map.from(
        (firstRecoveryTip.key -> firstRecoveryTip.finished.context) :: frames.map(frame => frame.artifact.ordinal -> frame.context)
      )
      allSeedlisted = res.pairs.map(peer).toSet
      validator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 3,
        seedlistPeerIds = allSeedlisted,
        allowancePeerIds = None,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(artifacts, contexts),
        stateAdvancer = stateAdvancer
      )
      accepted <- validator(candidate).attempt
      alternateTerminalAccepted <- validator(alternateTerminalCandidate).attempt
      underQuorumTerminalRejected <- validator(underQuorumTerminalCandidate).attempt
      invalidTerminalRejected <- validator(invalidTerminalCandidate).attempt
      firstSuccessorArtifacts = Map(
        firstRecoveryTip.key -> firstRecoveryTip.finished.signedMajorityArtifact,
        latestRecovery._2.head.artifact.ordinal -> latestRecovery._2.head.artifact
      )
      firstSuccessorContexts = Map(
        firstRecoveryTip.key -> firstRecoveryTip.finished.context,
        latestRecovery._2.head.artifact.ordinal -> latestRecovery._2.head.context
      )
      firstSuccessorValidator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 3,
        seedlistPeerIds = allSeedlisted,
        allowancePeerIds = None,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(firstSuccessorArtifacts, firstSuccessorContexts),
        stateAdvancer = stateAdvancer
      )
      firstSuccessorAccepted <- firstSuccessorValidator(firstSuccessor).attempt
      missingRecoveryMember = recoveryCommittee.last
      unseedlistedValidator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 3,
        seedlistPeerIds = allSeedlisted - missingRecoveryMember,
        allowancePeerIds = None,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(artifacts, contexts),
        stateAdvancer = stateAdvancer
      )
      rejected <- unseedlistedValidator(candidate).attempt
      allowanceOnlyValidator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 3,
        seedlistPeerIds = Set.empty,
        allowancePeerIds = Some(allSeedlisted),
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(artifacts, contexts),
        stateAdvancer = stateAdvancer
      )
      allowanceOnlyAccepted <- allowanceOnlyValidator(candidate).attempt
      noTrustRootValidator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 3,
        seedlistPeerIds = Set.empty,
        allowancePeerIds = None,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(artifacts, contexts),
        stateAdvancer = stateAdvancer
      )
      noTrustRoot <- noTrustRootValidator(candidate).attempt
      disallowedValidator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 3,
        seedlistPeerIds = allSeedlisted,
        allowancePeerIds = Some(allSeedlisted - missingRecoveryMember),
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(artifacts, contexts),
        stateAdvancer = stateAdvancer
      )
      disallowed <- disallowedValidator(candidate).attempt
      ineligibleValidator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 3,
        seedlistPeerIds = allSeedlisted,
        allowancePeerIds = None,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, peerId) => IO.pure(peerId =!= missingRecoveryMember),
        snapshotDownloadStorage = publicDownloadStorage(artifacts, contexts),
        stateAdvancer = stateAdvancer
      )
      ineligible <- ineligibleValidator(candidate).attempt
    } yield {
      val acceptedExpectation = accepted match {
        case Right(_)    => success
        case Left(error) => failure(s"public recovery-boundary validation unexpectedly failed: ${error.getMessage}")
      }
      val firstSuccessorExpectation = firstSuccessorAccepted match {
        case Right(_)    => success
        case Left(error) => failure(s"first recovery successor unexpectedly failed: ${error.getMessage}")
      }
      val alternateTerminalExpectation = alternateTerminalAccepted match {
        case Right(_)    => success
        case Left(error) => failure(s"valid alternate terminal proof envelope was rejected: ${error.getMessage}")
      }
      val allowanceOnlyExpectation = allowanceOnlyAccepted match {
        case Right(_)    => success
        case Left(error) => failure(s"allowance-only public recovery validation unexpectedly failed: ${error.getMessage}")
      }

      acceptedExpectation &&
      firstSuccessorExpectation &&
      alternateTerminalExpectation &&
      allowanceOnlyExpectation &&
      expect(underQuorumTerminalRejected.left.exists(_.getMessage.contains("artifact_under_quorum"))) &&
      expect(invalidTerminalRejected.left.exists(_.getMessage.contains("invalid_artifact_signature"))) &&
      expect(rejected.left.exists(_.getMessage.contains("recovery_seed_boundary_member_not_seedlisted"))) &&
      expect(noTrustRoot.left.exists(_.getMessage.contains("recovery_seed_boundary_trust_root_unavailable"))) &&
      expect(disallowed.left.exists(_.getMessage.contains("recovery_seed_boundary_member_not_allowed"))) &&
      expect(ineligible.isLeft)
    }
  }

  test("a peer-certified two-member subset cannot manufacture public recovery authority") { res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val hasherSelector: HasherSelector[IO] = res.selector
    implicit val provider: SecurityProvider[IO] = res.provider

    for {
      seededRoot <- signedRoot(res.pairs)
      genesisCommittee = SortedSet.from(seededRoot.finished.signedMajorityArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId))
      root = GlobalRecoverySeedOutcome.seed(
        seededRoot.finished.signedMajorityArtifact,
        seededRoot.finished.context,
        seededRoot.finished.snapshotHash,
        genesisCommittee
      )
      stateAdvancer = advancer(res.pairs.head)
      parentFrame <- buildFrame(root, Script(responders = Set(0)), res.pairs)
      publicParent <- derive(root, parentFrame, stateAdvancer).map(_._1)
      undersized = SortedSet.from(res.pairs.take(2).map(peer))
      invalidRecoveryRoot = GlobalRecoverySeedOutcome.seed(
        publicParent.finished.signedMajorityArtifact,
        publicParent.finished.context,
        publicParent.finished.snapshotHash,
        undersized
      )
      recoveryFrame <- buildFrame(invalidRecoveryRoot, Script(responders = Set(0, 1)), res.pairs)
      candidate <- derive(invalidRecoveryRoot, recoveryFrame, stateAdvancer).map(_._1)
      artifacts = Map(
        publicParent.key -> publicParent.finished.signedMajorityArtifact,
        recoveryFrame.artifact.ordinal -> recoveryFrame.artifact
      )
      contexts = Map(publicParent.key -> publicParent.finished.context, recoveryFrame.artifact.ordinal -> recoveryFrame.context)
      validator = GlobalCertifiedDownloadValidator.make[IO](
        config = config,
        coreCommitteeSize = 3,
        seedlistPeerIds = res.pairs.map(peer).toSet,
        allowancePeerIds = None,
        facilitatorSelector = FacilitatorSelector.make(None),
        isContextEligible = (_, _) => IO.pure(true),
        snapshotDownloadStorage = publicDownloadStorage(artifacts, contexts),
        stateAdvancer = stateAdvancer
      )
      rejected <- validator(candidate).attempt
    } yield expect(rejected.left.exists(_.getMessage.contains("recovery_seed_boundary_committee_too_small:2")))
  }

  test("public replay roots at configured activation A-1 rather than downloaded terminal T-1") { _ =>
    val activation = 100L
    val terminal = SnapshotOrdinal.unsafeApply(150L)

    (
      expect.same(
        Right(SnapshotOrdinal.unsafeApply(99L)),
        GlobalCertifiedDownloadValidator.activationParentOrdinal(activation, terminal)
      ) &&
        expect.same(
          Left("activation_after_downloaded_candidate"),
          GlobalCertifiedDownloadValidator.activationParentOrdinal(151L, terminal)
        )
    ).pure[IO]
  }
}
