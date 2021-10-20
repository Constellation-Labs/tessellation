package org.tesselation.infrastructure.cluster.rumour

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.tesselation.domain.cluster.storage.ClusterStorage
import org.tesselation.infrastructure.gossip.RumorHandler
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.gossip.ReceivedRumor
import org.tesselation.schema.node.NodeState

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def nodeStateHandler[F[_]: Async: KryoSerializer](clusterStorage: ClusterStorage[F]): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromReceivedRumorFn[F, NodeState] {
      case ReceivedRumor(origin, state) =>
        logger.info(s"Received state=${state.show} from id=${origin.show}") >>
          clusterStorage.setPeerState(origin, state)
    }
  }
}
