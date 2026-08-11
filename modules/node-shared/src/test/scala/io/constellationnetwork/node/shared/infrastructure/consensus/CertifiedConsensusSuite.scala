package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

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

  private val pA = peer('a')
  private val pB = peer('b')
  private val pC = peer('c')
  private val pD = peer('d')

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

  private def peerId(keyPair: java.security.KeyPair): PeerId = PeerId.fromId(keyPair.getPublic.toId)

  private def withCommittee(ids: List[PeerId]): ProposalValue =
    baseValue.copy(
      roundStartFacilitators = nonEmptyPeers(ids),
      roundStartCore = nonEmptyPeers(ids),
      admissionNominee = ids.lastOption,
      observedResponders = SortedSet.from(ids),
      observedSelfHealth = SortedMap.empty,
      timeoutVoters = SortedSet.from(ids.take(3))
    )

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

  test("every outcome-affecting field mutation changes valueHash") { res =>
    implicit val hasher: Hasher[IO] = res._1
    val base = baseValue
    val mutations = List(
      base.copy(domain = ConsensusDomain.CurrencyL0),
      base.copy(networkId = "testnet"),
      base.copy(key = base.key + 1L),
      base.copy(parentArtifactHash = hash("other-parent")),
      base.copy(artifactHash = hash("other-artifact")),
      base.copy(contextHash = hash("other-context")),
      base.copy(roundStartFacilitators = nonEmptyPeers(base.roundStartFacilitators.toSortedSet - pD)),
      base.copy(roundStartFacilitatorsHash = hash("other-full")),
      base.copy(roundStartCore = nonEmptyPeers(base.roundStartCore.toSortedSet - pD)),
      base.copy(roundStartCoreHash = hash("other-core")),
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

  test("three distinct frozen-Core prepare votes build and verify a 3-of-4 QC") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value = withCommittee(ids)
      signed <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      votes = SortedMap.from(ids.take(3).zip(signed))
      qc <- buildProposalQc[IO](value, votes, ids.toSet, 2.0 / 3.0)
      verified <- qc.traverse(verifyProposalQc[IO](_, ids.toSet, 2.0 / 3.0)).map(_.flatten)
    } yield expect.all(qc.isRight, verified === Right(()))
  }

  test("prepare QC rejects under-quorum and out-of-pool votes") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(5)
      corePairs = pairs.take(4)
      core = corePairs.map(peerId)
      outsiderId = peerId(pairs.last)
      value = withCommittee(core)
      two <- corePairs.take(2).traverse(signOutcomeVote[IO](value, _).map(_._2))
      outsider <- signOutcomeVote[IO](value, pairs.last).map(_._2)
      result <- buildProposalQc[IO](
        value,
        SortedMap.from(core.take(2).zip(two) :+ (outsiderId -> outsider)),
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
      value = withCommittee(ids)
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      proposalQcEither <- buildProposalQc[IO](value, SortedMap.from(ids.take(3).zip(votes)), ids.toSet, 2.0 / 3.0)
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
      value = withCommittee(ids)
      votes <- pairs.take(3).traverse(signOutcomeVote[IO](value, _).map(_._2))
      qcEither <- buildProposalQc[IO](value, SortedMap.from(ids.take(3).zip(votes)), ids.toSet, 2.0 / 3.0)
      qc <- IO.fromEither(qcEither.leftMap(new IllegalStateException(_)))
      decoded <- List.fill(64)(IO(decode[CertifiedProposalQC](qc.asJson.noSpaces))).parSequence
    } yield expect(decoded.forall(_ === Right(qc)))
  }

  test("different valid QC signer subsets certify one semantic valueHash") { res =>
    implicit val hasher: Hasher[IO] = res._1
    implicit val provider: SecurityProvider[IO] = res._2

    for {
      pairs <- keyPairs(4)
      ids = pairs.map(peerId)
      value = withCommittee(ids)
      votes <- pairs.traverse(signOutcomeVote[IO](value, _).map(_._2))
      first <- buildProposalQc[IO](value, SortedMap.from(ids.take(3).zip(votes.take(3))), ids.toSet, 2.0 / 3.0)
      second <- buildProposalQc[IO](value, SortedMap.from(ids.drop(1).zip(votes.drop(1))), ids.toSet, 2.0 / 3.0)
    } yield
      expect.all(
        first.exists(qc => second.exists(_.valueHash === qc.valueHash)),
        first.exists(qc => second.exists(_.signatures =!= qc.signatures))
      )
  }
}
