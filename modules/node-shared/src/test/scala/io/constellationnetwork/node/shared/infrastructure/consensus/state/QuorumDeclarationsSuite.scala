package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.ext.collection.FoldableOps.pickMajority
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusResources, PeerDeclarations}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

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

  /** Minimal advancer exposing the protected quorum method for testing */
  class TestAdvancer(quorumThreshold: Option[Double]) extends ConsensusStateAdvancer[IO, Key, Artifact, Context, Status, Outcome, Kind] {

    override protected def clusterStorage: ClusterStorage[IO] = ???

    override protected val config: ConsensusConfig = testConfig.copy(quorumThreshold = quorumThreshold)

    override def getConsensusOutcome(
      state: ConsensusState[Key, Status, Outcome, Kind]
    ): Option[(Previous[Key], Outcome)] = None

    override def advanceStatus(
      resources: ConsensusResources[Artifact, Kind]
    ): StateT[IO, ConsensusState[Key, Status, Outcome, Kind], IO[Unit]] =
      StateT.pure(IO.unit)

    def testQuorumDeclarations[A](
      state: ConsensusState[Key, Status, Outcome, Kind],
      resources: ConsensusResources[Artifact, Kind]
    )(getter: PeerDeclarations => Option[A]): IO[Option[SortedMap[PeerId, A]]] =
      maybeGetQuorumDeclarations(state, resources)(getter)(identity)
  }

  def facilitatorsGen: Gen[List[PeerId]] =
    Gen
      .choose(10, 30)
      .flatMap(size => Gen.containerOfN[Set, PeerId](size, arbitrary[PeerId]))
      .map(_.toList.sorted)

  def mkState(facilitators: List[PeerId]): ConsensusState[Key, Status, Outcome, Kind] =
    ConsensusState(
      key = 1,
      lastOutcome = (),
      facilitators = Facilitators(facilitators),
      status = ().asLeft,
      createdAt = FiniteDuration(0, "seconds"),
      spreadAckKinds = Set.empty
    )

  /** Create resources where specified peers have declared (getter returns Some for them) */
  def mkResources(declaringPeers: List[PeerId]): ConsensusResources[Artifact, Kind] = {
    val declMap = declaringPeers.map(pid => (pid, PeerDeclarations.empty)).toMap
    ConsensusResources(
      peerDeclarationsMap = declMap,
      acksMap = Map.empty,
      withdrawalsMap = Map.empty,
      ackKinds = Set.empty,
      artifacts = Map.empty[Hash, Artifact],
      updatedAt = FiniteDuration(10, "seconds")
    )
  }

  /** Getter that returns Some(()) for any PeerDeclarations entry (simulates "has declared") */
  val alwaysDeclared: PeerDeclarations => Option[Unit] = _ => Some(())

  test("quorum threshold 0.67 with 20 facilitators requires 14") {
    IO {
      val quorumSize = math.ceil(20 * 0.67).toInt.max(1)
      expect.same(14, quorumSize)
    }
  }

  test("quorum threshold 0.67 with 3 facilitators requires 3") {
    IO {
      // ceil(3 * 0.67) = ceil(2.01) = 3
      val quorumSize = math.ceil(3 * 0.67).toInt.max(1)
      expect.same(3, quorumSize)
    }
  }

  test("quorum threshold 0.67 with 1 facilitator requires 1") {
    IO {
      val quorumSize = math.ceil(1 * 0.67).toInt.max(1)
      expect.same(1, quorumSize)
    }
  }

  test("returns None when declarations < quorum") {
    forall(facilitatorsGen) { facilitators =>
      val threshold = 0.67
      val quorumSize = math.ceil(facilitators.size * threshold).toInt.max(1)
      val declaringPeers = facilitators.take(quorumSize - 1)

      val advancer = new TestAdvancer(Some(threshold))
      val state = mkState(facilitators)
      val resources = mkResources(declaringPeers)

      advancer.testQuorumDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isEmpty)
      }
    }
  }

  test("returns Some when declarations >= quorum") {
    forall(facilitatorsGen) { facilitators =>
      val threshold = 0.67
      val quorumSize = math.ceil(facilitators.size * threshold).toInt.max(1)
      val declaringPeers = facilitators.take(quorumSize)

      val advancer = new TestAdvancer(Some(threshold))
      val state = mkState(facilitators)
      val resources = mkResources(declaringPeers)

      advancer.testQuorumDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isDefined) && expect.same(quorumSize, result.get.size)
      }
    }
  }

  test("with threshold = None, requires 100% (backward compatible)") {
    forall(facilitatorsGen) { facilitators =>
      // All but one declare — should NOT meet 100% threshold
      val declaringPeers = facilitators.drop(1)

      val advancer = new TestAdvancer(None)
      val state = mkState(facilitators)
      val resources = mkResources(declaringPeers)

      advancer.testQuorumDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isEmpty)
      }
    }
  }

  test("returns all received declarations, not just quorum-size subset") {
    forall(facilitatorsGen) { facilitators =>
      val advancer = new TestAdvancer(Some(0.67))
      val state = mkState(facilitators)
      // All facilitators declare
      val resources = mkResources(facilitators)

      advancer.testQuorumDeclarations(state, resources)(alwaysDeclared).map { result =>
        expect(result.isDefined) && expect.same(facilitators.size, result.get.size)
      }
    }
  }

  test("quorum determinism: any two quorum-size subsets yield same pickMajority result") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val n = facilitators.size
        val threshold = 0.67
        val quorumSize = math.ceil(n * threshold).toInt.max(1)

        // Supermajority holds value "A", rest holds "B"
        val values = facilitators.take(quorumSize).map(_ => "A") ++
          facilitators.drop(quorumSize).map(_ => "B")

        // Two different quorum-size windows
        val subset1 = values.take(quorumSize)
        val subset2 = values.drop(n - quorumSize)

        val majority1 = pickMajority(subset1)
        val majority2 = pickMajority(subset2)

        expect.same(majority1, majority2)
      }
    }
  }

  // === Safe quorum (supermajority safety gate) tests ===

  /** Simulates the safety gate logic from maybeGetSafeQuorumDeclarations */
  def safeQuorumCheck[V](
    totalFacilitators: Int,
    threshold: Double,
    values: List[V]
  ): Option[List[V]] = {
    val quorumSize = math.ceil(totalFacilitators * threshold).toInt.max(1)
    val receivedCount = values.size
    if (receivedCount >= quorumSize) {
      val maxSupport = values.groupBy(identity).values.map(_.size).maxOption.getOrElse(0)
      if (maxSupport >= quorumSize) Some(values)
      else None
    } else None
  }

  test("safeMajority passes when majority has quorum support") {
    IO {
      // 14 out of 20 agree on "A", 6 on "B"
      val values = List.fill(14)("A") ++ List.fill(6)("B")
      val result = safeQuorumCheck(20, 0.67, values)
      // quorumSize = ceil(20 * 0.67) = 14, maxSupport = 14 >= 14 → passes
      expect(result.isDefined)
    }
  }

  test("safeMajority blocks when no value has quorum support") {
    IO {
      // 8 vs 6 split — neither reaches quorum of 14
      val values = List.fill(8)("A") ++ List.fill(6)("B")
      val result = safeQuorumCheck(20, 0.67, values)
      // receivedCount = 14 >= quorumSize = 14, but maxSupport = 8 < 14 → blocks
      expect(result.isEmpty)
    }
  }

  test("safeMajority with all-same values always passes") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val n = facilitators.size
        val threshold = 0.67
        val quorumSize = math.ceil(n * threshold).toInt.max(1)
        // All facilitators vote the same
        val values = List.fill(n)("unanimous")
        val result = safeQuorumCheck(n, threshold, values)
        expect(result.isDefined)
      }
    }
  }

  test("safeMajority determinism: safe majority guarantees same pickMajority across quorum views") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val n = facilitators.size
        val threshold = 0.67
        val quorumSize = math.ceil(n * threshold).toInt.max(1)

        // Value "A" has exactly quorumSize support (safe)
        val values = List.fill(quorumSize)("A") ++ List.fill(n - quorumSize)("B")

        // Verify safety gate passes
        val isSafe = safeQuorumCheck(n, threshold, values).isDefined

        // Any two quorum-size subsets must agree
        val subset1 = values.take(quorumSize)
        val subset2 = values.drop(n - quorumSize)
        val majority1 = pickMajority(subset1)
        val majority2 = pickMajority(subset2)

        expect(isSafe) && expect.same(majority1, majority2)
      }
    }
  }
}
