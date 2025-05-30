package io.constellationnetwork.node.shared.infrastructure.healthcheck

import cats.effect._
import cats.effect.std.Supervisor
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.{Applicative, Show}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.http.p2p.clients.NodeClient
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.peer._

import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry._

object LocalHealthcheck {
  def make[F[_]: Async: Supervisor](nodeClient: NodeClient[F], clusterStorage: ClusterStorage[F]): F[LocalHealthcheck[F]] = {
    def mkPeersR = MapRef.ofConcurrentHashMap[F, PeerId, F[Fiber[F, Throwable, Unit]]]()
    def retryPolicy: RetryPolicy[F] = RetryPolicies.fibonacciBackoff[F](2.seconds)

    mkPeersR.map(make(_, retryPolicy, nodeClient, clusterStorage))
  }

  case class PeerUnresponsive(id: PeerId) extends NoStackTrace {
    implicit val show: Show[PeerId] = PeerId.shortShow
    override val getMessage = s"Peer ${id.show} is unresponsive"
  }

  def make[F[_]: Async](
    peersR: MapRef[F, PeerId, Option[F[Fiber[F, Throwable, Unit]]]],
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

    def start(peer: Peer): F[Unit] =
      clusterStorage.getPeer(peer.id).flatMap {
        case Some(p) if p.responsiveness === Unresponsive => Applicative[F].unit
        case None                                         => Applicative[F].unit
        case _ =>
          Deferred[F, Fiber[F, Throwable, Unit]].flatMap { d =>
            peersR(peer.id).modify {
              case Some(f) => (Some(f), false)
              case _       => (Some(d.get), true)
            }.ifM(
              spawn(peer).flatMap(d.complete).void,
              Applicative[F].unit
            )
          }
      }

    def cancel(peerId: PeerId): F[Unit] =
      peersR(peerId).getAndSet(None).flatMap {
        case Some(fiber) => fiber.flatMap(_.cancel) >> logger.debug(s"Cancelled local healthcheck for ${peerId.show}")
        case _           => Applicative[F].unit
      }

    def spawn(peer: Peer): F[Fiber[F, Throwable, Unit]] = {
      def responsive = clusterStorage.setPeerResponsiveness(peer.id, Responsive)
      def unresponsive = clusterStorage.setPeerResponsiveness(peer.id, Unresponsive)

      S.supervise {
        retryingOnAllErrors(policy = retryPolicy, onError = onError) {
          check(peer).flatMap {
            case Some(session) =>
              clusterStorage.getPeer(peer.id).flatMap {
                case Some(p) if p.session === session =>
                  responsive >> cancel(peer.id)
                case _ =>
                  logger.info(s"Peer ${peer.id.show} is responsive but found different session.") >>
                    clusterStorage.removePeer(peer.id) >>
                    cancel(peer.id)
              }
            case _ =>
              unresponsive >> PeerUnresponsive(peer.id).raiseError[F, Unit]
          }
        }
      }
    }

    def check(peer: Peer): F[Option[SessionToken]] =
      nodeClient.getSession
        .run(peer)
        .handleError(_ => none)
  }
}
