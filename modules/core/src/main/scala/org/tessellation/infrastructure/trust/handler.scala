package org.tessellation.infrastructure.trust

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.tessellation.domain.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.PeerRumor
import org.tessellation.schema.trust.PublicTrust
import org.tessellation.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def trustHandler[F[_]: Async: KryoSerializer](
    trustStorage: TrustStorage[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromPeerRumorConsumer[F, PublicTrust](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, trust) =>
        logger.info(s"Received trust=${trust} from id=${origin.show}") >> {
          trustStorage.updatePeerPublicTrustInfo(origin, trust)
        }
    }
  }
}
