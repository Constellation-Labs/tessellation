package io.constellationnetwork.currency.l0.snapshot

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration.{Duration, _}

import io.constellationnetwork.currency.l0.snapshot.schema.CurrencyConsensusKind._
import io.constellationnetwork.currency.l0.snapshot.synchronous._
import io.constellationnetwork.currency.l0.snapshot.synchronous.update.UnlockConsensusUpdate
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{EventTrigger, TimeTrigger}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Pins the two membership rules that keep a small synchronous metagraph recoverable:
  *
  *   - ACK removal uses the exact release/mainnet fixed-universe thresholds;
  *   - one successful round cannot admit more peers than the incumbent committee can subsequently ACK-remove.
  */
object CurrencySynchronousCommitteeSuite extends SimpleIOSuite {

  private def peer(n: Int): PeerId = PeerId(Hex(f"$n%064x"))

  pureTest("the complete Facility event union is deterministically capped") {
    val limit = io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool.DefaultSnapshotLimit
    val hashes = (0 to limit).map(index => Hash.fromBytes(BigInt(index).toByteArray))
    val bounded = CurrencySnapshotConsensusStateAdvancer.boundedFacilityEventHashes(hashes)

    expect.all(bounded.size === limit, bounded === CurrencySnapshotConsensusStateAdvancer.boundedFacilityEventHashes(hashes.reverse))
  }

  pureTest("per-member Facility shares keep singleton, ordinary, and large committees inside one fixed work budget") {
    val limit = io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool.DefaultSnapshotLimit

    expect.all(
      CurrencySnapshotConsensusStateCreator.facilityEventLimit(1) === limit,
      CurrencySnapshotConsensusStateCreator.facilityEventLimit(3) === limit / 3,
      CurrencySnapshotConsensusStateCreator.facilityEventLimit(73) === limit / 73
    )
  }

  pureTest("availability fan-out budgets every bounded-concurrency wave") {
    expect.all(
      CurrencySnapshotConsensusStateCreator.availabilityFanoutDeadline(0, 8) === 6.seconds,
      CurrencySnapshotConsensusStateCreator.availabilityFanoutDeadline(7, 8) === 6.seconds,
      CurrencySnapshotConsensusStateCreator.availabilityFanoutDeadline(8, 8) === 6.seconds,
      CurrencySnapshotConsensusStateCreator.availabilityFanoutDeadline(9, 8) === 11.seconds,
      CurrencySnapshotConsensusStateCreator.availabilityFanoutDeadline(20, 8) === 16.seconds
    )
  }

  test("availability confirmations for a 73-member committee use bounded concurrency") {
    val peers = (2 to 73).toList.map(peer)
    val hashes = Set(Hash("event-a"), Hash("event-b"))

    def awaitStarted(ref: Ref[IO, (Int, Int, Int)]): IO[Unit] =
      ref.get.flatMap {
        case (_, _, started) =>
          if (started >= CurrencySnapshotConsensusStateCreator.availabilityProbeParallelism) IO.unit
          else IO.sleep(5.millis) >> awaitStarted(ref)
      }

    for {
      gate <- Deferred[IO, Unit]
      concurrency <- Ref.of[IO, (Int, Int, Int)]((0, 0, 0))
      fiber <- CurrencySnapshotConsensusStateCreator
        .retainUniversallyAvailableHashes[IO](hashes, peers) { (_, requested) =>
          concurrency.update {
            case (active, maximum, started) =>
              val next = active + 1
              (next, math.max(maximum, next), started + 1)
          } >> gate.get.guarantee(concurrency.update { case (active, maximum, started) => (active - 1, maximum, started) }).as(requested)
        }
        .start
      _ <- awaitStarted(concurrency).timeout(2.seconds)
      beforeRelease <- concurrency.get
      _ <- gate.complete(())
      result <- fiber.joinWithNever
      after <- concurrency.get
    } yield
      expect.all(
        beforeRelease._2 === CurrencySnapshotConsensusStateCreator.availabilityProbeParallelism,
        after._2 === CurrencySnapshotConsensusStateCreator.availabilityProbeParallelism,
        after._3 === peers.size,
        result.toSet === hashes
      )
  }

  test("one unavailable facilitator defers the event set without blocking an empty round") {
    val peers = List(peer(2), peer(3))
    val hashes = Set(Hash("event-a"), Hash("event-b"))

    CurrencySnapshotConsensusStateCreator
      .retainUniversallyAvailableHashes[IO](hashes, peers) {
        case (id, _) if id === peer(3) => Set.empty[Hash].pure[IO]
        case (_, requested)            => requested.pure[IO]
      }
      .map(result => expect(result.isEmpty))
  }

  test("empty event availability performs no network probes") {
    val peers = (2 to 73).toList.map(peer)

    for {
      calls <- Ref.of[IO, Int](0)
      result <- CurrencySnapshotConsensusStateCreator.retainUniversallyAvailableHashes[IO](Set.empty, peers) { (_, requested) =>
        calls.update(_ + 1).as(requested)
      }
      count <- calls.get
    } yield expect.all(result.isEmpty, count === 0)
  }

  test("the aggregate availability deadline defers all events when a facilitator does not answer") {
    val peers = List(peer(2), peer(3))
    val hashes = Set(Hash("event-a"))

    CurrencySnapshotConsensusStateCreator
      .retainUniversallyAvailableHashes[IO](hashes, peers, deadline = 100.millis.some) {
        case (id, _) if id === peer(3) => IO.never
        case (_, requested)            => requested.pure[IO]
      }
      .map(result => expect(result.isEmpty))
  }

  pureTest("an all-None first-round Facility set uses the pinned EventTrigger default") {
    expect.all(
      CurrencySnapshotConsensusStateAdvancer.selectFacilityTrigger(List(none, none)) === EventTrigger,
      CurrencySnapshotConsensusStateAdvancer.selectFacilityTrigger(List(none, TimeTrigger.some, none)) === TimeTrigger,
      CurrencySnapshotConsensusStateAdvancer.selectFacilityTrigger(
        List(EventTrigger.some, none, TimeTrigger.some, EventTrigger.some)
      ) === EventTrigger
    )
  }

  private def lockedState(members: List[PeerId]): ConsensusState[Int, Option[Unit], Unit, Unit] =
    ConsensusState(
      key = 1,
      lastOutcome = (),
      facilitators = Facilitators(members),
      status = ().some,
      createdAt = Duration.Zero,
      lockStatus = LockStatus.Closed,
      spreadAckKinds = Set.empty
    )

  private def unlock(
    members: List[PeerId],
    acknowledgements: Map[(PeerId, Unit), Set[PeerId]]
  ): IO[ConsensusState[Int, Option[Unit], Unit, Unit]] =
    UnlockConsensusUpdate
      .tryUnlock[IO, ConsensusState[Int, Option[Unit], Unit, Unit], Unit](acknowledgements)(_.status)
      .run(lockedState(members))
      .map(_._1)

  test("one missing member is ACK-removed for N=3 through N=7") {
    (3 to 7).toList.traverse { n =>
      val members = (1 to n).toList.map(peer)
      val active = members.dropRight(1)
      val acks = active.map(voter => (voter, ()) -> active.toSet).toMap

      unlock(members, acks).map { result =>
        expect.all(
          result.lockStatus === LockStatus.Reopened,
          result.facilitators.value === active,
          result.removedFacilitators.value === Set(members.last)
        )
      }
    }.map(_.combineAll)
  }

  test("N=1 through N=7 progress exactly when a strict majority resolves every member") {
    (1 to 7).toList.traverse { n =>
      val members = (1 to n).toList.map(peer)
      val required = n / 2 + 1
      val sufficient = members.take(required)
      val insufficient = members.take(required - 1)
      val sufficientAcks = sufficient.map(voter => (voter, ()) -> sufficient.toSet).toMap
      val insufficientAcks = insufficient.map(voter => (voter, ()) -> insufficient.toSet).toMap

      (unlock(members, sufficientAcks), unlock(members, insufficientAcks)).mapN { (progressed, held) =>
        expect.all(
          progressed.lockStatus === LockStatus.Reopened,
          progressed.facilitators.value === sufficient,
          progressed.removedFacilitators.value === members.drop(required).toSet,
          held === lockedState(members)
        )
      }
    }.map(_.combineAll)
  }

  test("N=2 cannot silently contract to singleton when one member disappears") {
    val members = List(peer(1), peer(2))
    val acks = Map((peer(1), ()) -> Set(peer(1)))

    unlock(members, acks).map(result => expect.same(lockedState(members), result))
  }

  test("inconclusive ACK evidence leaves the phase and committee locked") {
    val members = List(peer(1), peer(2), peer(3), peer(4))
    val acks = Map(
      (peer(1), ()) -> Set(peer(1), peer(2), peer(3)),
      (peer(2), ()) -> Set(peer(1), peer(2), peer(4))
    )

    unlock(members, acks).map(result => expect.same(lockedState(members), result))
  }

  pureTest("candidate selection is deterministic and preserves incumbent ACK headroom") {
    val registered = Set(peer(6), peer(5), peer(4), peer(3), peer(2))
    val singleton = CurrencySnapshotConsensusStateAdvancer.selectCandidates(Set(peer(1)), registered, none, 20)
    val two = CurrencySnapshotConsensusStateAdvancer.selectCandidates(Set(peer(1), peer(2)), registered, none, 20)
    val three = CurrencySnapshotConsensusStateAdvancer.selectCandidates(Set(peer(1), peer(2), peer(3)), registered, none, 20)

    expect.all(
      singleton.candidates.value === Set(peer(2), peer(3)),
      two.candidates.value === Set(peer(3)),
      three.candidates.value === Set(peer(4), peer(5))
    )
  }

  test("post-Facility contraction re-caps candidates against the retained incumbents") {
    (3 to 7).toList.traverse { initialSize =>
      val initial = (1 to initialSize).toList.map(peer)
      val retained = initial.dropRight(1)
      val originallySelected = CurrencySnapshotConsensusStateAdvancer
        .selectCandidates(
          initial.toSet,
          Set(peer(20), peer(21), peer(22), peer(23), peer(24), peer(25)),
          none,
          20
        )
      val finalCandidates = CurrencySnapshotConsensusStateAdvancer.selectCandidates(
        retained.toSet,
        originallySelected.candidates.value,
        none,
        20
      )
      val nextRound = retained ++ finalCandidates.candidates.value.toList.sorted
      val incumbentAcks = retained.map(voter => (voter, ()) -> retained.toSet).toMap

      unlock(nextRound, incumbentAcks).map { result =>
        expect.all(
          finalCandidates.candidates.value.size <= retained.size - 1,
          result.lockStatus === LockStatus.Reopened,
          result.facilitators.value === retained,
          result.removedFacilitators.value === finalCandidates.candidates.value
        )
      }
    }.map(_.combineAll)
  }

  pureTest("the controlled singleton keeps the explicit two-candidate bootstrap bound") {
    val incumbent = peer(1)
    val registered = Set(peer(2), peer(3), peer(4))

    expect(
      CurrencySnapshotConsensusStateAdvancer
        .selectCandidates(Set(incumbent), registered, none, 3)
        .candidates
        .value === Set(peer(2), peer(3))
    )
  }

  pureTest("the private cursor gives every stable registered peer a bounded admission turn across observation gaps") {
    val incumbents = Set(peer(10), peer(11))
    val registered = Set(peer(1), peer(2), peer(3), peer(4), peer(5))
    val (_, selectedAcrossFiveAttempts) = (0 until 5).foldLeft((none[PeerId], Set.empty[PeerId])) {
      case ((cursor, selected), _) =>
        val next = CurrencySnapshotConsensusStateAdvancer.selectCandidates(incumbents, registered, cursor, 20)
        next.cursor -> (selected ++ next.candidates.value)
    }

    expect.same(registered, selectedAcrossFiveAttempts)
  }

  pureTest("the legacy flat-committee cap bounds admissions without shedding incumbents") {
    val seventeen = (1 to 17).map(peer).toSet
    val twenty = (1 to 20).map(peer).toSet
    val overCap = (1 to 22).map(peer).toSet
    val registered = (30 to 40).map(peer).toSet

    expect.all(
      CurrencySnapshotConsensusStateAdvancer.selectCandidates(seventeen, registered, none, 20).candidates.value.size === 3,
      CurrencySnapshotConsensusStateAdvancer.selectCandidates(twenty, registered, none, 20).candidates.value.isEmpty,
      CurrencySnapshotConsensusStateAdvancer.selectCandidates(overCap, registered, none, 20).candidates.value.isEmpty
    )
  }

  test("next-round candidate headroom uses eligibility from the newly signed context") {
    val incumbents = List(peer(1), peer(2), peer(3))
    val registered = Set(peer(4), peer(5))

    CurrencySnapshotConsensusStateAdvancer
      .projectNextRoundMembership[IO](incumbents, registered, none, 20, _ => true)(peerId => IO.pure(peerId =!= peer(3)))
      .map { projected =>
        expect(projected.exists {
          case (next, candidates, _) =>
            next === List(peer(1), peer(2)) && candidates === Candidates(Set(peer(4)))
        })
      }
  }

  test("a child that leaves no eligible incumbent cannot publish an authority-free outcome") {
    CurrencySnapshotConsensusStateAdvancer
      .projectNextRoundMembership[IO](
        List(peer(1), peer(2), peer(3)),
        Set(peer(4)),
        none,
        20,
        _ => true
      )(_ => IO.pure(false))
      .map(projected => expect(projected.isEmpty))
  }

  pureTest("withdrawal targets the first declaration the leaving node has not emitted") {
    val key = SnapshotOrdinal.unsafeApply(10L)
    val nextKey = SnapshotOrdinal.unsafeApply(11L)

    expect.all(
      CurrencySnapshotConsensusStateRemover.withdrawalTarget(key, none, hasActiveState = false) === (key -> Facility),
      CurrencySnapshotConsensusStateRemover.withdrawalTarget(key, Facility.some, hasActiveState = true) ===
        (key -> Proposal),
      CurrencySnapshotConsensusStateRemover.withdrawalTarget(key, Proposal.some, hasActiveState = true) === (key -> Signature),
      CurrencySnapshotConsensusStateRemover.withdrawalTarget(key, Signature.some, hasActiveState = true) ===
        (key -> BinarySignature),
      CurrencySnapshotConsensusStateRemover.withdrawalTarget(key, BinarySignature.some, hasActiveState = true) ===
        (nextKey -> Facility),
      CurrencySnapshotConsensusStateRemover.withdrawalTarget(key, none, hasActiveState = true) === (nextKey -> Facility)
    )
  }

  pureTest("peer-ahead re-anchor requires a strict majority of the frozen authority") {
    val authority = Set(peer(1), peer(2), peer(3))
    val localKey = 10

    expect.all(
      !ConsensusManager.hasStrictAuthorityMajorityAhead(peer(1), localKey, authority, Map(peer(2) -> 11.some)),
      ConsensusManager.hasStrictAuthorityMajorityAhead(
        peer(1),
        localKey,
        authority,
        Map(peer(2) -> 11.some, peer(3) -> 12.some)
      ),
      !ConsensusManager.hasStrictAuthorityMajorityAhead(
        peer(1),
        localKey,
        authority,
        Map(peer(2) -> 11.some, peer(4) -> 11.some)
      ),
      !ConsensusManager.hasStrictAuthorityMajorityAhead(
        peer(1),
        localKey,
        authority,
        Map(peer(2) -> 9.some, peer(3) -> none)
      )
    )
  }

  pureTest("a benign one-round peer lead preserves the installed immediate-successor attempt") {
    val authority = Set(peer(1), peer(2), peer(3))
    val immediateSuccessor = Map(peer(2) -> 11.some, peer(3) -> 11.some)
    val beyondImmediateSuccessor = Map(peer(2) -> 12.some, peer(3) -> 12.some)

    expect.all(
      ConsensusManager.hasStrictAuthorityMajorityAhead(peer(1), 10, authority, immediateSuccessor),
      !ConsensusManager.hasStrictAuthorityMajorityAhead(peer(1), 11, authority, immediateSuccessor),
      ConsensusManager.hasStrictAuthorityMajorityAhead(peer(1), 11, authority, beyondImmediateSuccessor),
      ConsensusManager.preservePeerAheadGeneration(
        hasCurrentGeneration = true,
        currentGenerationFinished = false,
        authorityMajorityBeyondImmediateSuccessor = false
      ),
      !ConsensusManager.preservePeerAheadGeneration(
        hasCurrentGeneration = true,
        currentGenerationFinished = false,
        authorityMajorityBeyondImmediateSuccessor = true
      ),
      !ConsensusManager.preservePeerAheadGeneration(
        hasCurrentGeneration = false,
        currentGenerationFinished = false,
        authorityMajorityBeyondImmediateSuccessor = false
      ),
      ConsensusManager.preservePeerAheadGeneration(
        hasCurrentGeneration = true,
        currentGenerationFinished = true,
        authorityMajorityBeyondImmediateSuccessor = true
      )
    )
  }

  pureTest("downloaded private outcome requires the exact ACK-minimum corroboration cohort") {
    val evenSplit = ConsensusManager.analyzeCorroboratedOutcome(
      4,
      List(peer(1) -> "left", peer(2) -> "left", peer(3) -> "right", peer(4) -> "right")
    )

    expect.all(
      ConsensusManager.selectCorroboratedOutcome(1, List(peer(1) -> "root")).contains("root"),
      ConsensusManager
        .selectCorroboratedOutcome(3, List(peer(1) -> "honest", peer(2) -> "honest", peer(3) -> "liar"))
        .contains("honest"),
      ConsensusManager
        .selectCorroboratedOutcome(4, List(peer(1) -> "retained", peer(2) -> "retained"))
        .contains("retained"),
      ConsensusManager
        .selectCorroboratedOutcome(4, List(peer(1) -> "left", peer(2) -> "left", peer(3) -> "right", peer(4) -> "right"))
        .isEmpty,
      evenSplit.ambiguous,
      evenSplit.selected.isEmpty,
      evenSplit.threshold == 2,
      evenSplit.validResponses == 4,
      evenSplit.maxMatching == 2,
      evenSplit.distinctValidValues == 2,
      ConsensusManager
        .selectCorroboratedOutcome(3, List(peer(1) -> "same", peer(1) -> "same", peer(2) -> "other"))
        .isEmpty,
      ConsensusManager.selectCorroboratedOutcome[String](3, List.empty).isEmpty
    )
  }

  pureTest("a two-member authority may re-anchor from its sole authenticated peer but never adopts private authority") {
    val authority = Set(peer(1), peer(2))
    val localKey = 10

    expect.all(
      ConsensusManager.hasStrictAuthorityMajorityAhead(peer(1), localKey, authority, Map(peer(2) -> 11.some)),
      !ConsensusManager.hasStrictAuthorityMajorityAhead(peer(1), localKey, authority, Map(peer(2) -> 10.some)),
      !ConsensusManager.hasStrictAuthorityMajorityAhead(peer(1), localKey, authority, Map(peer(3) -> 11.some)),
      !ConsensusManager.hasStrictAuthorityMajorityAhead(peer(3), localKey, authority, Map(peer(1) -> 11.some))
    )
  }
}
