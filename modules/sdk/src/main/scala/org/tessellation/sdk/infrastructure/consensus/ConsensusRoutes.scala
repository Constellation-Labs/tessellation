package org.tessellation.sdk.infrastructure.consensus

import cats.Order
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.http.p2p.headers.`X-Id`

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

class ConsensusRoutes[F[_]: Async: KryoSerializer, Key: Order](
  storage: ConsensusStorage[F, _, Key, _]
) extends Http4sDsl[F] {

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "registration" =>
      for {
        exchangeRequest <- req.as[RegistrationExchangeRequest[Key]]
        idHeader <- req.headers.get[`X-Id`].liftTo[F](new RuntimeException("Missing X-Id header"))
        _ <- exchangeRequest.maybeKey.traverse(storage.registerPeer(idHeader.id))
        exchangeResponse <- storage.getOwnRegistration.map(RegistrationExchangeResponse(_))
        result <- Ok(exchangeResponse)
      } yield result
  }

}
