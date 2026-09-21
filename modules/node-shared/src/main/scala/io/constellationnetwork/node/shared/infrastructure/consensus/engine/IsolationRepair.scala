package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.{LocalHealthcheck, PeerRecheckOutcome}
import io.constellationnetwork.schema.cluster.{PeerToJoin, SessionToken}
import io.constellationnetwork.schema.peer._

/** Building blocks for the B2' peer rehabilitation pass and the B1' bounded isolation repair. Everything here is diagnostic/repair
  * machinery on the local peer table: it never reads or writes consensus state, node state, or the recovery decision.
  *
  *   - `Hooks`: the layer-supplied transport/discovery capabilities. `Hooks.none` (the default) leaves every step inert and reported as not
  *     wired, so the consensus engine compiles and behaves as before until a layer wires them.
  *   - `Trigger`: the pure B1' trigger rule.
  *   - `PeerRecheckLedger`: per-peer single-flight and cooldown shared by rehabilitation and recheck.
  *   - `Rehabilitation`: bounded session preflight of retained Unresponsive peers (restore via `setPeerResponsiveness`, differing session
  *     via the session-conditional `removePeerIfSession`).
  *   - `Recheck`: bounded, jittered, non-demoting recheck of retained Responsive peers through `LocalHealthcheck.recheck`.
  *   - `RunGuard`: single-flight plus cooldown for the repair operation itself.
  */
object IsolationRepair {

  /** Layer-supplied capabilities. Each is optional so the engine can run with none of them wired. */
  final case class Hooks[F[_]](
    // `/session` of a peer; `None` on transport failure. Used by the rehabilitation pass and, through `LocalHealthcheck`, by recheck.
    checkSession: Option[Peer => F[Option[SessionToken]]],
    // Non-demoting single check (`LocalHealthcheck.recheck`).
    recheckPeer: Option[Peer => F[PeerRecheckOutcome]],
    // Re-discovery against priority/seed peers; returns the number of new candidates queued for ordinary session/join validation.
    rediscover: Option[F[Int]]
  )

  object Hooks {
    def none[F[_]]: Hooks[F] = Hooks(None, None, None)

    /** Production wiring helper for a layer that has the shared `LocalHealthcheck` and a `/session` client. `rediscover` is the layer's
      * bounded `PeerDiscovery.discoverFrom` fan-out over its priority peers with every candidate handed to the ordinary handshake
      * validation (`Rediscovery.through`); the peer table is never written directly.
      */
    def wired[F[_]](
      checkSession: Peer => F[Option[SessionToken]],
      localHealthcheck: LocalHealthcheck[F],
      rediscover: Option[F[Int]]
    ): Hooks[F] =
      Hooks(checkSession.some, ((peer: Peer) => localHealthcheck.recheck(peer)).some, rediscover)
  }

  /** B1' trigger rule (pure, monotonic inputs only).
    *
    * Fires when `silence > silenceIntervals x timeTriggerInterval` AND the responsive Ready peers this node can see, counted together with
    * itself, cannot form the last finalized round's Core quorum. `silence` is the age of the last external Facility whenever that age is
    * known: a recent Facility means the node is being talked to, however long the current key has been resident. Only when no external
    * Facility has been received this session does the residence of the current key stand in, so a node that never heard a Facility still
    * repairs; with neither known the rule cannot fire. Before the first finalized round with a Core committee of at least two (`coreSize <
    * 2`) the rule never fires. The quorum reference is the unshrunk Core quorum of the committee frozen from the last finalized outcome.
    */
  final case class Trigger(
    silence: Option[FiniteDuration],
    silenceThreshold: FiniteDuration,
    responsiveReadyPeers: Int,
    coreSize: Int,
    coreQuorum: Int
  ) {
    def silenceExceeded: Boolean = silence.exists(_ > silenceThreshold)
    def quorumShort: Boolean = coreSize >= 2 && responsiveReadyPeers + 1 < coreQuorum
    def fire: Boolean = silenceExceeded && quorumShort

    def logPairs: List[(String, String)] =
      List(
        "repairSilenceMs" -> silence.fold("unknown")(_.toMillis.toString),
        "repairSilenceThresholdMs" -> silenceThreshold.toMillis.toString,
        "repairResponsiveReadyPeers" -> responsiveReadyPeers.toString,
        "repairCoreSize" -> coreSize.toString,
        "repairCoreQuorum" -> coreQuorum.toString
      )
  }

  object Trigger {
    val SilenceIntervals: Int = 3

    def evaluate(
      residence: Option[FiniteDuration],
      lastExternalFacilityAgo: Option[FiniteDuration],
      timeTriggerInterval: FiniteDuration,
      responsiveReadyPeers: Int,
      coreSize: Int,
      coreQuorum: Int
    ): Trigger = {
      val silence = lastExternalFacilityAgo.orElse(residence)
      Trigger(silence, timeTriggerInterval * SilenceIntervals.toLong, responsiveReadyPeers, coreSize, coreQuorum)
    }
  }

  /** Per-peer single-flight and cooldown, bounded in size. A reservation succeeds only when no check for the peer is in flight and its last
    * check completed at least `cooldown` ago.
    */
  final class PeerRecheckLedger[F[_]: Async](ref: Ref[F, Map[PeerId, PeerRecheckLedger.Entry]], maxEntries: Int) {
    import PeerRecheckLedger._

    def tryReserve(id: PeerId, now: FiniteDuration, cooldown: FiniteDuration): F[Boolean] =
      ref.modify { entries =>
        val entry = entries.getOrElse(id, Entry(inFlight = false, lastCheckedAt = None))
        val cooling = entry.lastCheckedAt.exists(at => now - at < cooldown)
        if (entry.inFlight || cooling) (entries, false)
        else {
          val pruned =
            if (entries.size >= maxEntries)
              entries.filter { case (_, e) => e.inFlight || e.lastCheckedAt.exists(at => now - at < cooldown) }
            else entries
          (pruned.updated(id, entry.copy(inFlight = true)), true)
        }
      }

    def release(id: PeerId, now: FiniteDuration): F[Unit] =
      ref.update(entries => entries.updated(id, Entry(inFlight = false, lastCheckedAt = now.some)))

    def entries: F[Map[PeerId, Entry]] = ref.get
  }

  object PeerRecheckLedger {
    val MaxEntries: Int = 1024

    final case class Entry(inFlight: Boolean, lastCheckedAt: Option[FiniteDuration])

    def make[F[_]: Async](maxEntries: Int = MaxEntries): F[PeerRecheckLedger[F]] =
      Ref.of[F, Map[PeerId, Entry]](Map.empty).map(new PeerRecheckLedger[F](_, maxEntries))

    def unsafe[F[_]: Async]: PeerRecheckLedger[F] = new PeerRecheckLedger[F](Ref.unsafe(Map.empty), MaxEntries)
  }

  /** Tuning shared by rehabilitation and recheck. `perPeerTimeout`/`overallTimeout` mirror the committed-ahead probe budget. */
  final case class Budget(
    sampleSize: Int,
    parallelism: Int,
    perPeerTimeout: FiniteDuration,
    overallTimeout: FiniteDuration,
    peerCooldown: FiniteDuration,
    maxJitter: FiniteDuration
  )

  object Budget {
    val PerPeerTimeout: FiniteDuration = PeersCommittedAheadProbe.PerPeerTimeout
    val OverallTimeout: FiniteDuration = PeersCommittedAheadProbe.OverallTimeout
    val Parallelism: Int = PeersCommittedAheadProbe.Parallelism
  }

  /** B2' rehabilitation pass over retained Unresponsive peers. */
  object Rehabilitation {

    final case class Result(
      wired: Boolean,
      candidates: Int,
      sampled: Int,
      restored: Int,
      sessionChanged: Int,
      removed: Int,
      unreachable: Int,
      skipped: Int,
      timedOut: Boolean,
      failed: Boolean
    ) {

      def logPairs: List[(String, String)] =
        List(
          "rehabWired" -> wired.toString,
          "rehabCandidates" -> candidates.toString,
          "rehabSampled" -> sampled.toString,
          "rehabRestored" -> restored.toString,
          "rehabSessionChanged" -> sessionChanged.toString,
          "rehabRemoved" -> removed.toString,
          "rehabUnreachable" -> unreachable.toString,
          "rehabSkipped" -> skipped.toString,
          "rehabTimedOut" -> timedOut.toString,
          "rehabFailed" -> failed.toString
        )
    }

    object Result {
      val notWired: Result = Result(wired = false, 0, 0, 0, 0, 0, 0, 0, timedOut = false, failed = false)
      val failed: Result = notWired.copy(wired = true, failed = true)
      def timedOut(candidates: Int, sampled: Int): Result =
        notWired.copy(wired = true, candidates = candidates, sampled = sampled, timedOut = true)
    }

    sealed trait PeerOutcome
    object PeerOutcome {
      case object Restored extends PeerOutcome
      final case class SessionChanged(removed: Boolean) extends PeerOutcome
      case object Unreachable extends PeerOutcome
      case object Skipped extends PeerOutcome
    }

    /** Sample up to `budget.sampleSize` retained Unresponsive peers (distinct identities), reserve each in the ledger (single-flight plus
      * cooldown), run the session preflight inside the per-peer/overall budget, and apply the outcome:
      *   - reported session == recorded session: `setPeerResponsiveness(Responsive)` (the ordinary restore path);
      *   - reported session != recorded session: `removePeerIfSession(recorded)` (compare-and-set; a concurrently installed newer session
      *     is left alone);
      *   - no answer: the peer stays Unresponsive. Never confirms anything about cluster progress: the ordinary probe still has to
      *     corroborate committed progress afterwards.
      */
    def run[F[_]: Async](
      clusterStorage: ClusterStorage[F],
      checkSession: Option[Peer => F[Option[SessionToken]]],
      ledger: PeerRecheckLedger[F],
      now: F[FiniteDuration],
      budget: Budget
    ): F[Result] =
      checkSession match {
        case None => Result.notWired.pure[F]
        case Some(check) =>
          val pass = for {
            random <- Random.scalaUtilRandom[F]
            peers <- clusterStorage.getPeers
            candidates = peers.iterator.filter(_.responsiveness === Unresponsive).toList.distinctBy(_.id)
            sample <- random.shuffleList(candidates).map(_.take(math.max(0, budget.sampleSize)))
            outcomes <- sample.parTraverseN(math.max(1, budget.parallelism)) { peer =>
              rehabilitateOne(clusterStorage, check, ledger, now, budget, peer)
            }
          } yield summarize(candidates.size, sample.size, outcomes)

          pass
            .timeoutTo(budget.overallTimeout, Result.timedOut(0, 0).pure[F])
            .handleError(_ => Result.failed)
      }

    private def rehabilitateOne[F[_]: Async](
      clusterStorage: ClusterStorage[F],
      check: Peer => F[Option[SessionToken]],
      ledger: PeerRecheckLedger[F],
      now: F[FiniteDuration],
      budget: Budget,
      peer: Peer
    ): F[PeerOutcome] =
      now.flatMap(at => ledger.tryReserve(peer.id, at, budget.peerCooldown)).flatMap {
        case false => (PeerOutcome.Skipped: PeerOutcome).pure[F]
        case true =>
          val preflight: F[PeerOutcome] =
            check(peer)
              .timeoutTo(budget.perPeerTimeout, none[SessionToken].pure[F])
              .handleError(_ => none[SessionToken])
              .flatMap {
                case None => (PeerOutcome.Unreachable: PeerOutcome).pure[F]
                case Some(reported) =>
                  clusterStorage.getPeer(peer.id).flatMap {
                    case Some(recorded) if recorded.session === reported =>
                      clusterStorage.setPeerResponsiveness(peer.id, Responsive).as(PeerOutcome.Restored: PeerOutcome)
                    case Some(recorded) =>
                      clusterStorage.removePeerIfSession(peer.id, recorded.session).map(PeerOutcome.SessionChanged(_): PeerOutcome)
                    case None => (PeerOutcome.Unreachable: PeerOutcome).pure[F]
                  }
              }
          preflight.guarantee(now.flatMap(ledger.release(peer.id, _)))
      }

    private[engine] def summarize(candidates: Int, sampled: Int, outcomes: List[PeerOutcome]): Result =
      Result(
        wired = true,
        candidates = candidates,
        sampled = sampled,
        restored = outcomes.count(_ == PeerOutcome.Restored),
        sessionChanged = outcomes.count { case PeerOutcome.SessionChanged(_) => true; case _ => false },
        removed = outcomes.count { case PeerOutcome.SessionChanged(true) => true; case _ => false },
        unreachable = outcomes.count(_ == PeerOutcome.Unreachable),
        skipped = outcomes.count(_ == PeerOutcome.Skipped),
        timedOut = false,
        failed = false
      )
  }

  /** B1' step (1): bounded, jittered, non-demoting recheck of retained Responsive peers through `LocalHealthcheck.recheck`. Retained
    * Unresponsive peers are the rehabilitation pass's job (step 3), so each peer is checked at most once per repair run.
    */
  object Recheck {

    final case class Result(
      wired: Boolean,
      candidates: Int,
      sampled: Int,
      healthy: Int,
      joined: Int,
      sessionChanged: Int,
      unreachable: Int,
      demotionStarted: Int,
      skipped: Int,
      timedOut: Boolean,
      failed: Boolean
    ) {

      def logPairs: List[(String, String)] =
        List(
          "recheckWired" -> wired.toString,
          "recheckCandidates" -> candidates.toString,
          "recheckSampled" -> sampled.toString,
          "recheckHealthy" -> healthy.toString,
          "recheckJoined" -> joined.toString,
          "recheckSessionChanged" -> sessionChanged.toString,
          "recheckUnreachable" -> unreachable.toString,
          "recheckDemotionStarted" -> demotionStarted.toString,
          "recheckSkipped" -> skipped.toString,
          "recheckTimedOut" -> timedOut.toString,
          "recheckFailed" -> failed.toString
        )
    }

    object Result {
      val notWired: Result = Result(wired = false, 0, 0, 0, 0, 0, 0, 0, 0, timedOut = false, failed = false)
      val failed: Result = notWired.copy(wired = true, failed = true)
      val timedOut: Result = notWired.copy(wired = true, timedOut = true)
    }

    sealed trait PeerOutcome
    object PeerOutcome {
      final case class Checked(outcome: PeerRecheckOutcome) extends PeerOutcome
      case object Skipped extends PeerOutcome
    }

    def run[F[_]: Async](
      clusterStorage: ClusterStorage[F],
      recheckPeer: Option[Peer => F[PeerRecheckOutcome]],
      ledger: PeerRecheckLedger[F],
      now: F[FiniteDuration],
      budget: Budget
    ): F[Result] =
      recheckPeer match {
        case None => Result.notWired.pure[F]
        case Some(recheck) =>
          val pass = for {
            random <- Random.scalaUtilRandom[F]
            peers <- clusterStorage.getPeers
            candidates = peers.iterator.filter(_.responsiveness === Responsive).toList.distinctBy(_.id)
            sample <- random.shuffleList(candidates).map(_.take(math.max(0, budget.sampleSize)))
            outcomes <- sample.parTraverseN(math.max(1, budget.parallelism)) { peer =>
              now.flatMap(at => ledger.tryReserve(peer.id, at, budget.peerCooldown)).flatMap {
                case false => (PeerOutcome.Skipped: PeerOutcome).pure[F]
                case true =>
                  val jitter = random.betweenLong(0L, math.max(1L, budget.maxJitter.toMillis + 1L)).map(_.millis)
                  jitter
                    .flatMap(Async[F].sleep)
                    .productR(recheck(peer))
                    .timeoutTo(
                      budget.perPeerTimeout + budget.maxJitter,
                      (PeerRecheckOutcome.Unreachable(demotionStarted = false): PeerRecheckOutcome).pure[F]
                    )
                    .handleError(_ => PeerRecheckOutcome.Unreachable(demotionStarted = false): PeerRecheckOutcome)
                    .map(PeerOutcome.Checked(_): PeerOutcome)
                    .guarantee(now.flatMap(ledger.release(peer.id, _)))
              }
            }
          } yield summarize(candidates.size, sample.size, outcomes)

          pass
            .timeoutTo(budget.overallTimeout + budget.maxJitter, Result.timedOut.pure[F])
            .handleError(_ => Result.failed)
      }

    private[engine] def summarize(candidates: Int, sampled: Int, outcomes: List[PeerOutcome]): Result = {
      val checked = outcomes.collect { case PeerOutcome.Checked(o) => o }
      Result(
        wired = true,
        candidates = candidates,
        sampled = sampled,
        healthy = checked.count(_ == PeerRecheckOutcome.Healthy),
        joined = checked.count(_ == PeerRecheckOutcome.Joined),
        sessionChanged = checked.count { case PeerRecheckOutcome.SessionChanged(_) => true; case _ => false },
        unreachable = checked.count { case PeerRecheckOutcome.Unreachable(_) => true; case _ => false },
        demotionStarted = checked.count { case PeerRecheckOutcome.Unreachable(true) => true; case _ => false },
        skipped = outcomes.count(_ == PeerOutcome.Skipped),
        timedOut = false,
        failed = false
      )
    }
  }

  /** B1' step (2): re-discovery through the layer hook. Candidates are only queued for ordinary session/join validation. */
  object Rediscovery {
    final case class Result(wired: Boolean, candidates: Int, failed: Boolean) {
      def logPairs: List[(String, String)] =
        List("rediscoverWired" -> wired.toString, "rediscoverCandidates" -> candidates.toString, "rediscoverFailed" -> failed.toString)
    }

    object Result {
      val notWired: Result = Result(wired = false, 0, failed = false)
    }

    def run[F[_]: Async](rediscover: Option[F[Int]], timeout: FiniteDuration): F[Result] =
      rediscover.fold(Result.notWired.pure[F]) { discover =>
        discover
          .map(Result(wired = true, _, failed = false))
          .timeoutTo(timeout, Result(wired = true, 0, failed = true).pure[F])
          .handleError(_ => Result(wired = true, 0, failed = true))
      }

    val MaxSources: Int = 3
    val MaxCandidates: Int = 8

    /** The production `rediscover` hook, built from the ordinary cluster programs and never from a new client.
      *
      * Sources are the retained peers whose id is in `priorityPeerIds` (any responsiveness: an isolated node has typically marked them
      * Unresponsive); when none is retained, a random sample of responsive peers. Up to `maxSources` sources are queried through
      * `discoverFrom` (`PeerDiscovery.discoverFrom`: filters self, the source, known peers with a session at least as new, and already
      * queued candidates). Up to `maxCandidates` distinct candidates are then handed to `rejoin` (`Joining.rejoin`: the ordinary seedlist,
      * registration-request, handshake and signature validation followed by `addPeer`), so nothing reaches the peer table without that
      * validation; the attempt is marked finished in the discovery queue either way. Per-source and per-candidate failures are swallowed.
      * Returns the number of candidates handed to validation.
      */
    def through[F[_]: Async](
      clusterStorage: ClusterStorage[F],
      priorityPeerIds: Option[NonEmptySet[PeerId]],
      discoverFrom: Peer => F[Set[Peer]],
      markAttemptsFinished: Set[PeerId] => F[Unit],
      rejoin: PeerToJoin => F[Unit],
      maxSources: Int = MaxSources,
      maxCandidates: Int = MaxCandidates,
      parallelism: Int = Budget.Parallelism
    ): F[Int] = {
      val priority = priorityPeerIds.fold(Set.empty[PeerId])(_.toSortedSet.toSet)
      val fanOut = math.max(1, parallelism)
      for {
        random <- Random.scalaUtilRandom[F]
        retained <- clusterStorage.getPeers
        preferred = retained.iterator.filter(p => priority.contains(p.id)).toList.distinctBy(_.id)
        pool <- if (preferred.nonEmpty) preferred.pure[F] else clusterStorage.getResponsivePeers.map(_.toList.distinctBy(_.id))
        sources <- random.shuffleList(pool).map(_.take(math.max(0, maxSources)))
        discovered <- sources.parTraverseN(fanOut)(source => discoverFrom(source).handleError(_ => Set.empty[Peer]))
        candidates <- random.shuffleList(discovered.combineAll.toList.distinctBy(_.id)).map(_.take(math.max(0, maxCandidates)))
        _ <- candidates
          .parTraverseN(fanOut)(candidate => rejoin(PeerToJoin(candidate.id, candidate.ip, candidate.p2pPort)).attempt.void)
          .guarantee(markAttemptsFinished(candidates.map(_.id).toSet))
          .whenA(candidates.nonEmpty)
      } yield candidates.size
    }
  }

  /** Single-flight plus cooldown for the repair operation. */
  final class RunGuard[F[_]: Async](ref: Ref[F, RunGuard.State], cooldown: FiniteDuration) {
    import RunGuard._

    def tryStart(now: FiniteDuration): F[Option[Skip]] =
      ref.modify { state =>
        if (state.inFlight) (state, (Skip.InFlight: Skip).some)
        else
          state.lastFinishedAt match {
            case Some(at) if now - at < cooldown => (state, (Skip.Cooldown(cooldown - (now - at)): Skip).some)
            case _                               => (state.copy(inFlight = true), none[Skip])
          }
      }

    def finish(now: FiniteDuration): F[Unit] =
      ref.update(_.copy(inFlight = false, lastFinishedAt = now.some))

    def runs: F[Int] = ref.get.map(_.runs)

    def countRun: F[Unit] = ref.update(s => s.copy(runs = s.runs + 1))
  }

  object RunGuard {
    final case class State(inFlight: Boolean, lastFinishedAt: Option[FiniteDuration], runs: Int)

    sealed abstract class Skip(val label: String)
    object Skip {
      case object InFlight extends Skip("in_flight")
      final case class Cooldown(remaining: FiniteDuration) extends Skip("cooldown")
    }

    def make[F[_]: Async](cooldown: FiniteDuration): F[RunGuard[F]] =
      Ref.of[F, State](State(inFlight = false, None, 0)).map(new RunGuard[F](_, cooldown))

    def unsafe[F[_]: Async](cooldown: FiniteDuration): RunGuard[F] =
      new RunGuard[F](Ref.unsafe(State(inFlight = false, None, 0)), cooldown)
  }
}
