package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message.{ConsensusPeerTimeoutVote, ConsensusPeerVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.state.Candidates
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

import io.circe.Printer
import io.circe.parser.decode
import io.circe.syntax._
import weaver.MutableIOSuite

object CertifiedConsensusSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      serializer <- Resource.eval(JsonSerializer.forAsync[IO])
      provider <- SecurityProvider.forAsync[IO]
      implicit0(json: JsonSerializer[IO]) = serializer
      hasher = Hasher.forJson[IO]
    } yield (hasher, provider)

  private def peer(char: Char): PeerId = PeerId(Hex(char.toString * 128))
  private def hash(value: String): Hash = Hash.fromBytes(value.getBytes("UTF-8"))

  private def sizedPeer(index: Int): PeerId =
    PeerId(Hex(f"$index%0128x"))

  private def sizedProof(index: Int): SignatureProof =
    SignatureProof(Id(sizedPeer(index).value), Signature(Hex("ab" * 64)))

  private def invalidateSignature[A](signed: Signed[A]): Signed[A] =
    signed.copy(
      proofs = NonEmptySet.fromSetUnsafe(
        SortedSet.from(signed.proofs.toSortedSet.toList.map(_.copy(signature = Signature(Hex("00")))))
      )
    )

  private val pA = peer('a')
  private val pB = peer('b')
  private val pC = peer('c')
  private val pD = peer('d')

  private final case class LineageFrame(key: Long, lineage: Option[CertifiedLineageEvidenceV1])

  private def nonEmptyPeers(peers: Iterable[PeerId]): NonEmptySet[PeerId] =
    NonEmptySet.fromSetUnsafe(SortedSet.from(peers))

  private def baseValue: ProposalValue =
    ProposalValue(
      schemaVersion = SchemaVersion,
      domain = ConsensusDomain.DagL0,
      networkId = "integrationnet",
      key = 5881764L,
      parentArtifactHash = hash("parent"),
      artifactHash = hash("artifact"),
      contextHash = hash("context"),
      roundStartFacilitators = nonEmptyPeers(List(pD, pB, pA, pC)),
      roundStartFacilitatorsHash = hash("full-committee"),
      roundStartCore = nonEmptyPeers(List(pC, pA, pD, pB)),
      roundStartCoreHash = hash("core-committee"),
      nextRoundAuthority = CertifiedRoundAuthorityV1(
        nonEmptyPeers(List(pD, pB, pA, pC)),
        hash("next-full-committee"),
        nonEmptyPeers(List(pC, pA, pD, pB)),
        hash("next-core-committee")
      ),
      nextOperationalStateHash = hash("next-operational-state"),
      committedView = 2L,
      trigger = EventTrigger,
      admissionNominee = Some(pD),
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty,
      observedResponders = SortedSet(pD, pB, pA, pC),
      observedSelfHealth = SortedMap(pA -> SelfHealthHint.Healthy, pB -> SelfHealthHint.Degraded),
      timeoutVoters = SortedSet(pC, pA, pB),
      consensusEndTime = Some(1770000000000L)
    )

  private def keyPairs(n: Int)(implicit provider: SecurityProvider[IO]): IO[List[java.security.KeyPair]] =
    List.fill(n)(KeyPairGenerator.makeKeyPair[IO]).sequence

  private def certifyOutcome(
    value: ProposalValue,
    pairs: List[java.security.KeyPair],
    ids: List[PeerId]
  )(implicit hasher: Hasher[IO], provider: SecurityProvider[IO]): IO[CertifiedOutcome] =
    for {
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      proposal <- buildProposalQc[IO](
        value,
        SortedMap.from(ids.take(3).zip(votes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      commits <- pairs.take(3).traverse(signCoreCommit[IO](proposal, _))
      commit <- buildCoreCommitQc[IO](
        proposal,
        SortedMap.from(ids.take(3).zip(commits)),
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
    } yield CertifiedOutcome(proposal, commit)

  private def peerId(keyPair: java.security.KeyPair): PeerId = PeerId.fromId(keyPair.getPublic.toId)

  private def withCommittee(ids: List[PeerId])(implicit hasher: Hasher[IO]): IO[ProposalValue] = {
    val committee = nonEmptyPeers(ids)

    (
      Hasher[IO].hash(committee),
      Hasher[IO].hash(committee)
    ).mapN { (fullHash, coreHash) =>
      baseValue.copy(
        roundStartFacilitators = committee,
        roundStartFacilitatorsHash = fullHash,
        roundStartCore = committee,
        roundStartCoreHash = coreHash,
        nextRoundAuthority = CertifiedRoundAuthorityV1(committee, fullHash, committee, coreHash),
        admissionNominee = ids.lastOption,
        observedResponders = SortedSet.from(ids),
        observedSelfHealth = SortedMap.empty,
        timeoutVoters = SortedSet.from(ids.take(3))
      )
    }
  }

  test("canonical collection types make input iteration order unobservable") { res =>
    implicit val hasher: Hasher[IO] = res._1
    val forward = baseValue
    val reversed = forward.copy(
      roundStartFacilitators = nonEmptyPeers(forward.roundStartFacilitators.toSortedSet.toList.reverse),
      roundStartCore = nonEmptyPeers(forward.roundStartCore.toSortedSet.toList.reverse),
      observedResponders = SortedSet.from(forward.observedResponders.toList.reverse),
      timeoutVoters = SortedSet.from(forward.timeoutVoters.toList.reverse)
    )

    (valueHash[IO](forward), valueHash[IO](reversed)).mapN { (a, b) =>
      expect.all(a === b, forward === reversed, ProposalValue.validate(reversed).isRight)
    }
  }

  test("ProposalValue repository-Hasher encoding has a pinned golden hash") { res =>
    implicit val hasher: Hasher[IO] = res._1
    val expected = Hash("f6d84407259512b4ee94bfb9afde6a3c7ab4d9511017679f2450cbfaf973b8fe")

    valueHash[IO](baseValue).map { actual =>
      expect(actual === expected, s"ProposalValue golden hash changed: actual=${actual.value}")
    }
  }

  test("every shared v35 wire type has a pinned repository encoding") { res =>
    implicit val hasher: Hasher[IO] = res._1

    val proof = NonEmptySet.one(sizedProof(1))
    val trigger = triggerStatement(
      ConsensusDomain.DagL0,
      "integrationnet",
      5881764L,
      hash("parent"),
      hash("committee"),
      hash("config"),
      EventTrigger.some
    )
    val certification = CertificationStatement(
      CertificationPurpose.Prepare,
      SchemaVersion,
      ConsensusDomain.DagL0,
      "integrationnet",
      5881764L,
      hash("parent"),
      hash("value"),
      hash("committee"),
      hash("core"),
      2L
    )
    val proposalQc = CertifiedProposalQC(baseValue, hash("value"), proof)
    val commitQc = CoreCommitQC(hash("value"), hash("core"), proof)
    val outcome = CertifiedOutcome(proposalQc, commitQc)
    val lineage = CertifiedLineageEvidenceV1(outcome)
    val values: List[(String, IO[Hash])] = List(
      "consensus-trigger-event" -> Hasher[IO].hash[ConsensusTrigger](EventTrigger),
      "consensus-trigger-time" -> Hasher[IO].hash[ConsensusTrigger](TimeTrigger),
      "self-health-healthy" -> Hasher[IO].hash[SelfHealthHint](SelfHealthHint.Healthy),
      "self-health-degraded" -> Hasher[IO].hash[SelfHealthHint](SelfHealthHint.Degraded),
      "self-health-critical" -> Hasher[IO].hash[SelfHealthHint](SelfHealthHint.Critical),
      "consensus-domain-dag" -> Hasher[IO].hash[ConsensusDomain](ConsensusDomain.DagL0),
      "certification-purpose-prepare" -> Hasher[IO].hash[CertificationPurpose](CertificationPurpose.Prepare),
      "certification-purpose-commit" -> Hasher[IO].hash[CertificationPurpose](CertificationPurpose.Commit),
      "trigger-purpose-facility" -> Hasher[IO].hash[TriggerStatementPurpose](TriggerStatementPurpose.Facility),
      "trigger-statement" -> Hasher[IO].hash(trigger),
      "round-authority-v1" -> Hasher[IO].hash(baseValue.nextRoundAuthority),
      "proposal-value" -> Hasher[IO].hash(baseValue),
      "certification-statement" -> Hasher[IO].hash(certification),
      "proposal-qc" -> Hasher[IO].hash(proposalQc),
      "core-commit-qc" -> Hasher[IO].hash(commitQc),
      "certified-outcome" -> Hasher[IO].hash(outcome),
      "certified-lineage" -> Hasher[IO].hash(lineage)
    )
    val expected = List(
      "consensus-trigger-event" -> Hash("e417e39c7d5b55430dc1ed87ff8c93f2b1d2a6a3b8e47a75953e5878f53b35c0"),
      "consensus-trigger-time" -> Hash("b01fd718977b774081dbece08fbff857798967457c9e9629a170125a47a1f3cd"),
      "self-health-healthy" -> Hash("339e9b51a0c403c2f75e816b7ac50c8cd876b0d70aa78fb3bb845adf9715426a"),
      "self-health-degraded" -> Hash("ede5604c3ee33ea06a51b9fe76ed080cea2996351466262ff9786747609eaa80"),
      "self-health-critical" -> Hash("38082a4c8fff902ce9abafdfc9054f8e108362169bbb6f70030a524c33c8dd43"),
      "consensus-domain-dag" -> Hash("e14e8e622ab2062bdb8e38ad791f5b9504543e0e3998be2976622aeac552fd02"),
      "certification-purpose-prepare" -> Hash(
        "552ea109444b1f3355ecceb21ef352bc5ca514b665cd504a369e091d101f4a37"
      ),
      "certification-purpose-commit" -> Hash("14fd9ea0aea122e9826239231e152fb961cd1048ad64509b170cee6136086beb"),
      "trigger-purpose-facility" -> Hash("1c12cb032ad8f7492bb8e21026c42b59f8cb0b78f0141375ae2d74d2da4030df"),
      "trigger-statement" -> Hash("d49b54eb323ced6686bc9220feee7d02914e43d275918f5a7e2e7536b87eb4f4"),
      "round-authority-v1" -> Hash("5f7e5aaf61ca3ce7609ccd9d1de0385de110df41246b178aeaeb05a3bf329866"),
      "proposal-value" -> Hash("f6d84407259512b4ee94bfb9afde6a3c7ab4d9511017679f2450cbfaf973b8fe"),
      "certification-statement" -> Hash("3ba72cf59f5ee9ded1111e3068d2fbcb2cdedafc24235d977d65a8237a5ee549"),
      "proposal-qc" -> Hash("c64c93283c353f309d89aba216d725df280462ddcfd2f70c2c2c259aca5f3b50"),
      "core-commit-qc" -> Hash("285024d39c2ac559b3964588f1b5ccc407316933e941dfe520e85f11ec7d6fad"),
      "certified-outcome" -> Hash("cfd304f02b243c0846d57c1b1919d4e561e324b3f6553f41d7a40393f26391a3"),
      // Currency L0 no longer participates in v35 certification, so the
      // pre-activation lineage schema is now the one-field GL0 evidence type.
      "certified-lineage" -> Hash("4841c368a43e647f8e95bdb8d171bcc26b89087b890d7e36749e00fe72de10ee")
    )

    values.traverse { case (label, encoded) => encoded.tupleLeft(label) }.map { actual =>
      expect(actual === expected, s"shared v35 wire encoding changed: actual=$actual")
    }
  }

  test("absent v35 fields preserve the pre-activation declaration JSON shape") { _ =>
    val printer = Printer(dropNullValues = true, indent = "", sortKeys = true)
    val signature = Signature(Hex("00"))
    val proposal = Proposal(
      hash = hash("artifact"),
      facilitatorsHash = hash("committee"),
      lastSnapshotHash = hash("parent"),
      view = 0L,
      vcc = None
    )
    val majority = MajoritySignature(signature, hash("committee"), hash("parent"), 0L, hash("artifact"))
    val viewChange = ViewChangeVote(0L, 1L, hash("committee"), hash("parent"), None)
    val timeout = TimeoutVote(0L, 1L, hash("committee"), hash("parent"), None, TimeoutReason.NoProgress)
    val facility = Facility(
      eventHashes = Set.empty,
      candidates = Candidates.empty,
      trigger = None,
      facilitatorsHash = hash("committee"),
      lastGlobalSnapshotOrdinal = io.constellationnetwork.schema.SnapshotOrdinal.MinValue,
      lastSnapshotHash = hash("parent")
    )

    def absentFieldIsByteNeutral[A](value: A, field: String)(implicit encoder: io.circe.Encoder[A]): Boolean = {
      val encoded = value.asJson
      printer.print(encoded) === printer.print(encoded.mapObject(_.remove(field)))
    }

    val proposalJson = printer.print(proposal.asJson.mapObject(_.remove("proposalValue").remove("triggerEvidence")))
    val facilityJson = printer.print(facility.asJson.mapObject(_.remove("triggerStatement")))
    val majorityJson = printer.print(
      majority.asJson.mapObject(_.remove("proposalValueHash").remove("proposalQc").remove("coreCommit"))
    )
    val viewChangeJson = printer.print(viewChange.asJson.mapObject(_.remove("highestKnownCertifiedQc")))
    val timeoutJson = printer.print(timeout.asJson.mapObject(_.remove("highestKnownCertifiedQc")))

    IO.pure(
      expect.all(
        absentFieldIsByteNeutral(proposal, "proposalValue"),
        absentFieldIsByteNeutral(proposal, "triggerEvidence"),
        absentFieldIsByteNeutral(facility, "triggerStatement"),
        absentFieldIsByteNeutral(majority, "proposalValueHash"),
        absentFieldIsByteNeutral(majority, "proposalQc"),
        absentFieldIsByteNeutral(majority, "coreCommit"),
        absentFieldIsByteNeutral(viewChange, "highestKnownCertifiedQc"),
        absentFieldIsByteNeutral(timeout, "highestKnownCertifiedQc"),
        decode[Proposal](proposalJson).contains(proposal),
        decode[Facility](facilityJson).contains(facility),
        decode[MajoritySignature](majorityJson).contains(majority),
        decode[ViewChangeVote](viewChangeJson).contains(viewChange),
        decode[TimeoutVote](timeoutJson).contains(timeout)
      )
    )
  }

  test("leader-carried trigger statements authorize one deterministic trigger without local pacing input") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      committee = ids.toSet
      committeeHash <- Hasher[IO].hash(nonEmptyPeers(ids))
      statements = List(EventTrigger.some, EventTrigger.some, TimeTrigger.some, none).map(
        triggerStatement(
          ConsensusDomain.DagL0,
          "integrationnet",
          5881764L,
          hash("parent"),
          committeeHash,
          hash("config"),
          _
        )
      )
      signed <- statements.zip(pairs).traverse { case (statement, pair) => signTriggerStatement[IO](statement, pair) }
      allNone <- pairs.traverse(pair =>
        signTriggerStatement[IO](
          triggerStatement(
            ConsensusDomain.DagL0,
            "integrationnet",
            5881764L,
            hash("parent"),
            committeeHash,
            hash("config"),
            none
          ),
          pair
        )
      )
      authorized <- validateTriggerEvidence[IO](
        signed.take(3),
        ConsensusDomain.DagL0,
        "integrationnet",
        5881764L,
        hash("parent"),
        committeeHash,
        hash("config"),
        committee,
        requiredQuorum = 3,
        proposedTrigger = EventTrigger,
        requiredLeader = ids.head
      )
      allNoneDefaultsToEvent <- validateTriggerEvidence[IO](
        allNone.take(3),
        ConsensusDomain.DagL0,
        "integrationnet",
        5881764L,
        hash("parent"),
        committeeHash,
        hash("config"),
        committee,
        requiredQuorum = 3,
        proposedTrigger = EventTrigger,
        requiredLeader = ids.head
      )
      // No view is accepted by the verifier: evidence is deliberately reusable across
      // view changes at the same key, while every cross-key binding remains signed.
      sameKeyLaterViewReuse <- validateTriggerEvidence[IO](
        signed.take(3),
        ConsensusDomain.DagL0,
        "integrationnet",
        5881764L,
        hash("parent"),
        committeeHash,
        hash("config"),
        committee,
        requiredQuorum = 3,
        proposedTrigger = EventTrigger,
        requiredLeader = ids.head
      )
      missingLeader <- validateTriggerEvidence[IO](
        signed.tail,
        ConsensusDomain.DagL0,
        "integrationnet",
        5881764L,
        hash("parent"),
        committeeHash,
        hash("config"),
        committee,
        requiredQuorum = 3,
        proposedTrigger = EventTrigger,
        requiredLeader = ids.head
      )
      wrongMajority <- validateTriggerEvidence[IO](
        signed.take(3),
        ConsensusDomain.DagL0,
        "integrationnet",
        5881764L,
        hash("parent"),
        committeeHash,
        hash("config"),
        committee,
        requiredQuorum = 3,
        proposedTrigger = TimeTrigger,
        requiredLeader = ids.head
      )
      rebound <- signTriggerStatement[IO](statements.head.copy(parentArtifactHash = hash("other-parent")), pairs.head)
      wrongBinding <- validateTriggerEvidence[IO](
        rebound :: signed.slice(1, 3),
        ConsensusDomain.DagL0,
        "integrationnet",
        5881764L,
        hash("parent"),
        committeeHash,
        hash("config"),
        committee,
        requiredQuorum = 3,
        proposedTrigger = EventTrigger,
        requiredLeader = ids.head
      )
    } yield
      expect.all(
        authorized === Right(EventTrigger),
        allNoneDefaultsToEvent === Right(EventTrigger),
        sameKeyLaterViewReuse === Right(EventTrigger),
        missingLeader === Left("trigger_evidence_missing_leader"),
        wrongMajority === Left("trigger_evidence_majority_mismatch"),
        wrongBinding === Left("trigger_evidence_binding_mismatch")
      )
  }

  test("v35 wire-size envelope is measured at realistic and configured-maximum committee sizes") { _ =>
    final case class Row(
      committee: Int,
      triggerEvidence: Int,
      proposalValue: Int,
      proposalQc: Int,
      coreCommitQc: Int,
      certifiedOutcome: Int
    )

    val productionPrinter = Printer.noSpaces.copy(dropNullValues = true)

    def bytes[A: io.circe.Encoder](value: A): IO[Int] =
      IO.pure(productionPrinter.print(value.asJson).getBytes("UTF-8").length)

    def row(committeeSize: Int): IO[Row] = {
      val peers = List.tabulate(committeeSize)(sizedPeer)
      val full = nonEmptyPeers(peers)
      // Mainnet's configured Core floor is the largest public-network floor. The broad
      // DAG committee still scales to the configured facilitator maximum.
      val coreSize = math.min(committeeSize, 15)
      val corePeers = peers.take(coreSize)
      val core = nonEmptyPeers(corePeers)
      val fullQuorum = requiredArtifactQuorum(committeeSize, coreSize, 2.0 / 3.0)
      val coreQuorum = requiredCoreQuorum(coreSize, 2.0 / 3.0)
      val coreProofs = NonEmptySet.fromSetUnsafe(SortedSet.from(List.tabulate(coreQuorum)(sizedProof)))
      val triggerEvidence: List[Signed[TriggerStatement]] = List.tabulate(fullQuorum) { index =>
        Signed(
          triggerStatement(
            ConsensusDomain.DagL0,
            "mainnet",
            6000000L,
            hash("parent"),
            hash("committee"),
            hash("config"),
            EventTrigger.some
          ),
          NonEmptySet.one(sizedProof(index))
        )
      }
      val value = baseValue.copy(
        networkId = "mainnet",
        key = 6000000L,
        roundStartFacilitators = full,
        roundStartCore = core,
        admissionNominee = peers.lastOption,
        observedResponders = SortedSet.from(peers),
        observedSelfHealth = SortedMap.empty,
        timeoutVoters = SortedSet.from(corePeers.take(coreQuorum))
      )
      val valueHash = hash(s"value-$committeeSize")
      val proposalQc = CertifiedProposalQC(value, valueHash, coreProofs)
      val coreCommitQc = CoreCommitQC(valueHash, value.roundStartCoreHash, coreProofs)
      val certified = CertifiedOutcome(proposalQc, coreCommitQc)
      (
        bytes(triggerEvidence),
        bytes(value),
        bytes(proposalQc),
        bytes(coreCommitQc),
        bytes(certified)
      ).mapN(Row(committeeSize, _, _, _, _, _))
    }

    List(3, 31, 73, 100, 200, 1000).traverse(row).flatMap { rows =>
      val rendered = rows
        .map(r => s"${r.committee},${r.triggerEvidence},${r.proposalValue},${r.proposalQc},${r.coreCommitQc},${r.certifiedOutcome}")
        .mkString(
          "committee,triggerEvidence,proposalValue,proposalQc,coreCommitQc,certifiedOutcome\n",
          "\n",
          ""
        )

      IO.println(s"V35_WIRE_SIZE_BYTES\n$rendered") *>
        IO.pure(
          expect.all(
            rows.map(_.committee) === List(3, 31, 73, 100, 200, 1000),
            rows.sliding(2).forall {
              case List(a, b) =>
                b.triggerEvidence > a.triggerEvidence &&
                b.proposalValue > a.proposalValue
              case _ => true
            },
            rows.forall(row => row.proposalQc > row.proposalValue),
            rows.forall(row => row.certifiedOutcome > row.proposalQc)
          )
        )
    }
  }

  test("Facility evidence selection ignores malformed statements but still requires a valid leader-bearing quorum") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      leader = ids.min
      faulty = ids.find(_ =!= leader).get
      committeeHash <- Hasher[IO].hash(nonEmptyPeers(ids))
      statements = ids.map(_ =>
        triggerStatement(
          ConsensusDomain.DagL0,
          "integrationnet",
          5881764L,
          hash("parent"),
          committeeHash,
          hash("config"),
          EventTrigger.some
        )
      )
      signed <- statements.zip(pairs).traverse { case (statement, pair) => signTriggerStatement[IO](statement, pair) }
      signedById = ids.zip(signed).toMap
      facilities = SortedMap.from(ids.zip(signed).map {
        case (id, statement) =>
          id -> Facility(
            eventHashes = Set.empty,
            candidates = Candidates.empty,
            trigger = EventTrigger.some,
            facilitatorsHash = committeeHash,
            lastGlobalSnapshotOrdinal = SnapshotOrdinal.MinValue,
            lastSnapshotHash = hash("parent"),
            triggerStatement = statement.some
          )
      })
      select = (input: SortedMap[PeerId, Facility], quorum: Int) =>
        selectTriggerEvidence[IO](
          input,
          ConsensusDomain.DagL0,
          "integrationnet",
          5881764L,
          hash("parent"),
          committeeHash,
          hash("config"),
          ids.toSet,
          quorum,
          leader
        )
      selected <- select(facilities, 3)
      invalidSignature = signedById(faulty).copy(value = signedById(faulty).value.copy(trigger = TimeTrigger.some))
      invalidSignatureFacilities = facilities.updated(
        faulty,
        facilities(faulty).copy(trigger = TimeTrigger.some, triggerStatement = invalidSignature.some)
      )
      filtered <- select(invalidSignatureFacilities, 3)
      underQuorum <- select(invalidSignatureFacilities, 4)
      missingLeaderFacilities = facilities.updated(
        leader,
        facilities(leader).copy(triggerStatement = signedById(faulty).some)
      )
      missingLeader <- select(missingLeaderFacilities, 3)
    } yield
      expect.all(
        selected === Right(signed.sortBy(_.proofs.head.id.toPeerId) -> EventTrigger),
        filtered.exists { case (evidence, trigger) => evidence.size === 3 && trigger === EventTrigger },
        underQuorum === Left("trigger_evidence_under_quorum:3/4"),
        missingLeader === Left("trigger_evidence_missing_leader")
      )
  }

  test("pacemaker voting keeps legacy targets but restricts certified votes to frozen Core") { _ =>
    val full = Set(pA, pB, pC, pD)
    val core = Set(pA, pB)
    val legacy = Set(pA, pC)

    val legacyPlan = pacemakerVoteTargets(
      certifiedConsensusActive = false,
      selfId = pA,
      frozenCommittee = full,
      frozenCore = core,
      legacyFacilitators = legacy
    )
    val certifiedCorePlan = pacemakerVoteTargets(
      certifiedConsensusActive = true,
      selfId = pA,
      frozenCommittee = full,
      frozenCore = core,
      legacyFacilitators = legacy
    )
    val certifiedTier1Plan = pacemakerVoteTargets(
      certifiedConsensusActive = true,
      selfId = pC,
      frozenCommittee = full,
      frozenCore = core,
      legacyFacilitators = legacy
    )

    IO.pure(
      expect.all(
        legacyPlan.contains(Set(pC)),
        certifiedCorePlan.contains(Set(pB, pC, pD)),
        certifiedTier1Plan.isEmpty
      )
    )
  }

  test("every outcome-affecting field mutation changes valueHash") { res =>
    implicit val hasher: Hasher[IO] = res._1
    val base = baseValue
    val mutations = List(
      base.copy(networkId = "testnet"),
      base.copy(key = base.key + 1L),
      base.copy(parentArtifactHash = hash("other-parent")),
      base.copy(artifactHash = hash("other-artifact")),
      base.copy(contextHash = hash("other-context")),
      base.copy(roundStartFacilitators = nonEmptyPeers(base.roundStartFacilitators.toSortedSet - pD)),
      base.copy(roundStartFacilitatorsHash = hash("other-full")),
      base.copy(roundStartCore = nonEmptyPeers(base.roundStartCore.toSortedSet - pD)),
      base.copy(roundStartCoreHash = hash("other-core")),
      base.copy(
        nextRoundAuthority = base.nextRoundAuthority.copy(
          facilitators = nonEmptyPeers(base.nextRoundAuthority.facilitators.toSortedSet - pD)
        )
      ),
      base.copy(nextRoundAuthority = base.nextRoundAuthority.copy(facilitatorsHash = hash("other-next-full"))),
      base.copy(
        nextRoundAuthority = base.nextRoundAuthority.copy(core = nonEmptyPeers(base.nextRoundAuthority.core.toSortedSet - pD))
      ),
      base.copy(nextRoundAuthority = base.nextRoundAuthority.copy(coreHash = hash("other-next-core"))),
      base.copy(nextOperationalStateHash = hash("other-next-operational-state")),
      base.copy(committedView = base.committedView + 1L),
      base.copy(trigger = TimeTrigger),
      base.copy(admissionNominee = None),
      base.copy(admittedPeers = SortedSet(pD)),
      base.copy(evictedPeers = SortedSet(pD)),
      base.copy(observedResponders = base.observedResponders - pD),
      base.copy(observedSelfHealth = base.observedSelfHealth.updated(pA, SelfHealthHint.Critical)),
      base.copy(timeoutVoters = base.timeoutVoters - pC),
      base.copy(consensusEndTime = base.consensusEndTime.map(_ + 1L))
    )

    for {
      expected <- valueHash[IO](base)
      changed <- mutations.traverse(valueHash[IO])
    } yield expect(changed.forall(_ =!= expected), "no committed-field mutation may retain the original valueHash")
  }

  test("next authority is structurally valid only when Core is a subset of the full committee") { _ =>
    val invalid = baseValue.copy(
      nextRoundAuthority = baseValue.nextRoundAuthority.copy(
        facilitators = nonEmptyPeers(List(pA, pB, pC)),
        core = nonEmptyPeers(List(pA, pB, pC, pD))
      )
    )

    IO.pure(expect(ProposalValue.validate(invalid) === Left("next_round_core_not_subset")))
  }

  test("historical QC verification uses the fixed supermajority floor, not a downloader policy fraction") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      proposal <- buildProposalQc[IO](
        value,
        SortedMap.from(ids.take(3).zip(votes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      commits <- pairs.take(3).traverse(signCoreCommit[IO](proposal, _))
      commit <- buildCoreCommitQc[IO](
        proposal,
        SortedMap.from(ids.take(3).zip(commits)),
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      outcome = CertifiedOutcome(proposal, commit)
      historical <- verifyOutcomeAtSafetyFloor[IO](outcome, ids.toSet, ids.toSet)
      currentUnanimity <- verifyOutcome[IO](outcome, ids.toSet, ids.toSet, configuredFraction = 1.0d)
    } yield
      expect.all(
        historical === Right(()),
        currentUnanimity === Left("core_under_quorum:3/4")
      )
  }

  test("historical identity rejects validly re-certified hashes and locally mismatched inherited committees") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(5)
      ids = pairs.take(4).map(peerId)
      value <- withCommittee(ids)
      valid <- certifyOutcome(value, pairs, ids)
      wrongParent <- certifyOutcome(value.copy(parentArtifactHash = hash("wrong-parent")), pairs, ids)
      wrongArtifact <- certifyOutcome(value.copy(artifactHash = hash("wrong-artifact")), pairs, ids)
      wrongContext <- certifyOutcome(value.copy(contextHash = hash("wrong-context")), pairs, ids)
      expectedFull = value.roundStartFacilitators
      expectedCore = value.roundStartCore
      differentFull = nonEmptyPeers(ids.dropRight(1))
      differentCore = nonEmptyPeers(ids.tail)
      validResult <- verifyHistoricalOutcomeIdentity[IO](
        valid,
        value.domain,
        value.networkId,
        value.key,
        value.parentArtifactHash,
        value.artifactHash,
        value.contextHash.some,
        expectedFull,
        expectedCore
      )
      parentResult <- verifyHistoricalOutcomeIdentity[IO](
        wrongParent,
        value.domain,
        value.networkId,
        value.key,
        value.parentArtifactHash,
        value.artifactHash,
        value.contextHash.some,
        expectedFull,
        expectedCore
      )
      artifactResult <- verifyHistoricalOutcomeIdentity[IO](
        wrongArtifact,
        value.domain,
        value.networkId,
        value.key,
        value.parentArtifactHash,
        value.artifactHash,
        value.contextHash.some,
        expectedFull,
        expectedCore
      )
      contextResult <- verifyHistoricalOutcomeIdentity[IO](
        wrongContext,
        value.domain,
        value.networkId,
        value.key,
        value.parentArtifactHash,
        value.artifactHash,
        value.contextHash.some,
        expectedFull,
        expectedCore
      )
      fullResult <- verifyHistoricalOutcomeIdentity[IO](
        valid,
        value.domain,
        value.networkId,
        value.key,
        value.parentArtifactHash,
        value.artifactHash,
        value.contextHash.some,
        differentFull,
        expectedCore
      )
      coreResult <- verifyHistoricalOutcomeIdentity[IO](
        valid,
        value.domain,
        value.networkId,
        value.key,
        value.parentArtifactHash,
        value.artifactHash,
        value.contextHash.some,
        expectedFull,
        differentCore
      )
    } yield
      expect.all(
        validResult === Right(()),
        parentResult === Left("historical_parent_hash_mismatch"),
        artifactResult === Left("historical_artifact_hash_mismatch"),
        contextResult === Left("historical_context_hash_mismatch"),
        fullResult === Left("historical_full_committee_mismatch"),
        coreResult === Left("historical_core_committee_mismatch")
      )
  }

  test("proposal QC rejects inconsistent hashes on both next-round authority sets") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      badFull = value.copy(nextRoundAuthority = value.nextRoundAuthority.copy(facilitatorsHash = hash("bad-next-full")))
      badCore = value.copy(nextRoundAuthority = value.nextRoundAuthority.copy(coreHash = hash("bad-next-core")))
      fullVotes <- pairs.take(3).traverse(signOutcomeVote[IO](badFull, _).map(_._2))
      coreVotes <- pairs.take(3).traverse(signOutcomeVote[IO](badCore, _).map(_._2))
      fullResult <- buildProposalQc[IO](
        badFull,
        SortedMap.from(ids.take(3).zip(fullVotes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      )
      coreResult <- buildProposalQc[IO](
        badCore,
        SortedMap.from(ids.take(3).zip(coreVotes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      )
    } yield
      expect.all(
        fullResult === Left("next_full_committee_hash_mismatch"),
        coreResult === Left("next_core_committee_hash_mismatch")
      )
  }

  test("three distinct frozen-Core prepare votes build and verify a 3-of-4 QC") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      signed <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      votes = SortedMap.from(ids.take(3).zip(signed))
      qc <- buildProposalQc[IO](value, votes, ids.toSet, ids.toSet, 2.0 / 3.0)
      verified <- qc.traverse(verifyProposalQc[IO](_, ids.toSet, ids.toSet, 2.0 / 3.0)).map(_.flatten)
    } yield expect.all(qc.isRight, verified === Right(()))
  }

  test("one ProposalQC binds both halves of an atomic N-to-N replacement") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(5)
      committee = pairs.take(4).map(peerId)
      admitted = peerId(pairs.last)
      base <- withCommittee(committee)
      value = base.copy(
        admittedPeers = SortedSet(admitted),
        evictedPeers = SortedSet(committee.last)
      )
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      qcEither <- buildProposalQc[IO](
        value,
        SortedMap.from(committee.take(3).zip(votes)),
        committee.toSet,
        committee.toSet,
        2.0 / 3.0
      )
      qc <- IO.fromEither(qcEither.leftMap(new IllegalStateException(_)))
      verified <- verifyProposalQc[IO](qc, committee.toSet, committee.toSet, 2.0 / 3.0)
      stripped <- verifyProposalQc[IO](
        qc.copy(value = value.copy(evictedPeers = SortedSet.empty)),
        committee.toSet,
        committee.toSet,
        2.0 / 3.0
      )
      applied = CertifiedMembershipTransition.applyTo(
        committee,
        value.admittedPeers.toSet,
        value.evictedPeers.toSet,
        maxChanges = 1
      )
    } yield
      expect.all(
        verified === Right(()),
        stripped.isLeft,
        applied.exists(next => next.size == committee.size && next.contains(admitted) && !next.contains(committee.last))
      )
  }

  test("prepare QC rejects under-quorum and out-of-pool votes") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(5)
      corePairs = pairs.take(4)
      core = corePairs.map(peerId)
      outsiderId = peerId(pairs.last)
      value <- withCommittee(core)
      two <- corePairs.take(2).traverse(signOutcomeVote[IO](value, _).map(_._2))
      outsider <- signOutcomeVote[IO](value, pairs.last).map(_._2)
      result <- buildProposalQc[IO](
        value,
        SortedMap.from(core.take(2).zip(two) :+ (outsiderId -> outsider)),
        core.toSet,
        core.toSet,
        2.0 / 3.0
      )
    } yield expect(result.left.exists(_.startsWith("core_under_quorum")))
  }

  test("Core commit QC is domain-separated from prepare and requires a frozen-Core quorum") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      proposalQcEither <- buildProposalQc[IO](value, SortedMap.from(ids.take(3).zip(votes)), ids.toSet, ids.toSet, 2.0 / 3.0)
      proposalQc <- IO.fromEither(proposalQcEither.leftMap(new IllegalStateException(_)))
      commits <- pairs.take(3).traverse(signCoreCommit[IO](proposalQc, _))
      commitQcEither <- buildCoreCommitQc[IO](proposalQc, SortedMap.from(ids.take(3).zip(commits)), ids.toSet, 2.0 / 3.0)
      verified <- commitQcEither.traverse(verifyCoreCommitQc[IO](proposalQc, _, ids.toSet, 2.0 / 3.0)).map(_.flatten)
      // Reusing prepare signatures as commit signatures must fail because the purpose prefix differs.
      reused = commitQcEither.map(qc => qc.copy(signatures = proposalQc.signatures))
      reusedResult <- reused.traverse(verifyCoreCommitQc[IO](proposalQc, _, ids.toSet, 2.0 / 3.0))
    } yield expect.all(commitQcEither.isRight, verified === Right(()), reusedResult.exists(_.isLeft))
  }

  test("QC-bearing codecs are safe under concurrent first touch") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      qcEither <- buildProposalQc[IO](value, SortedMap.from(ids.take(3).zip(votes)), ids.toSet, ids.toSet, 2.0 / 3.0)
      qc <- IO.fromEither(qcEither.leftMap(new IllegalStateException(_)))
      decoded <- List.fill(64)(IO(decode[CertifiedProposalQC](qc.asJson.noSpaces))).parSequence
    } yield expect(decoded.forall(_ === Right(qc)))
  }

  test("QC-carrying view-change and timeout wire envelopes are safe under concurrent first touch") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      prepareVotes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      qcEither <- buildProposalQc[IO](
        value,
        SortedMap.from(ids.take(3).zip(prepareVotes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      )
      qc <- IO.fromEither(qcEither.leftMap(new IllegalStateException(_)))
      viewChangeVote <- Signed.forAsyncHasher[IO, ViewChangeVote](
        ViewChangeVote(
          fromView = 2L,
          toView = 3L,
          facilitatorsHash = value.roundStartFacilitatorsHash,
          lastSnapshotHash = value.parentArtifactHash,
          highestKnownQc = None,
          highestKnownCertifiedQc = qc.some
        ),
        pairs.head
      )
      timeoutVote <- Signed.forAsyncHasher[IO, TimeoutVote](
        TimeoutVote(
          fromView = 2L,
          toView = 3L,
          facilitatorsHash = value.roundStartFacilitatorsHash,
          lastSnapshotHash = value.parentArtifactHash,
          highestKnownQc = None,
          reason = TimeoutReason.NoProgress,
          highestKnownCertifiedQc = qc.some
        ),
        pairs(1)
      )
      peerVote = ConsensusPeerVote(value.key, viewChangeVote)
      peerTimeoutVote = ConsensusPeerTimeoutVote(value.key, timeoutVote)
      proposal = Proposal(
        hash = value.artifactHash,
        facilitatorsHash = value.roundStartFacilitatorsHash,
        lastSnapshotHash = value.parentArtifactHash,
        view = 3L,
        vcc = ViewChangeCertificate(2L, 3L, value.roundStartFacilitatorsHash, NonEmptySet.one(viewChangeVote)).some,
        timeoutCertificate = TimeoutCertificate(
          2L,
          3L,
          value.roundStartFacilitatorsHash,
          value.parentArtifactHash,
          TimeoutReason.NoProgress,
          NonEmptySet.one(timeoutVote)
        ).some,
        proposalValue = value.some
      )
      voteJson = peerVote.asJson.noSpaces
      timeoutJson = peerTimeoutVote.asJson.noSpaces
      proposalJson = proposal.asJson.noSpaces
      decoded <- List
        .fill(64)(
          IO(
            (
              decode[ConsensusPeerVote[Long]](voteJson),
              decode[ConsensusPeerTimeoutVote[Long]](timeoutJson),
              decode[Proposal](proposalJson)
            )
          )
        )
        .parSequence
    } yield
      expect(
        decoded.forall {
          case (Right(decodedVote), Right(decodedTimeout), Right(decodedProposal)) =>
            decodedVote == peerVote && decodedTimeout == peerTimeoutVote && decodedProposal == proposal
          case _ => false
        },
        "every concurrently initialized codec path must preserve the QC-bearing payload"
      )
  }

  test("different valid QC signer subsets certify one semantic valueHash") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      votes <- pairs.traverse(signOutcomeVote[IO](value, _).map(_._2))
      first <- buildProposalQc[IO](value, SortedMap.from(ids.take(3).zip(votes.take(3))), ids.toSet, ids.toSet, 2.0 / 3.0)
      second <- buildProposalQc[IO](value, SortedMap.from(ids.drop(1).zip(votes.drop(1))), ids.toSet, ids.toSet, 2.0 / 3.0)
    } yield
      expect.all(
        first.exists(qc => second.exists(_.valueHash === qc.valueHash)),
        first.exists(qc => second.exists(_.signatures =!= qc.signatures))
      )
  }

  test("an invalid first-arriving prepare vote cannot poison an honest Core quorum") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      votes <- pairs.traverse(signOutcomeVote[IO](value, _).map(_._2))
      poisoned = SortedMap.from(ids.zip(votes.updated(3, invalidateSignature(votes(3)))))
      accepted <- buildProposalQc[IO](value, poisoned, ids.toSet, ids.toSet, 2.0 / 3.0)
      underQuorum <- buildProposalQc[IO](
        value,
        SortedMap.from(ids.take(3).zip(votes.take(2) :+ invalidateSignature(votes(2)))),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      )
    } yield
      expect.all(
        accepted.exists(_.signatures.size === 3L),
        accepted.exists(!_.signatures.toSortedSet.exists(_.id.toPeerId === ids(3))),
        underQuorum === Left("core_under_quorum:2/3")
      )
  }

  test("an invalid first-arriving Core commit cannot poison final certification") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      proposal <- buildProposalQc[IO](
        value,
        SortedMap.from(ids.take(3).zip(votes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      commits <- pairs.traverse(signCoreCommit[IO](proposal, _))
      poisoned = SortedMap.from(ids.zip(commits.updated(3, invalidateSignature(commits(3)))))
      accepted <- buildCoreCommitQc[IO](proposal, poisoned, ids.toSet, 2.0 / 3.0)
      underQuorum <- buildCoreCommitQc[IO](
        proposal,
        SortedMap.from(ids.take(3).zip(commits.take(2) :+ invalidateSignature(commits(2)))),
        ids.toSet,
        2.0 / 3.0
      )
    } yield
      expect.all(
        accepted.exists(_.signatures.size === 3L),
        accepted.exists(!_.signatures.toSortedSet.exists(_.id.toPeerId === ids(3))),
        underQuorum === Left("core_under_quorum:2/3")
      )
  }

  test("child lineage accepts an equivalent parent QC subset but preserves the exact carried envelope") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      prepareVotes <- pairs.traverse(signOutcomeVote[IO](value, _).map(_._2))
      firstProposal <- buildProposalQc[IO](
        value,
        SortedMap.from(ids.take(3).zip(prepareVotes.take(3))),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      secondProposal <- buildProposalQc[IO](
        value,
        SortedMap.from(ids.drop(1).zip(prepareVotes.drop(1))),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      firstCommits <- pairs.take(3).traverse(signCoreCommit[IO](firstProposal, _))
      secondCommits <- pairs.drop(1).traverse(signCoreCommit[IO](secondProposal, _))
      firstCommit <- buildCoreCommitQc[IO](
        firstProposal,
        SortedMap.from(ids.take(3).zip(firstCommits)),
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      secondCommit <- buildCoreCommitQc[IO](
        secondProposal,
        SortedMap.from(ids.drop(1).zip(secondCommits)),
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      trusted = CertifiedOutcome(firstProposal, firstCommit)
      carriedOutcome = CertifiedOutcome(secondProposal, secondCommit)
      carried = CertifiedLineageEvidenceV1(carriedOutcome)
      verified <- verifyCarriedParentOutcome[IO](
        carried.some,
        trusted.some,
        ConsensusDomain.DagL0,
        2.0 / 3.0
      )
      missing <- verifyCarriedParentOutcome[IO](None, trusted.some, ConsensusDomain.DagL0, 2.0 / 3.0)
      unexpected <- verifyCarriedParentOutcome[IO](carried.some, None, ConsensusDomain.DagL0, 2.0 / 3.0)
    } yield
      expect.all(
        trusted.proposalQc.signatures =!= carriedOutcome.proposalQc.signatures,
        trusted.coreCommitQc.signatures =!= carriedOutcome.coreCommitQc.signatures,
        verified === Right(carried.some),
        missing === Left("certified_lineage_missing_after_root"),
        unexpected === Left("certified_lineage_unexpected_at_root")
      )
  }

  test("sequential lineage takes interior certificates only from children and terminal evidence only from the peer tip") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      firstValue <- withCommittee(ids)
      first <- certifyOutcome(firstValue, pairs, ids)
      secondValue = firstValue.copy(
        key = firstValue.key + 1L,
        parentArtifactHash = firstValue.artifactHash,
        artifactHash = hash("second-artifact")
      )
      second <- certifyOutcome(secondValue, pairs, ids)
      frames = List(
        LineageFrame(firstValue.key, None),
        LineageFrame(secondValue.key, CertifiedLineageEvidenceV1(first).some)
      )
      terminal = CertifiedLineageEvidenceV1(second)
      replayed <- verifySequentialLineage[IO, Option[CertifiedOutcome], LineageFrame](
        trustedRoot = None,
        trustedRootKey = firstValue.key - 1L,
        frames = frames,
        terminalEvidence = terminal.some,
        domain = ConsensusDomain.DagL0,
        configuredFraction = 2.0 / 3.0,
        keyOf = _.key,
        lineageOf = _.lineage,
        certifiedOutcomeOf = identity
      )((_, _, authority) => authority.parentOutcome.some.asRight[String].pure[IO])
      missingInterior <- verifySequentialLineage[IO, Option[CertifiedOutcome], LineageFrame](
        None,
        firstValue.key - 1L,
        frames.updated(1, frames(1).copy(lineage = None)),
        terminal.some,
        ConsensusDomain.DagL0,
        2.0 / 3.0,
        _.key,
        _.lineage,
        identity
      )((_, _, authority) => authority.parentOutcome.some.asRight[String].pure[IO])
      missingTerminal <- verifySequentialLineage[IO, Option[CertifiedOutcome], LineageFrame](
        None,
        firstValue.key - 1L,
        frames,
        None,
        ConsensusDomain.DagL0,
        2.0 / 3.0,
        _.key,
        _.lineage,
        identity
      )((_, _, authority) => authority.parentOutcome.some.asRight[String].pure[IO])
    } yield
      expect.all(
        replayed === Right(List(first.some, second.some)),
        missingInterior === Left(s"certified_lineage_missing_child_certificate:${firstValue.key}"),
        missingTerminal === Left(s"certified_lineage_terminal_certificate_missing:${secondValue.key}")
      )
  }

  test("highest-QC selection verifies candidates before comparing their views") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      qcEither <- buildProposalQc[IO](value, SortedMap.from(ids.take(3).zip(votes)), ids.toSet, ids.toSet, 2.0 / 3.0)
      qc <- IO.fromEither(qcEither.leftMap(new IllegalStateException(_)))
      // Its embedded value claims a higher view, but its original signatures/valueHash do not certify that mutation.
      invalidHigher = qc.copy(value = qc.value.copy(committedView = qc.value.committedView + 100L))
      selected <- highestVerifiedProposalQc[IO](
        List(invalidHigher, qc),
        CertifiedRoundIdentity.from(value),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      )
    } yield expect(selected === Right(qc.some), "an invalid high-view advertisement must not eclipse a lower valid QC")
  }

  test("highest-QC selection ignores genuine certificates from another round identity") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      currentValue <- withCommittee(ids)
      staleValues = List(
        currentValue.copy(key = currentValue.key - 1L, committedView = 100L),
        currentValue.copy(parentArtifactHash = hash("stale-parent"), committedView = 101L),
        currentValue.copy(networkId = "other-network", committedView = 103L)
      )
      allValues = currentValue :: staleValues
      qcs <- allValues.traverse { value =>
        pairs
          .take(3)
          .traverse(signOutcomeVote[IO](value, _).map(_._2))
          .flatMap { votes =>
            buildProposalQc[IO](
              value,
              SortedMap.from(ids.take(3).zip(votes)),
              ids.toSet,
              ids.toSet,
              2.0 / 3.0
            ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
          }
      }
      current = qcs.head
      stale = qcs.tail
      expectedRound = CertifiedRoundIdentity.from(currentValue)
      selected <- highestVerifiedProposalQc[IO](stale :+ current, expectedRound, ids.toSet, ids.toSet, 2.0 / 3.0)
      staleOnly <- highestVerifiedProposalQc[IO](stale, expectedRound, ids.toSet, ids.toSet, 2.0 / 3.0)
    } yield
      expect.all(
        selected === Right(current.some),
        staleOnly === Right(none[CertifiedProposalQC])
      )
  }

  test("highest-QC selection fails closed on two valid values at the same highest view") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      firstValue <- withCommittee(ids)
      secondValue = firstValue.copy(artifactHash = hash("other-certified-artifact"))
      firstVotes <- pairs.take(3).traverse(signOutcomeVote[IO](firstValue, _).map(_._2))
      secondVotes <- pairs.take(3).traverse(signOutcomeVote[IO](secondValue, _).map(_._2))
      first <- buildProposalQc[IO](
        firstValue,
        SortedMap.from(ids.take(3).zip(firstVotes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      second <- buildProposalQc[IO](
        secondValue,
        SortedMap.from(ids.take(3).zip(secondVotes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
      selected <- highestVerifiedProposalQc[IO](
        List(first, second),
        CertifiedRoundIdentity.from(firstValue),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      )
    } yield expect(selected === Left(s"divergent_certified_qc_at_view:${firstValue.committedView}"))
  }

  test("a certified lock rejects cross-view equivocation after a prepare QC") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value <- withCommittee(ids)
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      qcEither <- buildProposalQc[IO](
        value,
        SortedMap.from(ids.take(3).zip(votes)),
        ids.toSet,
        ids.toSet,
        2.0 / 3.0
      )
      qc <- IO.fromEither(qcEither.leftMap(new IllegalStateException(_)))
      conflictingHash <- valueHash[IO](value.copy(artifactHash = hash("conflicting-artifact"), committedView = 3L))
      locked = CertifiedVoteLock.empty.withAdvancedQc(qc)
      rejected = locked.acceptVote(view = 3L, valueHash = conflictingHash, effectiveLockedQc = None)
      idempotent = locked.acceptVote(view = 3L, valueHash = qc.valueHash, effectiveLockedQc = None)
    } yield
      expect(rejected.left.exists(_.isInstanceOf[VoteRejection.LockedOnQc]))
        .and(expect(idempotent.isRight))
  }
}
