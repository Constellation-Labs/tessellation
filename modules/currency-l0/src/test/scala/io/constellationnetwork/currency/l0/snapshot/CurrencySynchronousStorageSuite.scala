package io.constellationnetwork.currency.l0.snapshot

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.snapshot.synchronous._
import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration.{AttemptDomain, Facility}
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.TimeTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import monocle.Lens
import weaver.SimpleIOSuite

object CurrencySynchronousStorageSuite extends SimpleIOSuite {

  @derive(eqv)
  final case class TestOutcome(key: SnapshotOrdinal)

  private implicit val outcomeKey: Lens[TestOutcome, SnapshotOrdinal] =
    Lens[TestOutcome, SnapshotOrdinal](_.key)(key => _.copy(key = key))

  private type State = ConsensusState[SnapshotOrdinal, Int, TestOutcome, Unit]

  private val config = ConsensusConfig(
    timeTriggerInterval = 10.seconds,
    declarationTimeout = 10.seconds,
    declarationRangeLimit = NonNegLong.unsafeFrom(3L),
    lockDuration = 10.seconds,
    eventCutter = EventCutterConfig(PosInt(1024), PosInt(1024))
  )

  private val key = SnapshotOrdinal.unsafeApply(10L)
  private val member = PeerId(Hex("01" * 64))

  private def domain(label: String): AttemptDomain = {
    val bytes = label.getBytes("UTF-8")
    AttemptDomain(Hash.fromBytes(bytes), Hash.fromBytes(bytes ++ Array[Byte](1)), Hash.fromBytes(bytes ++ Array[Byte](2)))
  }

  private def state(status: Int): State =
    ConsensusState(
      key,
      TestOutcome(SnapshotOrdinal.unsafeApply(9L)),
      Facilitators(List(member)),
      status,
      Duration.Zero,
      spreadAckKinds = Set.empty
    )

  test("a later phase cannot commit until the prior retained effect completes") {
    for {
      storage <- ConsensusStorage.make[IO, Unit, SnapshotOrdinal, String, Unit, Int, TestOutcome, Unit](config)
      firstStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      effects <- Ref.of[IO, Vector[String]](Vector.empty)
      firstEffect = firstStarted.complete(()).void >> releaseFirst.get >> effects.update(_ :+ "E1")
      secondEffect = effects.update(_ :+ "E2")
      secondCompleted <- Deferred[IO, Unit]
      _ <- storage.condModifyStateWithEffect(key) {
        case None    => (state(1).some, ().some, firstEffect).some.pure[IO]
        case Some(_) => new IllegalStateException("unexpected state").raiseError[IO, Option[(Option[State], Option[Unit], IO[Unit])]]
      }
      _ <- firstStarted.get
      secondFiber <- storage
        .condModifyStateWithEffect(key) {
          case Some(current) if current.status === 1 => (state(2).some, ().some, secondEffect).some.pure[IO]
          case other =>
            new IllegalStateException(s"unexpected state $other").raiseError[IO, Option[(Option[State], Option[Unit], IO[Unit])]]
        }
        .flatTap(_ => secondCompleted.complete(()))
        .start
      _ <- IO.sleep(100.millis)
      beforeRelease <- storage.getState(key)
      secondBeforeRelease <- secondCompleted.tryGet
      _ <- releaseFirst.complete(())
      _ <- secondFiber.joinWithNever
      _ <- storage.runRetainedEffect(key)
      afterRelease <- storage.getState(key)
      emitted <- effects.get
    } yield
      expect.all(
        beforeRelease.exists(_.status === 1),
        secondBeforeRelease.isEmpty,
        afterRelease.exists(_.status === 2),
        emitted === Vector("E1", "E2")
      )
  }

  test("a stale same-key declaration cannot occupy the live parent-domain slot") {
    val stale = domain("stale")
    val live = domain("live")
    val staleFacility = Facility(SortedSet.empty, Candidates.empty, TimeTrigger.some, key, stale)
    val liveFacility = staleFacility.copy(domain = live)

    for {
      storage <- ConsensusStorage.make[IO, Unit, SnapshotOrdinal, String, Unit, Int, TestOutcome, Unit](config)
      _ <- storage.addFacility(member, key, staleFacility, expectedDomain = none)
      _ <- storage.addPeerDeclarationAck(member, key, (), Set(member), stale, expectedDomain = none)
      _ <- storage.addFacility(member, key, liveFacility, expectedDomain = live.some)
      _ <- storage.retainAttemptDomain(key, live)
      resources <- storage.getResources(key)
    } yield
      expect.all(
        resources.peerDeclarationsMap.get(member).flatMap(_.facility).contains(liveFacility),
        resources.acksMap.isEmpty
      )
  }

  test("peer-ahead abandonment waits for the retained effect and cannot resurrect the old generation") {
    val parentKey = SnapshotOrdinal.unsafeApply(9L)

    for {
      storage <- ConsensusStorage.make[IO, Unit, SnapshotOrdinal, String, Unit, Int, TestOutcome, Unit](config)
      installed <- storage.trySetInitialConsensusOutcome(TestOutcome(parentKey))
      effectStarted <- Deferred[IO, Unit]
      releaseEffect <- Deferred[IO, Unit]
      effects <- Ref.of[IO, Int](0)
      _ <- storage.condModifyStateWithEffect(key) {
        case None =>
          val effect = effectStarted.complete(()).void >> releaseEffect.get >> effects.update(_ + 1)
          (state(1).copy(lastOutcome = TestOutcome(parentKey)).some, ().some, effect).some.pure[IO]
        case other =>
          new IllegalStateException(s"unexpected state $other")
            .raiseError[IO, Option[(Option[State], Option[Unit], IO[Unit])]]
      }
      _ <- effectStarted.get
      abandonmentCompleted <- Deferred[IO, Boolean]
      abandonedFiber <- storage
        .abandonGenerationIfCurrent(parentKey)(_.exists(_.status === 2))
        .flatTap(abandonmentCompleted.complete)
        .start
      _ <- IO.sleep(100.millis)
      beforeRelease <- abandonmentCompleted.tryGet
      _ <- releaseEffect.complete(())
      abandoned <- abandonedFiber.joinWithNever
      currentState <- storage.getState(key)
      currentOutcome <- storage.getLastConsensusOutcome
      effectCount <- effects.get
      _ <- IO.sleep(50.millis)
      stateAfterRetryWindow <- storage.getState(key)
    } yield
      expect.all(
        installed,
        beforeRelease.isEmpty,
        abandoned,
        currentState.isEmpty,
        currentOutcome.isEmpty,
        effectCount === 1,
        stateAfterRetryWindow.isEmpty
      )
  }

  test("peer-ahead abandonment preserves a Finished generation for ordinary outcome commit") {
    val parentKey = SnapshotOrdinal.unsafeApply(9L)

    for {
      storage <- ConsensusStorage.make[IO, Unit, SnapshotOrdinal, String, Unit, Int, TestOutcome, Unit](config)
      _ <- storage.trySetInitialConsensusOutcome(TestOutcome(parentKey))
      _ <- storage.condModifyStateWithEffect(key) {
        case None => (state(2).copy(lastOutcome = TestOutcome(parentKey)).some, ().some, IO.unit).some.pure[IO]
        case other =>
          new IllegalStateException(s"unexpected state $other")
            .raiseError[IO, Option[(Option[State], Option[Unit], IO[Unit])]]
      }
      _ <- storage.runRetainedEffect(key)
      abandoned <- storage.abandonGenerationIfCurrent(parentKey)(_.exists(_.status === 2))
      currentState <- storage.getState(key)
      currentOutcome <- storage.getLastConsensusOutcome
    } yield
      expect.all(
        !abandoned,
        currentState.exists(_.status === 2),
        currentOutcome.contains(TestOutcome(parentKey))
      )
  }
}
