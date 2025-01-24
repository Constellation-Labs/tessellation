package com.my.project_template.l0.custom_routes

import cats.effect.Async
import cats.syntax.all._
import com.my.project_template.shared_data.calculated_state.CalculatedStateService
import eu.timepit.refined.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.CORS
import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.routes.internal.{InternalUrlPrefix, PublicRoutes}
import io.constellationnetwork.schema.address.Address

case class CustomRoutes[F[_] : Async](
  calculatedStateService: CalculatedStateService[F],
  context               : L0NodeContext[F]
) extends Http4sDsl[F] with PublicRoutes[F] {

  private def getAllDevices: F[Response[F]] = {
    calculatedStateService.getCalculatedState
      .flatMap(value => Ok(value.state.devices))
  }

  private def getDeviceByAddress(address: Address): F[Response[F]] = {
    calculatedStateService.getCalculatedState
      .flatMap(_.state.devices.get(address).fold(NotFound())(Ok(_)))
  }

  private val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "addresses" / AddressVar(address) => getDeviceByAddress(address)
    case GET -> Root / "addresses" => getAllDevices
  }

  val public: HttpRoutes[F] =
    CORS
      .policy
      .withAllowCredentials(false)
      .httpRoutes(routes)

  override protected def prefixPath: InternalUrlPrefix = "/"
}
