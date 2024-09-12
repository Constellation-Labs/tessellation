package io.constellationnetwork.node.shared.infrastructure.node

import cats.Order
import cats.data.NonEmptySet
import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.schema.cluster.PeerToJoin
import io.constellationnetwork.schema.peer.Peer.toP2PContext
import io.constellationnetwork.schema.peer.{Peer, PeerId}

import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait RestartService[F[_], A <: CliMethod] {
  def signalClusterLeaveRestart(): F[Unit]
  def setClusterLeaveRestartMethod(method: A): F[Unit]

  def signalNodeForkedRestart(majorityForkPeers: List[PeerId]): F[Unit]
  def setNodeForkedRestartMethod(method: NonEmptySet[PeerToJoin] => A): F[Unit]
}

object RestartService {
  def make[F[_]: Async, A <: CliMethod](
    restartSignal: SignallingRef[F, Option[A]],
    clusterStorage: ClusterStorage[F]
  ): F[RestartService[F, A]] = for {
    clusterLeaveRestartMethod <- Ref.of[F, Option[A]](None)
    nodeForkedRestartMethod <- Ref.of[F, Option[NonEmptySet[PeerToJoin] => A]](None)
  } yield
    new RestartService[F, A] {
      private val logger = Slf4jLogger.getLogger[F]

      def setClusterLeaveRestartMethod(method: A): F[Unit] =
        clusterLeaveRestartMethod.set(method.some) >> logger.info(
          s"Restart method for cluster leave has been set to: ${method.getClass.getSimpleName}"
        )

      def signalClusterLeaveRestart(): F[Unit] = clusterLeaveRestartMethod.get.flatMap {
        case Some(method) => restartSignal.set(method.some)
        case None         => logger.warn(s"Restart method for cluster leave is not set! The initial run method will be used as a default.")
      }

      def setNodeForkedRestartMethod(method: NonEmptySet[PeerToJoin] => A): F[Unit] =
        nodeForkedRestartMethod.set(method.some) >> logger.info(
          s"Restart method for node forked has been set to: ${method.getClass.getSimpleName}"
        )

      def signalNodeForkedRestart(majorityForkPeers: List[PeerId]): F[Unit] = nodeForkedRestartMethod.get.flatMap {
        case Some(method) =>
          implicit val order: Order[Peer] = Order[PeerId].contramap(_.id)

          majorityForkPeers
            .traverseFilter(clusterStorage.getPeer)
            .map(_.toNel.map(_.toNes))
            .flatMap {
              case Some(majorityPeersToJoin) => restartSignal.set(method(majorityPeersToJoin.map(toP2PContext)).some)
              case None => new Throwable("Unexpected state. Couldn't find P2P Context of majority fork peers.").raiseError

            }
        case None => logger.warn(s"Restart method for node forked is not set! The initial run method will be used as a default.")
      }

    }
}
