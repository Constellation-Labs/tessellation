package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.trust.domain.storage.TrustStorage

import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class TrustRoutes[F[_]: Async: KryoSerializer](
  trustStorage: TrustStorage[F]
) extends Http4sDsl[F] {
  private[routes] val prefixPath = "/trust"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      trustStorage.getPublicTrust.flatMap { publicTrust =>
        Ok(publicTrust)
      }
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
