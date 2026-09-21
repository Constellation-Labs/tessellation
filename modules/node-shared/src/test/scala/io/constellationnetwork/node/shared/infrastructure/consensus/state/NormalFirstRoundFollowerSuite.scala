package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect._
import cats.effect.std.{Queue, Random}
import cats.effect.testkit.TestControl
import cats.kernel.{Next, PartialOrder}
import cats.syntax.all._
import cats.{Eq, Order}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.{ClusterStorage => ClusterStorageImpl}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, FirstRoundStartGate}
import io.constellationnetwork.node.shared.infrastructure.consensus.state.StateTransitions.{
  NormalFirstRoundPulsePeerOutcome => PulseOutcome,
  NormalFirstRoundReentryResult => ReentryResult
}
import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}
import io.constellationnetwork.schema.peer.{Peer, PeerId, Responsive}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import weaver.SimpleIOSuite

object NormalFirstRoundFollowerSuite extends SimpleIOSuite {

  private final case class TestOutcome(key: Int, artifact: String, operationalValue: Int)
  private implicit val testOutcomeEq: Eq[TestOutcome] = Eq.fromUniversalEquals

  private implicit val nextInt: Next[Int] = new Next[Int] {
    def next(a: Int): Int = a + 1
    def partialOrder: PartialOrder[Int] = Order[Int]
  }

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  private def hashOf(outcome: TestOutcome): Hash = Hash.fromBytes(outcome.artifact.getBytes("UTF-8"))

  private def peerOf(id: PeerId): Peer =
    Peer(
      id,
      Host.fromString("127.0.0.1").get,
      Port.fromInt(9000).get,
      Port.fromInt(9001).get,
      ClusterSessionToken(Generation(PosLong.unsafeFrom(1L))),
      SessionToken(Generation(PosLong.unsafeFrom(1L))),
      NodeState.Ready,
      Responsive,
      Hash.fromBytes("jar".getBytes("UTF-8"))
    )

  private val parentKey = 100
  private val parent = TestOutcome(parentKey, "parent-artifact", operationalValue = 1)
  private val parentHash = hashOf(parent)

  private def classify(observed: Option[TestOutcome]): PulseOutcome[Int] =
    StateTransitions.classifyNormalFirstRoundPulseOutcome(parentKey, parent, observed)(_.key, hashOf)

  pureTest("pulse classification orders the reported key against the installed parent") {
    val behind = classify(TestOutcome(parentKey - 1, "older", 1).some)
    val ahead = classify(TestOutcome(parentKey + 1, "newer", 1).some)
    val aligned = classify(parent.some)
    val missing = classify(None)

    expect.same(PulseOutcome.Behind(parentKey - 1, hashOf(TestOutcome(parentKey - 1, "older", 1))), behind) &&
    expect.same(PulseOutcome.Ahead(parentKey + 1, hashOf(TestOutcome(parentKey + 1, "newer", 1))), ahead) &&
    expect.same(PulseOutcome.Aligned(parentKey, parentHash), aligned) &&
    expect.same(PulseOutcome.Missing, missing) &&
    expect.same(Some(parentKey - 1), behind.reportedKey) &&
    expect.same("ok", ahead.httpResult)
  }

  pureTest("same key and hash with a different outcome stays mismatched, never aligned") {
    val sameArtifactDifferentOutcome = parent.copy(operationalValue = 2)
    val classified = classify(sameArtifactDifferentOutcome.some)

    expect.same(PulseOutcome.MismatchedAtP(parentKey, parentHash), classified) &&
    expect.same(Some(parentHash), classified.reportedHash) &&
    expect(classified.reportedHash === PulseOutcome.Aligned(parentKey, parentHash).reportedHash, "hash equal but outcome differs")
  }

  pureTest("a behind origin cannot monopolize the preferred future-declaration candidate pool") {
    val self = pid("self")
    val behind = pid("behind")
    val atParent = pid("at-parent")
    val atNext = pid("at-next")
    val beyondNext = pid("beyond-next")
    val outsider = pid("outsider")
    val committee = SortedSet(self, behind, atParent, atNext, beyondNext)
    val nextKey = parentKey + 1

    val origins = StateTransitions.normalFirstRoundFutureDeclarationOrigins(
      committee,
      nextKey,
      Map(
        behind -> (parentKey - 5),
        atParent -> parentKey,
        atNext -> nextKey,
        beyondNext -> (nextKey + 1),
        outsider -> (nextKey + 10)
      )
    )

    expect.same(Set(beyondNext), origins) &&
    expect(!origins.contains(behind), "a straggler below the parent must not be a preferred ahead probe") &&
    expect(!origins.contains(atParent), "the installed parent key is not future evidence") &&
    expect(!origins.contains(outsider), "non-committee origins are never candidates")
  }

  pureTest("pulse status reports behind origins and unqueried eligible origins separately from fetch failures") {
    val self = pid("self")
    val behind = pid("behind")
    val failed = pid("failed")
    val unqueried = pid("unqueried")
    val committee = SortedSet(self, behind, failed, unqueried)
    val status = StateTransitions.normalFirstRoundPulseStatus(
      committee,
      matchingFacilityOrigins = Set(behind, failed, unqueried),
      aheadProbeOrigins = Set.empty,
      responsivePeerStates = Map(behind -> NodeState.Ready, failed -> NodeState.Ready, unqueried -> NodeState.WaitingForReady),
      peerOutcomes = Map[PeerId, PulseOutcome[Int]](
        behind -> PulseOutcome.Behind(parentKey - 1, parentHash),
        failed -> PulseOutcome.FetchFailed("timeout")
      )
    )

    expect.same(SortedSet(behind), status.behindOrigins) &&
    expect.same(SortedSet(failed), status.fetchFailed) &&
    expect.same(SortedSet(unqueried), status.unqueried) &&
    expect(status.aheadOrigin.isEmpty, "a behind origin is never ahead evidence") &&
    expect(status.releaseOrigin.isEmpty, "a behind origin is never a release origin") &&
    expect.same("peer_behind", status.outcomeLabel)
  }

  test("an early aligned result is not acted on while another sampled task is pending") {
    val fast = pid("fast")
    val slow = pid("slow")
    val stuck = pid("stuck")
    val committee = SortedSet(fast, slow, stuck)
    val fetch: PeerId => IO[PulseOutcome[Int]] = {
      case `fast`  => IO.pure(PulseOutcome.Aligned(parentKey, parentHash))
      case `slow`  => IO.sleep(2.seconds).as(PulseOutcome.Ahead(parentKey + 3, parentHash))
      case `stuck` => IO.never
      case other   => IO.raiseError(new IllegalStateException(s"unexpected origin $other"))
    }
    val tick = StateTransitions.observeNormalFirstRoundSample[IO, Int, PeerId](List(fast, slow, stuck), fetch, 4.seconds)

    TestControl.execute(tick).flatMap { control =>
      for {
        _ <- control.tick
        afterFast <- control.results
        _ <- control.advanceAndTick(2.seconds)
        afterSlow <- control.results
        _ <- control.advanceAndTick(2.seconds)
        afterDeadline <- control.results
        observed = afterDeadline.flatMap(_.fold(None, _ => None, Some(_)))
        status = observed.map(o =>
          StateTransitions
            .normalFirstRoundPulseStatus(committee, committee, Set.empty, committee.toList.map(_ -> NodeState.Ready).toMap, o.toMap)
        )
      } yield
        expect(afterFast.isEmpty, "fast aligned result must not complete the tick alone") &&
          expect(afterSlow.isEmpty, "a still-pending task keeps the tick open") &&
          expect.same(
            Some(
              List(
                fast -> PulseOutcome.Aligned(parentKey, parentHash),
                slow -> PulseOutcome.Ahead(parentKey + 3, parentHash),
                stuck -> PulseOutcome.FetchFailed("tick_deadline")
              )
            ),
            observed
          ) &&
          expect.same(Some(Some(slow)), status.map(_.aheadOrigin)) &&
          expect.same(Some(None), status.map(_.releaseOrigin)) &&
          expect(status.exists(s => !StateTransitions.shouldReleaseNormalFirstRoundPulse(s, recoveryOnly = false)))
    }
  }

  test("a tick with every task completing returns before the deadline and never fails the tick") {
    val a = pid("a")
    val b = pid("b")
    val failure = new RuntimeException("boom")
    val fetch: PeerId => IO[PulseOutcome[Int]] = {
      case `a`   => IO.sleep(500.millis).as(PulseOutcome.Aligned(parentKey, parentHash))
      case `b`   => IO.raiseError(failure)
      case other => IO.raiseError(new IllegalStateException(s"unexpected origin $other"))
    }
    val tick = StateTransitions.observeNormalFirstRoundSample[IO, Int, PeerId](List(a, b), fetch, 4.seconds)

    TestControl.execute(tick).flatMap { control =>
      for {
        _ <- control.tick
        _ <- control.advanceAndTick(500.millis)
        results <- control.results
      } yield
        expect.same(
          Some(
            Outcome.succeeded[cats.Id, Throwable, List[(PeerId, PulseOutcome[Int])]](
              List(a -> PulseOutcome.Aligned(parentKey, parentHash), b -> PulseOutcome.FetchFailed("RuntimeException"))
            )
          ),
          results
        )
    }
  }

  private def reentry(
    permitOwned: Boolean,
    originCurrent: Boolean,
    transition: NodeStateTransition
  ): IO[(ReentryResult, Boolean, Boolean, Long)] =
    for {
      marked <- Ref.of[IO, Boolean](false)
      transitioned <- Ref.of[IO, Boolean](false)
      episode <- Ref.of[IO, Long](4L)
      result <- StateTransitions.enterNormalFirstRoundRecovery[IO](
        IO.pure(permitOwned),
        IO.pure(originCurrent),
        marked.set(true),
        transitioned.set(true).as(transition),
        episode
      )
      wasMarked <- marked.get
      wasTransitioned <- transitioned.get
      episodeAfter <- episode.get
    } yield (result, wasMarked, wasTransitioned, episodeAfter)

  test("a stale permit generation is inert: no recovery flag, no transition, no episode change") {
    reentry(permitOwned = false, originCurrent = true, NodeStateTransition.Success).map {
      case (result, marked, transitioned, episode) =>
        expect.same(ReentryResult.StaleGeneration, result) &&
        expect(!marked, "recovery download flag must not be set by a stale generation") &&
        expect(!transitioned, "a stale generation must not attempt a node-state transition") &&
        expect.same(4L, episode)
    }
  }

  test("a stale origin session is inert and leaves the episode counter alone") {
    reentry(permitOwned = true, originCurrent = false, NodeStateTransition.Success).map {
      case (result, marked, transitioned, episode) =>
        expect.same(ReentryResult.StaleOriginSession, result) &&
        expect(!marked) &&
        expect(!transitioned) &&
        expect.same(4L, episode)
    }
  }

  test("the episode counter increments only on a successful result-returning transition") {
    for {
      success <- reentry(permitOwned = true, originCurrent = true, NodeStateTransition.Success)
      rejected <- reentry(permitOwned = true, originCurrent = true, NodeStateTransition.Failure)
    } yield
      expect.same(ReentryResult.Entered(5L), success._1) &&
        expect(success._2, "successful re-entry marks recovery download") &&
        expect.same(5L, success._4) &&
        expect.same(ReentryResult.TransitionRejected, rejected._1) &&
        expect(rejected._3, "the transition was attempted") &&
        expect.same(4L, rejected._4)
  }

  private def session(n: Long): SessionToken = SessionToken(Generation(PosLong.unsafeFrom(n)))

  private def clusterStorage(peers: Peer*) =
    ClusterStorageImpl.make[IO](ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"), peers.map(p => p.id -> p).toMap)

  private final case class FallbackProbe(
    result: ReentryResult,
    marked: Boolean,
    transitioned: Boolean,
    episode: Long,
    freshHeld: Option[Boolean]
  )

  /** Run the fallback decision body against a real gate with the origin revalidation paused; `interleave` runs while it is paused. */
  private def fallbackWhilePaused(
    gate: FirstRoundStartGate[IO, Int],
    permit: FirstRoundStartGate.Permit[Int],
    originStillCurrent: IO[Boolean],
    interleave: IO[Option[FirstRoundStartGate.Permit[Int]]]
  ): IO[FallbackProbe] =
    for {
      originRead <- Deferred[IO, Unit]
      finishRead <- Deferred[IO, Unit]
      marked <- Ref.of[IO, Boolean](false)
      transitioned <- Ref.of[IO, Boolean](false)
      episode <- Ref.of[IO, Long](0L)
      run <- StateTransitions
        .enterNormalFirstRoundRecovery[IO](
          gate.isPending(permit),
          originRead.complete(()).void >> finishRead.get >> originStillCurrent,
          marked.set(true),
          transitioned.set(true).as(NodeStateTransition.Success),
          episode
        )
        .start
      _ <- originRead.get
      fresh <- interleave
      _ <- finishRead.complete(())
      result <- run.joinWithNever
      hasMarked <- marked.get
      hasTransitioned <- transitioned.get
      episodeAfter <- episode.get
      freshHeld <- fresh.traverse(gate.isPending)
    } yield FallbackProbe(result, hasMarked, hasTransitioned, episodeAfter, freshHeld)

  test("the fallback is inert when a new gate generation is armed while the origin session is being revalidated") {
    for {
      gate <- FirstRoundStartGate.make[IO, Int](initiallyHeld = true)
      old <- gate.arm(parentKey)
      probe <- fallbackWhilePaused(gate, old, IO.pure(true), gate.arm(parentKey + 1).map(_.some))
      oldHeld <- gate.isPending(old)
    } yield
      expect.same(ReentryResult.StaleGeneration, probe.result) &&
        expect(!probe.marked, s"a superseded permit must not set the recovery download flag; $probe") &&
        expect(!probe.transitioned, s"a superseded permit must not transition the node; $probe") &&
        expect.same(0L, probe.episode) &&
        expect(probe.freshHeld.contains(true), s"the newer permit remains held; $probe") &&
        expect(!oldHeld, "the old permit is no longer pending")
  }

  test("the fallback is inert when the origin session is replaced while it is being revalidated") {
    val origin = peerOf(pid("origin")).copy(session = session(1L))
    val replaced = origin.copy(session = session(2L))
    for {
      gate <- FirstRoundStartGate.make[IO, Int](initiallyHeld = true)
      permit <- gate.arm(parentKey)
      cs <- clusterStorage(origin)
      stillCurrent = cs.getResponsivePeers.map(_.exists(p => p.id === origin.id && p.session === origin.session))
      probe <- fallbackWhilePaused(gate, permit, stillCurrent, cs.addPeer(replaced).as(none))
      stillHeld <- gate.isPending(permit)
      stored <- cs.getPeer(origin.id)
    } yield
      expect.same(ReentryResult.StaleOriginSession, probe.result) &&
        expect(!probe.marked && !probe.transitioned, s"a vanished origin session must not enter recovery; $probe") &&
        expect.same(0L, probe.episode) &&
        expect(stillHeld, "the permit is still held: the follower may observe fresh evidence and decide again") &&
        expect(stored.contains(replaced), s"the newer origin record is untouched, got $stored")
  }

  test("the pulse fiber only queues the generation-bound command; the serialized handler decides and completes the handle") {
    val origin = peerOf(pid("origin"))
    type Command = ConsensusCommand[Int, Nothing, Nothing, Nothing]
    for {
      gate <- FirstRoundStartGate.make[IO, Int](initiallyHeld = true)
      permit <- gate.arm(parentKey)
      queue <- Queue.unbounded[IO, Command]
      handles <- Ref.of[IO, Map[Long, Deferred[IO, ReentryResult]]](Map.empty)
      marked <- Ref.of[IO, Boolean](false)
      episode <- Ref.of[IO, Long](0L)
      waiting <- StateTransitions
        .awaitNormalFirstRoundFallback[IO](
          handles,
          permit.generation,
          queue.offer(ConsensusCommand.NormalFirstRoundFallback(permit, origin))
        )
        .start
      queued <- queue.take
      markedBeforeHandler <- marked.get
      registered <- handles.get.map(_.contains(permit.generation))
      // What the FSM handler does on the command loop for the queued command.
      result <- StateTransitions.enterNormalFirstRoundRecovery[IO](
        gate.isPending(permit),
        IO.pure(true),
        marked.set(true),
        IO.pure(NodeStateTransition.Success),
        episode
      )
      _ <- handles.get.flatMap(_.get(permit.generation).traverse_(_.complete(result)))
      awaited <- waiting.joinWithNever
      unregistered <- handles.get.map(_.isEmpty)
      markedAfter <- marked.get
    } yield
      expect.same(ConsensusCommand.NormalFirstRoundFallback(permit, origin), queued) &&
        expect(!markedBeforeHandler, "queueing the command mutates nothing") &&
        expect(registered, "the handle is registered while the pulse fiber waits") &&
        expect.same(ReentryResult.Entered(1L), result) &&
        expect.same(result, awaited) &&
        expect(markedAfter, "the serialized handler performed the mutation") &&
        expect(unregistered, "the handle is unregistered once the wait ends")
  }

  pureTest("the single fallback decision is due only after the bounded safe pulse retries") {
    val retries = StateTransitions.NormalFirstRoundSafePulseRetries
    val firstAhead = 7L

    expect(!StateTransitions.normalFirstRoundFallbackDue(firstAhead, firstAhead, retries), "the ahead tick itself is not the decision") &&
    expect(!StateTransitions.normalFirstRoundFallbackDue(firstAhead, firstAhead + retries - 1L, retries)) &&
    expect(StateTransitions.normalFirstRoundFallbackDue(firstAhead, firstAhead + retries, retries)) &&
    expect.same(retries.toLong + 1L, StateTransitions.normalFirstRoundPulseCycles(firstAhead, firstAhead + retries))
  }

  pureTest("a recovery-only episode blocks release even when an aligned origin reappears") {
    val self = pid("self")
    val aligned = pid("aligned")
    val status = StateTransitions.normalFirstRoundPulseStatus(
      SortedSet(self, aligned),
      matchingFacilityOrigins = Set(aligned),
      aheadProbeOrigins = Set.empty,
      responsivePeerStates = Map(aligned -> NodeState.Ready),
      peerOutcomes = Map[PeerId, PulseOutcome[Int]](aligned -> PulseOutcome.Aligned(parentKey, parentHash))
    )
    val episode = StateTransitions.NormalFirstRoundFollowerEpisode.initial[Int]
    val evidencePeer = StateTransitions.NormalFirstRoundAheadEvidence[Int](
      origin = peerOf(pid("ahead")),
      outcome = PulseOutcome.Ahead(parentKey + 1, parentHash),
      firstAttempt = 1L
    )

    expect(StateTransitions.shouldReleaseNormalFirstRoundPulse(status, episode.recoveryOnly)) &&
    expect(!StateTransitions.shouldReleaseNormalFirstRoundPulse(status, episode.copy(aheadEvidence = evidencePeer.some).recoveryOnly)) &&
    expect(!StateTransitions.shouldReleaseNormalFirstRoundPulse(status, episode.copy(recoveryConcluded = true).recoveryOnly))
  }

  test(
    "an ahead observation is an irreversible latch: losing the origin session clears the fallback evidence, not the recovery-only state"
  ) {
    val self = pid("self")
    val straggler = pid("straggler")
    val ahead = peerOf(pid("ahead"))
    val committee = SortedSet(self, straggler, ahead.id)
    val alignedStraggler = StateTransitions.normalFirstRoundPulseStatus(
      committee,
      matchingFacilityOrigins = Set(straggler),
      aheadProbeOrigins = Set.empty,
      responsivePeerStates = Map(straggler -> NodeState.Ready),
      peerOutcomes = Map[PeerId, PulseOutcome[Int]](straggler -> PulseOutcome.Aligned(parentKey, parentHash))
    )
    val evidence = StateTransitions.NormalFirstRoundAheadEvidence[Int](ahead, PulseOutcome.Ahead(parentKey + 1, parentHash), 1L)
    val (observed, _) = StateTransitions.NormalFirstRoundFollowerEpisode.initial[Int].observe(evidence.some, "peer_ahead")
    val originGone = observed.afterFallback(ReentryResult.StaleOriginSession)
    val (stragglerOnly, _) = originGone.observe(None, alignedStraggler.outcomeLabel)

    // Offer counting mirrors the alignment loop: the release predicate is evaluated with the episode's recoveryOnly flag.
    val loopReleases = for {
      episode <- Ref.of[IO, StateTransitions.NormalFirstRoundFollowerEpisode[Int]](
        StateTransitions.NormalFirstRoundFollowerEpisode.initial[Int]
      )
      offers <- Ref.of[IO, Int](0)
      ticks <- Ref.of[IO, Int](0)
      _ <- StateTransitions.runFirstRoundAlignmentLoop[IO, Int](
        inspect = ticks.updateAndGet(_ + 1),
        isAligned = _ => false,
        record = (tick, _) =>
          episode.update { current =>
            if (tick == 1) current.observe(evidence.some, "peer_ahead")._1
            else if (tick == 2) current.afterFallback(ReentryResult.StaleOriginSession)
            else current.observe(None, alignedStraggler.outcomeLabel)._1
          } >> episode.get.flatMap { current =>
            offers.update(_ + 1).whenA(StateTransitions.shouldReleaseNormalFirstRoundPulse(alignedStraggler, current.recoveryOnly))
          },
        pause = IO.unit,
        offerStart = offers.update(_ + 1),
        startPending = ticks.get.map(_ < 5),
        reportFailure = (_, _) => IO.unit
      )
      offered <- offers.get
      finalEpisode <- episode.get
    } yield (offered, finalEpisode)

    loopReleases.map {
      case (offered, finalEpisode) =>
        expect(observed.observedAhead && observed.recoveryOnly, s"an ahead observation latches recovery-only, got $observed") &&
        expect(originGone.aheadEvidence.isEmpty, s"the session-bound fallback evidence is discarded, got $originGone") &&
        expect(originGone.observedAhead && !originGone.recoveryConcluded, s"the latch survives the vanished origin, got $originGone") &&
        expect(stragglerOnly.recoveryOnly, s"an aligned straggler afterwards keeps the generation recovery-only, got $stragglerOnly") &&
        expect(
          !StateTransitions.shouldReleaseNormalFirstRoundPulse(alignedStraggler, stragglerOnly.recoveryOnly),
          "the stale first round must not be released on the straggler's aligned pulse"
        ) &&
        expect.same(0, offered) &&
        expect(finalEpisode.observedAhead && finalEpisode.aheadEvidence.isEmpty, s"loop-level lifecycle, got $finalEpisode")
    }
  }

  test("fanout 1 samples exactly one origin and larger fanouts sample distinct origins") {
    val candidates = List("a", "b", "c", "d")

    Random.scalaUtilRandomSeedInt[IO](7).flatMap { implicit random =>
      for {
        single <- StateTransitions.sampleNormalFirstRoundOrigins[IO, String](candidates, fanout = 1)
        three <- StateTransitions.sampleNormalFirstRoundOrigins[IO, String](candidates, fanout = 3)
        bounded <- StateTransitions.sampleNormalFirstRoundOrigins[IO, String](candidates.take(2), fanout = 3)
        none <- StateTransitions.sampleNormalFirstRoundOrigins[IO, String](List.empty[String], fanout = 3)
      } yield
        expect.same(1, single.size) &&
          expect(candidates.contains(single.head)) &&
          expect.same(3, three.size) &&
          expect.same(3, three.distinct.size) &&
          expect(three.forall(candidates.contains)) &&
          expect.same(2, bounded.size) &&
          expect(none.isEmpty)
    }
  }

  pureTest("the pulse fanout defaults to one so existing deployments keep single-origin ticks") {
    val config = ConsensusConfig(
      timeTriggerInterval = 43.seconds,
      declarationTimeout = 45.seconds,
      declarationRangeLimit = 3L,
      lockDuration = 10.seconds,
      eventCutter = EventCutterConfig(maxBinarySizeBytes = 1024, maxUpdateNodeParametersSize = 10)
    )

    expect.same(1, config.firstRoundPulseFanout)
  }

  pureTest("queried origins are summarized with peer, http result, key and hash") {
    val peer = pid("queried")
    val summary = StateTransitions.normalFirstRoundQueriedSummary[Int](
      List(
        peer -> PulseOutcome.Ahead(parentKey + 2, parentHash),
        peer -> PulseOutcome.FetchFailed("http_503"),
        peer -> PulseOutcome.Missing
      )
    )

    expect(summary.contains(s":ok:${parentKey + 2}:${parentHash.value}")) &&
    expect(summary.contains(":http_503:n/a:n/a")) &&
    expect(summary.contains(":empty:n/a:n/a")) &&
    expect.same("none", StateTransitions.normalFirstRoundQueriedSummary[Int](List.empty))
  }
}
