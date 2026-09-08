package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.{Kleisli, StateT}
import cats.effect.std.{Random, Supervisor}
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.PeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.node.shared.infrastructure.node.NodeStorage
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.Encoder
import io.circe.syntax._
import monocle.Lens
import weaver.SimpleIOSuite

/** Runs the real stock manager, storage, updater, unlock and ack timers under a virtual clock. Artifact creation/validation, transport and
  * phase readiness are test doubles. This verifies timer/ack control flow, not signed artifacts or Mainnet network latency.
  */
object StockRecoveryTimerSuite extends SimpleIOSuite {
  type State = ConsensusState[Int, Int, Int, Int]
  type Storage = ConsensusStorage[IO, String, Int, String, String, Int, Int, Int]
  type Updater = ConsensusStateUpdater[IO, Int, String, String, Int, Int, Int]
  private implicit val nextInt: cats.kernel.Next[Int] = new cats.kernel.Next[Int] {
    def next(value: Int): Int = value + 1
    def partialOrder: cats.PartialOrder[Int] = cats.Order[Int]
  }
  private val config = ConsensusConfig(43.seconds, 50.seconds, 3L, 10.seconds, 10.seconds, EventCutterConfig(1024, 100))
  private val peers = (1 to 144).toList.map(i => PeerId(Hex(f"$i%0128x")))
  private implicit val metrics: Metrics[IO] = NoOpMetrics.make
  private implicit val keyLens: Lens[Int, Int] = Lens[Int, Int](identity)(value => _ => value)
  private implicit val contextLens: Lens[Int, String] = Lens[Int, String](_ => "")(_ => identity)
  private implicit val triggerLens: Lens[Int, ConsensusTrigger] = Lens[Int, ConsensusTrigger](_ => EventTrigger)(_ => identity)
  private implicit val artifactLens: Lens[Int, Signed[String]] =
    Lens[Int, Signed[String]](_ => throw new IllegalStateException("artifact path must not execute in this component test"))(_ => identity)

  private val ops = new ConsensusOps[Int, Int] {
    def collectedKinds(status: Int): Set[Int] = (0 until status).toSet
    def maybeCollectingKind(status: Int): Option[Int] = Option.when(status < 3)(status)
    def kindGetter: Int => PeerDeclarations => Option[PeerDeclaration] = _ => _.proposal
  }

  private val advancer = new ConsensusStateAdvancer[IO, Int, String, String, Int, Int, Int] {
    def getConsensusOutcome(state: State): Option[(Previous[Int], Int)] = None
    def advanceStatus(resources: Resources): StateT[IO, State, IO[Unit]] = StateT { state =>
      // Readiness fixture: each phase has one different unavailable participant.
      // Only the real ack-based updater can grow removedFacilitators.
      val next =
        if (state.lockStatus != LockStatus.Closed && state.status < 3 && state.removedFacilitators.value.size > state.status)
          state.copy(status = state.status + 1, lockStatus = LockStatus.Open)
        else state
      IO.pure((next, IO.unit))
    }
  }

  private val client = new ConsensusClient[IO, Int, Int] {
    def getRegistration: PeerResponse[IO, RegistrationResponse[Int]] =
      Kleisli.liftF(IO.raiseError(new IllegalStateException("network must not execute")))
    def getLatestConsensusOutcome: PeerResponse[IO, Option[Int]] =
      Kleisli.liftF(IO.raiseError(new IllegalStateException("network must not execute")))
    def getSpecificConsensusOutcome(request: GetConsensusOutcomeRequest[Int]): PeerResponse[IO, Option[Int]] =
      getLatestConsensusOutcome
  }

  test("candidate registration is ordinal-specific, not automatic permanent membership") {
    for {
      storage <- ConsensusStorage.make[IO, String, Int, String, String, Int, Int, Int](config)
      before <- storage.getCandidates(2)
      accepted <- storage.registerPeer(peers.head, 2)
      atKey <- storage.getCandidates(2)
      otherKey <- storage.getCandidates(3)
    } yield
      expect(before.value.isEmpty) && expect(accepted) &&
        expect.same(atKey.value, Set(peers.head)) && expect(otherKey.value.isEmpty)
  }

  test("later registration supersedes an earlier key and cannot be rolled backward") {
    for {
      storage <- ConsensusStorage.make[IO, String, Int, String, String, Int, Int, Int](config)
      _ <- storage.registerPeer(peers.head, 2)
      later <- storage.registerPeer(peers.head, 3)
      old <- storage.getCandidates(2)
      backward <- storage.registerPeer(peers.head, 2)
      current <- storage.getCandidates(3)
    } yield expect(later) && expect(old.value.isEmpty) && expect(!backward) && expect.same(current.value, Set(peers.head))
  }

  test("duplicate or changed acknowledgment from one sender cannot add a vote or replace its first statement") {
    for {
      storage <- ConsensusStorage.make[IO, String, Int, String, String, Int, Int, Int](config)
      _ <- storage.trySetInitialConsensusOutcome(0)
      _ <- storage.addPeerDeclarationAck(peers.head, 1, 0, peers.tail.toSet)
      _ <- storage.addPeerDeclarationAck(peers.head, 1, 0, Set.empty)
      result <- storage.getResources(1)
    } yield expect.same(result.acksMap.size, 1) && expect.same(result.acksMap((peers.head, 0)), peers.tail.toSet)
  }

  test("acknowledgments outside the stock declaration ordinal range are rejected") {
    for {
      storage <- ConsensusStorage.make[IO, String, Int, String, String, Int, Int, Int](config)
      _ <- storage.trySetInitialConsensusOutcome(10)
      old <- storage.addPeerDeclarationAck(peers.head, 9, 0, peers.toSet)
      future <- storage.addPeerDeclarationAck(peers.head, 14, 0, peers.toSet)
      edge <- storage.addPeerDeclarationAck(peers.head, 13, 0, peers.toSet)
    } yield expect(old.isEmpty) && expect(future.isEmpty) && expect(edge.isDefined)
  }

  test("real manager locks each unchanged phase after 50s and sends its local ack after 60s; three decisions are distinct") {
    TestControl.executeEmbed {
      Supervisor[IO].use { implicit supervisor =>
        Random.scalaUtilRandomSeedInt[IO](0).flatMap { implicit random =>
          for {
            storage <- ConsensusStorage.make[IO, String, Int, String, String, Int, Int, Int](config)
            lockTimes <- Ref.of[IO, List[(Int, FiniteDuration)]](Nil)
            ackTimes <- Ref.of[IO, List[(Int, FiniteDuration)]](Nil)
            gossip = new Gossip[IO] {
              def spread[A: TypeTag: Encoder](value: A): IO[Unit] =
                value.asJson.hcursor.get[Int]("kind").toOption.traverse_ { kind =>
                  IO.monotonic.flatMap(time => ackTimes.update(_ :+ (kind, time)))
                }
              def spreadCommon[A: TypeTag: Encoder](value: A): IO[Unit] =
                IO.raiseError(new IllegalStateException("artifact gossip must not execute"))
            }
            realUpdater = ConsensusStateUpdater.make[IO, String, Int, String, String, Int, Int, Int](advancer, storage, gossip, ops)
            instrumented = new Updater {
              def tryUpdateConsensus(key: Int, resources: ConsensusResources[String, Int]): IO[StateUpdateResult] =
                realUpdater.tryUpdateConsensus(key, resources)
              def trySpreadAck(key: Int, kind: Int, resources: ConsensusResources[String, Int]): IO[StateUpdateResult] =
                realUpdater.trySpreadAck(key, kind, resources)
              def tryLockConsensus(key: Int, reference: State): IO[StateUpdateResult] =
                realUpdater
                  .tryLockConsensus(key, reference)
                  .flatTap(_.traverse_ {
                    case (_, state) =>
                      IO.monotonic.flatMap(time => lockTimes.update(_ :+ (state.status, time)))
                  })
            }
            creator = new ConsensusStateCreator[IO, Int, String, String, Int, Int, Int] {
              def tryFacilitateConsensus(
                key: Int,
                outcome: Int,
                trigger: Option[ConsensusTrigger],
                resources: ConsensusResources[String, Int]
              ): IO[StateCreateResult] =
                storage
                  .condModifyState(key)(toCreateStateFn(IO.monotonic.map { time =>
                    (ConsensusState(key, outcome, Facilitators(peers), 0, time, spreadAckKinds = Set.empty[Int]), IO.unit)
                  }))
                  .flatMap(evalEffect)
            }
            remover = new ConsensusStateRemover[IO, Int, String, String, String, Int, Int, Int](storage, gossip) {
              def getWithdrawalDeclaration(key: Int, state: Option[State]): ConsensusWithdrawPeerDeclaration[Int, Int] =
                ConsensusWithdrawPeerDeclaration(key, state.fold(0)(_.status))
            }
            nodeStorage <- NodeStorage.make[IO]
            clusterStorage <- ClusterStorage.make[IO](ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"))
            _ <- storage.trySetInitialConsensusOutcome(0)
            manager <- ConsensusManager.make[IO, String, Int, String, String, Int, Int, Int](
              config,
              storage,
              creator,
              instrumented,
              advancer,
              remover,
              ops,
              nodeStorage,
              clusterStorage,
              client
            )
            _ <- manager.facilitateOnEvent
            checks <- (0 until 3).toList.foldLeftM(success) { (checks, phase) =>
              for {
                _ <- IO.sleep(49.seconds)
                before <- storage.getState(1).map(_.get)
                _ <- IO.sleep(2.seconds)
                locked <- storage.getState(1).map(_.get)
                _ <- IO.sleep(8.seconds)
                acksBefore <- ackTimes.get
                _ <- IO.sleep(2.seconds)
                acksAfter <- ackTimes.get
                current <- storage.getState(1).map(_.get)
                missing = peers(phase)
                received = current.facilitators.value.toSet - missing
                senders = current.facilitators.value.filterNot(_ == missing)
                threshold = current.facilitators.value.size / 2 + 1
                _ <- senders.take(threshold - 1).traverse_(peer => storage.addPeerDeclarationAck(peer, 1, phase, received))
                resourcesBefore <- storage.getResources(1)
                _ <- manager.checkForStateUpdate(1)(resourcesBefore)
                _ <- IO.sleep(1.millisecond)
                insufficient <- storage.getState(1).map(_.get)
                _ <- storage.addPeerDeclarationAck(senders(threshold - 1), 1, phase, received)
                resourcesAfter <- storage.getResources(1)
                _ <- manager.checkForStateUpdate(1)(resourcesAfter)
                _ <- IO.sleep(1.millisecond)
                recovered <- storage.getState(1).map(_.get)
              } yield
                checks && expect.same(before.lockStatus, LockStatus.Open) &&
                  expect.same(locked.lockStatus, LockStatus.Closed) && expect(!acksBefore.exists(_._1 == phase)) &&
                  expect(acksAfter.exists(_._1 == phase)) && expect.same(insufficient.lockStatus, LockStatus.Closed) &&
                  expect.same(recovered.status, phase + 1) && expect.same(recovered.facilitators.value.size, 143 - phase) &&
                  expect.same(recovered.removedFacilitators.value, peers.take(phase + 1).toSet)
            }
            locks <- lockTimes.get
            acks <- ackTimes.get
            // Each next phase starts after explicit test delivery of the decisive ack.
            // The 1ms checkpoints above are test-driver delays, not protocol defaults.
            expectedLocks = List(50.seconds, 111.seconds + 1.millisecond, 172.seconds + 3.milliseconds)
            expectedAcks = expectedLocks.map(_ + 10.seconds)
          } yield
            checks && expect.same(locks.map(_._1), List(0, 1, 2)) &&
              expect.same(locks.map(_._2), expectedLocks) && expect.same(acks.map(_._2), expectedAcks)
        }
      }
    }
  }
}
