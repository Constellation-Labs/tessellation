package io.constellationnetwork.node.shared.infrastructure.healthcheck

import cats.effect._
import cats.effect.std.Supervisor
import cats.effect.syntax.all._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.{Applicative, Show}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.{LocalHealthcheck, PeerRecheckOutcome}
import io.constellationnetwork.node.shared.http.p2p.clients.NodeClient
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.peer._

import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry._

object LocalHealthcheck {

  /** A running check loop, bound to the exact peer session it was acquired for and identified by its acquisition (`workerId`, strictly
    * increasing per `Workers`). Every storage mutation the loop performs is a compare-and-set on `session`; slot release, cancellation
    * handoff and replacement compare `workerId`, so a retiring worker never touches a successor's slot, not even one bound to the same
    * session. The fiber is spawned before the slot is published, so a visible worker always resolves to a supervised fiber.
    */
  final case class Worker[F[_]](session: SessionToken, workerId: Long, fiber: Fiber[F, Throwable, Unit])

  /** Worker slots keyed by peer id (each holding the worker bound to one session of that peer) and the acquisition counter. */
  final case class Workers[F[_]](slots: MapRef[F, PeerId, Option[Worker[F]]], acquisitions: Ref[F, Long])

  def mkWorkers[F[_]: Sync]: F[Workers[F]] =
    (MapRef.ofConcurrentHashMap[F, PeerId, Worker[F]](), Ref.of[F, Long](0L)).mapN(Workers(_, _))

  private sealed trait Acquisition[F[_]]

  private object Acquisition {

    /** A worker bound to the same session already holds the slot. */
    final case class Joined[F[_]]() extends Acquisition[F]

    /** The slot is held for a newer session: the request carries a superseded record and must not replace it. */
    final case class Rejected[F[_]]() extends Acquisition[F]

    /** The slot is now owned by the request's worker; `superseded` is the older-session worker it replaced, if any. */
    final case class Acquired[F[_]](superseded: Option[Worker[F]]) extends Acquisition[F]
  }

  def make[F[_]: Async: Supervisor](nodeClient: NodeClient[F], clusterStorage: ClusterStorage[F]): F[LocalHealthcheck[F]] = {
    def retryPolicy: RetryPolicy[F] = RetryPolicies.fibonacciBackoff[F](2.seconds)

    mkWorkers[F].map(make(_, retryPolicy, nodeClient, clusterStorage))
  }

  case class PeerUnresponsive(id: PeerId) extends NoStackTrace {
    implicit val show: Show[PeerId] = PeerId.shortShow
    override val getMessage = s"Peer ${id.show} is unresponsive"
  }

  def make[F[_]: Async](
    workers: Workers[F],
    retryPolicy: RetryPolicy[F],
    nodeClient: NodeClient[F],
    clusterStorage: ClusterStorage[F]
  )(implicit S: Supervisor[F]): LocalHealthcheck[F] = new LocalHealthcheck[F] {

    val logger = Slf4jLogger.getLogger[F]

    def onError(err: Throwable, details: RetryDetails) =
      err match {
        case PeerUnresponsive(id) =>
          logger.debug(
            s"Peer ${id.show} is unresponsive - retriesSoFar: ${details.retriesSoFar.show}, cumulativeDelay: ${details.cumulativeDelay.toSeconds.show}s"
          )
        case _ => logger.warn(err)(s"Unexpected error when checking peer responsiveness.")
      }

    def start(peer: Peer): F[Unit] = startBound(peer).void

    /** Acquire the worker slot for `(peer.id, peer.session)` and spawn the bound loop, but only while that exact session is the recorded
      * Responsive one. Returns whether a loop was started.
      *
      * The record read and the slot update are not one atomic step, so the slot itself enforces a monotonic protocol: a slot held for the
      * same session is joined (nothing spawned); one held for an older session is superseded (its worker is cancelled and replaced); one
      * held for a newer session rejects the request, since cluster storage only ever moves a peer's recorded session forward (`addPeer`
      * keeps the newer session), so the request's captured record is stale. A stale request that finds the slot empty acquires a worker
      * whose first session-conditional mark is refused, so it retires without a network round trip. `acquire` is one bounded uncancelable
      * step with the fiber spawned before publication; the wait for a superseded worker is owned by a separate supervised task.
      */
    private def startBound(peer: Peer): F[Boolean] =
      clusterStorage.getPeer(peer.id).flatMap {
        case Some(current) if current.session === peer.session && current.responsiveness === Responsive => acquire(peer)
        case _                                                                                          => false.pure[F]
      }

    private def acquire(peer: Peer): F[Boolean] =
      Async[F].uncancelable { _ =>
        for {
          workerId <- workers.acquisitions.updateAndGet(_ + 1L)
          launch <- Deferred[F, Boolean]
          // Spawned before the slot is visible, released by its exact identity however it ends: a published handle is always a live
          // supervised fiber, and a worker that never owns the slot (joined, rejected, cancelled while launching) retires as a no-op.
          fiber <- S.supervise(launch.get.ifM(ifFalse = Applicative[F].unit, ifTrue = run(peer)).guarantee(retire(peer.id, workerId)))
          acquisition <- workers.slots(peer.id).modify {
            case held @ Some(worker) if worker.session === peer.session => (held, Acquisition.Joined[F](): Acquisition[F])
            case held @ Some(worker) if worker.session > peer.session   => (held, Acquisition.Rejected[F](): Acquisition[F])
            case previous => (Worker(peer.session, workerId, fiber).some, Acquisition.Acquired[F](previous): Acquisition[F])
          }
          started <- acquisition match {
            case Acquisition.Acquired(superseded) =>
              // The predecessor's cancellation may wait on its in-flight request; that wait is owned by the supervisor, never by the
              // caller (whose deadline must keep working) nor by the new worker (whose own cancellation must not chain behind it).
              superseded
                .traverse_(worker => S.supervise(worker.fiber.cancel).void)
                .onError(_ => retire(peer.id, workerId) >> launch.complete(false).void) >>
                launch.complete(true).as(true)
            case Acquisition.Rejected() =>
              logger.debug(s"Peer ${peer.id.show}: healthcheck acquisition for a superseded session rejected.") >>
                launch.complete(false).as(false)
            case Acquisition.Joined() =>
              launch.complete(false).as(false)
          }
        } yield started
      }

    def cancel(peerId: PeerId): F[Unit] =
      workers.slots(peerId).getAndSet(None).flatMap {
        case Some(worker) => worker.fiber.cancel >> logger.debug(s"Cancelled local healthcheck for ${peerId.show}")
        case _            => Applicative[F].unit
      }

    /** Release the slot only while it is still held by this exact acquisition; a successor's slot (same session or not) is never touched.
      */
    private def retire(peerId: PeerId, workerId: Long): F[Unit] =
      workers.slots(peerId).update {
        case Some(worker) if worker.workerId === workerId => None
        case other                                        => other
      }

    private def run(peer: Peer): F[Unit] = {
      def mark(responsiveness: PeerResponsiveness): F[Boolean] =
        clusterStorage.setPeerResponsivenessIfSession(peer.id, peer.session, responsiveness)

      def superseded: F[Unit] =
        logger.debug(s"Peer ${peer.id.show} no longer carries the checked session; retiring its local healthcheck.")

      val loop =
        retryingOnAllErrors(policy = retryPolicy, onError = onError) {
          check(peer).flatMap {
            case Some(session) if session === peer.session =>
              mark(Responsive).ifM(ifFalse = superseded, ifTrue = Applicative[F].unit)
            case Some(_) =>
              logger.info(s"Peer ${peer.id.show} is responsive but found different session.") >>
                clusterStorage.removePeerIfSession(peer.id, peer.session).ifM(ifFalse = superseded, ifTrue = Applicative[F].unit)
            case None =>
              mark(Unresponsive).ifM(ifFalse = superseded, ifTrue = PeerUnresponsive(peer.id).raiseError[F, Unit])
          }
        }

      // Eagerly mark Unresponsive so gossip peer selection skips this peer on its
      // next cycle, instead of waiting for the first check() to time out (which
      // can take 15s+ per attempt). If the peer is actually healthy, the very
      // next check() succeeds and restores Responsive via the Some(session) path.
      // Every mark is bound to the checked session: a record replaced meanwhile is
      // never demoted on this session's evidence and the worker retires at once.
      mark(Unresponsive).ifM(ifFalse = superseded, ifTrue = loop)
    }

    def recheck(peer: Peer): F[PeerRecheckOutcome] =
      clusterStorage.getPeer(peer.id).flatMap {
        case None => (PeerRecheckOutcome.Unknown: PeerRecheckOutcome).pure[F]
        case Some(recorded) =>
          workers.slots(peer.id).get.flatMap {
            case Some(worker) if worker.session === recorded.session => (PeerRecheckOutcome.Joined: PeerRecheckOutcome).pure[F]
            case _                                                   =>
              // The captured record is both the endpoint queried and the session every mutation below is bound to, so an
              // answer for a record that was replaced during the round trip cannot relabel or remove its successor.
              check(recorded).flatMap {
                case Some(session) if session === recorded.session =>
                  clusterStorage
                    .setPeerResponsivenessIfSession(recorded.id, recorded.session, Responsive)
                    .map {
                      case true  => PeerRecheckOutcome.Healthy: PeerRecheckOutcome
                      case false => PeerRecheckOutcome.Superseded: PeerRecheckOutcome
                    }
                case Some(_) =>
                  logger.info(s"Peer ${recorded.id.show} is responsive but found different session (recheck).") >>
                    clusterStorage
                      .removePeerIfSession(recorded.id, recorded.session)
                      .map(PeerRecheckOutcome.SessionChanged(_): PeerRecheckOutcome)
                case None =>
                  // Evidence first, demotion second: the failed check is handed to the ordinary loop bound to the queried session.
                  // `startBound` acquires the worker for that session only while it is still the recorded Responsive one, and every
                  // mutation of the loop is a compare-and-set on it, so a record replaced at any point (before acquisition, or while
                  // a retry is in flight) is never demoted, restored or removed on its predecessor's evidence.
                  startBound(recorded).map(started => PeerRecheckOutcome.Unreachable(demotionStarted = started): PeerRecheckOutcome)
              }
          }
      }

    def check(peer: Peer): F[Option[SessionToken]] =
      nodeClient.getSession
        .run(peer)
        .handleError(_ => none)
  }
}
