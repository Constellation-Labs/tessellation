package org.tessellation.sdk.infrastructure.consensus

import cats.effect.Async
import cats.syntax.all._
import cats.{Order, Show}

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.infrastructure.consensus.registration.RegistrationResponse

import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

class ConsensusRoutes[F[_]: Async: KryoSerializer, Key: Order: Show: Encoder: Decoder](
  storage: ConsensusStorage[F, _, Key, _]
) extends Http4sDsl[F] {

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "consensus" / "registration" =>
      storage.getOwnRegistration.map(RegistrationResponse(_)).flatMap(Ok(_))
  }

}
