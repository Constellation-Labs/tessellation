package io.constellationnetwork.node.shared.infrastructure.healthcheck

import cats.effect._
import cats.effect.std.Supervisor
import cats.effect.syntax.all._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
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

  /** A running check loop, bound to the exact peer session it was acquired for. Every storage mutation the loop performs is a
    * compare-and-set on `session`, and the loop retires, without touching the record or any successor worker, as soon as that session is no
    * longer the recorded one.
    */
  final case class Worker[F[_]](session: SessionToken, fiber: F[Fiber[F, Throwable, Unit]])

  /** Worker slots keyed by peer id; the slot holds the worker bound to one session of that peer. */
  type Workers[F[_]] = MapRef[F, PeerId, Option[Worker[F]]]

  def mkWorkers[F[_]: Sync]: F[Workers[F]] = MapRef.ofConcurrentHashMap[F, PeerId, Worker[F]]()

  private sealed trait Acquisition[F[_]]

  private object Acquisition {
    final case class Joined[F[_]]() extends Acquisition[F]
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
    peersR: Workers[F],
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

    /** Acquire the worker slot for `(peer.id, peer.session)` and spawn the bound loop, but only while that exact session is still the
      * recorded Responsive one. A slot already held for the same session is joined (nothing spawned). A slot held for another session is
      * superseded: that worker is cancelled and replaced, since its evidence concerns a session that is no longer recorded. Returns whether
      * a loop was started.
      */
    private def startBound(peer: Peer): F[Boolean] =
      clusterStorage.getPeer(peer.id).flatMap {
        case Some(current) if current.session === peer.session && current.responsiveness === Responsive =>
          Deferred[F, Fiber[F, Throwable, Unit]].flatMap { d =>
            peersR(peer.id).modify {
              case held @ Some(worker) if worker.session === peer.session => (held, Acquisition.Joined[F](): Acquisition[F])
              case previous => (Worker(peer.session, d.get).some, Acquisition.Acquired[F](previous): Acquisition[F])
            }.flatMap {
              case Acquisition.Joined() => false.pure[F]
              case Acquisition.Acquired(superseded) =>
                superseded.traverse_(_.fiber.flatMap(_.cancel)) >> spawn(peer).flatMap(d.complete).as(true)
            }
          }
        case _ => false.pure[F]
      }

    def cancel(peerId: PeerId): F[Unit] =
      peersR(peerId).getAndSet(None).flatMap {
        case Some(worker) => worker.fiber.flatMap(_.cancel) >> logger.debug(s"Cancelled local healthcheck for ${peerId.show}")
        case _            => Applicative[F].unit
      }

    /** Release the slot only while it is still held by this worker's session; a successor's slot is never touched. */
    private def retire(peer: Peer): F[Unit] =
      peersR(peer.id).update {
        case Some(worker) if worker.session === peer.session => None
        case other                                           => other
      }

    def spawn(peer: Peer): F[Fiber[F, Throwable, Unit]] = {
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

      S.supervise {
        // Eagerly mark Unresponsive so gossip peer selection skips this peer on its
        // next cycle, instead of waiting for the first check() to time out (which
        // can take 15s+ per attempt). If the peer is actually healthy, the very
        // next check() succeeds and restores Responsive via the Some(session) path.
        // Every mark is bound to the checked session: a record replaced meanwhile is
        // never demoted on this session's evidence and the worker retires at once.
        mark(Unresponsive).ifM(ifFalse = superseded, ifTrue = loop).guarantee(retire(peer))
      }
    }

    def recheck(peer: Peer): F[PeerRecheckOutcome] =
      clusterStorage.getPeer(peer.id).flatMap {
        case None => (PeerRecheckOutcome.Unknown: PeerRecheckOutcome).pure[F]
        case Some(recorded) =>
          peersR(peer.id).get.flatMap {
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
