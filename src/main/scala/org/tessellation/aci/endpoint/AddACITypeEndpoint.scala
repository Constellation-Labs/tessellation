package org.tessellation.aci.endpoint

import cats.effect.{Concurrent, ContextShift}
import cats.implicits._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.tessellation.aci.ACIRegistry

class AddACITypeEndpoint[F[_]: Concurrent: ContextShift](
  private val aciRegistry: ACIRegistry[F]
) extends Http4sDsl[F] {

  def routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "type" / address =>
        for {
          payload <- req.as[String]
          aciType <- aciRegistry.createACIType(address, payload)
          result <- Ok(s"saved and registered ${aciType.kryoRegistrationId}")
        } yield result
    }

}
