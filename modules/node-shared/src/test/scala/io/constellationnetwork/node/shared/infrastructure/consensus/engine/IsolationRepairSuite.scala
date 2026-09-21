package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.std.Random
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.PeerRecheckOutcome
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.{ClusterStorage => ClusterStorageImpl}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.AbandonmentTracker.SuppressedBy
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.IsolationRepair._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.{ClusterId, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** B2' rehabilitation pass and B1' repair building blocks.
  *
  *   - zero responsive + retained reachable sessions -> rehabilitation restores -> the next ordinary probe confirms;
  *   - retained all dead -> nothing restored, the probe sees no candidates, `decide` stays false (no recovery);
  *   - 3-node / 1-dead: the responsive-primary probe behaves exactly as before rehabilitation existed;
  *   - session-conditional handling of a changed session, per-peer single-flight/cooldown;
  *   - B1' trigger: common partition (every node repairs), a known Facility age alone measures silence, residence stands in only when
  *     no Facility was ever received, healthy quorum never triggers; recheck never demotes a healthy peer; the repair guard is
  *     single-flight under repeated triggers.
  */
object IsolationRepairSuite extends SimpleIOSuite with Checkers {

  private val key = SnapshotOrdinal.unsafeApply(100L)
  private val interval = 43.seconds
  private val hexChars = "0123456789abcdef"

  private def session(n: Long): SessionToken = SessionToken(Generation(PosLong.unsafeFrom(n)))

  private def peer(base: Peer, i: Int, responsiveness: PeerResponsiveness, sessionId: Long = 1L): Peer =
    base.copy(
      id = PeerId(Hex(hexChars(i % 16).toString * 128)),
      state = NodeState.Ready,
      responsiveness = responsiveness,
      session = session(sessionId)
    )

  private def storage(peers: Peer*): IO[ClusterStorage[IO]] =
    ClusterStorageImpl.make[IO](ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"), peers.map(p => p.id -> p).toMap)

  private val budget = Budget(
    sampleSize = 8,
    parallelism = 4,
    perPeerTimeout = 2.seconds,
    overallTimeout = 10.seconds,
    peerCooldown = 60.seconds,
    maxJitter = Duration.Zero
  )

  private def clockAt(t: FiniteDuration): IO[Ref[IO, FiniteDuration]] = Ref.of[IO, FiniteDuration](t)

  private def metadata(n: Long): SnapshotMetadata = SnapshotMetadata(SnapshotOrdinal.unsafeApply(n), Hash("h"), Hash("p"))

  private def withRandom[A](run: Random[IO] => IO[A]): IO[A] = Random.scalaUtilRandom[IO].flatMap(run)

  test("zero responsive peers with retained reachable sessions: rehabilitation restores them and the next probe confirms") {
    forall(peerGen) { base =>
      val dead = List(peer(base, 0, Unresponsive), peer(base, 1, Unresponsive), peer(base, 2, Unresponsive))
      val reachable = dead.take(2).map(_.id).toSet
      val checkSession: Peer => IO[Option[SessionToken]] =
        p => if (reachable.contains(p.id)) IO.pure(session(1L).some) else IO.raiseError(new Exception("timeout"))
      val fetch: Peer => IO[SnapshotMetadata] = _ => IO.pure(metadata(101L))
      for {
        cs <- storage(dead: _*)
        clock <- clockAt(1000.seconds)
        ledger <- PeerRecheckLedger.make[IO]()
        before <- cs.getResponsivePeers
        beforeProbe <- withRandom(implicit r => PeersCommittedAheadProbe.make[IO](cs, fetch).apply(key))
        result <- Rehabilitation.run[IO](cs, checkSession.some, ledger, clock.get, budget)
        after <- cs.getResponsivePeers
        afterProbe <- withRandom(implicit r => PeersCommittedAheadProbe.make[IO](cs, fetch).apply(key))
      } yield expect(before.isEmpty, "precondition: nobody is responsive")
        .and(expect(beforeProbe.probedPeers == 0 && !beforeProbe.confirmedAhead, s"before rehabilitation the probe has no candidates, got $beforeProbe"))
        .and(expect(result.restored == 2, s"the two reachable peers are restored, got $result"))
        .and(expect(result.unreachable == 1, s"the dead peer stays Unresponsive, got $result"))
        .and(expect(after.map(_.id) == reachable, s"only the reachable peers are Responsive, got ${after.map(_.id)}"))
        .and(expect(afterProbe.probedPeers == 2 && afterProbe.confirmedAhead, s"the next ordinary probe samples the restored peers and confirms, got $afterProbe"))
    }
  }

  test("retained peers all dead: nothing is restored, the probe reports no_candidates and the decision stays false") {
    forall(peerGen) { base =>
      val dead = List(peer(base, 0, Unresponsive), peer(base, 1, Unresponsive))
      val checkSession: Peer => IO[Option[SessionToken]] = _ => IO.pure(none[SessionToken])
      val fetch: Peer => IO[SnapshotMetadata] = _ => IO.raiseError(new Exception("must not be called"))
      for {
        cs <- storage(dead: _*)
        clock <- clockAt(1000.seconds)
        ledger <- PeerRecheckLedger.make[IO]()
        result <- Rehabilitation.run[IO](cs, checkSession.some, ledger, clock.get, budget)
        responsive <- cs.getResponsivePeers
        probe <- withRandom(implicit r => PeersCommittedAheadProbe.make[IO](cs, fetch).apply(key))
        signal = AbandonmentTracker.EscalationSignal.noRumor
      } yield expect(result.restored == 0 && result.unreachable == 2, s"no dead peer is restored, got $result")
        .and(expect(responsive.isEmpty, "the responsive pool stays empty"))
        .and(expect(!signal.probeRequired(responsive.size), "with no Ready peer the probe is not required"))
        .and(expect(AbandonmentTracker.probeSuppressedBy(probe) == SuppressedBy.NoCandidates, s"the probe reports no_candidates, got ${probe.outcome}"))
        .and(expect(!signal.decide(probe.confirmedAhead), "no recovery decision without corroborated progress"))
    }
  }

  test("3-node cluster with one dead remote: the responsive-primary probe confirms from the single live peer, exactly as before") {
    forall(peerGen) { base =>
      val live = peer(base, 0, Responsive)
      val dead = peer(base, 1, Unresponsive)
      val checkSession: Peer => IO[Option[SessionToken]] = _ => IO.pure(none[SessionToken])
      val fetch: Peer => IO[SnapshotMetadata] =
        p => if (p.id == live.id) IO.pure(metadata(101L)) else IO.raiseError(new Exception("dead"))
      for {
        cs <- storage(live, dead)
        clock <- clockAt(1000.seconds)
        ledger <- PeerRecheckLedger.make[IO]()
        rehab <- Rehabilitation.run[IO](cs, checkSession.some, ledger, clock.get, budget)
        probe <- withRandom(implicit r => PeersCommittedAheadProbe.make[IO](cs, fetch).apply(key))
      } yield expect(rehab.candidates == 1 && rehab.restored == 0, s"the dead remote is sampled but not restored, got $rehab")
        .and(expect(probe.probedPeers == 1, s"the probe samples only the responsive pool (clamp unchanged), got $probe"))
        .and(expect(probe.requiredCorroborators == 1, s"one sampled peer needs one corroborator, got ${probe.requiredCorroborators}"))
        .and(expect(probe.confirmedAhead, s"the single live peer confirms as it did before rehabilitation existed, got $probe"))
    }
  }

  test("a differing session is handled by compare-and-set removal, never by unconditional removal") {
    forall(peerGen) { base =>
      val changed = peer(base, 0, Unresponsive, sessionId = 1L)
      val checkSession: Peer => IO[Option[SessionToken]] = _ => IO.pure(session(2L).some)
      for {
        cs <- storage(changed)
        clock <- clockAt(1000.seconds)
        ledger <- PeerRecheckLedger.make[IO]()
        result <- Rehabilitation.run[IO](cs, checkSession.some, ledger, clock.get, budget)
        changedNow <- cs.getPeer(changed.id)
      } yield expect(result.sessionChanged == 1 && result.removed == 1, s"the stale record is removed through the CAS, got $result")
        .and(expect(result.restored == 0, "a differing session never restores"))
        .and(expect(changedNow.isEmpty, "the stale record is gone"))
      // The concurrent-newer-session branch of the CAS (removed = false) is pinned in ClusterStorageSuite.removePeerIfSession.
    }
  }

  test("per-peer single-flight and cooldown: a second pass inside the cooldown skips every peer") {
    forall(peerGen) { base =>
      val dead = List(peer(base, 0, Unresponsive), peer(base, 1, Unresponsive))
      val calls = Ref.unsafe[IO, Int](0)
      val checkSession: Peer => IO[Option[SessionToken]] = _ => calls.update(_ + 1).as(none[SessionToken])
      for {
        cs <- storage(dead: _*)
        clock <- clockAt(1000.seconds)
        ledger <- PeerRecheckLedger.make[IO]()
        first <- Rehabilitation.run[IO](cs, checkSession.some, ledger, clock.get, budget)
        second <- Rehabilitation.run[IO](cs, checkSession.some, ledger, clock.get, budget)
        _ <- clock.update(_ + 60.seconds)
        third <- Rehabilitation.run[IO](cs, checkSession.some, ledger, clock.get, budget)
        total <- calls.get
      } yield expect(first.unreachable == 2, s"first pass checks both, got $first")
        .and(expect(second.skipped == 2 && second.unreachable == 0, s"second pass inside the cooldown skips both, got $second"))
        .and(expect(third.unreachable == 2, s"after the cooldown both are checked again, got $third"))
        .and(expect(total == 4, s"four session checks in total, got $total"))
    }
  }

  test("rehabilitation is inert when the session hook is not wired") {
    forall(peerGen) { base =>
      for {
        cs <- storage(peer(base, 0, Unresponsive))
        clock <- clockAt(1000.seconds)
        ledger <- PeerRecheckLedger.make[IO]()
        result <- Rehabilitation.run[IO](cs, none, ledger, clock.get, budget)
        responsive <- cs.getResponsivePeers
      } yield expect(result == Rehabilitation.Result.notWired, s"not wired is reported, got $result")
        .and(expect(responsive.isEmpty, "nothing changes in the peer table"))
    }
  }

  pureTest("B1' trigger: common partition fires on every node, healthy quorum never fires, pre-committee never fires") {
    def node(ready: Int) = Trigger.evaluate(200.seconds.some, 200.seconds.some, interval, ready, coreSize = 3, coreQuorum = 2)
    val partitioned = List(node(0), node(0), node(0))
    val healthy = node(1)
    val preCommittee = Trigger.evaluate(200.seconds.some, 200.seconds.some, interval, 0, coreSize = 1, coreQuorum = 1)

    expect(partitioned.forall(_.fire), s"every partitioned node repairs, got ${partitioned.map(_.fire)}")
      .and(expect(partitioned.forall(_.quorumShort), "each sees self alone below the Core quorum"))
      .and(expect(!healthy.fire, s"self plus one live peer meets a 2-of-3 quorum: no repair, got $healthy"))
      .and(expect(!preCommittee.fire, s"a singleton committee never triggers, got $preCommittee"))
  }

  pureTest("B1' trigger: a known Facility age is the silence measure; residence only stands in when no Facility was ever received") {
    val neverFacilityLong = Trigger.evaluate(130.seconds.some, None, interval, 0, 3, 2)
    val neverFacilityShort = Trigger.evaluate(129.seconds.some, None, interval, 0, 3, 2)
    val nothingKnown = Trigger.evaluate(None, None, interval, 0, 3, 2)
    val recentFacilityOldKey = Trigger.evaluate(300.seconds.some, 5.seconds.some, interval, 0, 3, 2)
    val oldFacilityFreshKey = Trigger.evaluate(5.seconds.some, 130.seconds.some, interval, 0, 3, 2)
    val oldFacilityNoResidence = Trigger.evaluate(None, 130.seconds.some, interval, 0, 3, 2)

    expect(neverFacilityLong.silenceThreshold == 129.seconds, s"threshold is 3 x interval, got ${neverFacilityLong.silenceThreshold}")
      .and(expect(neverFacilityLong.silence.contains(130.seconds), s"unknown Facility: residence is the silence input, got $neverFacilityLong"))
      .and(expect(neverFacilityLong.fire, s"130s residence with no Facility ever fires, got $neverFacilityLong"))
      .and(expect(!neverFacilityShort.fire, s"exactly the threshold does not fire (strictly greater), got $neverFacilityShort"))
      .and(expect(!nothingKnown.fire, s"with neither age known the rule cannot fire, got $nothingKnown"))
      .and(expect(recentFacilityOldKey.silence.contains(5.seconds), s"known Facility age is the silence input on its own, got $recentFacilityOldKey"))
      .and(expect(!recentFacilityOldKey.fire, s"a long residence with a recent Facility is not silence: no repair, got $recentFacilityOldKey"))
      .and(expect(oldFacilityFreshKey.silence.contains(130.seconds), s"residence never shortens a known Facility age, got $oldFacilityFreshKey"))
      .and(expect(oldFacilityFreshKey.fire, s"an old Facility fires even on a freshly installed key, got $oldFacilityFreshKey"))
      .and(expect(oldFacilityNoResidence.fire, s"a known old Facility fires without a residence clock, got $oldFacilityNoResidence"))
  }

  test("recheck never demotes a healthy Responsive peer and only starts demotion when its check fails") {
    forall(peerGen) { base =>
      val healthy = peer(base, 0, Responsive)
      val failing = peer(base, 1, Responsive)
      val recheckPeer: Peer => IO[PeerRecheckOutcome] =
        p => if (p.id == healthy.id) IO.pure(PeerRecheckOutcome.Healthy) else IO.pure(PeerRecheckOutcome.Unreachable(demotionStarted = true))
      for {
        cs <- storage(healthy, failing, peer(base, 2, Unresponsive))
        clock <- clockAt(1000.seconds)
        ledger <- PeerRecheckLedger.make[IO]()
        result <- Recheck.run[IO](cs, recheckPeer.some, ledger, clock.get, budget)
        stillResponsive <- cs.getPeer(healthy.id)
      } yield expect(result.candidates == 2, s"only Responsive peers are recheck candidates (Unresponsive ones belong to rehabilitation), got $result")
        .and(expect(result.healthy == 1 && result.demotionStarted == 1, s"one healthy, one handed to the demoting loop, got $result"))
        .and(expect(stillResponsive.exists(_.responsiveness == Responsive), "the healthy peer keeps its classification"))
    }
  }

  test("the repair guard is single-flight under repeated triggers and cools down after a run") {
    for {
      guard <- RunGuard.make[IO](60.seconds)
      first <- guard.tryStart(1000.seconds)
      second <- guard.tryStart(1001.seconds)
      third <- guard.tryStart(1002.seconds)
      _ <- guard.finish(1010.seconds)
      cooling <- guard.tryStart(1030.seconds)
      afterCooldown <- guard.tryStart(1070.seconds)
    } yield expect(first.isEmpty, s"the first trigger starts a run, got $first")
      .and(expect(second.contains(RunGuard.Skip.InFlight), s"a repeated trigger while running is skipped, got $second"))
      .and(expect(third.contains(RunGuard.Skip.InFlight), s"and again, got $third"))
      .and(expect(cooling == Some(RunGuard.Skip.Cooldown(40.seconds)), s"20s after finishing the guard cools down for 40s more, got $cooling"))
      .and(expect(afterCooldown.isEmpty, s"after the cooldown a new run starts, got $afterCooldown"))
  }

  test("the recheck ledger is bounded") {
    forall(peerGen) { base =>
      for {
        ledger <- PeerRecheckLedger.make[IO](maxEntries = 2)
        a <- ledger.tryReserve(peer(base, 0, Responsive).id, 1000.seconds, 60.seconds)
        _ <- ledger.release(peer(base, 0, Responsive).id, 1000.seconds)
        b <- ledger.tryReserve(peer(base, 1, Responsive).id, 1000.seconds, 60.seconds)
        _ <- ledger.release(peer(base, 1, Responsive).id, 1000.seconds)
        c <- ledger.tryReserve(peer(base, 2, Responsive).id, 1100.seconds, 60.seconds)
        entries <- ledger.entries
      } yield expect(a && b && c, "every distinct peer can be reserved once")
        .and(expect(entries.size == 1, s"entries past their cooldown are pruned at the cap, got ${entries.size}"))
    }
  }
}
