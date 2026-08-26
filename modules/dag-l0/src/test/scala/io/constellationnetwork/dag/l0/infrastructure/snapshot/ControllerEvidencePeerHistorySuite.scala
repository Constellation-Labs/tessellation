package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus.ConsensusDomain
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Proposal
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.consensus.{CertifiedConsensus, ControllerEvidenceDerivation}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Printer
import io.circe.parser.decode
import io.circe.syntax._
import weaver.MutableIOSuite

/** Stage 4 regression coverage for the controller-evidence peerHistory plumbing on the REAL `GlobalConsensusOutcome`.
  *
  *   - Round trip: outcome -> `toOperationalState` -> circe encode/decode (exactly what `PeerHistorySidecarStorage` does) -> seed-style
  *     extraction (the Main.scala `seedOperational` reads) preserves `controllerEvidence` and `penaltyUntil` byte-identically across the
  *     cold-restart boundary.
  *   - Signed-bytes divergence: two outcomes that agree on the deterministic chain-derived windows but carry DIFFERENT per-peer operational
  *     state (poisoned restart seed, the alpha.92/129/147 wedge class) produce byte-identical `signedArtifactPeerHistory` payloads -- the
  *     carried divergence cannot reach the proposal-critical bytes.
  */
object ControllerEvidencePeerHistorySuite extends MutableIOSuite {
  implicit val globalStateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    implicit0(js: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (js, h, sp)

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')

  private def entry(roundStart: Set[PeerId], signers: Set[PeerId]): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.from(roundStart),
      completedSigners = SortedSet.from(signers),
      timeoutVoters = SortedSet.empty,
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty
    )

  private val evidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
    SortedMap.from((10L to 14L).map { o =>
      val signers = if (o == 10L) Set(a, b, c) else Set(a, b)
      ord(o) -> entry(Set(a, b, c), signers)
    })

  private val penaltyUntil: SortedMap[PeerId, SnapshotOrdinal] =
    SortedMap(c -> ord(120L))

  private val recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
    evidence.map { case (o, en) => o -> en.completedSigners }

  private val recentProofSizes: SortedMap[SnapshotOrdinal, Int] =
    evidence.map { case (o, en) => o -> en.completedSigners.size }

  private def mkFinished(
    implicit sp: SecurityProvider[IO],
    h: Hasher[IO],
    js: JsonSerializer[IO]
  ): IO[Finished] = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
    signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
    lastArtifact <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
    signedLastArtifact <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair)
    snapshotHash <- signedLastArtifact.toHashed.map(_.hash)
  } yield
    Finished(
      signedLastArtifact,
      signedGenesis.value.info.toGlobalSnapshotInfo,
      EventTrigger,
      Candidates.empty,
      Hash.empty,
      snapshotHash
    )

  private def withSignedPeerHistory(finished: Finished, peerHistory: ConsensusOperationalState)(
    implicit sp: SecurityProvider[IO],
    h: Hasher[IO]
  ): IO[Finished] =
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      artifact = finished.signedMajorityArtifact.value.copy(peerHistory = Some(peerHistory))
      signed <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](artifact, keyPair)
      snapshotHash <- Hasher[IO].hash(artifact)
    } yield finished.copy(signedMajorityArtifact = signed, snapshotHash = snapshotHash)

  private def mkOutcome(
    finished: Finished,
    peerQuality: SortedMap[PeerId, (Int, Int)],
    activeAdmissionScores: SortedMap[PeerId, Int],
    peerTiers: SortedMap[PeerId, Int],
    recentRoundEndTimes: SortedMap[SnapshotOrdinal, Long],
    readmissionCountdown: SortedMap[PeerId, Int] = SortedMap.empty
  ): GlobalConsensusOutcome =
    GlobalConsensusOutcome(
      key = ord(14L),
      facilitators = Facilitators(List(a, b, c)),
      removedFacilitators = RemovedFacilitators.empty,
      withdrawnFacilitators = WithdrawnFacilitators.empty,
      eligibleFacilitators = EligibleFacilitators(List(a, b, c)),
      finished = finished,
      peerQuality = peerQuality,
      recentProofSizes = recentProofSizes,
      readmissionCountdown = readmissionCountdown,
      recentSigners = recentSigners,
      peerTiers = peerTiers,
      activeAdmissionScores = activeAdmissionScores,
      recentRoundEndTimes = recentRoundEndTimes,
      controllerEvidence = Some(evidence),
      penaltyUntil = Some(penaltyUntil)
    )

  test("outcome -> toOperationalState -> sidecar-style encode/decode -> seed-style extraction preserves the evidence fields") { res =>
    implicit val (js, h, sp) = res

    for {
      finished <- mkFinished
      outcome = mkOutcome(
        finished,
        peerQuality = SortedMap(a -> (5, 5), b -> (5, 5), c -> (1, 5)),
        activeAdmissionScores = SortedMap(a -> 150, b -> 150, c -> 60),
        peerTiers = SortedMap(a -> 2, b -> 2, c -> 1),
        recentRoundEndTimes = SortedMap(ord(14L) -> 1700000000000L)
      )
      operational = outcome.toOperationalState
      // PeerHistorySidecarStorage.write encodes with `asJson.noSpaces`; mirror it exactly.
      encoded = operational.asJson.noSpaces
      decoded = decode[ConsensusOperationalState](encoded)
      // Main.scala seedOperational extraction: Option-shaped end to end, normalized non-empty.
      seededEvidence = decoded.toOption.flatMap(_.controllerEvidence.filter(_.nonEmpty))
      seededPenaltyUntil = decoded.toOption.flatMap(_.penaltyUntil.filter(_.nonEmpty))
    } yield
      expect(decoded.isRight, s"sidecar-style decode failed: $decoded") &&
        expect(decoded.contains(operational), "decoded operational state differs from the encoded one") &&
        expect.same(Some(evidence), seededEvidence) &&
        expect.same(Some(penaltyUntil), seededPenaltyUntil) &&
        // And the seeded outcome (what startFacilitatingAfterRollback receives) carries
        // the identical windows forward.
        expect.same(outcome.controllerEvidence, seededEvidence) &&
        expect.same(outcome.penaltyUntil, seededPenaltyUntil)
  }

  test("a strict superset of artifact proofs at pack time yields byte-identical signedArtifactPeerHistory") { res =>
    implicit val (js, h, sp) = res

    // Ordinal-3150166 regression: `signedMajorityArtifact.proofs` accretes asymmetrically
    // (a fast finalizer packs quorum-many proofs while a slower one already holds a strict
    // superset; SnapshotStorage.prepend never merges later arrivals). The outcome built by
    // either node must still expose identical signed peerHistory bytes -- the proofs set has
    // no path into `signedArtifactPeerHistory`.
    val printer = Printer.noSpaces.copy(dropNullValues = true)

    for {
      keyPairs <- List.range(0, 4).traverse(_ => KeyPairGenerator.makeKeyPair[IO])
      genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPairs.head)
      lastArtifact <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
      signedPerKey <- keyPairs.traverse(kp => Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](lastArtifact, kp))
      allProofs = signedPerKey.flatMap(_.proofs.toNonEmptyList.toList)
      quorumProofs = NonEmptySet.fromSetUnsafe(SortedSet.from(allProofs.take(3)))
      supersetProofs = NonEmptySet.fromSetUnsafe(SortedSet.from(allProofs))
      fastFinalizerArtifact = Signed(lastArtifact, quorumProofs)
      slowFinalizerArtifact = Signed(lastArtifact, supersetProofs)
      snapshotHash <- fastFinalizerArtifact.toHashed.map(_.hash)
      info = signedGenesis.value.info.toGlobalSnapshotInfo
      finishedOf = (artifact: Signed[GlobalIncrementalSnapshot]) =>
        Finished(artifact, info, EventTrigger, Candidates.empty, Hash.empty, snapshotHash)
      sharedFields = (
        SortedMap(a -> (5, 5), b -> (5, 5), c -> (1, 5)),
        SortedMap(a -> 150, b -> 150, c -> 60),
        SortedMap(a -> 2, b -> 2, c -> 1),
        SortedMap(ord(14L) -> 1700000000000L)
      )
      nodeAOutcome = mkOutcome(finishedOf(fastFinalizerArtifact), sharedFields._1, sharedFields._2, sharedFields._3, sharedFields._4)
      nodeBOutcome = mkOutcome(finishedOf(slowFinalizerArtifact), sharedFields._1, sharedFields._2, sharedFields._3, sharedFields._4)
      signedA = nodeAOutcome.signedArtifactPeerHistory
      signedB = nodeBOutcome.signedArtifactPeerHistory
    } yield
      // The proofs divergence is real (strict superset)...
      expect(quorumProofs.toSortedSet.subsetOf(supersetProofs.toSortedSet)) &&
        expect(quorumProofs =!= supersetProofs) &&
        expect(nodeAOutcome.finished.signedMajorityArtifact.proofs =!= nodeBOutcome.finished.signedMajorityArtifact.proofs) &&
        // ...but the signed peerHistory payloads are identical, structurally and byte-for-byte.
        expect.same(signedA, signedB) &&
        expect.same(signedA.asJson.printWith(printer), signedB.asJson.printWith(printer))
  }

  test("sticky zero probation survives sidecar packing without entering signed artifact bytes") { res =>
    implicit val (js, h, sp) = res

    for {
      finished <- mkFinished
      outcome = mkOutcome(
        finished,
        peerQuality = SortedMap(a -> (5, 5), b -> (5, 5), c -> (1, 5)),
        activeAdmissionScores = SortedMap(a -> 150, b -> 150, c -> 60),
        peerTiers = SortedMap(a -> 2, b -> 2, c -> 1),
        recentRoundEndTimes = SortedMap(ord(14L) -> 1700000000000L),
        readmissionCountdown = SortedMap(c -> 0)
      )
      operational = outcome.toOperationalState
      decoded = decode[ConsensusOperationalState](operational.asJson.noSpaces)
      seededReadmission = decoded.toOption
        .map(state =>
          SortedMap.from(state.perPeer.collect {
            case (pid, record) if record.readmissionCountdown > 0 => pid -> record.readmissionCountdown
          })
        )
        .getOrElse(SortedMap.empty[PeerId, Int])
    } yield
      expect.same(Some(1), operational.perPeer.get(c).map(_.readmissionCountdown)) &&
        expect.same(SortedMap(c -> 1), seededReadmission) &&
        expect(outcome.signedArtifactPeerHistory.perPeer.isEmpty)
  }

  test("divergent carried perPeer state yields byte-identical signedArtifactPeerHistory for the same evidence") { res =>
    implicit val (js, h, sp) = res

    // Production snapshot serialization drops null values; compare with the same printer.
    val printer = Printer.noSpaces.copy(dropNullValues = true)

    for {
      finished <- mkFinished
      // Healthy node: carried state matches reality.
      healthy = mkOutcome(
        finished,
        peerQuality = SortedMap(a -> (5, 5), b -> (5, 5), c -> (1, 5)),
        activeAdmissionScores = SortedMap(a -> 150, b -> 150, c -> 60),
        peerTiers = SortedMap(a -> 2, b -> 2, c -> 1),
        recentRoundEndTimes = SortedMap(ord(14L) -> 1700000000000L)
      )
      // Restarted node with a poisoned local seed: same deterministic windows, divergent
      // carried perPeer dimensions AND divergent local time anchors.
      poisoned = mkOutcome(
        finished,
        peerQuality = SortedMap(a -> (5, 5), b -> (1, 9), c -> (9, 9)),
        activeAdmissionScores = SortedMap(a -> 150, b -> 10, c -> 150),
        peerTiers = SortedMap(a -> 2, b -> 0, c -> 2),
        recentRoundEndTimes = SortedMap(ord(14L) -> 1700000099999L)
      )
      healthySigned = healthy.signedArtifactPeerHistory
      poisonedSigned = poisoned.signedArtifactPeerHistory
    } yield
      // The carried divergence is real (full operational states differ)...
      expect(healthy.toOperationalState != poisoned.toOperationalState) &&
        // ...but the signed payloads are identical, structurally and byte-for-byte.
        expect.same(healthySigned, poisonedSigned) &&
        expect.same(healthySigned.asJson.printWith(printer), poisonedSigned.asJson.printWith(printer)) &&
        // The locally-divergent fields never enter the signed payload.
        expect(healthySigned.perPeer.isEmpty) &&
        expect(healthySigned.recentRoundEndTimes.isEmpty) &&
        // The deterministic windows do.
        expect.same(Some(evidence), healthySigned.controllerEvidence) &&
        expect.same(Some(penaltyUntil), healthySigned.penaltyUntil) &&
        expect.same(Some(recentSigners), healthySigned.recentSigners) &&
        expect.same(recentProofSizes, healthySigned.recentProofSizes)
  }

  test("the v35 activation bridge flushes divergent legacy sidecars before deriving the first certified value") { res =>
    implicit val (js, h, sp) = res

    for {
      rawFinished <- mkFinished
      seedCarrier = mkOutcome(
        rawFinished,
        peerQuality = SortedMap.empty,
        activeAdmissionScores = SortedMap.empty,
        peerTiers = SortedMap.empty,
        recentRoundEndTimes = SortedMap.empty
      )
      finished <- withSignedPeerHistory(rawFinished, seedCarrier.signedArtifactPeerHistory)
      healthy = mkOutcome(
        finished,
        peerQuality = SortedMap(a -> (5, 5), b -> (5, 5), c -> (1, 5)),
        activeAdmissionScores = SortedMap(a -> 150, b -> 150, c -> 60),
        peerTiers = SortedMap(a -> 2, b -> 2, c -> 1),
        recentRoundEndTimes = SortedMap(ord(14L) -> 1700000000000L)
      )
      poisoned = mkOutcome(
        finished,
        peerQuality = SortedMap(a -> (1, 9), b -> (9, 9), c -> (9, 10)),
        activeAdmissionScores = SortedMap(a -> 5, b -> 200, c -> 200),
        peerTiers = SortedMap(a -> 0, b -> 2, c -> 2),
        recentRoundEndTimes = SortedMap(ord(14L) -> 1700000099999L)
      ).copy(
        recentProofSizes = SortedMap(ord(14L) -> 1),
        recentSigners = SortedMap(ord(14L) -> SortedSet(c)),
        controllerEvidence = Some(SortedMap(ord(14L) -> entry(Set(a, b, c), Set(c)))),
        penaltyUntil = Some(SortedMap(a -> ord(999L)))
      )
      resetHealthy <- GlobalSnapshotConsensusStateCreator.resetLegacyOutcome[IO](ord(15L), healthy, 2.0 / 3.0)
      resetPoisoned <- GlobalSnapshotConsensusStateCreator.resetLegacyOutcome[IO](ord(15L), poisoned, 2.0 / 3.0)
      seed = NonEmptySet.fromSetUnsafe(SortedSet.from(resetHealthy.facilitators.value))
      proposal = Proposal(
        hash = finished.snapshotHash,
        facilitatorsHash = resetHealthy.finished.facilitatorsHash,
        lastSnapshotHash = finished.snapshotHash,
        view = 0L,
        vcc = None
      )
      authorityHealthy <- CertifiedConsensus.roundAuthority[IO](seed, seed)
      valueHealthy <- CertifiedConsensus.proposalValue[IO, GlobalSnapshotContext](
        domain = ConsensusDomain.DagL0,
        networkId = "integrationnet",
        key = 15L,
        parentArtifactHash = finished.snapshotHash,
        artifactHash = finished.snapshotHash,
        context = finished.context,
        roundStartFacilitators = seed,
        roundStartCore = seed,
        nextRoundAuthority = authorityHealthy,
        nextOperationalStateHash = Hash.empty,
        committedView = 0L,
        trigger = EventTrigger,
        proposal = proposal,
        consensusEndTime = None
      )
      poisonedSeed = NonEmptySet.fromSetUnsafe(SortedSet.from(resetPoisoned.facilitators.value))
      authorityPoisoned <- CertifiedConsensus.roundAuthority[IO](poisonedSeed, poisonedSeed)
      valuePoisoned <- CertifiedConsensus.proposalValue[IO, GlobalSnapshotContext](
        domain = ConsensusDomain.DagL0,
        networkId = "integrationnet",
        key = 15L,
        parentArtifactHash = finished.snapshotHash,
        artifactHash = finished.snapshotHash,
        context = finished.context,
        roundStartFacilitators = poisonedSeed,
        roundStartCore = poisonedSeed,
        nextRoundAuthority = authorityPoisoned,
        nextOperationalStateHash = Hash.empty,
        committedView = 0L,
        trigger = EventTrigger,
        proposal = proposal,
        consensusEndTime = None
      )
      healthyHash <- CertifiedConsensus.valueHash[IO](valueHealthy)
      poisonedHash <- CertifiedConsensus.valueHash[IO](valuePoisoned)
    } yield
      expect.all(
        healthy.toOperationalState =!= poisoned.toOperationalState,
        resetHealthy === resetPoisoned,
        resetHealthy.facilitators.value === ControllerEvidenceDerivation.nextCommittee(evidence.last._2).toList,
        resetHealthy.facilitators.value.size === 3,
        resetHealthy.controllerEvidence.contains(SortedMap.empty[SnapshotOrdinal, ControllerEvidenceEntry]),
        resetHealthy.penaltyUntil.contains(SortedMap.empty[PeerId, SnapshotOrdinal]),
        resetHealthy.recentSigners.isEmpty,
        resetHealthy.peerTiers.isEmpty,
        valueHealthy === valuePoisoned,
        healthyHash === poisonedHash
      )
  }

  test("the v35 activation bridge rejects a canonical singleton seed that cannot admit a second seat") { res =>
    implicit val (js, h, sp) = res

    for {
      rawFinished <- mkFinished
      carrier = mkOutcome(
        rawFinished,
        peerQuality = SortedMap.empty,
        activeAdmissionScores = SortedMap.empty,
        peerTiers = SortedMap.empty,
        recentRoundEndTimes = SortedMap.empty
      )
      singletonHistory = carrier.signedArtifactPeerHistory.copy(
        controllerEvidence = Some(SortedMap(ord(14L) -> entry(Set(a), Set(a))))
      )
      finished <- withSignedPeerHistory(rawFinished, singletonHistory)
      outcome = carrier.copy(finished = finished)
      result <- GlobalSnapshotConsensusStateCreator.resetLegacyOutcome[IO](ord(15L), outcome, 2.0 / 3.0).attempt
    } yield expect(result.left.exists(_.getMessage.contains("minimum coordinated size=2")))
  }
}
