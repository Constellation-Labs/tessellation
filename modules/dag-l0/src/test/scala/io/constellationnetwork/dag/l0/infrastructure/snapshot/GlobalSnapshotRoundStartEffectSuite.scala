package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Eq
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{ConsensusState, Facilitators}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import monocle.Lens
import weaver.SimpleIOSuite

object GlobalSnapshotRoundStartEffectSuite extends SimpleIOSuite {

  private final case class Outcome(key: SnapshotOrdinal)

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
  private val entropy = Hash.fromBytes("round-start-effect-suite".getBytes("UTF-8"))

  private def storage: IO[Storage] =
    ConsensusStorage.make[IO, Unit, SnapshotOrdinal, Unit, Unit, String, Outcome, Unit](consensusConfig)

  private def state(key: SnapshotOrdinal): State =
    ConsensusState(
      key = key,
      lastOutcome = Outcome(key),
      facilitators = Facilitators(List(leader)),
      roundStartFacilitators = Facilitators(List(leader)),
      status = "committed",
      createdAt = Duration.Zero,
      leader = leader,
      entropy = entropy
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

  test("atomic replacement vote observes the committed round before Facility delivery") {
    val key = SnapshotOrdinal.unsafeApply(40L)

    for {
      consensusStorage <- storage
      observed <- Ref.of[IO, List[String]](List.empty)
      vote = consensusStorage.getState(key).flatMap { committed =>
        observed.update(_ :+ s"vote-state-${committed.isDefined}")
      }
      assembly = observed.update(_ :+ "assembly")
      facility = observed.update(_ :+ "facility")
      roundStartEffect = GlobalSnapshotConsensusStateCreator.atomicReplacementRoundStartEffect(
        alreadyVoted = false,
        emitVote = vote,
        checkAssembly = assembly
      )
      before <- observed.get
      _ <- installWithEffect(consensusStorage, key, state(key), roundStartEffect >> facility)
      after <- observed.get
    } yield
      expect(before.isEmpty, s"the retained effect ran before state installation: $before") &&
        expect.same(List("vote-state-true", "assembly", "facility"), after)
  }

  test("an existing vote skips signing but still checks certificate assembly") {
    for {
      observed <- Ref.of[IO, List[String]](List.empty)
      effect = GlobalSnapshotConsensusStateCreator.atomicReplacementRoundStartEffect(
        alreadyVoted = true,
        emitVote = observed.update(_ :+ "vote"),
        checkAssembly = observed.update(_ :+ "assembly")
      )
      _ <- effect
      after <- observed.get
    } yield expect.same(List("assembly"), after)
  }

  List[ConsensusTrigger](EventTrigger, TimeTrigger).foreach { trigger =>
    test(s"accepted $trigger applies its timer effect only after state installation") {
      val key = SnapshotOrdinal.unsafeApply(41L)
      for {
        consensusStorage <- storage
        timer <- Ref.of[IO, Boolean](true)
        published <- Ref.of[IO, Option[(Boolean, Boolean)]](None)
        effect = GlobalSnapshotConsensusStateAdvancer.acceptedProposalEffect(
          trigger,
          timer.set(false),
          (consensusStorage.getState(key), timer.get)
            .mapN((committed, pending) => (committed.isDefined, pending))
            .flatMap(value => published.set(value.some))
        )
        before <- timer.get
        _ <- installWithEffect(consensusStorage, key, state(key), effect)
        after <- published.get
      } yield
        expect(before, "speculative construction must leave the timer pending") &&
          expect.same(Some((true, trigger == EventTrigger)), after)
    }
  }

  test("trigger mismatch leaves the timer and proposal effects untouched") {
    val key = SnapshotOrdinal.unsafeApply(42L)
    for {
      consensusStorage <- storage
      timer <- Ref.of[IO, Boolean](true)
      published <- Ref.of[IO, Boolean](false)
      result <- GlobalSnapshotTriggerValidation.whenValid(EpochProgress.MinValue, TimeTrigger, EpochProgress.MinValue)(_ =>
        none[Unit].pure[IO]
      )(
        installWithEffect(
          consensusStorage,
          key,
          state(key),
          GlobalSnapshotConsensusStateAdvancer.acceptedProposalEffect(TimeTrigger, timer.set(false), published.set(true))
        )
      )
      pending <- timer.get
      sent <- published.get
      committed <- consensusStorage.getState(key)
    } yield
      expect(result.isEmpty, "invalid proposals must not produce a transition") &&
        expect(pending, "rejection must preserve the timer") &&
        expect(!sent, "rejection must not publish votes or signatures") &&
        expect(committed.isEmpty, "rejection must not commit accepted state")
  }

  test("cancelled accepted-Time delivery retains the committed transition for retry") {
    val key = SnapshotOrdinal.unsafeApply(43L)
    for {
      consensusStorage <- storage
      timer <- Ref.of[IO, Boolean](true)
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      published <- Ref.of[IO, Int](0)
      effect = GlobalSnapshotConsensusStateAdvancer.acceptedProposalEffect(
        TimeTrigger,
        timer.set(false),
        entered.complete(()).void >> release.get >> published.update(_ + 1)
      )
      fiber <- installWithEffect(consensusStorage, key, state(key), effect).start
      _ <- entered.get
      _ <- fiber.cancel
      committed <- consensusStorage.getState(key)
      pending <- timer.get
      beforeRetry <- published.get
      _ <- release.complete(())
      _ <- consensusStorage.resumePendingStateEffect(key)
      _ <- consensusStorage.resumePendingStateEffect(key)
      afterRetry <- published.get
    } yield
      expect(committed.isDefined, "accepted state must survive delivery cancellation") &&
        expect(!pending, "accepted Time authority must consume the timer") &&
        expect.same(0, beforeRetry) &&
        expect.same(1, afterRetry)
  }

}
