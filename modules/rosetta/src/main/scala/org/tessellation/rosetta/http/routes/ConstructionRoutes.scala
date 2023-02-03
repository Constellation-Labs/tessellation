package org.tessellation.rosetta.http.routes

import cats.effect.Async

import org.tessellation.rosetta.domain.api.construction.ConstructionDerive
import org.tessellation.rosetta.domain.construction.ConstructionService
import org.tessellation.rosetta.ext.http4s.refined._
import org.tessellation.sdk.config.AppEnvironment

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final class ConstructionRoutes[F[_]: Async](
  constructionService: ConstructionService[F],
  appEnvironment: AppEnvironment
) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/construction"

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "derive" =>
      req.decodeRosettaWithNetworkValidation[ConstructionDerive.Request](appEnvironment, _.networkIdentifier) { deriveReq =>
        constructionService
          .derive(deriveReq.publicKey)
          .bimap(_.toRosettaError, ConstructionDerive.Response(_))
          .asRosettaResponse
      }
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> public
  )
}
