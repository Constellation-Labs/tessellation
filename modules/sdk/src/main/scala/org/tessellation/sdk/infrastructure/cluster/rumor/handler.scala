package org.tessellation.sdk.infrastructure.cluster.rumor

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.show._

import org.tessellation.schema.gossip.PeerRumor
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def nodeStateHandler[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    localHealthcheck: LocalHealthcheck[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromPeerRumorConsumer[F, NodeState](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, state) =>
        clusterStorage.updatePeerState(origin, state).flatTap { updateSuccess =>
          logger.info(s"Received state=${state.show} from id=${origin.show}. Peer is ${if (updateSuccess) "" else "un"}known.")
        } >> {
          localHealthcheck.cancel(origin) >>
            clusterStorage.removePeer(origin)
        }.whenA(NodeState.absent.contains(state))
    }
  }
}
