package io.constellationnetwork.node.shared.infrastructure.healthcheck

import cats.Monad
import cats.data.Kleisli
import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.applicative._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.parallel._
import cats.syntax.traverse._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.PeerRecheckOutcome
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.clients.NodeClient
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.ClusterStorage
import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import retry.{RetryPolicies, RetryPolicy}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object LocalHealthcheckSuite extends SimpleIOSuite with Checkers {

  def mkPeersR = LocalHealthcheck.mkWorkers[IO]
  def retryPolicy: RetryPolicy[IO] = RetryPolicies.fibonacciBackoff[IO](2.seconds)
  def nodeClient = mkNodeClient(responsive = false)
  def mapPeer: Peer => Peer = _.copy(responsiveness = Responsive, state = NodeState.Ready, session = SessionToken(Generation.MinValue))

  test("does not spawn healthcheck for an unknown node") {

    val initialPeers: Map[PeerId, Peer] = Map.empty

    forall(peerGen) { peer =>
      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

          lh.start(peer)
        }

        TestControl.executeEmbed(prog).flatMap { _ =>
          peersR.slots.keys.map(_.size).map(expect.same(_, 0))
        }
      }
    }
  }

  test("does not spawn healthcheck for already unresponsive peer") {

    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> peer.copy(responsiveness = Unresponsive))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)

          lh.start(peer)
        }

        TestControl.executeEmbed(prog) >>
          peersR.slots.keys.map(_.size).map(expect.same(_, 0))
      }
    }
  }

  test("spawns healthcheck for responsive peer") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        // Slots are observed while the supervisor is alive: workers release their slot when they end.
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)
          lh.start(mapPeer(peer)) >> peersR.slots.keys.map(_.size)
        }

        TestControl.executeEmbed(prog).map(expect.same(_, 1))
      }
    }
  }

  test("spawns healthcheck for responsive peer and expect closed fiber") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, mkNodeClient(responsive = true), cs)
          lh.start(mapPeer(peer)) >> lh.cancel(peer.id)
        }

        TestControl.executeEmbed(prog).flatMap { _ =>
          peersR.slots.keys.map(_.size).map(expect.same(_, 0))
        }
      }
    }
  }

  test("cancels existing healthcheck") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, mkNodeClient(responsive = true), cs)
          lh.start(mapPeer(peer)) >> lh.cancel(peer.id)
        }

        TestControl.executeEmbed(prog).flatMap { _ =>
          peersR.slots(peer.id).get.map(expect.same(_, None))
        }
      }
    }
  }

  test("spawns healthcheck for many responsive peers") {

    forall(peersGen()) { peers =>
      val initialPeers: Map[PeerId, Peer] =
        peers.map(mapPeer).map(p => (p.id, p)).toMap

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)
          peers.toList.parTraverse(peer => lh.start(mapPeer(peer))) >> peersR.slots.keys.map(_.size)
        }

        TestControl.executeEmbed(prog).map(expect.same(_, peers.size))
      }
    }
  }

  test("spawns healthcheck for many responsive peers and cancels all") {

    forall(peersGen()) { peers =>
      val initialPeers: Map[PeerId, Peer] =
        peers.map(mapPeer).map(p => (p.id, p)).toMap

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)
          peers.toList.parTraverse(peer => lh.start(mapPeer(peer)) >> lh.cancel(peer.id))
        }

        TestControl.executeEmbed(prog) >>
          peersR.slots.keys.map(_.size).map(expect.same(_, 0))
      }
    }
  }

  test("spawns healthcheck for many responsive peers twice") {

    forall(peersGen()) { peers =>
      val initialPeers: Map[PeerId, Peer] =
        peers.map(mapPeer).map(p => (p.id, p)).toMap

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)
          peers.toList.parTraverse { peer =>
            lh.start(mapPeer(peer)) >> lh.cancel(peer.id) >> lh.start(mapPeer(peer))
          } >> peersR.slots.keys.map(_.size)
        }

        TestControl.executeEmbed(prog).map(expect.same(_, peers.size))

      }
    }
  }

  // --- B1' non-demoting recheck ---

  private val otherSession: SessionToken = SessionToken(Generation(PosLong.unsafeFrom(2L)))

  test("recheck keeps a healthy Responsive peer Responsive and spawns no fiber") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, mkNodeClient(responsive = true), cs)
          lh.recheck(peer)
        }

        TestControl.executeEmbed(prog).flatMap { outcome =>
          (cs.getPeer(peer.id), peersR.slots.keys.map(_.size)).tupled.map {
            case (stored, fibers) =>
              expect
                .same(PeerRecheckOutcome.Healthy, outcome)
                .and(expect(stored.exists(_.responsiveness == Responsive), "a healthy peer is never demoted"))
                .and(expect.same(0, fibers))
          }
        }
      }
    }
  }

  test("recheck of an unreachable Responsive peer demotes only after the failed check, through the ordinary loop") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)
          // The demotion runs on the ordinary loop's supervised fiber: observe it under the test clock.
          lh.recheck(peer).flatMap { outcome =>
            (IO.sleep(10.millis) >> cs.getPeer(peer.id))
              .iterateUntil(_.exists(_.responsiveness == Unresponsive))
              .timeout(5.seconds)
              .attempt
              .map(stored => outcome -> stored.toOption.flatten)
          }
        }

        TestControl.executeEmbed(prog).map {
          case (outcome, stored) =>
            expect
              .same(PeerRecheckOutcome.Unreachable(demotionStarted = true), outcome)
              .and(expect(stored.exists(_.responsiveness == Unresponsive), "after a failed check the ordinary loop demotes the peer"))
        }
      }
    }
  }

  test("recheck of an unreachable Unresponsive peer leaves it Unresponsive and spawns nothing") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer).copy(responsiveness = Unresponsive))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)
          lh.recheck(peer)
        }

        TestControl.executeEmbed(prog).flatMap { outcome =>
          (cs.getPeer(peer.id), peersR.slots.keys.map(_.size)).tupled.map {
            case (stored, fibers) =>
              expect
                .same(PeerRecheckOutcome.Unreachable(demotionStarted = false), outcome)
                .and(expect(stored.exists(_.responsiveness == Unresponsive), "still Unresponsive"))
                .and(expect.same(0, fibers))
          }
        }
      }
    }
  }

  test("recheck restores an Unresponsive peer that answers with its recorded session") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer).copy(responsiveness = Unresponsive))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, mkNodeClient(responsive = true), cs)
          lh.recheck(peer)
        }

        TestControl.executeEmbed(prog).flatMap { outcome =>
          cs.getPeer(peer.id).map { stored =>
            expect
              .same(PeerRecheckOutcome.Healthy, outcome)
              .and(expect(stored.exists(_.responsiveness == Responsive), "restored through setPeerResponsiveness"))
          }
        }
      }
    }
  }

  test("recheck with a differing session removes the record only through the session-conditional path") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, mkNodeClient(responsive = true, session = otherSession.some), cs)
          lh.recheck(peer)
        }

        TestControl.executeEmbed(prog).flatMap { outcome =>
          cs.getPeer(peer.id).map { stored =>
            expect
              .same(PeerRecheckOutcome.SessionChanged(removed = true), outcome)
              .and(expect(stored.isEmpty, "the stale record is removed"))
          }
        }
      }
    }
  }

  test("recheck joins an existing healthcheck fiber instead of spawning or checking again") {
    forall(peerGen) { peer =>
      val initialPeers: Map[PeerId, Peer] = Map(peer.id -> mapPeer(peer))

      (mkClusterStorage(initialPeers), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, nodeClient, cs)
          lh.start(mapPeer(peer)) >> lh.recheck(mapPeer(peer))
        }

        TestControl.executeEmbed(prog).flatMap { outcome =>
          peersR.slots.keys.map(_.size).map(fibers => expect.same(PeerRecheckOutcome.Joined, outcome).and(expect.same(1, fibers)))
        }
      }
    }
  }

  /** Recheck `queried` (session 1), pause the `/session` answer, install `replacement` through the real `addPeer`, then deliver `answer`.
    */
  private def recheckWhileReplaced(
    queried: Peer,
    replacement: Peer,
    answer: SessionToken
  ): IO[(PeerRecheckOutcome, Option[Peer], Int)] =
    (mkClusterStorage(Map(queried.id -> queried)), mkPeersR, Deferred[IO, Unit], Deferred[IO, Unit]).flatMapN {
      (cs, peersR, started, respond) =>
        val gated = new NodeClient[IO] {
          def getState: PeerResponse.PeerResponse[IO, NodeState] = ???
          def health: PeerResponse.PeerResponse[IO, Boolean] = ???
          def getSession: PeerResponse.PeerResponse[IO, Option[SessionToken]] =
            Kleisli(_ => started.complete(()).void >> respond.get.as(answer.some))
        }
        Supervisor[IO].use { implicit s =>
          for {
            run <- LocalHealthcheck.make(peersR, retryPolicy, gated, cs).recheck(queried).start
            _ <- started.get
            _ <- cs.addPeer(replacement)
            _ <- respond.complete(())
            outcome <- run.joinWithNever
            stored <- cs.getPeer(queried.id)
            fibers <- peersR.slots.keys.map(_.size)
          } yield (outcome, stored, fibers)
        }
    }

  test("recheck binds the restore to the record it queried: a session installed in flight keeps its own responsiveness") {
    forall(peerGen) { peer =>
      val queried = mapPeer(peer).copy(responsiveness = Unresponsive, session = SessionToken(Generation(PosLong.unsafeFrom(1L))))
      val fresh = queried.copy(session = otherSession, responsiveness = Unresponsive)
      recheckWhileReplaced(queried, fresh, answer = queried.session).map {
        case (outcome, stored, fibers) =>
          expect
            .same(PeerRecheckOutcome.Superseded, outcome)
            .and(expect(stored.contains(fresh), s"the newer record must not be relabelled by the old session's answer, got $stored"))
            .and(expect.same(0, fibers))
      }
    }
  }

  test("recheck binds the removal to the record it queried: a differing answer never removes a session installed in flight") {
    forall(peerGen) { peer =>
      val queried = mapPeer(peer).copy(session = SessionToken(Generation(PosLong.unsafeFrom(1L))))
      val fresh = queried.copy(session = otherSession)
      recheckWhileReplaced(queried, fresh, answer = SessionToken(Generation(PosLong.unsafeFrom(3L)))).map {
        case (outcome, stored, fibers) =>
          expect
            .same(PeerRecheckOutcome.SessionChanged(removed = false), outcome)
            .and(expect(stored.contains(fresh), s"the newer record survives, got $stored"))
            .and(expect.same(0, fibers))
      }
    }
  }

  test("recheck of an unknown peer reports Unknown") {
    forall(peerGen) { peer =>
      (mkClusterStorage(Map.empty), mkPeersR).flatMapN { (cs, peersR) =>
        val prog = Supervisor[IO].use { implicit s =>
          LocalHealthcheck.make(peersR, retryPolicy, mkNodeClient(responsive = true), cs).recheck(peer)
        }

        TestControl.executeEmbed(prog).map(outcome => expect.same(PeerRecheckOutcome.Unknown, outcome))
      }
    }
  }

  // --- R6-1: worker acquisition and every worker mutation are bound to the queried session ---

  private def session(n: Long): SessionToken = SessionToken(Generation(PosLong.unsafeFrom(n)))

  /** Fixed record for the deterministic session-binding scenarios: the interleavings, not the peer data, are what is exercised. */
  private val boundPeer: Peer = mapPeer(peerGen.sample.get).copy(session = session(1L))

  /** Delegate to `underlying`; the `pauseAtRead`-th `getPeer` completes `paused` after reading and waits for `resume`. The atomic
    * session-conditional operations are delegated unchanged.
    */
  private def pausingReads(
    underlying: ClusterStorage[IO],
    reads: Ref[IO, Int],
    pauseAtRead: Int,
    paused: Deferred[IO, Unit],
    resume: Deferred[IO, Unit]
  ): ClusterStorage[IO] = new ClusterStorage[IO] {
    def getPeers = underlying.getPeers
    def getResponsivePeers = underlying.getResponsivePeers
    def getPeer(id: PeerId) =
      reads.updateAndGet(_ + 1).flatMap { n =>
        underlying.getPeer(id).flatTap(_ => (paused.complete(()).void >> resume.get).whenA(n == pauseAtRead))
      }
    def addPeer(peer: Peer) = underlying.addPeer(peer)
    def hasPeerId(id: PeerId) = underlying.hasPeerId(id)
    def hasPeerHostPort(host: Host, p2pPort: Port) = underlying.hasPeerHostPort(host, p2pPort)
    def updatePeerState(id: PeerId, state: NodeState) = underlying.updatePeerState(id, state)
    def setPeerResponsiveness(id: PeerId, responsiveness: PeerResponsiveness) = underlying.setPeerResponsiveness(id, responsiveness)
    def removePeer(id: PeerId) = underlying.removePeer(id)
    def removePeers(ids: Set[PeerId]) = underlying.removePeers(ids)
    override def removePeerIfSession(id: PeerId, expectedSession: SessionToken)(implicit F: Monad[IO]) =
      underlying.removePeerIfSession(id, expectedSession)
    override def setPeerResponsivenessIfSession(id: PeerId, expectedSession: SessionToken, responsiveness: PeerResponsiveness)(
      implicit F: Monad[IO]
    ) = underlying.setPeerResponsivenessIfSession(id, expectedSession, responsiveness)
    def peerChanges = underlying.peerChanges
    def createToken = underlying.createToken
    def getToken = underlying.getToken
    def setToken(token: ClusterSessionToken) = underlying.setToken(token)
    def getClusterId = underlying.getClusterId
  }

  test("a failed recheck is bound to the queried session: a session installed before worker acquisition keeps its own responsiveness") {
    val old = boundPeer
    val fresh = old.copy(session = session(2L))
    for {
      cs <- mkClusterStorage(Map(old.id -> old))
      peersR <- mkPeersR
      reads <- Ref.of[IO, Int](0)
      paused <- Deferred[IO, Unit]
      resume <- Deferred[IO, Unit]
      checks <- Ref.of[IO, Int](0)
      failing = new NodeClient[IO] {
        def getState: PeerResponse.PeerResponse[IO, NodeState] = ???
        def health: PeerResponse.PeerResponse[IO, Boolean] = ???
        def getSession: PeerResponse.PeerResponse[IO, Option[SessionToken]] = Kleisli(_ => checks.update(_ + 1).as(none[SessionToken]))
      }
      probe <- Supervisor[IO].use { implicit s =>
        // The second `getPeer` is the guard read before worker acquisition: the reviewer's interleaving.
        val lh = LocalHealthcheck.make(peersR, retryPolicy, failing, pausingReads(cs, reads, pauseAtRead = 2, paused, resume))
        for {
          run <- lh.recheck(old).start
          _ <- paused.get
          _ <- cs.addPeer(fresh)
          _ <- lh.cancel(old.id) // ordinary joining's order: install the new session, then cancel by id
          _ <- resume.complete(())
          outcome <- run.joinWithNever
          // The guard read returned the old record before the pause, so the loop may have been acquired; its first
          // session-conditional mark then refuses and it retires. Wait for that (bounded) before reading the slot.
          acquired <- peersR.slots(old.id).get
          _ <- acquired.traverse_(_.fiber.join.timeout(5.seconds).attempt)
          stored <- cs.getPeer(old.id)
          slot <- peersR.slots(old.id).get
          performed <- checks.get
        } yield (outcome, stored, slot, performed)
      }
      (outcome, stored, slot, performed) = probe
    } yield
      expect(outcome match { case PeerRecheckOutcome.Unreachable(_) => true; case _ => false }, s"the check failed, got $outcome") &&
        expect(stored.contains(fresh), s"the new session must retain its own responsiveness, got $stored") &&
        expect(slot.isEmpty, s"no worker stays bound to the superseded session, got ${slot.map(_.session)}") &&
        expect.same(1, performed)
  }

  /** Start the demoting loop for `queried`, pause its first `/session` answer, install `replacement` through the real `addPeer`, then
    * deliver `answer` and wait for the worker to finish.
    */
  private def workerWhileReplaced(
    queried: Peer,
    replacement: Peer,
    answer: Option[SessionToken]
  ): IO[(Option[Peer], Option[SessionToken], Option[Peer], Option[SessionToken])] =
    (mkClusterStorage(Map(queried.id -> queried)), mkPeersR, Deferred[IO, Unit], Deferred[IO, Unit]).flatMapN {
      (cs, peersR, started, respond) =>
        val gated = new NodeClient[IO] {
          def getState: PeerResponse.PeerResponse[IO, NodeState] = ???
          def health: PeerResponse.PeerResponse[IO, Boolean] = ???
          def getSession: PeerResponse.PeerResponse[IO, Option[SessionToken]] =
            Kleisli(_ => started.complete(()).void >> respond.get.as(answer))
        }
        Supervisor[IO].use { implicit s =>
          val lh = LocalHealthcheck.make(peersR, retryPolicy, gated, cs)
          for {
            _ <- lh.start(queried)
            _ <- started.get
            demoted <- cs.getPeer(queried.id)
            worker <- peersR.slots(queried.id).get
            _ <- cs.addPeer(replacement)
            _ <- respond.complete(())
            _ <- worker.traverse_(_.fiber.join.timeout(5.seconds).attempt)
            stored <- cs.getPeer(queried.id)
            slot <- peersR.slots(queried.id).get
          } yield (demoted, worker.map(_.session), stored, slot.map(_.session))
        }
    }

  test(
    "a superseded worker retires: a late answer for the queried session never relabels, removes or demotes a session installed in flight"
  ) {
    val queried = boundPeer
    val fresh = queried.copy(session = session(2L))
    List(session(1L).some, session(3L).some, none[SessionToken]).traverse { answer =>
      workerWhileReplaced(queried, fresh, answer).map {
        case (demoted, workerSession, stored, slot) =>
          expect(
            demoted.contains(queried.copy(responsiveness = Unresponsive)),
            s"answer=$answer: the queried session is demoted eagerly on its own evidence, got $demoted"
          ) &&
          expect(
            workerSession.contains(queried.session),
            s"answer=$answer: the worker is bound to the queried session, got $workerSession"
          ) &&
          expect(stored.contains(fresh), s"answer=$answer: the session installed in flight is untouched, got $stored") &&
          expect(slot.isEmpty, s"answer=$answer: the superseded worker retires and releases its slot, got $slot")
      }
    }.map(_.reduce(_ && _))
  }

  test("acquiring a worker for a newer session supersedes and cancels the worker bound to the older session") {
    val old = boundPeer
    val fresh = old.copy(session = session(2L))
    for {
      cs <- mkClusterStorage(Map(old.id -> old))
      peersR <- mkPeersR
      oldStarted <- Deferred[IO, Unit]
      oldCancelled <- Deferred[IO, Unit]
      calls <- Ref.of[IO, Int](0)
      // The first `/session` call is the old session's worker; it hangs until cancelled.
      hanging = new NodeClient[IO] {
        def getState: PeerResponse.PeerResponse[IO, NodeState] = ???
        def health: PeerResponse.PeerResponse[IO, Boolean] = ???
        def getSession: PeerResponse.PeerResponse[IO, Option[SessionToken]] =
          Kleisli { _ =>
            calls.getAndUpdate(_ + 1).flatMap {
              case 0 => oldStarted.complete(()).void >> IO.never.onCancel(oldCancelled.complete(()).void)
              case _ => IO.never
            }
          }
      }
      probe <- Supervisor[IO].use { implicit s =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, hanging, cs)
        for {
          _ <- lh.start(old)
          _ <- oldStarted.get
          older <- peersR.slots(old.id).get
          _ <- cs.addPeer(fresh)
          _ <- lh.start(fresh)
          _ <- oldCancelled.get.timeout(5.seconds)
          // The superseded worker's own retirement (exact acquisition identity) must leave the successor's slot alone.
          _ <- older.traverse_(_.fiber.join.timeout(5.seconds))
          slot <- peersR.slots(old.id).get
          stored <- cs.getPeer(old.id)
        } yield (older.map(_.session), slot.map(_.session), stored)
      }
      (older, slot, stored) = probe
    } yield
      expect(older.contains(old.session), s"the first worker is bound to the older session, got $older") &&
        expect(slot.contains(fresh.session), s"the slot is held by the newer session's worker, got $slot") &&
        expect(
          stored.contains(fresh.copy(responsiveness = Unresponsive)),
          s"only the newer worker marks the newer session, on its own evidence, got $stored"
        )
  }

  // --- R8-1..R8-3: acquisition identity, monotonic slot protocol and cancellation-safe handoff ---

  /** A `/session` client whose every call runs `action`; the scenarios below hang it and observe its cancellation. */
  private def clientRunning(action: IO[Option[SessionToken]]): NodeClient[IO] = new NodeClient[IO] {
    def getState: PeerResponse.PeerResponse[IO, NodeState] = ???
    def health: PeerResponse.PeerResponse[IO, Boolean] = ???
    def getSession: PeerResponse.PeerResponse[IO, Option[SessionToken]] = Kleisli(_ => action)
  }

  test("a delayed old-session acquisition is rejected at the slot: it neither cancels nor replaces the newer session's worker") {
    val old = boundPeer
    val fresh = old.copy(session = session(2L))
    for {
      cs <- mkClusterStorage(Map(old.id -> old))
      peersR <- mkPeersR
      reads <- Ref.of[IO, Int](0)
      paused <- Deferred[IO, Unit]
      resume <- Deferred[IO, Unit]
      started <- Deferred[IO, Unit]
      cancelled <- Ref.of[IO, Boolean](false)
      probe <- Supervisor[IO].use { implicit s =>
        // The first `getPeer` is the old session's guard read; it is paused after returning the old Responsive record.
        val lh = LocalHealthcheck.make(
          peersR,
          retryPolicy,
          clientRunning(started.complete(()).void >> IO.never.onCancel(cancelled.set(true))),
          pausingReads(cs, reads, pauseAtRead = 1, paused, resume)
        )
        for {
          stale <- lh.start(old).start
          _ <- paused.get
          _ <- cs.addPeer(fresh)
          _ <- lh.start(fresh)
          _ <- started.get
          _ <- resume.complete(())
          _ <- stale.joinWithNever
          wasCancelled <- cancelled.get
          slot <- peersR.slots(old.id).get
          stored <- cs.getPeer(old.id)
        } yield (wasCancelled, slot.map(_.session), stored)
      }
      (wasCancelled, slot, stored) = probe
    } yield
      expect(!wasCancelled, "the newer session's worker must survive the stale acquisition") &&
        expect(slot.contains(fresh.session), s"the slot stays with the newer session's worker, got $slot") &&
        expect(
          stored.contains(fresh.copy(responsiveness = Unresponsive)),
          s"the newer session is demoted only on its own evidence, got $stored"
        )
  }

  test("a retiring worker releases only its own slot: a same-session replacement keeps its slot while the old finalizer runs") {
    val peer = boundPeer
    for {
      cs <- mkClusterStorage(Map(peer.id -> peer))
      peersR <- mkPeersR
      calls <- Ref.of[IO, Int](0)
      oldStarted <- Deferred[IO, Unit]
      newStarted <- Deferred[IO, Unit]
      cancellingOld <- Deferred[IO, Unit]
      finishOld <- Deferred[IO, Unit]
      // The first `/session` call is the first worker; its cancellation finalizer waits until released.
      client = clientRunning(calls.getAndUpdate(_ + 1).flatMap {
        case 0 => oldStarted.complete(()).void >> IO.never.onCancel(cancellingOld.complete(()).void >> finishOld.get)
        case _ => newStarted.complete(()).void >> IO.never
      })
      probe <- Supervisor[IO].use { implicit s =>
        val lh = LocalHealthcheck.make(peersR, retryPolicy, client, cs)
        for {
          _ <- lh.start(peer)
          _ <- oldStarted.get
          first <- peersR.slots(peer.id).get
          _ <- cs.addPeer(peer) // A revalidated same-session registration restores Responsive.
          cancelling <- lh.cancel(peer.id).start // Ordinary joining cancels by id after addPeer.
          _ <- cancellingOld.get
          _ <- lh.start(peer) // A new gossip failure starts another worker for the still-current session.
          _ <- newStarted.get
          second <- peersR.slots(peer.id).get
          _ <- finishOld.complete(())
          _ <- cancelling.joinWithNever
          _ <- first.traverse_(_.fiber.join.timeout(5.seconds))
          current <- peersR.slots(peer.id).get
        } yield (first.map(_.workerId), second.map(_.workerId), current.map(w => (w.session, w.workerId)))
      }
      (first, second, current) = probe
    } yield
      expect(
        first.isDefined && second.isDefined && first =!= second,
        s"the replacement is a distinct acquisition, got $first and $second"
      ) &&
        expect(
          current.exists { case (s, id) => s === peer.session && second.contains(id) },
          s"the successor worker is still running and must own its slot after the old finalizer, got $current"
        )
  }

  test("cancelling the caller during a replacement handoff never leaves a slot without a live worker") {
    val old = boundPeer
    val fresh = old.copy(session = session(2L))
    for {
      cs <- mkClusterStorage(Map(old.id -> old))
      peersR <- mkPeersR
      oldStarted <- Deferred[IO, Unit]
      cancellingOld <- Deferred[IO, Unit]
      finishOld <- Deferred[IO, Unit]
      cancelRequested <- Deferred[IO, Unit]
      probe <- Supervisor[IO].use { implicit s =>
        val lh = LocalHealthcheck.make(
          peersR,
          retryPolicy,
          clientRunning(oldStarted.complete(()).void >> IO.never.onCancel(cancellingOld.complete(()).void >> finishOld.get)),
          cs
        )
        for {
          _ <- lh.start(old)
          _ <- oldStarted.get
          _ <- cs.addPeer(fresh)
          replacing <- lh.start(fresh).start
          _ <- cancellingOld.get
          cancel <- (cancelRequested.complete(()) >> replacing.cancel).start
          _ <- cancelRequested.get
          _ <- IO.cede.replicateA_(5)
          _ <- finishOld.complete(())
          _ <- cancel.joinWithNever
          slot <- peersR.slots(old.id).get
          // A visible handle must be a real worker: cancelling through it completes, and the slot is released.
          released <- lh.cancel(old.id).timeout(5.seconds).attempt
          after <- peersR.slots(old.id).get
        } yield (slot.map(_.session), released.isRight, after.map(_.session))
      }
      (slot, released, after) = probe
    } yield
      expect(slot.contains(fresh.session), s"the replacement owns the slot, got $slot") &&
        expect(released, "cancelling through the published handle must complete: the handle is a live fiber") &&
        expect(after.isEmpty, s"the cancelled worker released its slot, got $after")
  }

  def mkNodeClient(responsive: Boolean, session: Option[SessionToken] = None): NodeClient[IO] = new NodeClient[IO] {
    def getState: PeerResponse.PeerResponse[IO, NodeState] = ???

    def health: PeerResponse.PeerResponse[IO, Boolean] = ???

    def getSession: PeerResponse.PeerResponse[IO, Option[SessionToken]] =
      Kleisli.apply { _ =>
        if (responsive)
          IO(session.orElse(Some(SessionToken(Generation.MinValue))))
        else
          IO.raiseError[Option[SessionToken]](new Throwable("unresponsive"))
      }
  }

  def mkClusterStorage(initialPeers: Map[PeerId, Peer] = Map.empty): IO[ClusterStorage[IO]] = {
    val id = ClusterId("d2547754-8aea-428b-a1aa-048e8b2d344b")
    ClusterStorage.make[IO](id, initialPeers)
  }
}
