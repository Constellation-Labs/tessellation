package org.tessellation.sdk.infrastructure.consensus

import cats.effect.Async
import cats.syntax.all._
import cats.{Order, Show}

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.http.p2p.headers.`X-Id`

import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

class ConsensusRoutes[F[_]: Async: KryoSerializer, Key: Order: Show: Encoder: Decoder](
  storage: ConsensusStorage[F, _, Key, _]
) extends Http4sDsl[F] {

  private val logger = Slf4jLogger.getLogger[F]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "registration" =>
      for {
        exchangeRequest <- req.as[RegistrationExchangeRequest[Key]]
        idHeader <- req.headers.get[`X-Id`].liftTo[F](new RuntimeException("Missing X-Id header"))
        maybeRegistrationResult <- exchangeRequest.maybeKey.traverse(storage.registerPeer(idHeader.id, _))
        _ <- (exchangeRequest.maybeKey, maybeRegistrationResult).traverseN {
          case (key, result) =>
            logger.warn(s"Peer ${idHeader.id.show} cannot be registered at ${key.show}").unlessA(result)
        }
        exchangeResponse <- storage.getOwnRegistration.map(RegistrationExchangeResponse(_))
        result <- Ok(exchangeResponse)
      } yield result
  }

}
