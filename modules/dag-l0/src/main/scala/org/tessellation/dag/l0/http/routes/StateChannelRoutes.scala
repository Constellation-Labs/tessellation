package org.tessellation.dag.l0.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l0.domain.statechannel.StateChannelService
import org.tessellation.ext.http4s.AddressVar
import org.tessellation.routes.internal._
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, HttpRoutes}

final case class StateChannelRoutes[F[_]: Async](
  stateChannelService: StateChannelService[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  protected val prefixPath: InternalUrlPrefix = "/state-channels"
  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / AddressVar(address) / "snapshot" =>
      req
        .as[Signed[StateChannelSnapshotBinary]]
        .map(StateChannelOutput(address, _))
        .flatMap(stateChannelService.process)
        .flatMap {
          case Left(errors) => BadRequest(errors)
          case Right(_)     => Ok()
        }
  }

}
