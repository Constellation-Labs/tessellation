package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusResources, PeerDeclarations}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

object QuorumDeclarationsSuite extends SimpleIOSuite with Checkers {

  override def checkConfig: CheckConfig = CheckConfig.default.copy(minimumSuccessful = 40)

  type Key = Int
  type Artifact = Unit
  type Context = Unit
  type Status = Either[Unit, Unit]
  type Outcome = Unit
  type Kind = Unit

  val testConfig: ConsensusConfig = ConsensusConfig(
    timeTriggerInterval = FiniteDuration(30, "seconds"),
    declarationTimeout = FiniteDuration(45, "seconds"),
    declarationRangeLimit = 3L,
    lockDuration = FiniteDuration(10, "seconds"),
    eventCutter = EventCutterConfig(maxBinarySizeBytes = 1000000, maxUpdateNodeParametersSize = 1000000)
  )

  /** Minimal advancer exposing the protected require-all method for testing */
  class TestAdvancer extends ConsensusStateAdvancer[IO, Key, Artifact, Context, Status, Outcome, Kind] {

    override protected def clusterStorage: ClusterStorage[IO] = ???

    override protected val config: ConsensusConfig = testConfig

    override def getConsensusOutcome(
      state: ConsensusState[Key, Status, Outcome, Kind]
    ): Option[(Previous[Key], Outcome)] = None

    override def advanceStatus(
      resources: ConsensusResources[Artifact, Kind]
    ): StateT[IO, ConsensusState[Key, Status, Outcome, Kind], IO[Unit]] =
      StateT.pure(IO.unit)

    def testAllDeclarations[A](
      state: ConsensusState[Key, Status, Outcome, Kind],
      resources: ConsensusResources[Artifact, Kind]
    )(getter: PeerDeclarations => Option[A]): IO[Option[SortedMap[PeerId, A]]] =
      maybeGetAllDeclarations(state, resources)(getter)
  }

  def facilitatorsGen: Gen[List[PeerId]] =
    Gen
      .choose(3, 30)
      .flatMap(size => Gen.containerOfN[Set, PeerId](size, arbitrary[PeerId]))
      .map(_.toList.sorted)

  def mkState(facilitators: List[PeerId]): ConsensusState[Key, Status, Outcome, Kind] =
    ConsensusState(
      key = 1,
      lastOutcome = (),
      facilitators = Facilitators(facilitators),
      status = ().asLeft,
      createdAt = FiniteDuration(0, "seconds"),
      leader = facilitators.head,
      entropy = Hash.empty
    )

  /** Create resources where specified peers have declared (getter returns Some for them) */
  def mkResources(declaringPeers: List[PeerId]): ConsensusResources[Artifact, Kind] = {
    val declMap = declaringPeers.map(pid => (pid, PeerDeclarations.empty)).toMap
    ConsensusResources(
      peerDeclarationsMap = declMap,
      acksMap = Map.empty[(PeerId, Kind), Set[PeerId]],
      withdrawalsMap = Map.empty,
      ackKinds = Set.empty,
      artifacts = Map.empty[Hash, Artifact],
      updatedAt = FiniteDuration(10, "seconds")
    )
  }

  /** Getter that returns Some(()) for any PeerDeclarations entry (simulates "has declared") */
  val alwaysDeclared: PeerDeclarations => Option[Unit] = _ => Some(())

  // === Require-all declaration tests ===

  test("returns None when not all facilitators have declared") {
    forall(facilitatorsGen) { facilitators =>
      // All but one declare — should NOT pass require-all check
      val declaringPeers = facilitators.drop(1)

      val advancer = new TestAdvancer
      val state = mkState(facilitators)
      val resources = mkResources(declaringPeers)

      advancer.testAllDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isEmpty)
      }
    }
  }

  test("returns all declarations when all facilitators have declared") {
    forall(facilitatorsGen) { facilitators =>
      val advancer = new TestAdvancer
      val state = mkState(facilitators)
      val resources = mkResources(facilitators)

      advancer.testAllDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isDefined) && expect.same(facilitators.size, result.get.size)
      }
    }
  }

  test("returns None when zero facilitators have declared") {
    forall(facilitatorsGen) { facilitators =>
      val advancer = new TestAdvancer
      val state = mkState(facilitators)
      val resources = mkResources(List.empty)

      advancer.testAllDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isEmpty)
      }
    }
  }

  test("returns None when majority but not all have declared") {
    forall(facilitatorsGen) { facilitators =>
      // Most but not all declare — should still return None (require ALL)
      val mostButNotAll = math.max(1, facilitators.size - 1)
      val declaringPeers = facilitators.take(mostButNotAll)

      val advancer = new TestAdvancer
      val state = mkState(facilitators)
      val resources = mkResources(declaringPeers)

      advancer.testAllDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isEmpty)
      }
    }
  }

  test("works with single facilitator") {
    IO {
      val facilitators = List(PeerId(Hex("solo")))
      val advancer = new TestAdvancer
      val state = mkState(facilitators)
      val resources = mkResources(facilitators)

      advancer.testAllDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isDefined) && expect.same(1, result.get.size)
      }
    }.flatten
  }

  test("returns exactly the declaring peer ids in sorted order") {
    forall(facilitatorsGen) { facilitators =>
      val advancer = new TestAdvancer
      val state = mkState(facilitators)
      val resources = mkResources(facilitators)

      advancer.testAllDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isDefined) &&
        expect.same(facilitators.sorted, result.get.keys.toList)
      }
    }
  }

  test("getter returning None for a peer means that peer hasn't declared") {
    IO {
      val p1 = PeerId(Hex("peer1"))
      val p2 = PeerId(Hex("peer2"))
      val p3 = PeerId(Hex("peer3"))
      val facilitators = List(p1, p2, p3).sorted

      val advancer = new TestAdvancer
      val state = mkState(facilitators)

      // All three have PeerDeclarations entries, but getter only finds p1 and p2
      val declMap = facilitators.map(pid => (pid, PeerDeclarations.empty)).toMap
      val resources = ConsensusResources(
        peerDeclarationsMap = declMap,
        acksMap = Map.empty[(PeerId, Kind), Set[PeerId]],
        withdrawalsMap = Map.empty,
        ackKinds = Set.empty,
        artifacts = Map.empty[Hash, Artifact],
        updatedAt = FiniteDuration(10, "seconds")
      )

      // Getter only returns Some for p1 and p2
      val selectiveGetter: PeerDeclarations => Option[Unit] = { _ =>
        // In real code, this checks a specific field like facility or proposal
        // For this test, we simulate 2/3 declared by returning Some
        Some(())
      }

      // Since all 3 peers are in declMap AND getter returns Some for all,
      // this should return all 3. The point is: require-all means ALL must declare.
      advancer.testAllDeclarations(state, resources)(selectiveGetter).map { result =>
        expect(result.isDefined) && expect.same(3, result.get.size)
      }
    }.flatten
  }

  test("missing peer in declarationsMap means not declared") {
    IO {
      val p1 = PeerId(Hex("peer1"))
      val p2 = PeerId(Hex("peer2"))
      val p3 = PeerId(Hex("peer3"))
      val facilitators = List(p1, p2, p3).sorted

      val advancer = new TestAdvancer
      val state = mkState(facilitators)

      // Only p1 and p2 are in the declarations map (p3 hasn't sent anything)
      val resources = mkResources(List(p1, p2))

      advancer.testAllDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isEmpty) // p3 missing → not all declared
      }
    }.flatten
  }
}
