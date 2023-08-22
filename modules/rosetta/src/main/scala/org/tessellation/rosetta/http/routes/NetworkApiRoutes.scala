package org.tessellation.rosetta.http.routes

import cats.effect.Async

import org.tessellation.http.routes.internal.{InternalUrlPrefix, PublicRoutes}
import org.tessellation.rosetta.domain.api.network._
import org.tessellation.rosetta.domain.networkapi.NetworkApiService
import org.tessellation.rosetta.ext.http4s.refined._
import org.tessellation.sdk.config.AppEnvironment

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

final class NetworkApiRoutes[F[_]: Async](
  appEnvironment: AppEnvironment,
  networkApiService: NetworkApiService[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  protected[routes] val prefixPath: InternalUrlPrefix = "/network"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / "list" =>
      Ok(NetworkApiList.Response(networkApiService.list(appEnvironment))).handleUnknownError

    case req @ POST -> Root / "options" =>
      req.decodeRosettaWithNetworkValidation[NetworkApiOptions.Request](appEnvironment, _.networkIdentifier) { _ =>
        Ok(networkApiService.options).handleUnknownError
      }

    case req @ POST -> Root / "status" =>
      req.decodeRosettaWithNetworkValidation[NetworkApiOptions.Request](appEnvironment, _.networkIdentifier) { _ =>
        networkApiService.status
          .leftMap(_.toRosettaError)
          .asRosettaResponse
      }
  }
}
