package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Eq
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import monocle.Lens
import weaver.SimpleIOSuite

object ConsensusStorageSideEffectSuite extends SimpleIOSuite {

  private final case class Outcome(key: SnapshotOrdinal, value: String)

  private implicit val outcomeEq: Eq[Outcome] = Eq.fromUniversalEquals
  private implicit val outcomeKey: Lens[Outcome, SnapshotOrdinal] =
    Lens[Outcome, SnapshotOrdinal](_.key)(key => _.copy(key = key))

  private type Storage = ConsensusStorage[IO, Unit, SnapshotOrdinal, Unit, Unit, String, Outcome, Unit]
  private type State = ConsensusState[SnapshotOrdinal, String, Outcome, Unit]

  private val consensusConfig =
    ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      eventCutter = EventCutterConfig(
        maxBinarySizeBytes = PosInt(1024),
        maxUpdateNodeParametersSize = PosInt(1024)
      )
    )

  private val leader = PeerId(Hex("01" * 64))
  private val entropy = Hash.fromBytes("storage-side-effect-suite".getBytes("UTF-8"))

  private def storage: IO[Storage] =
    ConsensusStorage.make[IO, Unit, SnapshotOrdinal, Unit, Unit, String, Outcome, Unit](consensusConfig)

  private def state(key: SnapshotOrdinal, status: String): State =
    ConsensusState(
      key = key,
      lastOutcome = Outcome(key, s"parent-$status"),
      facilitators = Facilitators(List(leader)),
      roundStartFacilitators = Facilitators(List(leader)),
      status = status,
      createdAt = Duration.Zero,
      leader = leader,
      entropy = entropy
    )

  private def facility(sourceValue: String, key: SnapshotOrdinal): Facility =
    Facility(
      eventHashes = Set(Hash.fromBytes(sourceValue.getBytes("UTF-8"))),
      candidates = Candidates(Set.empty),
      trigger = EventTrigger.some,
      facilitatorsHash = entropy,
      lastGlobalSnapshotOrdinal = key,
      lastSnapshotHash = entropy,
      consensusConfigHash = entropy.some
    )

  private def installWithEffect(
    storage: Storage,
    key: SnapshotOrdinal,
    nextState: State,
    effect: IO[Unit]
  ): IO[Option[Unit]] = {
    val modify: ModifyStateFn[IO, SnapshotOrdinal, String, Outcome, Unit, (Unit, IO[Unit])] =
      _ => ((nextState.some, ((), effect))).some.pure[IO]

    storage.condModifyStateWithSideEffect(key)(modify)
  }

  private def installPlain(storage: Storage, key: SnapshotOrdinal, nextState: State): IO[Option[Unit]] = {
    val modify: ModifyStateFn[IO, SnapshotOrdinal, String, Outcome, Unit, Unit] =
      _ => ((nextState.some, ())).some.pure[IO]

    storage.condModifyState(key)(modify)
  }

  test("failed post-commit effect remains pending and resumes exactly once") {
    val key = SnapshotOrdinal.unsafeApply(10L)

    for {
      consensusStorage <- storage
      attempts <- Ref.of[IO, Int](0)
      effect = attempts.modify(current => (current + 1, current == 0)).flatMap {
        case true  => IO.raiseError[Unit](new RuntimeException("first delivery fails"))
        case false => IO.unit
      }
      first <- installWithEffect(consensusStorage, key, state(key, "committed"), effect).attempt
      committed <- consensusStorage.getState(key)
      _ <- consensusStorage.resumePendingStateEffect(key)
      _ <- consensusStorage.resumePendingStateEffect(key)
      count <- attempts.get
    } yield
      expect(first.isLeft, s"the first effect delivery should fail, got $first") &&
        expect(committed.exists(_.status == "committed"), s"state must commit before effect delivery, got $committed") &&
        expect(count == 2, s"one failed delivery plus one successful replay expected, got $count invocations")
  }

  test("cancellation after state commit retains the exact effect for replay") {
    val key = SnapshotOrdinal.unsafeApply(11L)

    for {
      consensusStorage <- storage
      attempts <- Ref.of[IO, Int](0)
      started <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      effect = attempts.update(_ + 1) >> started.complete(()).void >> release.get
      fiber <- installWithEffect(consensusStorage, key, state(key, "committed-before-cancel"), effect).start
      _ <- started.get
      _ <- fiber.cancel
      committed <- consensusStorage.getState(key)
      _ <- release.complete(())
      _ <- consensusStorage.resumePendingStateEffect(key)
      _ <- consensusStorage.resumePendingStateEffect(key)
      count <- attempts.get
    } yield
      expect(
        committed.exists(_.status == "committed-before-cancel"),
        s"state must survive cancellation of its post-commit effect, got $committed"
      ) && expect(count == 2, s"cancelled delivery must replay once and then clear, got $count invocations")
  }

  test("failed Facility delivery replays the captured declaration after dynamic sources change") {
    val key = SnapshotOrdinal.unsafeApply(12L)
    val targets = Set(leader)

    for {
      consensusStorage <- storage
      dynamicSource <- Ref.of[IO, String]("original-mempool-health-clock-sample")
      sampled <- dynamicSource.get
      capturedFacility = facility(sampled, key)
      capturedDeclaration = ConsensusPeerDeclaration(key, capturedFacility)
      stored <- Ref.of[IO, List[Facility]](List.empty)
      gossiped <- Ref.of[IO, List[ConsensusPeerDeclaration[SnapshotOrdinal, Facility]]](List.empty)
      deliveryAttempts <- Ref.of[IO, Int](0)
      effect = ConsensusStateCreator.exactFacilityEffect[IO, SnapshotOrdinal](
        capturedFacility,
        capturedDeclaration,
        targets
      )(
        value => stored.update(_ :+ value),
        (value, _) =>
          gossiped.update(_ :+ value) >> deliveryAttempts.modify(current => (current + 1, current == 0)).flatMap {
            case true  => IO.raiseError[Unit](new RuntimeException("first direct delivery fails"))
            case false => IO.unit
          }
      )
      first <- installWithEffect(consensusStorage, key, state(key, "facility-captured"), effect).attempt
      _ <- dynamicSource.set("changed-mempool-health-clock-sample")
      changedFacility <- dynamicSource.get.map(facility(_, key))
      _ <- consensusStorage.resumePendingStateEffect(key)
      storedValues <- stored.get
      gossipedValues <- gossiped.get
    } yield
      expect(first.isLeft, s"first direct delivery should fail, got $first") &&
        expect(changedFacility =!= capturedFacility, "test precondition: dynamic source must produce a different Facility") &&
        expect(
          storedValues == List(capturedFacility, capturedFacility),
          s"self-store retry must use the captured Facility twice, got $storedValues"
        ) &&
        expect(
          gossipedValues == List(capturedDeclaration, capturedDeclaration),
          s"gossip retry must use the captured declaration twice, got $gossipedValues"
        )
  }

  test("a newer plain mutation of the same key invalidates the retained effect") {
    val key = SnapshotOrdinal.unsafeApply(12L)

    for {
      consensusStorage <- storage
      attempts <- Ref.of[IO, Int](0)
      failingEffect = attempts.update(_ + 1) >> IO.raiseError[Unit](new RuntimeException("retain me"))
      _ <- installWithEffect(consensusStorage, key, state(key, "old"), failingEffect).attempt
      _ <- installPlain(consensusStorage, key, state(key, "new"))
      _ <- consensusStorage.resumePendingStateEffect(key)
      current <- consensusStorage.getState(key)
      count <- attempts.get
    } yield
      expect(current.exists(_.status == "new"), s"new same-key state must win, got $current") &&
        expect(count == 1, s"superseded effect must not replay, got $count invocations")
  }

  test("a mutation at an unrelated key does not invalidate the retained effect") {
    val pendingKey = SnapshotOrdinal.unsafeApply(13L)
    val unrelatedKey = SnapshotOrdinal.unsafeApply(14L)

    for {
      consensusStorage <- storage
      attempts <- Ref.of[IO, Int](0)
      effect = attempts.modify(current => (current + 1, current == 0)).flatMap {
        case true  => IO.raiseError[Unit](new RuntimeException("retain across unrelated mutation"))
        case false => IO.unit
      }
      _ <- installWithEffect(consensusStorage, pendingKey, state(pendingKey, "pending"), effect).attempt
      _ <- installPlain(consensusStorage, unrelatedKey, state(unrelatedKey, "unrelated"))
      _ <- consensusStorage.resumePendingStateEffect(pendingKey)
      _ <- consensusStorage.resumePendingStateEffect(pendingKey)
      count <- attempts.get
    } yield expect(count == 2, s"unrelated-key mutation must preserve one pending replay, got $count invocations")
  }

  test("outcome update distinguishes advanced, idempotent retry, and conflict; cleanup runs only on advance") {
    val previousKey = SnapshotOrdinal.unsafeApply(20L)
    val nextKey = SnapshotOrdinal.unsafeApply(21L)
    val initialOutcome = Outcome(previousKey, "initial")
    val committedOutcome = Outcome(nextKey, "committed")

    for {
      consensusStorage <- storage
      initialized <- consensusStorage.trySetInitialConsensusOutcome(initialOutcome)
      _ <- installPlain(consensusStorage, previousKey, state(previousKey, "removed-on-advance"))
      advanced <- consensusStorage.tryUpdateLastConsensusOutcomeWithCleanup(Previous(previousKey), committedOutcome)
      afterAdvance <- consensusStorage.getState(previousKey)
      _ <- installPlain(consensusStorage, previousKey, state(previousKey, "preserved-on-retry"))
      alreadyCurrent <- consensusStorage.tryUpdateLastConsensusOutcomeWithCleanup(Previous(previousKey), committedOutcome)
      afterRetry <- consensusStorage.getState(previousKey)
      _ <- installPlain(consensusStorage, previousKey, state(previousKey, "preserved-on-conflict"))
      conflict <- consensusStorage.tryUpdateLastConsensusOutcomeWithCleanup(
        Previous(previousKey),
        committedOutcome.copy(value = "conflicting")
      )
      afterConflict <- consensusStorage.getState(previousKey)
    } yield
      expect(initialized) &&
        expect(advanced == ConsensusStorage.OutcomeUpdateResult.Advanced) &&
        expect(afterAdvance.isEmpty, s"advance must clean the previous state, got $afterAdvance") &&
        expect(alreadyCurrent == ConsensusStorage.OutcomeUpdateResult.AlreadyCurrent) &&
        expect(
          afterRetry.exists(_.status == "preserved-on-retry"),
          s"idempotent retry must not clean state again, got $afterRetry"
        ) &&
        expect(conflict == ConsensusStorage.OutcomeUpdateResult.Conflict) &&
        expect(
          afterConflict.exists(_.status == "preserved-on-conflict"),
          s"conflict must not clean state, got $afterConflict"
        )
  }
}
