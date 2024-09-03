package io.constellationnetwork.node.shared.infrastructure.cluster.rumor

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.show._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}
import io.constellationnetwork.schema.gossip.PeerRumor
import io.constellationnetwork.schema.node.NodeState

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
