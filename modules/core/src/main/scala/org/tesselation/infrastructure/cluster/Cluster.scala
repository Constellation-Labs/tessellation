package org.tesselation.infrastructure.cluster

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._

import org.tesselation.domain.cluster.{Cluster, ClusterStorage}
import org.tesselation.schema.cluster.{PeerHostPortInUse, PeerIdInUse, PeerToJoin}
import org.tesselation.schema.peer.Peer

object Cluster {

  def make[F[_]: Async](clusterStorage: ClusterStorage[F]): Cluster[F] =
    new Cluster[F] {

      def join(toPeer: PeerToJoin): F[Unit] =
        clusterStorage
          .hasPeerId(toPeer.id)
          .ifM(
            PeerIdInUse(toPeer.id).raiseError[F, Unit],
            clusterStorage
              .hasPeerHostPort(toPeer.ip, toPeer.p2pPort)
              .ifM(
                PeerHostPortInUse(toPeer.ip, toPeer.p2pPort).raiseError[F, Unit],
                clusterStorage.addPeer(Peer(toPeer.id, toPeer.ip, toPeer.p2pPort, toPeer.p2pPort))
              )
          )
    }
}
