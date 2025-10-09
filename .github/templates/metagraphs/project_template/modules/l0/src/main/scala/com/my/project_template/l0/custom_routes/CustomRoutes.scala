package com.my.project_template.l0.custom_routes

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.routes.internal.{InternalUrlPrefix, PublicRoutes}
import io.constellationnetwork.schema.address.Address

import com.my.project_template.shared_data.types.Types.UsageUpdateCalculatedState
import eu.timepit.refined.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.CORS

class CustomRoutes[F[_]: Async](
  implicit context: L0NodeContext[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  private def getState: F[UsageUpdateCalculatedState] =
    context.getCalculatedStateFromStorage[UsageUpdateCalculatedState].map(_._2)

  private def getAllDevices: F[Response[F]] =
    getState
      .flatMap(state => Ok(state.devices))

  private def getDeviceByAddress(address: Address): F[Response[F]] =
    getState
      .flatMap(_.devices.get(address).fold(NotFound())(Ok(_)))

  private def getLastSyncGlobalSnapshot: F[Response[F]] = {
    import io.circe.generic.auto._
    context.getLastSynchronizedGlobalSnapshot.flatMap {
      case Some(snapshot) => Ok(snapshot)
      case None           => NotFound()
    }
  }

  private val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "addresses" / AddressVar(address) => getDeviceByAddress(address)
    case GET -> Root / "addresses"                       => getAllDevices
    case GET -> Root / "global-snapshot" / "last-sync"   => getLastSyncGlobalSnapshot
  }

  val public: HttpRoutes[F] =
    CORS.policy
      .withAllowCredentials(false)
      .httpRoutes(routes)

  override protected def prefixPath: InternalUrlPrefix = "/"
}
