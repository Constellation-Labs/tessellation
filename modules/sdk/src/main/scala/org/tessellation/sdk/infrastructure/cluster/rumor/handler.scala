package org.tessellation.sdk.infrastructure.cluster.rumor

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.PeerRumor
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def nodeStateHandler[F[_]: Async: KryoSerializer](
    clusterStorage: ClusterStorage[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromPeerRumorConsumer[F, NodeState](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, state) =>
        clusterStorage.hasPeerId(origin).flatMap { hasPeer =>
          logger.info(s"Received state=${state.show} from id=${origin.show}. Peer is ${if (hasPeer) "" else "un"}known.")
        } >>
          clusterStorage.setPeerState(origin, state) >> {
            if (NodeState.absent.contains(state))
              clusterStorage.removePeer(origin)
            else
              Applicative[F].unit
          }
    }
  }
}
