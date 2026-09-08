package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.StateT
import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Proposal
import io.constellationnetwork.node.shared.infrastructure.consensus.update.UnlockConsensusUpdate
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

/** Stock generic barrier/recovery component reproduction, not an artifact/signature or network test. Phase numbers are fixtures. A
  * proposal-presence slot stands for the selected declaration kind. No production timeouts, messages, quorum rules, or signatures are
  * changed or fabricated.
  */
object SuccessivePhaseRecoverySuite extends SimpleIOSuite {
  type State = ConsensusState[Int, Int, Unit, Int]
  type Resources = ConsensusResources[String, Int]
  private val config = ConsensusConfig(43.seconds, 50.seconds, 3L, 10.seconds, 10.seconds, EventCutterConfig(1024, 100))
  private val peers = (1 to 144).toList.map(i => PeerId(Hex(f"$i%0128x")))
  private val proposal = Proposal(Hash.empty, Hash.empty)
  private val initial: State = ConsensusState(6885978, (), Facilitators(peers), 0, Duration.Zero, spreadAckKinds = Set.empty[Int])

  private val barrier = new ConsensusStateAdvancer[IO, Int, String, Unit, Int, Unit, Int] {
    def getConsensusOutcome(state: State): Option[(Previous[Int], Unit)] = None
    def advanceStatus(resources: Resources): StateT[IO, State, IO[Unit]] = StateT.pure(IO.unit)
    def collect(state: State, resources: Resources) = maybeGetAllDeclarations(state, resources, config)(_.proposal)
  }

  private def resources(received: Set[PeerId], acks: Map[(PeerId, Int), Set[PeerId]] = Map.empty): Resources =
    ConsensusResources(
      received.toList.map(_ -> PeerDeclarations.empty.copy(proposal = Some(proposal))).toMap,
      acks,
      Map.empty,
      acks.keySet.map(_._2),
      Map.empty,
      Duration.Zero
    )

  private def acknowledgments(state: State, missing: PeerId, count: Int, kind: Int): Map[(PeerId, Int), Set[PeerId]] = {
    val received = state.facilitators.value.toSet - missing
    state.facilitators.value.filterNot(_ == missing).take(count).map(p => (p, kind) -> received).toMap
  }

  private def unlock(state: State, acks: Map[(PeerId, Int), Set[PeerId]]): IO[State] =
    UnlockConsensusUpdate.tryUnlock[IO, State, Int](acks)(s => if (s.status < 3) Some(s.status) else None).runS(state)

  test("144 to 141: three distinct phase removals each need their own current-kind acknowledgment threshold") {
    (0 until 3).toList
      .foldLeftM((initial, success)) {
        case ((previous, checks), phase) =>
          val state = previous.copy(status = phase, lockStatus = LockStatus.Closed)
          val missing = peers(phase)
          val received = state.facilitators.value.toSet - missing
          val threshold = state.facilitators.value.size / 2 + 1
          for {
            blocked <- barrier.collect(state, resources(received))
            insufficient <- unlock(state, acknowledgments(state, missing, threshold - 1, phase))
            recovered <- unlock(state, acknowledgments(state, missing, threshold, phase))
            complete <- barrier.collect(recovered, resources(received))
          } yield
            (
              recovered,
              checks && expect(blocked.isEmpty) && expect.same(insufficient, state) &&
                expect.same(recovered.lockStatus, LockStatus.Reopened) &&
                expect.same(recovered.facilitators.value.size, 143 - phase) &&
                expect.same(recovered.removedFacilitators.value, peers.take(phase + 1).toSet) &&
                expect(recovered.withdrawnFacilitators.value.isEmpty) && expect.same(complete.map(_.keySet), Some(received))
            )
      }
      .map(_._2)
  }

  test("staleness and long elapsed time alone never remove a missing declaration") {
    TestControl.executeEmbed {
      val received = peers.tail.toSet
      for {
        before <- barrier.collect(initial, resources(received))
        _ <- IO.sleep(210.seconds)
        after <- barrier.collect(initial, resources(received))
      } yield expect(before.isEmpty) && expect(after.isEmpty)
    }
  }

  test("previous-phase acknowledgments cannot authorize a later-phase removal") {
    val state = initial.copy(status = 1, lockStatus = LockStatus.Closed)
    unlock(state, acknowledgments(state, peers.head, 144, 0)).map(result => expect.same(result, state))
  }

  test("removed participants and outsiders cannot supply the next phase's decisive acknowledgment") {
    val missing = peers(1)
    val state = initial.copy(
      status = 1,
      facilitators = Facilitators(peers.tail),
      lockStatus = LockStatus.Closed,
      removedFacilitators = RemovedFacilitators(Set(peers.head))
    )
    val received = state.facilitators.value.toSet - missing
    val outsider = PeerId(Hex("f" * 128))
    val insufficient = acknowledgments(state, missing, 71, 1)
    unlock(state, insufficient ++ Map((peers.head, 1) -> received, (outsider, 1) -> received))
      .map(result => expect.same(result, state))
  }

  test("a phase must remain closed when any participant's keep/remove decision is unresolved") {
    val state = initial.copy(lockStatus = LockStatus.Closed)
    val acks = peers.take(72).map(p => (p, 0) -> peers.tail.toSet).toMap
    unlock(state, acks).map(result => expect.same(result, state))
  }

  test("healthy declarations preserve all participants and require no recovery removal") {
    barrier.collect(initial, resources(peers.toSet)).map(result => expect.same(result.map(_.keySet), Some(peers.toSet)))
  }

  test("a keep decision does not supply a declaration missing from the local node") {
    val locked = initial.copy(lockStatus = LockStatus.Closed)
    val positiveAcks = peers.take(73).map(peer => (peer, 0) -> peers.toSet).toMap
    for {
      reopened <- unlock(locked, positiveAcks)
      stillMissing <- barrier.collect(reopened, resources(peers.tail.toSet))
      afterDelivery <- barrier.collect(reopened, resources(peers.toSet))
    } yield
      expect.same(reopened.lockStatus, LockStatus.Reopened) &&
        expect(reopened.removedFacilitators.value.isEmpty) && expect(stillMissing.isEmpty) && expect(afterDelivery.isDefined)
  }

  test("arrival of the missing declaration before recovery allows the unchanged barrier to complete") {
    for {
      blocked <- barrier.collect(initial, resources(peers.tail.toSet))
      complete <- barrier.collect(initial, resources(peers.toSet))
    } yield expect(blocked.isEmpty) && expect.same(complete.map(_.keySet), Some(peers.toSet))
  }
}
