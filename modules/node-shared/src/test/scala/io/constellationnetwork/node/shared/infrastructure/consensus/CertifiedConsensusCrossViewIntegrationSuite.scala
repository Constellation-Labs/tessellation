package io.constellationnetwork.node.shared.infrastructure.consensus

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ViewChangeCertificate, ViewChangeVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ViewChangeCertificateBuilder
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{Previous, QuorumPolicy}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import monocle.Lens
import weaver.MutableIOSuite

/** Aug 11 regression at the shared protocol boundary.
  *
  * Four independent production ConsensusStorage instances and real node keys model one selective-delivery/view-change execution. The two
  * candidate values deliberately carry the same artifact hash but different responder evidence. V35 must either preserve the complete
  * certified value across the view change or refuse to vote; artifact equality alone is not enough.
  */
object CertifiedConsensusCrossViewIntegrationSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      serializer <- Resource.eval(JsonSerializer.forAsync[IO])
      provider <- SecurityProvider.forAsync[IO]
      implicit0(json: JsonSerializer[IO]) = serializer
      hasher = Hasher.forJson[IO]
    } yield (serializer, hasher, provider)

  @derive(eqv, encoder, decoder)
  final case class PersistedCertifiedOutcome(
    key: SnapshotOrdinal,
    artifactHash: Hash,
    value: ProposalValue,
    certificate: CertifiedOutcome
  )

  private implicit val outcomeKeyLens: Lens[PersistedCertifiedOutcome, SnapshotOrdinal] =
    Lens[PersistedCertifiedOutcome, SnapshotOrdinal](_.key)(key => _.copy(key = key))

  private type NodeStorage =
    ConsensusStorage[IO, String, SnapshotOrdinal, String, Unit, String, PersistedCertifiedOutcome, String]

  private final case class TestNode(name: String, keyPair: KeyPair, peerId: PeerId, storage: NodeStorage)

  private final case class World(nodes: List[TestNode]) {
    private val byName = nodes.map(node => node.name -> node).toMap

    val committee: Set[PeerId] = nodes.map(_.peerId).toSet

    def a: TestNode = byName("A")
    def l: TestNode = byName("L")
    def c: TestNode = byName("C")
    def b: TestNode = byName("B")
  }

  private val genesisKey = SnapshotOrdinal(NonNegLong.unsafeFrom(0L))
  private val roundKey = SnapshotOrdinal(NonNegLong.unsafeFrom(1L))
  private val parentHash = Hash.fromBytes("aug11-parent".getBytes("UTF-8"))
  private val artifactHash = Hash.fromBytes("aug11-shared-artifact".getBytes("UTF-8"))
  private val contextHash = Hash.fromBytes("aug11-shared-context".getBytes("UTF-8"))
  private val configuredFraction = 2.0 / 3.0

  private val consensusConfig = ConsensusConfig(
    timeTriggerInterval = 10.seconds,
    declarationTimeout = 10.seconds,
    declarationRangeLimit = 100L,
    lockDuration = 10.seconds,
    eventCutter = EventCutterConfig(
      maxBinarySizeBytes = PosInt(1024),
      maxUpdateNodeParametersSize = PosInt(1024)
    )
  )

  private def nonEmptyPeers(peers: Iterable[PeerId]): NonEmptySet[PeerId] =
    NonEmptySet.fromSetUnsafe(SortedSet.from(peers))

  private def mkWorld(implicit provider: SecurityProvider[IO]): IO[World] =
    for {
      pairs <- List("A", "L", "C", "B").traverse(name => KeyPairGenerator.makeKeyPair[IO].tupleLeft(name))
      nodes <- pairs.traverse {
        case (name, keyPair) =>
          ConsensusStorage
            .make[IO, String, SnapshotOrdinal, String, Unit, String, PersistedCertifiedOutcome, String](consensusConfig)
            .map(TestNode(name, keyPair, PeerId.fromId(keyPair.getPublic.toId), _))
      }
    } yield World(nodes)

  private def proposalValue(world: World)(implicit hasher: Hasher[IO]): IO[ProposalValue] = {
    val committee = nonEmptyPeers(world.committee)

    Hasher[IO].hash(committee).map { committeeHash =>
      ProposalValue(
        schemaVersion = SchemaVersion,
        domain = ConsensusDomain.DagL0,
        networkId = "integrationnet",
        key = roundKey.value.value,
        parentArtifactHash = parentHash,
        artifactHash = artifactHash,
        contextHash = contextHash,
        roundStartFacilitators = committee,
        roundStartFacilitatorsHash = committeeHash,
        roundStartCore = committee,
        roundStartCoreHash = committeeHash,
        committedView = 0L,
        trigger = EventTrigger,
        admissionNominee = none,
        admittedPeers = SortedSet.empty,
        evictedPeers = SortedSet.empty,
        observedResponders = SortedSet.from(world.committee),
        observedSelfHealth = SortedMap.empty,
        timeoutVoters = SortedSet.empty,
        consensusEndTime = none
      )
    }
  }

  private def buildCertifiedOutcome(world: World, value: ProposalValue)(
    implicit hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[CertifiedOutcome] = {
    val voters = List(world.a, world.l, world.b)

    for {
      prepareVotes <- voters.traverse(node => signOutcomeVote[IO](value, node.keyPair).map(_._2))
      proposalQc <- buildProposalQc[IO](
        value,
        SortedMap.from(voters.map(_.peerId).zip(prepareVotes)),
        world.committee,
        world.committee,
        configuredFraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      commits <- voters.traverse(node => signCoreCommit[IO](proposalQc, node.keyPair))
      commitQc <- buildCoreCommitQc[IO](
        proposalQc,
        SortedMap.from(voters.map(_.peerId).zip(commits)),
        world.committee,
        configuredFraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
    } yield CertifiedOutcome(proposalQc, commitQc)
  }

  private def emitViewChangeVote(node: TestNode, value: ProposalValue)(
    implicit hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[Signed[ViewChangeVote]] =
    for {
      lock <- node.storage.getCertifiedVoteLock(roundKey)
      vote = ViewChangeVote(
        fromView = 0L,
        toView = 1L,
        facilitatorsHash = value.roundStartFacilitatorsHash,
        lastSnapshotHash = value.parentArtifactHash,
        highestKnownQc = none,
        highestKnownCertifiedQc = lock.flatMap(_.lockedQc)
      )
      signed <- Signed.forAsyncHasher[IO, ViewChangeVote](vote, node.keyPair)
    } yield signed

  private def assembleVcc(
    node: TestNode,
    world: World,
    value: ProposalValue,
    votes: List[(TestNode, Signed[ViewChangeVote])]
  ): IO[ViewChangeCertificate] =
    for {
      _ <- votes.traverse_ {
        case (origin, vote) => node.storage.addViewChangeVote(origin.peerId, roundKey, 0L, 1L, vote).void
      }
      resources <- node.storage.getResources(roundKey)
      accumulated = resources.viewChangeVotes.getOrElse((0L, 1L), Map.empty)
      certificate <- IO.fromEither(
        ViewChangeCertificateBuilder
          .build(
            fromView = 0L,
            toView = 1L,
            facilitatorsHash = value.roundStartFacilitatorsHash,
            lastSnapshotHash = value.parentArtifactHash,
            votes = accumulated,
            quorumSize = QuorumPolicy.supermajority(world.committee.size),
            witnessPool = world.committee
          )
          .leftMap(error => new IllegalStateException(error.toString))
      )
    } yield certificate

  private def persist(
    node: TestNode,
    value: ProposalValue,
    certificate: CertifiedOutcome
  ): IO[(Boolean, PersistedCertifiedOutcome)] = {
    val outcome = PersistedCertifiedOutcome(roundKey, value.artifactHash, value, certificate)

    node.storage
      .trySetInitialConsensusOutcome(outcome.copy(key = genesisKey))
      .flatMap(_ => node.storage.tryUpdateLastConsensusOutcomeWithCleanup(Previous(genesisKey), outcome))
      .tupleRight(outcome)
  }

  test("Aug 11: a later view preserves the certified outcome or refuses the same-artifact divergent value") { res =>
    implicit val serializer: JsonSerializer[IO] = res._1
    implicit val hasher: Hasher[IO] = res._2
    implicit val provider: SecurityProvider[IO] = res._3

    for {
      world <- mkWorld
      value0 <- proposalValue(world)
      certified0 <- buildCertifiedOutcome(world, value0)
      verified0 <- verifyOutcome[IO](certified0, world.committee, world.committee, configuredFraction)

      // View 0: A finalizes the complete value. L learns the prepare QC but not the completed outcome.
      _ <- List(world.a, world.l).traverse_ { node =>
        node.storage.addCertifiedProposalQc(roundKey, certified0.proposalQc).void >>
          node.storage.advanceCertifiedLockedQc(roundKey, certified0.proposalQc)
      }
      persistedAtView0 <- persist(world.a, value0, certified0)

      // View change: one honest carrier is sufficient because the real VCC itself has a Core quorum.
      lVote <- emitViewChangeVote(world.l, value0)
      cVote <- emitViewChangeVote(world.c, value0)
      bVote <- emitViewChangeVote(world.b, value0)
      vcc <- assembleVcc(world.c, world, value0, List(world.l -> lVote, world.c -> cVote, world.b -> bVote))
      carried <- highestVerifiedProposalQc[IO](
        proposalQcCandidates(vcc.some, none),
        world.committee,
        world.committee,
        configuredFraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      carriedQc <- IO.fromOption(carried)(new IllegalStateException("VCC did not carry the certified value"))

      // Same artifact, different responder evidence: this is the exact Aug 11 divergence class.
      divergent = value0.copy(
        committedView = 1L,
        observedResponders = value0.observedResponders - world.a.peerId
      )
      divergentHash <- valueHash[IO](divergent)
      divergentValidation <- validateValue[IO](
        actual = divergent,
        expected = value0,
        carriedQc = carriedQc.some,
        outerView = 1L,
        parentEndTime = none,
        viewInterval = 10.seconds,
        maxRoundDuration = 2.minutes.some,
        frozenCommittee = world.committee,
        frozenCore = world.committee,
        configuredFraction = configuredFraction
      )
      lDivergentLock <- world.l.storage.tryLockCertifiedVote(roundKey, 1L, divergentHash, carriedQc.some)
      cDivergentLock <- world.c.storage.tryLockCertifiedVote(roundKey, 1L, divergentHash, carriedQc.some)

      // The later-view leader re-proposes the embedded value unchanged. The outer view advances;
      // committedView intentionally remains the view whose Core QC certified the value.
      safeValidation <- validateValue[IO](
        actual = carriedQc.value,
        expected = value0,
        carriedQc = carriedQc.some,
        outerView = 1L,
        parentEndTime = none,
        viewInterval = 10.seconds,
        maxRoundDuration = 2.minutes.some,
        frozenCommittee = world.committee,
        frozenCore = world.committee,
        configuredFraction = configuredFraction
      )
      cSafeLock <- world.c.storage.tryLockCertifiedVote(
        roundKey,
        carriedQc.value.committedView,
        carriedQc.valueHash,
        carriedQc.some
      )
      verifiedAtView1 <- verifyOutcome[IO](certified0, world.committee, world.committee, configuredFraction)
      persistedAtView1 <- persist(world.c, carriedQc.value, certified0)

      aLast <- world.a.storage.getLastConsensusOutcome
      cLast <- world.c.storage.getLastConsensusOutcome
      aBytes <- aLast.traverse(JsonSerializer[IO].serialize(_))
      cBytes <- cLast.traverse(JsonSerializer[IO].serialize(_))
    } yield
      expect.all(
        verified0 === Right(()),
        persistedAtView0._1,
        vcc.votes.size === QuorumPolicy.supermajority(world.committee.size).toLong,
        lVote.value.highestKnownCertifiedQc.contains(certified0.proposalQc),
        carried.contains(certified0.proposalQc),
        divergent.artifactHash === value0.artifactHash,
        divergentHash =!= certified0.proposalQc.valueHash,
        divergentValidation.left.exists(error =>
          error === "proposal_value_semantics_mismatch" || error === "certified_value_carry_forward_mismatch"
        ),
        lDivergentLock.left.exists(_.code === "locked_on_qc"),
        cDivergentLock.left.exists(_.code === "locked_on_qc"),
        safeValidation.contains(value0),
        cSafeLock.isRight,
        verifiedAtView1 === Right(()),
        persistedAtView1._1,
        aLast === cLast,
        aBytes.exists(left => cBytes.exists(right => left.sameElements(right)))
      )
  }
}
