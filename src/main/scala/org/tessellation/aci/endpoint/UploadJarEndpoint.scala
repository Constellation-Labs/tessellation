package org.tessellation.aci.endpoint

import cats.effect.{Concurrent, ContextShift}
import cats.implicits._
import io.chrisdavenport.mapref.MapRef
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.tessellation.aci.{ACIRegistry, StateChannelJar, StateChannelRuntime}

class UploadJarEndpoint[F[_]: Concurrent: ContextShift](
  aciRegistry: ACIRegistry[F]
) extends Http4sDsl[F] {

  def routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "state-channel-jar" =>
        for {
          payload <- req.as[Array[Byte]]
          jar <- aciRegistry.createStateChannelJar(payload)
          result <- Created(s"State channel created under address ${jar.id}")
        } yield result
    }

}
