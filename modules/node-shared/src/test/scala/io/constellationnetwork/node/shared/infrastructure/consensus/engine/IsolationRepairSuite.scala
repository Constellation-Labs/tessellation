package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.{Kleisli, NonEmptySet}
import cats.effect.kernel.Fiber
import cats.effect.std.{Random, Supervisor}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.PeerRecheckOutcome
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.clients.NodeClient
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.{ClusterStorage => ClusterStorageImpl}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.AbandonmentTracker.SuppressedBy
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.IsolationRepair._
import io.constellationnetwork.node.shared.infrastructure.healthcheck.{LocalHealthcheck => LocalHealthcheckImpl}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.{ClusterId, PeerToJoin, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import io.chrisdavenport.mapref.MapRef
import retry.RetryPolicies
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** B2' rehabilitation pass and B1' repair building blocks.
  *
  *   - zero responsive + retained reachable sessions -> rehabilitation restores -> the next ordinary probe confirms;
  *   - retained all dead -> nothing restored, the probe sees no candidates, `decide` stays false (no recovery);
  *   - 3-node / 1-dead: the responsive-primary probe behaves exactly as before rehabilitation existed;
  *   - session-conditional handling of a changed session, per-peer single-flight/cooldown;
  *   - B1' trigger: common partition (every node repairs), a known Facility age alone measures silence, residence stands in only when no
  *     Facility was ever received, healthy quorum never triggers; recheck never demotes a healthy peer; the repair guard is single-flight
  *     under repeated triggers;
  *   - layer wiring: `Hooks.wired` reports every step as wired in the D1 repair pairs, `Hooks.none` reports none; `Rediscovery.through`
  *     queries priority sources through the discovery program and hands candidates to the ordinary rejoin validation only.
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
      } yield
        expect(before.isEmpty, "precondition: nobody is responsive")
          .and(
            expect(
              beforeProbe.probedPeers == 0 && !beforeProbe.confirmedAhead,
              s"before rehabilitation the probe has no candidates, got $beforeProbe"
            )
          )
          .and(expect(result.restored == 2, s"the two reachable peers are restored, got $result"))
          .and(expect(result.unreachable == 1, s"the dead peer stays Unresponsive, got $result"))
          .and(expect(after.map(_.id) == reachable, s"only the reachable peers are Responsive, got ${after.map(_.id)}"))
          .and(
            expect(
              afterProbe.probedPeers == 2 && afterProbe.confirmedAhead,
              s"the next ordinary probe samples the restored peers and confirms, got $afterProbe"
            )
          )
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
      } yield
        expect(result.restored == 0 && result.unreachable == 2, s"no dead peer is restored, got $result")
          .and(expect(responsive.isEmpty, "the responsive pool stays empty"))
          .and(expect(!signal.probeRequired(responsive.size), "with no Ready peer the probe is not required"))
          .and(
            expect(
              AbandonmentTracker.probeSuppressedBy(probe) == SuppressedBy.NoCandidates,
              s"the probe reports no_candidates, got ${probe.outcome}"
            )
          )
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
      } yield
        expect(rehab.candidates == 1 && rehab.restored == 0, s"the dead remote is sampled but not restored, got $rehab")
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
      } yield
        expect(result.sessionChanged == 1 && result.removed == 1, s"the stale record is removed through the CAS, got $result")
          .and(expect(result.restored == 0, "a differing session never restores"))
          .and(expect(changedNow.isEmpty, "the stale record is gone"))
    // The concurrent-newer-session branch of the CAS (removed = false) is pinned in ClusterStorageSuite.removePeerIfSession.
    }
  }

  /** Sample `queried` (Unresponsive, session 1), pause its session answer, install `replacement` through the real `addPeer` while the
    * request is in flight, then deliver `answer`. Returns the pass result and the record left in the table.
    */
  private def rehabilitateWhileReplaced(
    queried: Peer,
    replacement: Peer,
    answer: SessionToken
  ): IO[(Rehabilitation.Result, Option[Peer])] =
    for {
      cs <- storage(queried)
      started <- Deferred[IO, Unit]
      respond <- Deferred[IO, Unit]
      clock <- clockAt(1000.seconds)
      ledger <- PeerRecheckLedger.make[IO]()
      checkSession = (_: Peer) => started.complete(()).void >> respond.get.as(answer.some)
      run <- Rehabilitation.run[IO](cs, checkSession.some, ledger, clock.get, budget).start
      _ <- started.get
      installed <- cs.addPeer(replacement)
      _ <- respond.complete(())
      result <- run.joinWithNever
      retained <- cs.getPeer(queried.id)
    } yield (result.copy(candidates = if (installed) result.candidates else -1), retained)

  test("rehabilitation preserves a newer Responsive session installed while the session check was in flight") {
    forall(peerGen) { base =>
      val queried = peer(base, 0, Unresponsive, sessionId = 1L)
      val fresh = queried.copy(session = session(2L), responsiveness = Responsive)
      rehabilitateWhileReplaced(queried, fresh, answer = session(1L)).map {
        case (result, retained) =>
          expect(retained.contains(fresh), s"the newer session must survive an answer for the old one, got $retained")
            .and(expect(result.superseded == 1, s"the stale answer is reported as superseded, got $result"))
            .and(expect(result.restored == 0 && result.sessionChanged == 0 && result.removed == 0, s"nothing else is counted, got $result"))
      }
    }
  }

  test("the restore is bound to the queried session: a record replaced in flight keeps its own responsiveness") {
    forall(peerGen) { base =>
      val queried = peer(base, 0, Unresponsive, sessionId = 1L)
      val freshUnresponsive = queried.copy(session = session(2L), responsiveness = Unresponsive)
      rehabilitateWhileReplaced(queried, freshUnresponsive, answer = session(1L)).map {
        case (result, retained) =>
          expect(
            retained.contains(freshUnresponsive),
            s"the old session's healthy answer must not relabel the newer record Responsive, got $retained"
          ).and(expect(result.superseded == 1 && result.restored == 0, s"reported as superseded, not restored, got $result"))
      }
    }
  }

  test("a differing-session answer removes only the queried session, never a record installed in flight") {
    forall(peerGen) { base =>
      val queried = peer(base, 0, Unresponsive, sessionId = 1L)
      val fresh = queried.copy(session = session(2L), responsiveness = Responsive)
      rehabilitateWhileReplaced(queried, fresh, answer = session(3L)).map {
        case (result, retained) =>
          expect(retained.contains(fresh), s"the newer record survives a differing answer for the old one, got $retained")
            .and(expect(result.sessionChanged == 1 && result.removed == 0, s"session change observed, nothing removed, got $result"))
      }
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
      } yield
        expect(first.unreachable == 2, s"first pass checks both, got $first")
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
      } yield
        expect(result == Rehabilitation.Result.notWired, s"not wired is reported, got $result")
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
      .and(
        expect(neverFacilityLong.silence.contains(130.seconds), s"unknown Facility: residence is the silence input, got $neverFacilityLong")
      )
      .and(expect(neverFacilityLong.fire, s"130s residence with no Facility ever fires, got $neverFacilityLong"))
      .and(expect(!neverFacilityShort.fire, s"exactly the threshold does not fire (strictly greater), got $neverFacilityShort"))
      .and(expect(!nothingKnown.fire, s"with neither age known the rule cannot fire, got $nothingKnown"))
      .and(
        expect(
          recentFacilityOldKey.silence.contains(5.seconds),
          s"known Facility age is the silence input on its own, got $recentFacilityOldKey"
        )
      )
      .and(
        expect(!recentFacilityOldKey.fire, s"a long residence with a recent Facility is not silence: no repair, got $recentFacilityOldKey")
      )
      .and(
        expect(
          oldFacilityFreshKey.silence.contains(130.seconds),
          s"residence never shortens a known Facility age, got $oldFacilityFreshKey"
        )
      )
      .and(expect(oldFacilityFreshKey.fire, s"an old Facility fires even on a freshly installed key, got $oldFacilityFreshKey"))
      .and(expect(oldFacilityNoResidence.fire, s"a known old Facility fires without a residence clock, got $oldFacilityNoResidence"))
  }

  test("recheck never demotes a healthy Responsive peer and only starts demotion when its check fails") {
    forall(peerGen) { base =>
      val healthy = peer(base, 0, Responsive)
      val failing = peer(base, 1, Responsive)
      val recheckPeer: Peer => IO[PeerRecheckOutcome] =
        p =>
          if (p.id == healthy.id) IO.pure(PeerRecheckOutcome.Healthy) else IO.pure(PeerRecheckOutcome.Unreachable(demotionStarted = true))
      for {
        cs <- storage(healthy, failing, peer(base, 2, Unresponsive))
        clock <- clockAt(1000.seconds)
        ledger <- PeerRecheckLedger.make[IO]()
        result <- Recheck.run[IO](cs, recheckPeer.some, ledger, clock.get, budget)
        stillResponsive <- cs.getPeer(healthy.id)
      } yield
        expect(
          result.candidates == 2,
          s"only Responsive peers are recheck candidates (Unresponsive ones belong to rehabilitation), got $result"
        )
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
    } yield
      expect(first.isEmpty, s"the first trigger starts a run, got $first")
        .and(expect(second.contains(RunGuard.Skip.InFlight), s"a repeated trigger while running is skipped, got $second"))
        .and(expect(third.contains(RunGuard.Skip.InFlight), s"and again, got $third"))
        .and(
          expect(
            cooling == Some(RunGuard.Skip.Cooldown(40.seconds)),
            s"20s after finishing the guard cools down for 40s more, got $cooling"
          )
        )
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
      } yield
        expect(a && b && c, "every distinct peer can be reserved once")
          .and(expect(entries.size == 1, s"entries past their cooldown are pruned at the cap, got ${entries.size}"))
    }
  }

  private def nodeClientAnswering(answer: Option[SessionToken]): NodeClient[IO] = new NodeClient[IO] {
    def getState: PeerResponse.PeerResponse[IO, NodeState] = Kleisli(_ => IO.raiseError(new Exception("not used")))
    def health: PeerResponse.PeerResponse[IO, Boolean] = Kleisli(_ => IO.raiseError(new Exception("not used")))
    def getSession: PeerResponse.PeerResponse[IO, Option[SessionToken]] = Kleisli(_ => IO.pure(answer))
  }

  test("Hooks.wired reports every repair step as wired in the D1 repair pairs and runs them; Hooks.none reports none") {
    forall(peerGen) { base =>
      val healthy = peer(base, 0, Responsive)
      val retained = peer(base, 1, Unresponsive)
      val checkSession: Peer => IO[Option[SessionToken]] = _ => IO.pure(session(1L).some)
      Supervisor[IO].use { implicit supervisor =>
        for {
          cs <- storage(healthy, retained)
          peersR <- MapRef.ofConcurrentHashMap[IO, PeerId, IO[Fiber[IO, Throwable, Unit]]]()
          healthcheck = LocalHealthcheckImpl.make(
            peersR,
            RetryPolicies.fibonacciBackoff[IO](2.seconds),
            nodeClientAnswering(session(1L).some),
            cs
          )
          wired = Hooks.wired[IO](checkSession, healthcheck, IO.pure(3).some)
          none = Hooks.none[IO]
          clock <- clockAt(1000.seconds)
          ledger <- PeerRecheckLedger.make[IO]()
          recheck <- Recheck.run[IO](cs, wired.recheckPeer, ledger, clock.get, budget)
          rehab <- Rehabilitation.run[IO](cs, wired.checkSession, ledger, clock.get, budget)
          rediscovery <- Rediscovery.run[IO](wired.rediscover, 10.seconds)
          responsive <- cs.getResponsivePeers
          noneLedger <- PeerRecheckLedger.make[IO]()
          recheckNone <- Recheck.run[IO](cs, none.recheckPeer, noneLedger, clock.get, budget)
          rehabNone <- Rehabilitation.run[IO](cs, none.checkSession, noneLedger, clock.get, budget)
          rediscoveryNone <- Rediscovery.run[IO](none.rediscover, 10.seconds)
          wiredPairs = (recheck.logPairs ++ rehab.logPairs ++ rediscovery.logPairs).toMap
          nonePairs = (recheckNone.logPairs ++ rehabNone.logPairs ++ rediscoveryNone.logPairs).toMap
        } yield
          expect(wiredPairs.get("recheckWired").contains("true"), s"recheckWired=true, got $wiredPairs")
            .and(expect(wiredPairs.get("rehabWired").contains("true"), s"rehabWired=true, got $wiredPairs"))
            .and(expect(wiredPairs.get("rediscoverWired").contains("true"), s"rediscoverWired=true, got $wiredPairs"))
            .and(
              expect(recheck.healthy == 1, s"the wired recheck confirms the healthy peer through LocalHealthcheck.recheck, got $recheck")
            )
            .and(expect(rehab.restored == 1, s"the wired session preflight restores the retained peer, got $rehab"))
            .and(expect(rediscovery.candidates == 3, s"the wired re-discovery result is reported, got $rediscovery"))
            .and(
              expect(
                responsive.map(_.id) == Set(healthy.id, retained.id),
                s"both peers are Responsive afterwards, got ${responsive.map(_.id)}"
              )
            )
            .and(expect(nonePairs.get("recheckWired").contains("false"), s"Hooks.none: recheckWired=false, got $nonePairs"))
            .and(expect(nonePairs.get("rehabWired").contains("false"), s"Hooks.none: rehabWired=false, got $nonePairs"))
            .and(expect(nonePairs.get("rediscoverWired").contains("false"), s"Hooks.none: rediscoverWired=false, got $nonePairs"))
      }
    }
  }

  test(
    "Rediscovery.through queries retained priority sources, hands each new candidate to the ordinary rejoin validation and marks the attempt finished"
  ) {
    forall(peerGen) { base =>
      val priority = peer(base, 0, Unresponsive)
      val other = peer(base, 1, Responsive)
      val accepted = peer(base, 2, Responsive)
      val rejected = peer(base, 3, Responsive)
      for {
        cs <- storage(priority, other)
        sources <- Ref.of[IO, List[PeerId]](Nil)
        joined <- Ref.of[IO, List[PeerToJoin]](Nil)
        finished <- Ref.of[IO, Option[Set[PeerId]]](None)
        discoverFrom = (p: Peer) => sources.update(_ :+ p.id).as(if (p.id == priority.id) Set(accepted, rejected) else Set.empty[Peer])
        rejoin = (p: PeerToJoin) => joined.update(_ :+ p) >> IO.raiseError(new Exception("handshake rejected")).whenA(p.id == rejected.id)
        count <- Rediscovery.through[IO](cs, NonEmptySet.of(priority.id).some, discoverFrom, ids => finished.set(ids.some), rejoin)
        queried <- sources.get
        handshakes <- joined.get
        marked <- finished.get
        table <- cs.getPeers
      } yield
        expect(count == 2, s"both candidates are handed to validation, got $count")
          .and(
            expect(
              queried == List(priority.id),
              s"only the retained priority peer is queried, even though it is Unresponsive, got $queried"
            )
          )
          .and(expect(handshakes.map(_.id).toSet == Set(accepted.id, rejected.id), s"each candidate goes through rejoin, got $handshakes"))
          .and(
            expect(
              handshakes.forall(h => h.ip == base.ip && h.p2pPort == base.p2pPort),
              s"rejoin targets the candidate's p2p endpoint, got $handshakes"
            )
          )
          .and(expect(marked.contains(Set(accepted.id, rejected.id)), s"the discovery queue attempt is marked finished, got $marked"))
          .and(
            expect(
              table.map(_.id) == Set(priority.id, other.id),
              s"re-discovery never writes the peer table itself, got ${table.map(_.id)}"
            )
          )
    }
  }

  test(
    "Rediscovery.through falls back to responsive peers without a retained priority peer, is bounded, and is a no-op with nothing to query"
  ) {
    forall(peerGen) { base =>
      val responsive = (0 to 3).map(peer(base, _, Responsive)).toList
      val unknownPriority = peer(base, 14, Responsive).id
      val candidates = (4 to 13).map(peer(base, _, Responsive)).toSet
      for {
        cs <- storage(responsive: _*)
        sources <- Ref.of[IO, List[PeerId]](Nil)
        joined <- Ref.of[IO, List[PeerId]](Nil)
        discoverFrom = (p: Peer) => sources.update(_ :+ p.id).as(candidates)
        rejoin = (p: PeerToJoin) => joined.update(_ :+ p.id)
        count <- Rediscovery
          .through[IO](cs, NonEmptySet.of(unknownPriority).some, discoverFrom, _ => IO.unit, rejoin, maxSources = 2, maxCandidates = 8)
        queried <- sources.get
        handshakes <- joined.get
        empty <- storage()
        emptyTouched <- Ref.of[IO, Boolean](false)
        emptyCount <- Rediscovery.through[IO](
          empty,
          none,
          _ => emptyTouched.set(true).as(candidates),
          _ => emptyTouched.set(true),
          _ => emptyTouched.set(true)
        )
        touched <- emptyTouched.get
      } yield
        expect(queried.size == 2 && queried.toSet.subsetOf(responsive.map(_.id).toSet), s"two responsive sources are sampled, got $queried")
          .and(
            expect(count == 8 && handshakes.size == 8, s"candidates are bounded to eight, got count=$count handshakes=${handshakes.size}")
          )
          .and(
            expect(
              handshakes.distinct.size == 8 && handshakes.toSet.subsetOf(candidates.map(_.id)),
              s"each candidate is handed over once, got $handshakes"
            )
          )
          .and(expect(emptyCount == 0 && !touched, s"with no peer to query nothing is called, got count=$emptyCount touched=$touched"))
    }
  }
}
