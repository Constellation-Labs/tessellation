package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Eq
import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.ProposalQC
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import io.chrisdavenport.mapref.MapRef
import monocle.Lens
import weaver.SimpleIOSuite

/** Behavioral tests for the VoteLock subsystem of ConsensusStorage. Full ConsensusStorage requires many type-class witnesses that make a
  * direct test prohibitively boilerplate-heavy; these tests exercise the same invariants by driving the VoteLock manipulation logic against
  * a MapRef of the same shape that ConsensusStorage uses internally.
  */
object ConsensusStorageLockSuite extends SimpleIOSuite {

  private final case class StoredOutcome(key: SnapshotOrdinal)

  private implicit val storedOutcomeEq: Eq[StoredOutcome] = Eq.fromUniversalEquals

  private implicit val storedOutcomeKeyLens: Lens[StoredOutcome, SnapshotOrdinal] =
    Lens[StoredOutcome, SnapshotOrdinal](_.key)(key => outcome => outcome.copy(key = key))

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

  private def storage(
    policy: LegacyViewChangePolicy
  ): IO[ConsensusStorage[IO, Unit, SnapshotOrdinal, Unit, Unit, Unit, StoredOutcome, Unit]] =
    ConsensusStorage.make[IO, Unit, SnapshotOrdinal, Unit, Unit, Unit, StoredOutcome, Unit](consensusConfig, policy)

  private val hashA: Hash = Hash.fromBytes("A".getBytes("UTF-8"))
  private val hashB: Hash = Hash.fromBytes("B".getBytes("UTF-8"))
  private val facHash: Hash = Hash.fromBytes("FAC".getBytes("UTF-8"))

  private def dummyProof: SignatureProof =
    SignatureProof(Id(Hex("00")), Signature(Hex("00")))

  private def qc(view: Long, proposalHash: Hash): ProposalQC =
    ProposalQC(view, proposalHash, facHash, NonEmptySet.of(dummyProof))

  private def tryLockVote(
    voteLocksR: MapRef[IO, Long, Option[VoteLock]],
    key: Long,
    view: Long,
    proposalHash: Hash,
    effectiveLockedQc: Option[ProposalQC]
  ): IO[Either[VoteRejection, VoteLock]] =
    voteLocksR(key).modify { maybeLock =>
      val current = maybeLock.getOrElse(VoteLock.empty)
      current.acceptVote(view, proposalHash, effectiveLockedQc, LegacyViewChangePolicy.FreezeAfterVote) match {
        case Right(newLock)  => (newLock.some, Right(newLock))
        case Left(rejection) => (maybeLock, Left(rejection))
      }
    }

  test("tryLockVote atomic race: two concurrent calls with different hashes — exactly one succeeds") {
    MapRef.ofConcurrentHashMap[IO, Long, VoteLock]().flatMap { voteLocksR =>
      val key = 1L
      val viewN = 0L
      for {
        // Run two concurrent lock attempts at the same view with DIFFERENT proposal hashes.
        outcomes <- IO.both(
          tryLockVote(voteLocksR, key, viewN, hashA, None),
          tryLockVote(voteLocksR, key, viewN, hashB, None)
        )
        (r1, r2) = outcomes
        oneSucceeded = r1.isRight ^ r2.isRight
      } yield
        expect(
          oneSucceeded,
          s"expected exactly one of the two concurrent lock attempts to succeed, got r1=$r1 r2=$r2"
        )
    }
  }

  test("tryLockVote same-view same-hash concurrent calls both succeed (idempotent)") {
    MapRef.ofConcurrentHashMap[IO, Long, VoteLock]().flatMap { voteLocksR =>
      val key = 2L
      val viewN = 1L
      for {
        outcomes <- IO.both(
          tryLockVote(voteLocksR, key, viewN, hashA, None),
          tryLockVote(voteLocksR, key, viewN, hashA, None)
        )
        (r1, r2) = outcomes
      } yield expect(r1.isRight && r2.isRight, s"same-view same-hash should both succeed, got r1=$r1 r2=$r2")
    }
  }

  test("tryLockVote rejects higher-view attempt when lockedQc enforces a different hash") {
    MapRef.ofConcurrentHashMap[IO, Long, VoteLock]().flatMap { voteLocksR =>
      val key = 3L
      val lockedOnA = qc(view = 5L, proposalHash = hashA)
      for {
        first <- tryLockVote(voteLocksR, key, view = 5L, hashA, lockedOnA.some)
        second <- tryLockVote(voteLocksR, key, view = 6L, hashB, lockedOnA.some)
      } yield
        expect(first.isRight, s"initial vote should succeed, got: $first")
          .and(expect(second.isLeft, s"subsequent higher-view vote for different hash should fail, got: $second"))
    }
  }

  test("clearVoteLock removes the lock entry for a key") {
    MapRef.ofConcurrentHashMap[IO, Long, VoteLock]().flatMap { voteLocksR =>
      val key = 4L
      for {
        _ <- tryLockVote(voteLocksR, key, view = 0L, hashA, None)
        before <- voteLocksR(key).get
        _ <- voteLocksR(key).set(none)
        after <- voteLocksR(key).get
      } yield
        expect(before.isDefined, "lock should exist after vote").and(expect(after.isEmpty, "lock should be cleared after explicit clear"))
    }
  }

  test("real ConsensusStorage retains FreezeAfterVote locks across abandon cleanup and clears PreserveLegacy locks") {
    val key = SnapshotOrdinal.unsafeApply(7L)

    for {
      freeze <- storage(LegacyViewChangePolicy.FreezeAfterVote)
      preserve <- storage(LegacyViewChangePolicy.PreserveLegacy)
      freezeVote <- freeze.tryLockVote(key, view = 0L, hashA, effectiveLockedQc = None)
      preserveVote <- preserve.tryLockVote(key, view = 0L, hashA, effectiveLockedQc = None)
      _ <- freeze.clearResourcesPreservingDeclarations(key)
      _ <- preserve.clearResourcesPreservingDeclarations(key)
      freezeAfter <- freeze.getVoteLock(key)
      preserveAfter <- preserve.getVoteLock(key)
    } yield
      expect(freezeVote.isRight) &&
        expect(preserveVote.isRight) &&
        expect(freezeAfter.nonEmpty, "FreezeAfterVote must retain the local vote lock across same-key abandon cleanup") &&
        expect(preserveAfter.isEmpty, "PreserveLegacy must clear the old attempt lock to retain exact rc.7 retry behavior")
  }

  test("successful local pacemaker emission advances the real storage progress epoch exactly once") {
    val key = SnapshotOrdinal.unsafeApply(8L)

    for {
      consensusStorage <- storage(LegacyViewChangePolicy.FreezeAfterVote)
      before <- consensusStorage.getResourceGeneration(key)
      _ <- consensusStorage.markPacemakerEmissionProgress(key)
      after <- consensusStorage.getResourceGeneration(key)
    } yield expect(after == before + 1L)
  }

}
