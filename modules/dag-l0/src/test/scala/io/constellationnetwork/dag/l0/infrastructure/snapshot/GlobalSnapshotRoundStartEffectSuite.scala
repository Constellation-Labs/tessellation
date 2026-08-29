package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Eq
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{ConsensusState, Facilitators}
import io.constellationnetwork.schema.SnapshotOrdinal
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
}
