package org.tessellation.sdk.infrastructure.cluster.rumour

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.ReceivedRumor
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.infrastructure.gossip.RumorHandler

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def nodeStateHandler[F[_]: Async: KryoSerializer](
    clusterStorage: ClusterStorage[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromReceivedRumorFn[F, NodeState](latestOnly = true) {
      case ReceivedRumor(origin, state) =>
        logger.info(s"Received state=${state.show} from id=${origin.show}") >>
          clusterStorage.setPeerState(origin, state)
    }
  }
}
