package io.constellationnetwork.rosetta.http.routes

import cats.effect.Async

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.rosetta.domain.api.network._
import io.constellationnetwork.rosetta.domain.networkapi.NetworkApiService
import io.constellationnetwork.rosetta.ext.http4s.refined._
import io.constellationnetwork.routes.internal._

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
