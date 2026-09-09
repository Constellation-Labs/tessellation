package io.constellationnetwork.currency.l0.snapshot

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusOutcome, Finished}
import io.constellationnetwork.currency.l0.snapshot.synchronous._
import io.constellationnetwork.currency.schema.currency.{CurrencySnapshot, CurrencySnapshotContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.TimeTrigger
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema.CurrencyStateProofSelector
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

import io.circe.Encoder
import weaver.MutableIOSuite

object CurrencySynchronousOutcomeValidatorSuite extends MutableIOSuite {

  implicit val currencyStateProofSelector: CurrencyStateProofSelector = CurrencyStateProofSelector.instance

  final case class TestContext(serializer: JsonSerializer[IO], hasher: Hasher[IO], securityProvider: SecurityProvider[IO])

  override type Res = TestContext

  override def sharedResource: Resource[IO, Res] =
    for {
      serializer <- Resource.eval(JsonSerializer.forAsync[IO])
      securityProvider <- SecurityProvider.forAsync[IO]
      hasher = {
        implicit val jsonSerializer: JsonSerializer[IO] = serializer
        Hasher.forJson[IO]
      }
    } yield TestContext(serializer, hasher, securityProvider)

  private val metagraphId = Address.fromBytes("currency-synchronous-outcome".getBytes("UTF-8"))

  private def signWith[A: Encoder](value: A, keys: List[KeyPair])(
    implicit hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[Signed[A]] =
    keys.traverse(Signed.forAsyncHasher[IO, A](value, _)).map { signed =>
      Signed(value, NonEmptySet.fromSetUnsafe(SortedSet.from(signed.map(_.proofs.head))))
    }

  private def fixture(
    implicit serializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ): IO[(CurrencyConsensusOutcome, Signed[CurrencySnapshotArtifact], CurrencySnapshotContext, List[KeyPair], PeerId)] =
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      candidateKey <- KeyPairGenerator.makeKeyPair[IO]
      genesis = CurrencySnapshot.mkGenesis(Map.empty, None, None)
      signedGenesis <- Signed.forAsyncHasher[IO, CurrencySnapshot](genesis, keys.head)
      hashedGenesis <- signedGenesis.toHashed[IO]
      artifactValue <- CurrencySnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
      artifact <- signWith(artifactValue, keys)
      binaryHash = Hash.fromBytes("canonical-binary".getBytes("UTF-8"))
      committee = keys.map(key => PeerId.fromPublic(key.getPublic)).sorted
      candidate = PeerId.fromPublic(candidateKey.getPublic)
      facilitatorsHash <- committee.hash
      context = CurrencySnapshotContext(metagraphId, hashedGenesis.info.toCurrencySnapshotInfo)
      finished = Finished(
        artifact,
        binaryHash,
        context,
        TimeTrigger,
        Candidates(Set(candidate)),
        facilitatorsHash,
        candidate.some
      )
      outcome = CurrencyConsensusOutcome(
        artifact.ordinal,
        Facilitators(committee),
        RemovedFacilitators.empty,
        WithdrawnFacilitators.empty,
        finished
      )
    } yield (outcome, artifact, context, keys, candidate)

  test("the exact downloaded artifact authorizes both an incumbent and its accepted candidate") { implicit res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val securityProvider: SecurityProvider[IO] = res.securityProvider

    fixture.flatMap {
      case (outcome, artifact, context, keys, candidate) =>
        val incumbent = PeerId.fromPublic(keys.head.getPublic)

        (
          CurrencySnapshotConsensus.validateObservedOutcome(incumbent, outcome, outcome.key, artifact, context),
          CurrencySnapshotConsensus.validateObservedOutcome(candidate, outcome, outcome.key, artifact, context)
        ).mapN((incumbentAccepted, candidateAccepted) => expect.all(incumbentAccepted, candidateAccepted))
    }
  }

  test("an unlisted observer cannot acquire committee authority from a valid public artifact") { implicit res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val securityProvider: SecurityProvider[IO] = res.securityProvider

    fixture.flatMap {
      case (outcome, artifact, context, _, _) =>
        KeyPairGenerator.makeKeyPair[IO].flatMap { outsiderKey =>
          CurrencySnapshotConsensus
            .validateObservedOutcome(PeerId.fromPublic(outsiderKey.getPublic), outcome, outcome.key, artifact, context)
            .map(accepted => expect(!accepted))
        }
    }
  }

  test("randomized re-signing cannot substitute a different private proof envelope") { implicit res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val securityProvider: SecurityProvider[IO] = res.securityProvider

    fixture.flatMap {
      case (outcome, artifact, context, keys, _) =>
        signWith(artifact.value, keys).flatMap { resigned =>
          val substituted = outcome.copy(finished = outcome.finished.copy(signedMajorityArtifact = resigned))
          CurrencySnapshotConsensus
            .validateObservedOutcome(PeerId.fromPublic(keys.head.getPublic), substituted, outcome.key, artifact, context)
            .map(accepted => expect(!accepted))
        }
    }
  }

  test("key, context, committee hash, and disjoint membership remain exact") { implicit res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val securityProvider: SecurityProvider[IO] = res.securityProvider

    fixture.flatMap {
      case (outcome, artifact, context, keys, candidate) =>
        val self = PeerId.fromPublic(keys.head.getPublic)
        val wrongContext = context.copy(address = Address.fromBytes("wrong-metagraph".getBytes("UTF-8")))
        val wrongHash = outcome.copy(finished = outcome.finished.copy(facilitatorsHash = outcome.finished.binaryArtifactHash))
        val overlapping = outcome.copy(removedFacilitators = RemovedFacilitators(Set(candidate)))

        List(
          CurrencySnapshotConsensus.validateObservedOutcome(self, outcome, outcome.key.next, artifact, context),
          CurrencySnapshotConsensus.validateObservedOutcome(self, outcome, outcome.key, artifact, wrongContext),
          CurrencySnapshotConsensus.validateObservedOutcome(self, wrongHash, outcome.key, artifact, context),
          CurrencySnapshotConsensus.validateObservedOutcome(self, overlapping, outcome.key, artifact, context)
        ).sequence.map(results => expect(results.forall(!_)))
    }
  }

  test("a downloaded private outcome cannot carry a cursor outside its selected candidate set") { implicit res =>
    implicit val serializer: JsonSerializer[IO] = res.serializer
    implicit val hasher: Hasher[IO] = res.hasher
    implicit val securityProvider: SecurityProvider[IO] = res.securityProvider

    fixture.flatMap {
      case (outcome, artifact, context, keys, _) =>
        KeyPairGenerator.makeKeyPair[IO].flatMap { unrelated =>
          val tampered = outcome.copy(finished = outcome.finished.copy(candidateCursor = PeerId.fromPublic(unrelated.getPublic).some))
          CurrencySnapshotConsensus
            .validateObservedOutcome(PeerId.fromPublic(keys.head.getPublic), tampered, outcome.key, artifact, context)
            .map(accepted => expect(!accepted))
        }
    }
  }
}
