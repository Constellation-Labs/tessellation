package org.tesselation.infrastructure.trust

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.tesselation.domain.trust.storage.TrustStorage
import org.tesselation.infrastructure.gossip.RumorHandler
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.gossip.ReceivedRumor
import org.tesselation.schema.trust.PublicTrust

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def trustHandler[F[_]: Async: KryoSerializer](
    trustStorage: TrustStorage[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromReceivedRumorFn[F, PublicTrust](latestOnly = true) {
      case ReceivedRumor(origin, trust) =>
        logger.info(s"Received trust=${trust} from id=${origin.show}") >> {
          // Placeholder for updating trust scores
          Applicative[F].unit
        }
    }
  }
}
