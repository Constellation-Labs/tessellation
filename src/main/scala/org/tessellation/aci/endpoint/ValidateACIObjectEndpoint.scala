package org.tessellation.aci.endpoint

import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.tessellation.aci.ACIDeserializer
import org.tessellation.aci.demo.ValidatedLike

class ValidateACIObjectEndpoint[F[_]: Concurrent: ContextShift](
  private val deserializer: ACIDeserializer[F]
) extends Http4sDsl[F] {

  def routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "validate" / address =>
        for {
          payload <- req.as[String]
          result <- deserializer
            .deserialize[ValidatedLike](address, payload)
            .semiflatMap { validatedLike =>
              Ok(
                if (validatedLike.valid())
                  "valid"
                else
                  "invalid"
              )
            }
            .getOrElseF(NotFound("aci type not found"))

        } yield result
    }

}
